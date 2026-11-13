#!/usr/bin/env bash
# Disposable-only end-to-end Academic native PLAN/RESCHEDULE rehearsal.
# This does NOT enable a production Academic writer or change public routes.
set -euo pipefail
if [[ "${GITHUB_ACTIONS:-}" != true ||
      "${IIG_NATIVE_COMMAND_SMOKE:-}" != true ||
      ! "${GITHUB_RUN_ID:-}" =~ ^[0-9]+$ ]]; then
  echo "Refusing native Academic mutation smoke outside disposable CI" >&2
  exit 2
fi
compose() { docker compose -f docker-compose.microservices.yml "$@"; }
core_sql() {
  compose exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 \
    -tAc "$1" | tr -d '[:space:]'
}
academic_sql() {
  compose exec -T academic-postgres psql -U iig_academic -d iig_academic \
    -v ON_ERROR_STOP=1 -tAc "$1" | tr -d '[:space:]'
}
fail() { echo "Academic native E2E: $*" >&2; exit 1; }
class_id=""
prior_status=""
frozen=false
core_claims_enabled=false
cleanup() {
  local rc=$?
  trap - EXIT
  if [[ "$frozen" == true ]]; then
    compose exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 \
      -c "BEGIN;
       SELECT set_config('iig.attendance_handback_approved','true',true);
       UPDATE academic_session_writer_control SET writer_mode='CORE'
         WHERE singleton=TRUE AND writer_mode='QUIESCED';
       UPDATE teacher_attendance_writer_control SET writer_mode='CORE'
         WHERE singleton=TRUE AND writer_mode='QUIESCED';
       UPDATE scheduling_assignment_writer_control SET writer_mode='CORE'
         WHERE singleton=TRUE AND writer_mode='QUIESCED';
       UPDATE academic_roster_writer_control SET writer_mode='CORE'
         WHERE singleton=TRUE AND writer_mode='QUIESCED';
       COMMIT;" >/dev/null || echo "WARNING: disposable Core writer restore failed" >&2
  fi
  if [[ -n "$class_id" && -n "$prior_status" ]]; then
    core_sql "UPDATE classes SET status='$prior_status' WHERE class_id='$class_id'" \
      >/dev/null || echo "WARNING: disposable Core class status restore failed" >&2
  fi
  ACADEMIC_NATIVE_REHEARSAL_ENABLED=false \
    ACADEMIC_NATIVE_CLAIM_RECOVERY_ENABLED=false \
    ACADEMIC_ROSTER_SOURCE_RELAY_ENABLED=true \
    compose up -d --no-deps --force-recreate academic-service >/dev/null ||
    echo "WARNING: disposable Academic service restore failed" >&2
  if [[ "$core_claims_enabled" == true ]]; then
    ACADEMIC_NATIVE_COMMAND_CLAIMS_ENABLED=false \
      compose up -d --no-deps --force-recreate backend >/dev/null ||
      echo "WARNING: disposable Core native claim controller restore failed" >&2
  fi
  exit "$rc"
}
trap cleanup EXIT

# The earlier gateway smoke creates this class through the original FE API.
class_id="$(core_sql "SELECT class_id FROM classes
  WHERE class_code='MS-B-${GITHUB_RUN_ID}' LIMIT 1")"
[[ "$class_id" =~ ^[a-zA-Z0-9][a-zA-Z0-9._:-]{0,49}$ ]] ||
  fail "No verified disposable Core class fixture exists"
prior_status="$(core_sql "SELECT status FROM classes WHERE class_id='$class_id'")"
test "$prior_status" = DRAFT ||
  fail "The disposable Academic class was not in its original DRAFT state"
core_sql "UPDATE classes SET status='RUNNING' WHERE class_id='$class_id'" >/dev/null
# Enable ONLY the private class-scoped Core claim admission for this
# disposable Academic rehearsal. The Core SQL trigger itself is permanent.
ACADEMIC_NATIVE_COMMAND_CLAIMS_ENABLED=true \
  compose up -d --no-deps --force-recreate backend >/dev/null
core_claims_enabled=true

# The private rehearsal controller is explicitly disabled outside this smoke.
ACADEMIC_NATIVE_REHEARSAL_ENABLED=true \
  ACADEMIC_NATIVE_CLAIM_RECOVERY_ENABLED=true \
  ACADEMIC_ROSTER_SOURCE_RELAY_ENABLED=true \
  ACADEMIC_SESSION_SOURCE_RELAY_ENABLED=true \
  compose up -d --no-deps --force-recreate academic-service
health=false
for attempt in $(seq 1 80); do
  if compose exec -T scheduling-postgres wget -qO- \
    "http://academic-service:8099/actuator/health" 2>/dev/null | grep -q '"UP"'; then
    health=true;break
  fi
  sleep 2
