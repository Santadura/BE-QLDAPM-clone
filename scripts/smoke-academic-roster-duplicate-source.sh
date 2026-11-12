#!/usr/bin/env bash
# Disposable CI only: a second actor/key cannot reacquire the same immutable
# Target or Result source event after its first claim was RELEASED.
set -euo pipefail
if [[ "${GITHUB_ACTIONS:-}" != true || "${IIG_ROSTER_FENCE_SMOKE:-}" != true ||
      ! "${GITHUB_RUN_ID:-}" =~ ^[0-9]+$ ]]; then
  echo "Refusing duplicate-source roster smoke outside disposable CI" >&2
  exit 2
fi
compose() { docker compose -f docker-compose.microservices.yml "$@"; }
sql() {
  compose exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 -tAc "$1" | tr -d '[:space:]'
}
fail() { echo "Duplicate Academic roster claim: $*" >&2; exit 1; }
mode="$(sql "SELECT writer_mode FROM academic_roster_writer_control WHERE singleton=TRUE")"
test "$mode" = CORE || fail "Core roster writer not handed back"

restore() {
  code=$?
  if [[ "${frozen:-false}" = true ]]; then
    sql "UPDATE academic_roster_writer_control SET writer_mode='CORE'
      WHERE singleton=TRUE AND writer_mode='QUIESCED'" >/dev/null ||
      echo "WARNING: disposable Core roster writer restoration failed" >&2
  fi
  exit "$code"
}
trap restore EXIT

# Writer control row is SQL-serialized against all Core roster DML and claims.
sql "UPDATE academic_roster_writer_control SET writer_mode='QUIESCED'
  WHERE singleton=TRUE AND writer_mode='CORE'" >/dev/null
frozen=true
gen="$(sql "SELECT generation FROM academic_roster_writer_control WHERE singleton=TRUE")"
key="${ACADEMIC_INTERNAL_API_KEY:-local-dev-academic-key-change-before-deployment}"
endpoint="http://backend:8081/internal/academic/roster-candidate-claims"

for type in TARGET RESULT; do
  old="ci-${type,,}-candidate-${GITHUB_RUN_ID}"
  # Exact previously RELEASED source event, not fabricated evidence.
  item="$(sql "SELECT entity_id || '|' || source_event_id || '|' ||
    source_revision FROM academic_roster_candidate_claims
    WHERE actor_username='admin' AND request_key='$old' AND state='RELEASED'")"
  test -n "$item" || fail "Missing released $type source claim"
  IFS='|' read -r identifier event revision <<< "$item"
  new_key="ci-${type,,}-different-key-${GITHUB_RUN_ID}"
  before="$(sql "SELECT COUNT(*) FROM academic_roster_candidate_claims")"
  audits="$(sql "SELECT COUNT(*) FROM academic_roster_candidate_claim_audit")"
  payload="$(TYPE="$type" ENTITY="$identifier" EVENT="$event" REVISION="$revision" \
    GEN="$gen" KEY="$new_key" python3 -c '
import json,os
print(json.dumps(dict(
  actor="admin",requestKey=os.environ["KEY"],
  requestHash="d"*64,entityType=os.environ["TYPE"],
  entityId=os.environ["ENTITY"],sourceEventId=int(os.environ["EVENT"]),
  sourceRevision=int(os.environ["REVISION"]),
  writerGeneration=int(os.environ["GEN"]))))
')"
  response="$(compose exec -T scheduling-postgres wget -S -O- \
    --header "X-Internal-Key: $key" \
    --header "Content-Type: application/json" \
    --post-data "$payload" "$endpoint" 2>&1 || true)"
  printf '%s' "$response" | grep -q '409 Conflict' ||
    fail "Different key reacquired $type source event: $response"
  test "$(sql "SELECT COUNT(*) FROM academic_roster_candidate_claims")" = "$before" ||
    fail "$type rejected duplicate inserted a Core claim"
  test "$(sql "SELECT COUNT(*) FROM academic_roster_candidate_claim_audit")" = "$audits" ||
    fail "$type rejected duplicate appended Core audit"
  test "$(sql "SELECT COUNT(*) FROM academic_roster_candidate_claims
    WHERE actor_username='admin' AND request_key='$new_key'")" = 0 ||
    fail "$type duplicate created an unreleasable OPEN claim"
  echo "PASS: $type second key rejected, claim/audit unchanged"
done

test "$(sql "SELECT COUNT(*) FROM academic_roster_candidate_claims WHERE state='OPEN'")" = 0 ||
  fail "An OPEN Core roster claim blocks safe writer handback"
sql "UPDATE academic_roster_writer_control SET writer_mode='CORE'
  WHERE singleton=TRUE AND writer_mode='QUIESCED'" >/dev/null
frozen=false
test "$(sql "SELECT writer_mode FROM academic_roster_writer_control WHERE singleton=TRUE")" = CORE ||
  fail "Core roster writer not restored"
echo "PASS: duplicate source event rejected across released claims, and Core can hand back"
