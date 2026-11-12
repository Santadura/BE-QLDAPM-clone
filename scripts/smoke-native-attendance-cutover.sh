#!/usr/bin/env bash
# DESTRUCTIVE-TO-FIXTURE ONLY. This entire stack and all ten Postgres volumes
# are destroyed by CI. Never run on production or a shared database.
set -euo pipefail
if [ "${GITHUB_ACTIONS:-}" != "true" ] || [ "${IIG_NATIVE_ATTENDANCE_SMOKE:-}" != "true" ] ||
   ! [[ "${GITHUB_RUN_ID:-}" =~ ^[0-9]+$ ]]; then
  echo "Native Attendance writer cutover requires disposable GitHub Actions" >&2
  exit 2
fi

COMPOSE=(docker compose -f docker-compose.microservices.yml)
BASE="http://localhost:8080"
key="${ATTENDANCE_INTERNAL_API_KEY:-local-dev-attendance-key-change-before-deployment}"
academic_key="${ACADEMIC_INTERNAL_API_KEY:-local-dev-academic-key-change-before-deployment}"
scheduling_key="${SCHEDULING_INTERNAL_API_KEY:-local-dev-scheduling-key-change-before-deployment}"
class_id="class-0001"
schedule_id="teaching-001"
claim_key="ci-native-orphan-${GITHUB_RUN_ID}"
second_key="ci-native-public-${GITHUB_RUN_ID}"
support_id="native-ci-support-${GITHUB_RUN_ID}"
FAIL() { echo "Native Attendance cutover E2E: $*" >&2; exit 1; }
core_sql() {
  "${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 -tAc "$1" | tr -d '\r\n'
}
attendance_sql() {
  "${COMPOSE[@]}" exec -T attendance-postgres psql -U iig_attendance -d iig_attendance -v ON_ERROR_STOP=1 -tAc "$1" | tr -d '\r\n'
}
private_get() {
  "${COMPOSE[@]}" exec -T scheduling-postgres wget -qO- --header "X-Internal-Key: $key" --header "Authorization: Bearer $cs_token" "$1"
}
private_post() {
  "${COMPOSE[@]}" exec -T scheduling-postgres wget -qO- --header "X-Internal-Key: $key" --header "Authorization: Bearer $cs_token" --header "Content-Type: application/json" --post-data "$2" "$1"
}
health() {
  "${COMPOSE[@]}" exec -T scheduling-postgres wget -qO- "$1" 2>/dev/null | grep -q '"UP"'
}
core_ready() {
  # Core has no spring-boot-starter-actuator. Reuse its already-tested
  # private Scheduling writer-state endpoint rather than a nonexistent 404.
  "${COMPOSE[@]}" exec -T scheduling-postgres wget -qO- \
    --header "X-Internal-Key: $scheduling_key" \
    http://backend:8081/internal/scheduling/assignments/writer-state \
    >/dev/null 2>&1
}
login() {
  curl -fsS -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d "$1" |
    python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])'
}

# The default smoke intentionally revokes cs001's exact support shift.
# Restore a NEW, auditable disposable shift before freezing Scheduling; no
# stored/replayed CS credentials may be used to bypass current support ACL.
"${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 >/dev/null <<SQL
INSERT INTO staff_schedules(staff_schedule_id,employee_id,class_id,date,
 start_time,end_time,work_type,status)
VALUES('$support_id','cs-001','class-0001','2026-10-06',
 '18:30','20:30','CLASS_SUPPORT','ASSIGNED');
SQL