done
test "$health" = true || fail "Native Academic service did not boot"

# Attendance's preceding crash/handback smoke intentionally recreates Core.
# Establish the live Admin session BEFORE freezing the four business writers.
# HTTP 503 while identity/core is still booting is transient; never treat it
# as a domain rejection from Academic PLAN/RESCHEDULE.
key="${ACADEMIC_INTERNAL_API_KEY:-local-dev-academic-key-change-before-deployment}"
admin="$(curl --retry 24 --retry-delay 2 --retry-connrefused -fsS \
  -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"123456"}' |
  python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')" ||
  fail "Admin identity did not become available after Attendance handback"
test -n "$admin" || fail "No valid Admin token for private Academic rehearsal"

# Core owns all public writes until every independent proof passes. Freeze all
# FOUR sources together, without enabling any public native mutation route.
for table in academic_session_writer_control academic_roster_writer_control \
             teacher_attendance_writer_control scheduling_assignment_writer_control; do
  test "$(core_sql "SELECT writer_mode FROM $table WHERE singleton=TRUE")" = CORE ||
    fail "Core $table was not in CORE mode before native rehearsal"
done
compose exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 <<SQL
BEGIN;
UPDATE scheduling_assignment_writer_control SET writer_mode='QUIESCED'
  WHERE singleton=TRUE AND writer_mode='CORE';
UPDATE teacher_attendance_writer_control SET writer_mode='QUIESCED'
  WHERE singleton=TRUE AND writer_mode='CORE';
UPDATE academic_session_writer_control SET writer_mode='QUIESCED'
  WHERE singleton=TRUE AND writer_mode='CORE';
UPDATE academic_roster_writer_control SET writer_mode='QUIESCED'
  WHERE singleton=TRUE AND writer_mode='CORE';
COMMIT;
SQL
frozen=true

# All requests bypass the public Gateway and use the current disposable
# Docker bridge IP. Internal service ports are never published externally.
container="$(compose ps -q academic-service)"
[[ -n "$container" ]] || fail "Academic container missing"
ip="$(docker inspect -f '{{range.NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$container")"
[[ "$ip" =~ ^[0-9.]+$ ]] || fail "Academic private bridge IP invalid"
private="http://$ip:8099/internal/academic/native"
test "$(curl -sS -o /dev/null -w '%{http_code}' \
  "http://localhost:8080/internal/academic/native/activate/$class_id")" = 404 ||
  fail "Native Academic operator API leaked through the Gateway"

code="$(curl -sS -o /dev/null -w '%{http_code}' -X POST \
  "$private/activate/$class_id" -H 'X-Internal-Key: wrong' \
  -H "Authorization: Bearer $admin")"
test "$code" = 401 || fail "Native activation accepted an invalid internal key"

# Wait for complete historical importer parity while Core is frozen.
source_count="$(core_sql "SELECT COUNT(*) FROM academic_session_source_outbox")"
caught=false
for attempt in $(seq 1 90); do
  local_count="$(academic_sql "SELECT COUNT(*) FROM academic_session_source_receipts")"
  unacknowledged="$(core_sql "SELECT COUNT(*) FROM academic_session_source_outbox WHERE delivered_at IS NULL")"
  if [[ "$source_count" == "$local_count" && "$unacknowledged" = 0 ]]; then
    caught=true;break
  fi
  sleep 2
done
test "$caught" = true || fail "Core/Academic immutable timetable history diverged"

baseline="$(core_sql "SELECT COUNT(*) FROM class_sessions WHERE class_id='$class_id'")"
source_outbox="$(core_sql "SELECT COUNT(*) FROM academic_session_source_outbox")"
# A preceding Attendance fault-injection test restarts Core and its
# downstream services. A 503 here means unavailable independent proof, NOT a
# rejected native business command. Retry only transient 503/502/504, never
# bypass a 409 readiness conflict or authentication failure. Activation is
# exact-evidence idempotent, so a lost response can be retried safely.
activation_body="/tmp/iig-academic-native-activation-${GITHUB_RUN_ID}.json"
activate=""
for attempt in $(seq 1 45); do
  status="$(curl --connect-timeout 3 --max-time 30 -sS -o "$activation_body" \
    -w '%{http_code}' -X POST "$private/activate/$class_id" \
    -H "X-Internal-Key: $key" -H "Authorization: Bearer $admin")" || status="000"
  if [[ "$status" == 202 ]]; then
    activate="$(cat "$activation_body")"
    break
  fi
  case "$status" in
    000|502|503|504)
      echo "Academic native frozen activation deferred: dependent proof HTTP $status (attempt $attempt/45)" >&2
      sleep 3
      ;;
    *)
      echo "Academic native activation rejected with HTTP $status: $(head -c 600 "$activation_body")" >&2
      fail "Native Academic activation rejected by immutable cutover or ACL contract"
      ;;
  esac
