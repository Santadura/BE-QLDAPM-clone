#!/usr/bin/env bash
# Disposable CI: read-only cross-PostgreSQL diagnosis, then explicit
# audited discard of Target/Result REHEARSAL_ONLY outboxes after Core handback.
set -euo pipefail
if [[ "${GITHUB_ACTIONS:-}" != true || "${IIG_ROSTER_FENCE_SMOKE:-}" != true ||
      ! "${GITHUB_RUN_ID:-}" =~ ^[0-9]+$ ]]; then
  echo "Refusing roster disposition smoke outside disposable CI" >&2
  exit 2
fi
compose() { docker compose -f docker-compose.microservices.yml "$@"; }
core_sql() {
  compose exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 -tAc "$1" | tr -d '[:space:]'
}
academic_sql() {
  compose exec -T academic-postgres psql -U iig_academic -d iig_academic -v ON_ERROR_STOP=1 -tAc "$1" | tr -d '[:space:]'
}
fail() { echo "Academic roster disposition: $*" >&2; exit 1; }
test "$(core_sql "SELECT writer_mode FROM academic_roster_writer_control WHERE singleton=TRUE")" = CORE ||
  fail "Cannot discard while Core roster writer is frozen"
test "$(core_sql "SELECT COUNT(*) FROM academic_roster_candidate_claims WHERE state='OPEN'")" = 0 ||
  fail "Core has an unreleased roster candidate claim"
key="${ACADEMIC_INTERNAL_API_KEY:-local-dev-academic-key-change-before-deployment}"
admin="$(curl -fsS -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' -d '{"username":"admin","password":"123456"}' |
  python3 -c 'import json,sys;print(json.load(sys.stdin)["token"])')"
teacher="$(curl -fsS -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' -d '{"username":"teacher001","password":"123456"}' |
  python3 -c 'import json,sys;print(json.load(sys.stdin)["token"])')"
diagnosis="http://academic-service:8099/internal/academic/student-roster/candidates/reconciliation"
discard="http://academic-service:8099/internal/academic/student-roster/rehearsals/discard"
request_status() {
  compose exec -T scheduling-postgres wget -S -O- \
    --header "X-Internal-Key: $1" --header "Authorization: Bearer $2" \
    --header "Content-Type: application/json" --post-data "$3" \
    "$discard" 2>&1 || true
}
post() {
  compose exec -T scheduling-postgres wget -qO- \
    --header "X-Internal-Key: $key" --header "Authorization: Bearer $admin" \
    --header "Content-Type: application/json" --post-data "$1" "$discard"
}
inspect() {
  compose exec -T scheduling-postgres wget -qO- \
    --header "X-Internal-Key: $key" --header "Authorization: Bearer $admin" \
    "$diagnosis?actor=admin&requestKey=$1"
}
for path in /internal/academic/student-roster/candidates/reconciliation \
            /internal/academic/student-roster/rehearsals/discard; do
  test "$(curl -sS -o /dev/null -w '%{http_code}' "http://localhost:8080$path")" = 404 ||
    fail "Internal roster diagnostic/operation escaped Gateway isolation"
done

before_claims="$(core_sql "SELECT COUNT(*) FROM academic_roster_candidate_claims")"
before_events="$(academic_sql "SELECT COUNT(*) FROM academic_roster_candidate_outbox")"
for kind in TARGET RESULT; do
  request_key="ci-${kind,,}-candidate-${GITHUB_RUN_ID}"
  command_id="$(academic_sql "SELECT command_id FROM academic_roster_candidate_commands
    WHERE operator_username='admin' AND request_key='$request_key'")"
  test -n "$command_id" || fail "Missing immutable $kind candidate"
  first="$(inspect "$request_key")"
  STATE="$first" python3 -c '
import os,json
x=json.loads(os.environ["STATE"])
assert x["state"]=="RELEASED_REHEARSAL_UNAPPLIED",x
assert x["hasCompleteAcademicEvidence"] is True and x["hasLocalHandoffReceipt"] is True
assert x["hasDisposition"] is False and x["requiresManualReview"] is True
'
  body="$(KEY="$request_key" COMMAND="$command_id" python3 -c '
import os,json
print(json.dumps({"requestKey":os.environ["KEY"],"commandId":os.environ["COMMAND"]}))
')"
  printf '%s' "$(request_status invalid "$admin" "$body")" | grep -q '401' ||
    fail "Internal service key missing for $kind discard"
  printf '%s' "$(request_status "$key" "$teacher" "$body")" | grep -q '403' ||
    fail "Teacher was allowed to discard $kind rehearsal"
  result="$(post "$body")" || fail "Admin $kind discard failed"
  RESULT="$result" EXPECTED="$command_id" python3 -c '
import json,os
r=json.loads(os.environ["RESULT"])
assert r["disposition"]=="REHEARSAL_DISCARDED" and r["commandId"]==os.environ["EXPECTED"]
'
  # Same request+Admin is idempotent; no new dispositions or audits.
  retry="$(post "$body")" || fail "Idempotent $kind discard retry failed"
  test "$retry" = "$result" || fail "$kind discard retry differed"
  test "$(academic_sql "SELECT COUNT(*) FROM academic_roster_candidate_dispositions
    WHERE command_id='$command_id' AND actor_username='admin'
      AND request_key='$request_key' AND disposed_by='admin'
      AND disposition='REHEARSAL_DISCARDED'
      AND outbox_hash=(SELECT payload_hash FROM academic_roster_candidate_outbox
         WHERE command_id='$command_id')")" = 1 ||
    fail "$kind disposition missing or evidence mismatched"
  later="$(inspect "$request_key")"
  STATE="$later" python3 -c '
import os,json
x=json.loads(os.environ["STATE"])
assert x["state"]=="REHEARSAL_DISCARDED",x
assert x["hasDisposition"] is True and x["requiresManualReview"] is False
'
  echo "PASS: $kind private read-only diagnosis and idempotent audited discard"
done

test "$(academic_sql "SELECT COUNT(*) FROM academic_roster_candidate_dispositions")" = 2 ||
  fail "Expected two independently reviewed rehearsal dispositions"
test "$(academic_sql "SELECT COUNT(*) FROM academic_roster_candidate_outbox")" = "$before_events" ||
  fail "Rehearsal disposition removed or published immutable outbox"
test "$(core_sql "SELECT COUNT(*) FROM academic_roster_candidate_claims")" = "$before_claims" ||
  fail "Read-only diagnosis or discard changed Core claims"
if compose exec -T academic-postgres psql -U iig_academic -d iig_academic \
    -v ON_ERROR_STOP=1 -c "DELETE FROM academic_roster_candidate_dispositions" \
    >/tmp/iig-roster-disposition-audit.log 2>&1;then
  fail "Immutable rehearsal dispositions could be deleted"
fi
grep -q immutable /tmp/iig-roster-disposition-audit.log ||
  fail "Disposition rejection did not use SQL immutability trigger"
echo "PASS: immutable Target/Result journal remains intact and both rehearsals have explicit audited closure"