# Even on a failure no code path should silently leave an independent writer
# active on a different machine. The CI runner then destroys all DB volumes.
cleanup() {
  local result=$?
  trap - EXIT
  if [ "$result" -ne 0 ]; then
    echo "FAIL: preserving original Core/Attendance container logs until CI destroys disposable volumes" >&2
    exit "$result"
  fi
  if [ "${started:-false}" = true ]; then
    "${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 >/dev/null <<'SQL' || true
BEGIN;
SELECT set_config('iig.attendance_handback_approved','true',true);
UPDATE teacher_attendance_writer_control SET writer_mode='QUIESCED'
 WHERE singleton=TRUE AND writer_mode='ATTENDANCE';
UPDATE academic_session_writer_control SET writer_mode='CORE'
 WHERE singleton=TRUE AND writer_mode='QUIESCED';
UPDATE teacher_attendance_writer_control SET writer_mode='CORE'
 WHERE singleton=TRUE AND writer_mode='QUIESCED';
UPDATE scheduling_assignment_writer_control SET writer_mode='CORE'
 WHERE singleton=TRUE AND writer_mode='QUIESCED';
UPDATE academic_roster_writer_control SET writer_mode='CORE'
 WHERE singleton=TRUE AND writer_mode='QUIESCED';
COMMIT;
SQL
    core_sql "DELETE FROM staff_schedules WHERE staff_schedule_id='$support_id'" >/dev/null || true
    ATTENDANCE_NATIVE_WRITER_ENABLED=false ATTENDANCE_NATIVE_RECOVERY_ENABLED=false ATTENDANCE_NATIVE_ADMISSION_ENABLED=false "${COMPOSE[@]}" up -d --no-deps --force-recreate attendance-service >/dev/null || true
    ATTENDANCE_NATIVE_REFERENCE_ENABLED=false "${COMPOSE[@]}" up -d --no-deps --force-recreate backend >/dev/null || true
  fi
  exit "$result"
}
trap cleanup EXIT

admin_token="$(login '{"username":"admin","password":"123456"}')"
cs_token="$(login '{"username":"cs001","password":"123456"}')"
test -n "$admin_token" && test -n "$cs_token" || FAIL "Missing authenticated Admin or CS"

# No speculative writer promotion; first stop every conflicting source.
started=true
"${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 >/dev/null <<'SQL'
BEGIN;
UPDATE academic_roster_writer_control SET writer_mode='QUIESCED'
 WHERE singleton=TRUE AND writer_mode='CORE';
UPDATE scheduling_assignment_writer_control SET writer_mode='QUIESCED'
 WHERE singleton=TRUE AND writer_mode='CORE';
UPDATE teacher_attendance_writer_control SET writer_mode='QUIESCED'
 WHERE singleton=TRUE AND writer_mode='CORE';
UPDATE academic_session_writer_control SET writer_mode='QUIESCED'
 WHERE singleton=TRUE AND writer_mode='CORE';
COMMIT;
SQL

# These are NEW service flags, still disabled in normal production config.
ATTENDANCE_NATIVE_REFERENCE_ENABLED=true "${COMPOSE[@]}" up -d --no-deps --force-recreate backend >/dev/null
ATTENDANCE_NATIVE_ADMISSION_ENABLED=true ATTENDANCE_NATIVE_WRITER_ENABLED=false ATTENDANCE_NATIVE_RECOVERY_ENABLED=false "${COMPOSE[@]}" up -d --no-deps --force-recreate attendance-service >/dev/null
for i in $(seq 1 90); do
  if core_ready && health http://attendance-service:8100/actuator/health; then break; fi
  if [ "$i" = 90 ]; then FAIL "Native-ready Core/Attendance did not become healthy"; fi
  sleep 2
done

# Both independent history and Academic/Scheduling outbox receipts must be
# verified before any native owner generation is minted.
activated=""
for i in $(seq 1 75); do
  activated="$("${COMPOSE[@]}" exec -T scheduling-postgres wget -qO- --header "X-Internal-Key: $key" --header "Authorization: Bearer $admin_token" --header "Content-Type: application/json" --post-data '{}' http://attendance-service:8100/internal/attendance/native/activate 2>/dev/null || true)"
  if ACTIVATION="$activated" python3 -c 'import json,os; d=json.loads(os.environ["ACTIVATION"]); assert d["state"]=="BOOTSTRAPPED_AWAITING_CORE_HANDOFF"' >/dev/null 2>&1; then break; fi
  if [ "$i" = 75 ]; then FAIL "Frozen independent full-history activation did not complete"; fi
  sleep 2
done
generation="$(ACTIVATION="$activated" python3 -c 'import json,os;print(json.loads(os.environ["ACTIVATION"])["writerGeneration"])')"
test "$generation" = "$(core_sql "SELECT generation+1 FROM teacher_attendance_writer_control WHERE singleton=TRUE")" ||
  FAIL "Native bootstrap generation differs from the fenced Core writer"

"${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 >/dev/null <<'SQL'
BEGIN;
SELECT set_config('iig.attendance_cutover_approved','true',true);
UPDATE teacher_attendance_writer_control SET writer_mode='ATTENDANCE'
 WHERE singleton=TRUE AND writer_mode='QUIESCED';
