#!/usr/bin/env bash
# Terminal fail-closed probe: ONLY run as the final CI step against disposable
# Docker volumes. Deliberately leaves a Core-only OPEN claim and QUIESCED writers;
# the workflow's "down -v" is responsible for destroying this fixture.
set -euo pipefail
if [ "$GITHUB_ACTIONS" != "true" ] || [ "$IIG_TERMINAL_ORPHAN_PROBE" != "true" ]; then
  echo "Refusing non-disposable Academic orphan crash probe" >&2
  exit 2
fi
[[ "$GITHUB_RUN_ID" =~ ^[0-9]+$ ]] && [[ "$GITHUB_RUN_ATTEMPT" =~ ^[0-9]+$ ]] || {
  echo "A numeric GitHub run identity is required" >&2
  exit 2
}
compose() { docker compose -f docker-compose.microservices.yml "$@"; }
core_scalar() {
  compose exec -T postgres psql -U postgres -d qlapm_db -tAc "$1" | tr -d '[:space:]'
}
academic_scalar() {
  compose exec -T academic-postgres psql -U iig_academic -d iig_academic -tAc "$1" | tr -d '[:space:]'
}
key="ci-orphan-$GITHUB_RUN_ID-$GITHUB_RUN_ATTEMPT"
test "$(core_scalar "SELECT COUNT(*) FROM academic_candidate_claims WHERE state='OPEN'")" = "0" || {
  echo "Other OPEN claims exist: refusing ambiguous crash test" >&2
  exit 1
}
test "$(core_scalar "SELECT writer_mode FROM academic_session_writer_control WHERE singleton=TRUE")" = "CORE"
test "$(core_scalar "SELECT writer_mode FROM scheduling_assignment_writer_control WHERE singleton=TRUE")" = "CORE"
test "$(core_scalar "SELECT writer_mode FROM teacher_attendance_writer_control WHERE singleton=TRUE")" = "CORE"
test "$(core_scalar "SELECT COUNT(*) FROM academic_session_source_outbox e JOIN class_sessions c ON c.class_session_id=e.class_session_id WHERE e.event_kind='UPSERT' AND e.delivered_at IS NOT NULL AND e.payload IS NOT DISTINCT FROM to_jsonb(c)")" -gt 0

# Freeze all Core writers atomically. Do not enable any public candidate route.
compose exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 >/dev/null <<SQL
BEGIN;
UPDATE scheduling_assignment_writer_control SET writer_mode='QUIESCED' WHERE singleton=TRUE;
UPDATE teacher_attendance_writer_control SET writer_mode='QUIESCED' WHERE singleton=TRUE;
UPDATE academic_session_writer_control SET writer_mode='QUIESCED' WHERE singleton=TRUE;
COMMIT;
SQL

# Simulate a process crash AFTER Core commits the claim but BEFORE Academic
# commits even its first local candidate command. No fake local evidence.
compose exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 >/dev/null <<SQL
INSERT INTO academic_candidate_claims
 (actor_username,request_key,request_hash,class_id,class_session_id,
  source_event_id,source_revision,academic_generation,
  attendance_generation,scheduling_generation)
SELECT 'ci-crash','$key',repeat('b',64),
 e.class_id,e.class_session_id,e.event_id,e.source_revision,
 a.generation,t.generation,s.writer_generation
FROM academic_session_source_outbox e
JOIN class_sessions c ON c.class_session_id=e.class_session_id
CROSS JOIN academic_session_writer_control a
CROSS JOIN scheduling_assignment_writer_control s
CROSS JOIN teacher_attendance_writer_control t
WHERE e.event_kind='UPSERT' AND e.delivered_at IS NOT NULL
  AND e.payload IS NOT DISTINCT FROM to_jsonb(c)
  AND a.singleton=TRUE AND s.singleton=TRUE AND t.singleton=TRUE
ORDER BY e.event_id DESC LIMIT 1;
SQL

