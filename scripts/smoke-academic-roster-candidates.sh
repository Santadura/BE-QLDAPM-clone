#!/usr/bin/env bash
# Invoked ONLY while the prior smoke holds Core roster QUIESCED on disposable
# PostgreSQL volumes. Both candidate commands are REHEARSAL_ONLY; no domain
# writer transfer, no Core claim release, no Scheduling/Attendance dispatch.
set -euo pipefail
if [ "${GITHUB_ACTIONS:-}" != "true" ] || [ "${IIG_ROSTER_FENCE_SMOKE:-}" != "true" ]; then
  echo "Refusing non-disposable Academic roster candidate smoke" >&2
  exit 2
fi
[[ "${GITHUB_RUN_ID:-}" =~ ^[0-9]+$ ]] || exit 2
compose() { docker compose -f docker-compose.microservices.yml "$@"; }
core_sql() {
  compose exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 -tAc "$1" | tr -d '[:space:]'
}
academic_sql() {
  compose exec -T academic-postgres psql -U iig_academic -d iig_academic -v ON_ERROR_STOP=1 -tAc "$1" | tr -d '[:space:]'
}
fail() { echo "Academic roster candidate smoke: $*" >&2; exit 1; }

test "$(core_sql "SELECT writer_mode FROM academic_roster_writer_control WHERE singleton=TRUE")" = "QUIESCED" ||
  fail "Core student/roster writer not frozen"
generation="$(core_sql "SELECT generation FROM academic_roster_writer_control WHERE singleton=TRUE")"
key="${ACADEMIC_INTERNAL_API_KEY:-local-dev-academic-key-change-before-deployment}"
admin_token="$(curl -fsS -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' -d '{"username":"admin","password":"123456"}' |
  python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')"
teacher_token="$(curl -fsS -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' -d '{"username":"teacher001","password":"123456"}' |
  python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')"

endpoint="http://academic-service:8099/internal/academic/student-roster/candidates"
post() {
  local bearer="$1" secret="$2" body="$3"
  compose exec -T scheduling-postgres wget -qO- \
    --header "Content-Type: application/json" \
    --header "Authorization: Bearer $bearer" \
    --header "X-Internal-Key: $secret" \
    --post-data "$body" "$endpoint"
}
post_status() {
  local bearer="$1" secret="$2" body="$3"
  compose exec -T scheduling-postgres wget -S -O- \
    --header "Content-Type: application/json" \
    --header "Authorization: Bearer $bearer" \
    --header "X-Internal-Key: $secret" \
    --post-data "$body" "$endpoint" 2>&1 || true
}
test "$(curl -sS -o /dev/null -w '%{http_code}' \
  http://localhost:8080/internal/academic/student-roster/candidates)" = "404" ||
  fail "Private Academic rehearsal command was routed by Gateway"

target="st-001-ielts"
result="auto-sr-1"
for kind in TARGET RESULT; do
  if [ "$kind" = TARGET ]; then
    id="$target";table="academic_student_targets_shadow";col="student_target_id"
    value="8.00"
  else
    id="$result";table="academic_student_results_shadow";col="student_result_id"
    value="9.00"
  fi
  evidence="$(academic_sql "SELECT source_event_id || ':' || source_revision
    FROM $table WHERE $col='$id' AND event_kind='UPSERT'")"
  test -n "$evidence" || fail "Missing live Academic $kind shadow $id"
  event_id="${evidence%%:*}";revision="${evidence##*:}"
  request_key="ci-${kind,,}-candidate-${GITHUB_RUN_ID}"
  body="$(TYPE="$kind" IDENTIFIER="$id" EVENT="$event_id" \
    REVISION="$revision" REQUEST="$request_key" VALUE="$value" python3 -c '
import json,os
print(json.dumps({"requestKey":os.environ["REQUEST"],
 "entityType":os.environ["TYPE"],"entityId":os.environ["IDENTIFIER"],
 "expectedEventId":int(os.environ["EVENT"]),
 "expectedRevision":int(os.environ["REVISION"]),
 "action":"UPSERT","proposedValue":float(os.environ["VALUE"])}))
')"
  source_before="$(academic_sql "SELECT md5(source_payload::text) FROM $table WHERE $col='$id'")"
  core_before="$(core_sql "SELECT md5(to_jsonb(t)::text) FROM $( [ "$kind" = TARGET ] && echo student_targets || echo student_results ) t
    WHERE $( [ "$kind" = TARGET ] && echo student_target_id || echo student_result_id )='$id'")"
  response="$(post "$admin_token" "$key" "$body")" ||
    fail "Academic $kind candidate write rehearsal failed"
  command_id="$(RESPONSE="$response" python3 -c '