COMMIT;
SQL
test "$(core_sql "SELECT writer_mode FROM teacher_attendance_writer_control WHERE singleton=TRUE")" = "ATTENDANCE" ||
  FAIL "Native owner never received exclusive Core writer fence"

# Native Core owns the claim; Attendance has not yet staged a command.
# This is a deterministic crash-before-local-transaction simulation.
facts="$(private_get "http://backend:8081/internal/attendance/lesson-facts/native?classId=$class_id&teachingScheduleId=$schedule_id")"
token="$(printf '%s' "$facts" | python3 -c 'import json,sys;print(json.load(sys.stdin)["token"])')"
request_hash="$(python3 -c 'import hashlib;print(hashlib.sha256(b"v1\nclass-0001\nteaching-001\nPRESENT").hexdigest())')"
claim_body="$(FACT_TOKEN="$token" HASH="$request_hash" KEY="$claim_key" python3 -c 'import json,os;print(json.dumps(dict(classId="class-0001",teachingScheduleId="teaching-001",requestKey=os.environ["KEY"],requestHash=os.environ["HASH"],status="PRESENT",factsToken=os.environ["FACT_TOKEN"])))')"
claim="$(private_post http://backend:8081/internal/attendance/native/claims "$claim_body")"
test "$(printf '%s' "$claim" | python3 -c 'import json,sys;print(json.load(sys.stdin)["state"])')" = "OPEN" ||
  FAIL "Core did not commit original immutable native claim"
test "$(attendance_sql "SELECT count(*) FROM attendance_native_commands WHERE request_key='$claim_key'")" = 0 ||
  FAIL "Crash-before-local test incorrectly staged Attendance first"
test "$(core_sql "SELECT COUNT(*) FROM teacher_attendance_native_claims WHERE request_key='$claim_key' AND state='OPEN'")" = 1 ||
  FAIL "Core did not retain its durable OPEN writer claim"

before_events="$(core_sql "SELECT COUNT(*) FROM teacher_attendance_source_outbox WHERE teaching_schedule_id='$schedule_id'")"
before_audit="$(core_sql "SELECT COUNT(*) FROM audit_logs WHERE action='RECORD_TEACHER_ATTENDANCE_PRESENT' AND entity_id='$schedule_id'")"
ATTENDANCE_NATIVE_WRITER_ENABLED=true ATTENDANCE_NATIVE_RECOVERY_ENABLED=true ATTENDANCE_NATIVE_ADMISSION_ENABLED=true "${COMPOSE[@]}" up -d --no-deps --force-recreate attendance-service >/dev/null

result=""
for i in $(seq 1 85); do
  result="$(attendance_sql "SELECT state FROM attendance_native_commands WHERE request_key='$claim_key'" 2>/dev/null || true)"
  [ "$result" = COMMITTED ] && break
  if [ "$i" = 85 ]; then FAIL "Crash-before-local Core claim was not recovered to native COMMITTED"; fi
  sleep 2
done
test "$(core_sql "SELECT COUNT(*) FROM teacher_attendance_native_claims WHERE request_key='$claim_key' AND state='CONFIRMED'")" = 1 ||
  FAIL "Core orphan claim never finalized"
test "$(attendance_sql "SELECT COUNT(*) FROM attendance_native_outbox WHERE request_key='$claim_key' AND confirmed_at IS NOT NULL")" = 1 ||
  FAIL "Native outbox did not store exact Core confirmation"
test "$(core_sql "SELECT COUNT(*) FROM teacher_attendance_native_reference_receipts WHERE request_key='$claim_key'")" = 1 ||
  FAIL "Native business reference was duplicated or absent"
after_events="$(core_sql "SELECT COUNT(*) FROM teacher_attendance_source_outbox WHERE teaching_schedule_id='$schedule_id'")"
after_audit="$(core_sql "SELECT COUNT(*) FROM audit_logs WHERE action='RECORD_TEACHER_ATTENDANCE_PRESENT' AND entity_id='$schedule_id'")"
test "$after_events" -eq $((before_events+1)) && test "$after_audit" -eq $((before_audit+1)) ||
  FAIL "Crash recovery replayed or dropped Core attendance/audit mutations"
echo "PASS: native Attendance Core OPEN claim survived crash BEFORE local inbox and auto-committed exactly once"