assert_orphan() {
  counts="$(core_scalar "SELECT (SELECT COUNT(*) FROM academic_candidate_claims WHERE actor_username='ci-crash' AND request_key='$key' AND state='OPEN') || ':' || (SELECT COUNT(*) FROM academic_candidate_claim_audit WHERE actor_username='ci-crash' AND request_key='$key' AND event_kind='ACQUIRED') || ':' || (SELECT COUNT(*) FROM academic_candidate_claim_audit WHERE actor_username='ci-crash' AND request_key='$key' AND event_kind='RELEASED')")"
  test "$counts" = "1:1:0" || { echo "Core orphan/audit changed unexpectedly: $counts" >&2; exit 1; }
  local_counts="$(academic_scalar "SELECT (SELECT COUNT(*) FROM academic_session_candidate_commands WHERE operator_username='ci-crash' AND request_key='$key') || ':' || (SELECT COUNT(*) FROM academic_candidate_handoff_receipts WHERE actor_username='ci-crash' AND request_key='$key')")"
  test "$local_counts" = "0:0" || { echo "Academic fabricated local recovery evidence: $local_counts" >&2; exit 1; }
  if compose exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1      -c "UPDATE academic_session_writer_control SET writer_mode='CORE' WHERE singleton=TRUE" >/tmp/iig-orphan-handback.log 2>&1; then
    echo "Core handback succeeded without committed Academic command/outbox" >&2
    exit 1
  fi
  grep -q 'Unreleased Academic candidate claim prevents Core handback' /tmp/iig-orphan-handback.log || {
    cat /tmp/iig-orphan-handback.log >&2
    exit 1
  }
}
assert_orphan

# Private read-only triage must surface an orphan, without releasing it.
# Gateway must not expose even this diagnostic read to browsers.
private_key="${ACADEMIC_INTERNAL_API_KEY:-local-dev-academic-key-change-before-deployment}"
open_claims="$(compose exec -T scheduling-postgres wget -qO- \
  --header "X-Internal-Key: $private_key" \
  "http://backend:8081/internal/academic/candidate-claims/open?limit=25")"
CORE_ORPHAN_JSON="$open_claims" CORE_ORPHAN_KEY="$key" python3 -c \
  'import json,os; xs=json.loads(os.environ["CORE_ORPHAN_JSON"]); ys=[x for x in xs if x["actor"]=="ci-crash" and x["requestKey"]==os.environ["CORE_ORPHAN_KEY"]]; assert len(ys)==1 and ys[0]["eventId"]>0 and ys[0]["revision"]>0,(xs,ys)'
unauthorized="$(compose exec -T scheduling-postgres wget -S -O- \
  --header "X-Internal-Key: wrong-key" \
  "http://backend:8081/internal/academic/candidate-claims/open" 2>&1 || true)"
printf '%s' "$unauthorized" | grep -q '401' || {
  echo "Core OPEN-claim triage accepted an invalid key" >&2
  exit 1
}
gateway_code="$(curl -sS -o /dev/null -w '%{http_code}' \
  "http://localhost:8080/internal/academic/candidate-claims/open")"
test "$gateway_code" = "404" || {
  echo "Private Core claim triage exposed through Gateway: $gateway_code" >&2
  exit 1
}

# Restart Academic to force a fresh reconciler. Its scheduled retries must
# neither synthesize an absent outbox nor claim an unacknowledged Core release.
compose restart academic-service
healthy=false
for attempt in $(seq 1 80); do
  if compose exec -T scheduling-postgres wget -qO-        http://academic-service:8099/actuator/health 2>/dev/null | grep -q '"UP"'; then
    healthy=true
    break
  fi
  sleep 2
done
test "$healthy" = "true" || { echo "Academic failed to boot after crash simulation" >&2; exit 1; }
sleep 24
assert_orphan
echo "PASS: committed Core-only claim survives Academic restart/retries, has ACQUIRED-only audit, no synthetic local command/outbox, and blocks Core handback"