done
if [[ -z "$activate" ]]; then
  echo "Academic native activation remained unavailable; last HTTP $status" >&2
  if [[ -s "$activation_body" ]]; then head -c 1200 "$activation_body" >&2; echo >&2; fi
  # Probe individual READ-ONLY independent receipts; these are diagnostics,
  # never substitutes for the strict cross-service gate above.
  for target in \
    "attendance-service:8100/internal/attendance/verify/frozen" \
    "scheduling-service:8098/internal/scheduling/assignments/verify" \
    "payroll-service:8110/internal/payroll/attendance-reference/verify"; do
    host="${target%%/*}"
    endpoint="/${target#*/}"
    status_probe="$(compose exec -T scheduling-postgres wget -qO- \
      "http://$host$endpoint" 2>/dev/null | head -c 250 || true)"
    echo "Academic native dependency diagnostic: $host => ${status_probe:-unavailable}" >&2
  done
  fail "Timed out awaiting full independent frozen Academic/Attendance/Scheduling/Payroll proof"
fi
# Replay exact original source/generation proof after a simulated lost ACK.
activation_replay="$(curl -fsS -X POST "$private/activate/$class_id" \
  -H "X-Internal-Key: $key" -H "Authorization: Bearer $admin")" ||
  fail "An already committed Academic activation did not survive HTTP retry"
ACTIVATION="$activate" REPLAY="$activation_replay" python3 -c '
import json,os
assert json.loads(os.environ["ACTIVATION"])==json.loads(os.environ["REPLAY"])
' || fail "Native Academic activation replay returned a different result"
ACTIVATION="$activate" python3 -c '
import json,os
a=json.loads(os.environ["ACTIVATION"])
assert a["state"]=="REHEARSAL_ONLY" and a["importedRows"]>=1,a
' || fail "Native activation did not import the complete Core baseline"

# PLAN appends one future native-only lesson. Every originally planned Core
# lesson is preserved, but no write to Core/Attendance/Payroll is generated.
old_plan="$(core_sql "SELECT COALESCE(json_agg(json_build_object(
  'date',to_char(date,'YYYY-MM-DD'),
  'startTime',to_char(start_time,'HH24:MI'),
  'endTime',to_char(end_time,'HH24:MI'))
  ORDER BY date,start_time),'[]'::json)::text
  FROM class_sessions WHERE class_id='$class_id' AND status='PLANNED'")"
plan="$(CURRENT="$old_plan" python3 -c '
import os,json
p=json.loads(os.environ["CURRENT"])
p.append({"date":"2027-02-12","startTime":"18:00","endTime":"19:00"})
print(json.dumps(p,separators=(",",":")))
')"
plan_key="ci-native-plan-${GITHUB_RUN_ID}"
plan_result="$(curl -fsS -X PUT \
  "$private/classes/$class_id/planned-sessions" \
  -H "X-Internal-Key: $key" -H "Authorization: Bearer $admin" \
  -H "Idempotency-Key: $plan_key" \
  -H 'Content-Type: application/json' --data "$plan")" ||
  fail "Native Academic independent PLAN did not commit"
new_session="$(RESPONSE="$plan_result" python3 -c '
import os,json
rows=json.loads(os.environ["RESPONSE"])
matches=[x["id"] for x in rows if x["date"]=="2027-02-12" and
 x["startTime"]=="18:00" and x["endTime"]=="19:00"]
assert len(matches)==1,rows
print(matches[0])
')" || fail "Native PLAN did not create the expected lesson"
test -n "$new_session" || fail "Native PLAN returned an empty session ID"

replay="$(curl -fsS -X PUT "$private/classes/$class_id/planned-sessions" \
  -H "X-Internal-Key: $key" -H "Authorization: Bearer $admin" \
  -H "Idempotency-Key: $plan_key" \
  -H 'Content-Type: application/json' --data "$plan")" ||
  fail "Original Native PLAN idempotency replay failed"
test "$(printf '%s' "$replay" | jq -Sc .)" = "$(printf '%s' "$plan_result" | jq -Sc .)" ||
  fail "Native PLAN idempotency replay changed the committed outcome"

reschedule_key="ci-native-reschedule-${GITHUB_RUN_ID}"
reschedule="$(SESSION="$new_session" python3 -c '
import os,json
print(json.dumps({"originalSessionId":os.environ["SESSION"],
 "reason":"Disposable native academic rehearsal",
 "newDate":"2027-02-12","newStartTime":"19:00",
 "newEndTime":"20:00"},separators=(",",":")))
')"
curl -fsS -X POST "$private/classes/$class_id/reschedule" \
  -H "X-Internal-Key: $key" -H "Authorization: Bearer $admin" \
  -H "Idempotency-Key: $reschedule_key" \
  -H 'Content-Type: application/json' --data "$reschedule" >/dev/null ||
  fail "Native Academic independent RESCHEDULE failed"