import os,json
r=json.loads(os.environ["RESPONSE"])
assert r["state"]=="STAGED" and r["entityType"]==os.environ["TYPE"] and r["requestKey"]==os.environ["REQUEST"]
print(r["commandId"])
' TYPE="$kind" REQUEST="$request_key")"
  # Entire command+typed proposed payload+outbox commit in one Academic DB.
  test "$(academic_sql "SELECT COUNT(*) FROM academic_roster_candidate_commands
    WHERE command_id='$command_id' AND entity_type='$kind' AND entity_id='$id'
      AND writer_generation=$generation AND state='STAGED'")" = "1" ||
    fail "Missing $kind immutable Academic command"
  test "$(academic_sql "SELECT COUNT(*) FROM academic_roster_candidate_payloads
    WHERE command_id='$command_id' AND proposed_value=$value")" = "1" ||
    fail "Missing $kind typed proposed payload"
  test "$(academic_sql "SELECT COUNT(*) FROM academic_roster_candidate_outbox
    WHERE command_id='$command_id' AND event_kind='REHEARSAL_ONLY'
      AND length(payload_hash)=64")" = "1" ||
    fail "Missing $kind immutable rehearsal outbox"
  # A lost HTTP response may replay the exact SAME actor/key/amount.
  replay="$(post "$admin_token" "$key" "$body")" ||
    fail "Identical $kind candidate replay rejected"
  REPLAY="$replay" COMMAND="$command_id" python3 -c '
import json,os
assert json.loads(os.environ["REPLAY"])["commandId"]==os.environ["COMMAND"]
'
  test "$(academic_sql "SELECT COUNT(*) FROM academic_roster_candidate_commands
    WHERE operator_username='admin' AND request_key='$request_key'")" = "1" ||
    fail "Duplicate command created by $kind replay"
  test "$(academic_sql "SELECT COUNT(*) FROM academic_roster_candidate_outbox
    WHERE command_id='$command_id'")" = "1" ||
    fail "Duplicate outbox created by $kind replay"

  # Different payload with SAME idempotency key must be an explicit conflict.
  changed="$(BODY="$body" python3 -c '
import os,json
x=json.loads(os.environ["BODY"]);x["proposedValue"]+=1
print(json.dumps(x))
')"
  printf '%s' "$(post_status "$admin_token" "$key" "$changed")" | grep -q '409' ||
    fail "Conflicting $kind candidate idempotency key was accepted"
  printf '%s' "$(post_status "$teacher_token" "$key" "$body")" | grep -q '403' ||
    fail "Teacher could stage unrestricted $kind candidate"
  printf '%s' "$(post_status "$admin_token" 'invalid-private-key' "$body")" | grep -q '401' ||
    fail "Candidate $kind accepted an invalid internal key"

  test "$(academic_sql "SELECT md5(source_payload::text) FROM $table WHERE $col='$id'")" = "$source_before" ||
    fail "Candidate rehearsal mutated original Academic $kind projection"
  test "$(core_sql "SELECT md5(to_jsonb(t)::text) FROM $( [ "$kind" = TARGET ] && echo student_targets || echo student_results ) t
    WHERE $( [ "$kind" = TARGET ] && echo student_target_id || echo student_result_id )='$id'")" = "$core_before" ||
    fail "Candidate rehearsal mutated the authoritative Core $kind source"
done

test "$(academic_sql "SELECT COUNT(*) FROM academic_roster_candidate_commands
  WHERE operator_username='admin' AND request_key LIKE 'ci-%-candidate-${GITHUB_RUN_ID}'")" = "2" ||
  fail "Expected exactly one independent TARGET and one independent RESULT command"
test "$(academic_sql "SELECT COUNT(*) FROM academic_roster_candidate_outbox
  WHERE command_id IN (SELECT command_id FROM academic_roster_candidate_commands
    WHERE operator_username='admin' AND request_key LIKE 'ci-%-candidate-${GITHUB_RUN_ID}')")" = "2" ||
  fail "Expected exactly two independent durable rehearsal outbox records"

if compose exec -T academic-postgres psql -U iig_academic -d iig_academic -v ON_ERROR_STOP=1 \
    -c "DELETE FROM academic_roster_candidate_outbox" >/tmp/iig-roster-candidate-immutable.log 2>&1; then
  fail "Academic rehearsal outbox allowed direct delete"
fi
grep -q "immutable" /tmp/iig-roster-candidate-immutable.log ||
  fail "Unexpected Academic candidate immutability failure"

echo "PASS: TARGET and RESULT have isolated idempotent transactional commands, typed proposed JSON and immutable REHEARSAL_ONLY outboxes; no Core or Academic source row mutated"