# A new CS request must now enter the independent Attendance PostgreSQL.
public_url="$BASE/api/classes/$class_id/teacher-attendance/$schedule_id"
# Preserve the real Spring/Gateway response body when the second native
# command fails. A generic curl -f 409 obscures whether the rejected step
# was facts, Core claim, independent ledger or Core reference projection.
http_code="$(curl -sS -o /tmp/iig-native-second-response.json -w '%{http_code}' \\
  -X PUT "$public_url" -H "Authorization: Bearer $cs_token" \\
  -H "Idempotency-Key: $second_key" \\
  -H 'Content-Type: application/json' -d '{"status":"LATE"}')"
if [ "$http_code" != 200 ]; then
  echo "Native Attendance public PUT HTTP $http_code: $(cat /tmp/iig-native-second-response.json)" >&2
  echo "Core claim: $(core_sql "SELECT COALESCE(string_agg(request_key || ':' || state,','),'<none>') FROM teacher_attendance_native_claims WHERE teaching_schedule_id='$schedule_id'")" >&2
  echo "Local commands: $(attendance_sql "SELECT COALESCE(string_agg(request_key || ':' || state,','),'<none>') FROM attendance_native_commands WHERE teaching_schedule_id='$schedule_id'")" >&2
  echo "Local baseline: $(attendance_sql "SELECT status || ':' || version FROM attendance_native_teacher_rows WHERE teaching_schedule_id='$schedule_id'")" >&2
  FAIL "Native Attendance second CS command rejected at HTTP $http_code"
fi
reply="$(cat /tmp/iig-native-second-response.json)"
test "$(printf '%s' "$reply" | python3 -c 'import json,sys;print(json.load(sys.stdin)["status"])')" = LATE ||
  FAIL "Public CS native Attendance command returned wrong business result"
test "$(attendance_sql "SELECT COUNT(*) FROM attendance_native_commands WHERE request_key='$second_key' AND state='COMMITTED'")" = 1 ||
  FAIL "Public native Attendance command did not commit independently"
test "$(attendance_sql "SELECT version FROM attendance_native_teacher_rows WHERE teaching_schedule_id='$schedule_id'")" = 2 ||
  FAIL "Native domain row did not advance to revision 2"
replayed="$(curl -fsS -X PUT "$public_url" -H "Authorization: Bearer $cs_token" -H "Idempotency-Key: $second_key" -H 'Content-Type: application/json' -d '{"status":"LATE"}')"
test "$replayed" = "$reply" ||
  FAIL "Native retry response differs from immutable original"
test "$(core_sql "SELECT COUNT(*) FROM teacher_attendance_native_reference_receipts WHERE teaching_schedule_id='$schedule_id'")" = 2 ||
  FAIL "Native replay created an extra Core reference receipt"
test "$(curl -sS -o /dev/null -w '%{http_code}' -X PUT http://backend:8081/api/classes/$class_id/teacher-attendance/$schedule_id -H "Authorization: Bearer $cs_token" -H 'Content-Type: application/json' -d '{"status":"ABSENT"}')" != 200 ||
  FAIL "Core still acts as a parallel business writer"
echo "PASS: native Attendance public CS PUT, same-key replay and Core dual-writer exclusion"

# Stop all external mutations, reconcile independent full history, and prove
# no OPEN claim before restoring Core ownership. This must NOT be bypassed.
"${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 >/dev/null <<'SQL'
BEGIN;
SELECT set_config('iig.attendance_handback_approved','true',true);
UPDATE teacher_attendance_writer_control SET writer_mode='QUIESCED'
 WHERE singleton=TRUE AND writer_mode='ATTENDANCE';
COMMIT;
SQL
test "$(core_sql "SELECT COUNT(*) FROM teacher_attendance_native_claims WHERE state='OPEN'")" = 0 ||
  FAIL "Core handback has unsettled native claims"
for i in $(seq 1 65); do
  verified="$("${COMPOSE[@]}" exec -T scheduling-postgres wget -qO- --header "X-Internal-Key: $key" http://attendance-service:8100/internal/attendance/verify/frozen 2>/dev/null || true)"
  if VERIFIED="$verified" python3 -c 'import json,os; d=json.loads(os.environ["VERIFIED"]); assert d["ready"] is True' >/dev/null 2>&1; then break; fi
  if [ "$i" = 65 ]; then FAIL "Independent Attendance history did not converge before handback"; fi
  sleep 2
done
echo "PASS: frozen native Attendance history complete, zero unsettled claims, ready for Core handback"