test "$(academic_sql "SELECT COUNT(*) FROM academic_native_session_commands
  WHERE class_id='$class_id' AND actor_username='admin'")" = 2 ||
  fail "Native Academic command receipt was missing or duplicated"
test "$(academic_sql "SELECT COUNT(*) FROM academic_native_session_outbox
  WHERE class_id='$class_id' AND event_kind='REHEARSAL_ONLY'")" = 2 ||
  fail "Native Academic outbox was missing or duplicated"
test "$(academic_sql "SELECT COUNT(*) FROM academic_native_session_rows
  WHERE class_id='$class_id' AND source_imported=FALSE
    AND status='PLANNED'")" = 1 ||
  fail "Native RESCHEDULE failed to create one independent make-up lesson"
test "$(core_sql "SELECT COUNT(*) FROM class_sessions WHERE class_id='$class_id'")" = "$baseline" ||
  fail "Rehearsal incorrectly wrote to Core session table"
test "$(core_sql "SELECT COUNT(*) FROM academic_session_source_outbox")" = "$source_outbox" ||
  fail "Rehearsal incorrectly forged a Core committed source event"

# Every native operation must leave an EXACT Core RELEASED receipt, not a
# stale OPEN claim after a successful independent Academic DB commit.
test "$(core_sql "SELECT COUNT(*) FROM academic_native_command_claims
  WHERE class_id='$class_id' AND state='RELEASED'")" = 2 ||
  fail "Two native Academic commands did not acquire/release Core handback claims"
test "$(core_sql "SELECT COUNT(*) FROM academic_native_command_claims
  WHERE class_id='$class_id' AND state='OPEN'")" = 0 ||
  fail "An unresolved native Academic claim would block Core handback"
test "$(core_sql "SELECT COUNT(*) FROM academic_native_command_claim_audit
  WHERE class_id='$class_id'")" = 4 ||
  fail "Core native Academic claim audit did not record exact acquire/release pairs"

test "$(academic_sql "SELECT COUNT(*) FROM academic_native_claim_handoffs
  WHERE class_id='$class_id'")" = 2 ||
  fail "Academic did not durably store two exact Core native handoff receipts"
test "$(curl -sS -o /dev/null -w '%{http_code}' \
  "http://localhost:8080/internal/academic/native-command-claims/summary")" = 404 ||
  fail "Core native claim inspection leaked through public Gateway"

# A simulated crash *after Core claim admission but before Academic commit*
# must block BOTH SQL handbacks. Keep the claim inside an explicit Core SQL
# transaction and roll the fixture back; no compensating guess is permitted.
compose exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 <<SQL
BEGIN;
INSERT INTO academic_native_command_claims
  (actor_username,class_id,operation,request_key,request_hash,
   session_generation,roster_generation,attendance_generation,
   scheduling_generation)
SELECT 'admin','$class_id','PLAN','ci-crash-claim-$GITHUB_RUN_ID',
  repeat('f',64),a.generation,r.generation,t.generation,s.writer_generation
FROM academic_session_writer_control a,
     academic_roster_writer_control r,
     teacher_attendance_writer_control t,
     scheduling_assignment_writer_control s
WHERE a.singleton AND r.singleton AND t.singleton AND s.singleton;
DO \$\$
BEGIN
  BEGIN
    UPDATE academic_session_writer_control SET writer_mode='CORE'
      WHERE singleton=TRUE AND writer_mode='QUIESCED';
    RAISE EXCEPTION 'Core timetable handback incorrectly ignored OPEN native claim';
  EXCEPTION WHEN SQLSTATE '55000' THEN
    IF POSITION('Unreleased Academic' IN SQLERRM)=0 THEN RAISE; END IF;
  END;
  BEGIN
    UPDATE academic_roster_writer_control SET writer_mode='CORE'
      WHERE singleton=TRUE AND writer_mode='QUIESCED';
    RAISE EXCEPTION 'Core roster handback incorrectly ignored OPEN native claim';
  EXCEPTION WHEN SQLSTATE '55000' THEN
    IF POSITION('Unreleased native Academic' IN SQLERRM)=0 THEN RAISE; END IF;
  END;
END;
\$\$;
ROLLBACK;
SQL
test "$(core_sql "SELECT COUNT(*) FROM academic_native_command_claims
  WHERE state='OPEN'")" = 0 ||
  fail "Core retained a simulated disposable crash claim after rollback"
echo "PASS: Core native Academic claim admission/release, SQL handback exclusion and crash-before-local recovery barrier"

echo "PASS: Frozen Core writer, native Academic PLAN/RESCHEDULE, exact-key replay, independently committed PG rows/outbox, unchanged Core, private-only API"
