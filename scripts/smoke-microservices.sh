#!/usr/bin/env bash
# Run only against the disposable microservices compose database.
# Verifies gateway compatibility, durable delivery, and outage recovery.
set -euo pipefail
COMPOSE=(docker compose -f docker-compose.microservices.yml)
BASE="${IIG_GATEWAY_URL:-http://localhost:8080}"
for i in $(seq 1 120); do
  if curl --fail --silent "${BASE}/actuator/health" | grep -q '"UP"'; then break; fi
  if [ "$i" -eq 120 ]; then echo "Gateway not ready"; exit 1; fi
  sleep 3
done

# Wait for the backend to finish booting; the gateway may become healthy first.
response=""
for i in $(seq 1 100); do
  if response="$(curl --fail --silent -X POST "${BASE}/api/auth/login" -H 'Content-Type: application/json' -d '{"username":"admin","password":"123456"}' 2>/dev/null)" && printf '%s' "$response" | grep -q '"token"'; then
    break
  fi
  if [ "$i" -eq 100 ]; then echo "Core login did not become ready through Gateway"; exit 1; fi
  sleep 3
done
TOKEN="$(printf '%s' "$response" | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')"
test -n "$TOKEN"
echo "PASS: legacy JWT login works through Spring Cloud Gateway"
# Browsers require a preflight OPTIONS request (curl login alone does not).
# Verify Gateway accepts the origin exactly once before starting Playwright.
preflight="$(curl -sS -D - -o /dev/null -X OPTIONS "${BASE}/api/auth/login" \
  -H 'Origin: http://localhost:5173' \
  -H 'Access-Control-Request-Method: POST' \
  -H 'Access-Control-Request-Headers: content-type')"
count="$(printf '%s' "$preflight" | tr -d '\r' | grep -ic 'access-control-allow-origin: http://localhost:5173' || true)"
if [ "$count" != "1" ]; then
  echo "Gateway CORS preflight missing or duplicated the allowed origin:"
  printf '%s\n' "$preflight"
  exit 1
fi
echo "PASS: React browser CORS preflight returns a single origin header"
# Internal write API must not become a public Gateway route.
code="$(curl -sS -o /dev/null -w '%{http_code}' "${BASE}/internal/audit/events")"
test "$code" = "404"
echo "PASS: private audit API is not exposed through Gateway"

audit_count() {
  "${COMPOSE[@]}" exec -T audit-postgres psql -U iig_audit -d iig_audit -tAc \
    "SELECT count(*) FROM audit_events WHERE action='CREATE_CLASS';" | tr -d '[:space:]'
}
create_class() {
  local code="$1"
  curl --fail --silent --show-error -X POST "${BASE}/api/classes" \
    -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' \
    -d "{\"courseId\":\"course-ielts\",\"classCode\":\"${code}\",\"name\":\"Gateway smoke ${code}\",\"startDate\":\"2027-02-10\",\"endDate\":\"2027-02-12\",\"targetRequirements\":[{\"targetType\":\"OVERALL_BAND\",\"requiredTarget\":5}],\"plannedSessions\":[{\"date\":\"2027-02-11\",\"startTime\":\"09:00\",\"endTime\":\"11:00\"}]}" \
    | python3 -c 'import json,sys; x=json.load(sys.stdin); assert x["status"]=="DRAFT",x; print(x["classId"])'
}
wait_audit_count() {
  local minimum="$1"
  for i in $(seq 1 50); do
    local count
    count="$(audit_count)"
    if [[ "$count" =~ ^[0-9]+$ ]] && [ "$count" -ge "$minimum" ]; then return 0; fi
    sleep 2
  done
  echo "Audit microservice did not receive $minimum events"; exit 1
}

before="$(audit_count)"
one="$(create_class "MS-A-${GITHUB_RUN_ID:-LOCAL}")"
wait_audit_count "$((before+1))"
echo "PASS: class $one committed and projected to independent audit database"

"${COMPOSE[@]}" stop audit-service
two="$(create_class "MS-B-${GITHUB_RUN_ID:-LOCAL}")"
echo "PASS: core business transaction continues during audit-service outage ($two)"
"${COMPOSE[@]}" start audit-service
wait_audit_count "$((before+2))"
echo "PASS: durable outbox replayed the pending audit event after recovery"

# Both projections have unique event IDs and the source acknowledged delivery.
acks="$("${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -tAc \
  "SELECT count(*) FROM audit_outbox_deliveries d JOIN audit_logs a ON a.audit_log_id=d.audit_log_id WHERE a.action='CREATE_CLASS';" | tr -d '[:space:]')"
test "$acks" -ge 2
echo "PASS: original committed events are acknowledged after delivery"

# Academic GETs now traverse an independently deployed service and DB, while
# Core still enforces the LIVE JWT, class visibility and all session mutations.
# Always compare the public route with the authoritative Core response so the
# FE JSON shape cannot drift during read extraction.
academic_json=""
for attempt in $(seq 1 30); do
  if academic_json="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" \
      "${BASE}/api/class-planning" 2>/dev/null)"; then break; fi
  sleep 2
done
printf '%s' "$academic_json" | python3 -c \
  'import json,sys; assert isinstance(json.load(sys.stdin),list)'
core_planning="$("${COMPOSE[@]}" exec -T scheduling-postgres wget -qO- \
  --header "Authorization: Bearer ${TOKEN}" \
  "http://backend:8081/api/class-planning")"
CORE_PLANNING_JSON="$core_planning" ACADEMIC_PLANNING_JSON="$academic_json" python3 -c \
  'import os,json; assert json.loads(os.environ["CORE_PLANNING_JSON"]) == json.loads(os.environ["ACADEMIC_PLANNING_JSON"])'
if [ "$(curl -sS -o /dev/null -w '%{http_code}' "${BASE}/api/class-planning")" != "401" ]; then
  echo "Academic planning route did not enforce live JWT"; exit 1
fi
new_planned="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" \
  "${BASE}/api/classes/${one}/planned-sessions")"
core_planned="$("${COMPOSE[@]}" exec -T scheduling-postgres wget -qO- \
  --header "Authorization: Bearer ${TOKEN}" \
  "http://backend:8081/api/classes/${one}/planned-sessions")"
CORE_LESSONS_JSON="$core_planned" ACADEMIC_LESSONS_JSON="$new_planned" python3 -c \
  'import json,os; a=json.loads(os.environ["ACADEMIC_LESSONS_JSON"]); b=json.loads(os.environ["CORE_LESSONS_JSON"]); assert isinstance(a,list) and a==b'
academic_rows="$("${COMPOSE[@]}" exec -T academic-postgres psql -U iig_academic \
  -d iig_academic -tAc "SELECT count(*) FROM academic_read_snapshots;" | tr -d '[:space:]')"
planning_revision="$("${COMPOSE[@]}" exec -T academic-postgres psql -U iig_academic \
  -d iig_academic -tAc "SELECT source_revision FROM academic_planning_projection WHERE name='planning';" | tr -d '[:space:]')"
core_planning_revision="$("${COMPOSE[@]}" exec -T postgres psql -U postgres \
  -d qlapm_db -tAc "SELECT revision FROM academic_planning_revision WHERE singleton=TRUE;" | tr -d '[:space:]')"
if ! [[ "$academic_rows" =~ ^[0-9]+$ ]] || [ "$academic_rows" -lt 1 ] \
  || [ "$planning_revision" != "$core_planning_revision" ]; then
  echo "Academic planning revision is not synchronized or session snapshots were not stored"; exit 1
fi
echo "PASS: Academic owned planning projection matches Core source revision and live ACL"

# Attendance read extraction has its own DB but Core remains the actual
# attendance writer. The seeded cs001 owns class-0001 scope; compare the
# exact FE-visible JSON to Core under the same live CS token.
cs_login="$(curl -fsS -X POST "${BASE}/api/auth/login" \
  -H 'Content-Type: application/json' -d '{"username":"cs001","password":"123456"}')"
cs_token="$(printf '%s' "$cs_login" | python3 -c \
  'import json,sys; print(json.load(sys.stdin)["token"])')"
attendance_api="${BASE}/api/classes/class-0001/teacher-attendance"
attendance_view="$(curl -fsS -H "Authorization: Bearer ${cs_token}" "$attendance_api")"
core_attendance="$("${COMPOSE[@]}" exec -T scheduling-postgres wget -qO- \
  --header "Authorization: Bearer ${cs_token}" \
  "http://backend:8081/api/classes/class-0001/teacher-attendance")"
ATTENDANCE_SOURCE="$core_attendance" ATTENDANCE_READ="$attendance_view" python3 -c \
  'import os,json; a=json.loads(os.environ["ATTENDANCE_READ"]); b=json.loads(os.environ["ATTENDANCE_SOURCE"]); assert isinstance(a,list) and a==b and any(row.get("id")=="teaching-001" for row in a), (a,b)'
attendance_rows="$("${COMPOSE[@]}" exec -T attendance-postgres \
  psql -U iig_attendance -d iig_attendance -tAc \
  "SELECT count(*) FROM attendance_teacher_read_snapshots WHERE actor_username='cs001' AND class_id='class-0001';" | tr -d '[:space:]')"
test "$attendance_rows" = "1" || { echo "Attendance separate DB did not record scoped read"; exit 1; }
test "$(curl -sS -o /dev/null -w '%{http_code}' "$attendance_api")" = "401" || {
  echo "Attendance exposed records without JWT"; exit 1;
}
test "$(curl -sS -o /dev/null -w '%{http_code}' \
  -H "Authorization: Bearer ${TOKEN}" "$attendance_api")" = "403" || {
  echo "Attendance allowed ADMIN to read CS-only attendance"; exit 1;
}
test "$(curl -sS -o /dev/null -w '%{http_code}' -X PUT \
  -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' \
  -d '{"status":"PRESENT"}' "${attendance_api}/teaching-001")" = "403" || {
  echo "Teacher attendance PUT is not still protected by Core's CS-only writer"; exit 1;
}
echo "PASS: independent Attendance GET matches Core, scopes CS snapshots, protects JWT and leaves PUT in Core"

# Phase 8f: all Core attendance writes must publish a durable source event.
# Attendance imports into its own PostgreSQL before acknowledging the
# precise event/hash back to Core. Disposable fixture exercises all three
# event types (insert, update, delete) and duplicate-safe delivery.
attendance_fixture="ms-attendance-${GITHUB_RUN_ID:-local}"
"${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 \
  -c "INSERT INTO teacher_session_attendance(attendance_id,teaching_schedule_id,status,recorded_by) VALUES('${attendance_fixture}','teaching-001','PRESENT','cs-001')"
attendance_imported=0
for i in $(seq 1 60); do
  attendance_imported="$("${COMPOSE[@]}" exec -T attendance-postgres psql -U iig_attendance \
    -d iig_attendance -tAc "SELECT count(*) FROM attendance_teacher_source_records WHERE teaching_schedule_id='teaching-001' AND event_kind='UPSERT' AND payload->>'attendance_id'='${attendance_fixture}' AND payload->>'status'='PRESENT'" | tr -d '[:space:]')"
  [ "$attendance_imported" = "1" ] && break
  sleep 2
done
test "$attendance_imported" = "1" || { echo "Attendance did not import Core INSERT event"; exit 1; }

first_attendance_view="$(curl -fsS -H "Authorization: Bearer ${cs_token}" "$attendance_api")"
ATTENDANCE_VIEW="$first_attendance_view" python3 -c \
  'import json,os; rows=json.loads(os.environ["ATTENDANCE_VIEW"]); assert any(x.get("id")=="teaching-001" and x.get("attendanceStatus")=="PRESENT" for x in rows)'


"${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 \
  -c "UPDATE teacher_session_attendance SET status='LATE',updated_at=NOW() WHERE attendance_id='${attendance_fixture}'"
updated_imported=0
for i in $(seq 1 60); do
  updated_imported="$("${COMPOSE[@]}" exec -T attendance-postgres psql -U iig_attendance \
    -d iig_attendance -tAc "SELECT count(*) FROM attendance_teacher_source_records WHERE teaching_schedule_id='teaching-001' AND event_kind='UPSERT' AND payload->>'attendance_id'='${attendance_fixture}' AND payload->>'status'='LATE'" | tr -d '[:space:]')"
  [ "$updated_imported" = "1" ] && break
  sleep 2
done
test "$updated_imported" = "1" || { echo "Attendance did not apply the updated source revision"; exit 1; }

latest_attendance_view="$(curl -fsS -H "Authorization: Bearer ${cs_token}" "$attendance_api")"
ATTENDANCE_VIEW="$latest_attendance_view" python3 -c \
  'import json,os; rows=json.loads(os.environ["ATTENDANCE_VIEW"]); assert any(x.get("id")=="teaching-001" and x.get("attendanceStatus")=="LATE" for x in rows)'

# If a local projection is corrupted or lags Core, Attendance MUST fail
# closed instead of serving the last cached value. Repair the CI-only row.
"${COMPOSE[@]}" exec -T attendance-postgres psql -U iig_attendance -d iig_attendance -v ON_ERROR_STOP=1 \
  -c "UPDATE attendance_teacher_source_records SET payload=jsonb_set(payload,'{status}','\"ABSENT\"'::jsonb) WHERE teaching_schedule_id='teaching-001'"
mismatch_http="$(curl -sS -o /dev/null -w '%{http_code}' \
  -H "Authorization: Bearer ${cs_token}" "$attendance_api")"
if [ "$mismatch_http" != "503" ]; then
  echo "Attendance served a corrupted local projection instead of refusing: HTTP ${mismatch_http}"; exit 1
fi
"${COMPOSE[@]}" exec -T attendance-postgres psql -U iig_attendance -d iig_attendance -v ON_ERROR_STOP=1 \
  -c "UPDATE attendance_teacher_source_records SET payload=jsonb_set(payload,'{status}','\"LATE\"'::jsonb) WHERE teaching_schedule_id='teaching-001'"


"${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 \
  -c "DELETE FROM teacher_session_attendance WHERE attendance_id='${attendance_fixture}'"
deleted_imported=0
for i in $(seq 1 60); do
  deleted_imported="$("${COMPOSE[@]}" exec -T attendance-postgres psql -U iig_attendance \
    -d iig_attendance -tAc "SELECT count(*) FROM attendance_teacher_source_records WHERE teaching_schedule_id='teaching-001' AND event_kind='DELETE' AND payload->>'attendance_id'='${attendance_fixture}'" | tr -d '[:space:]')"
  [ "$deleted_imported" = "1" ] && break
  sleep 2
done
test "$deleted_imported" = "1" || { echo "Attendance failed to retain the source deletion tombstone"; exit 1; }

deleted_attendance_view="$(curl -fsS -H "Authorization: Bearer ${cs_token}" "$attendance_api")"
ATTENDANCE_VIEW="$deleted_attendance_view" python3 -c \
  'import json,os; rows=json.loads(os.environ["ATTENDANCE_VIEW"]); assert any(x.get("id")=="teaching-001" and x.get("attendanceStatus") is None for x in rows)'


source_confirmed="$("${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db \
  -tAc "SELECT count(*) FROM teacher_attendance_source_outbox WHERE payload->>'attendance_id'='${attendance_fixture}' AND delivered_at IS NOT NULL" | tr -d '[:space:]')"
import_receipts="$("${COMPOSE[@]}" exec -T attendance-postgres psql -U iig_attendance \
  -d iig_attendance -tAc "SELECT count(*) FROM attendance_import_receipts WHERE teaching_schedule_id='teaching-001'" | tr -d '[:space:]')"
if [ "$source_confirmed" != "3" ] || ! [[ "$import_receipts" =~ ^[0-9]+$ ]] || [ "$import_receipts" -lt 3 ]; then
  echo "Attendance event receipt mismatch: Core confirmed=${source_confirmed}, imported=${import_receipts}"; exit 1
fi
attendance_private_url="http://backend:8081/internal/attendance/events?limit=10"
if "${COMPOSE[@]}" exec -T attendance-postgres wget -qO- \
  "$attendance_private_url" >/dev/null 2>&1; then
  echo "Core Attendance outbox did not enforce its private service key"; exit 1
fi
if [ "$(curl -sS -o /dev/null -w '%{http_code}' "${BASE}/internal/attendance/events")" != "404" ]; then
  echo "Attendance source feed was exposed through public Gateway"; exit 1
fi
echo "PASS: Attendance-owned status read stays Core-consistent across INSERT/UPDATE/DELETE, rejects projection drift and confirms immutable receipts"

# Phase 8f-3: Attendance alone accepts the public PUT in microservices mode.
# Without an assigned exact CS support shift even the managing CS is denied.
attendance_write="${attendance_api}/teaching-001"
test "$(curl -sS -o /dev/null -w '%{http_code}' -X PUT \
  -H 'Content-Type: application/json' -d '{"status":"PRESENT"}' "$attendance_write")" = "401" || {
  echo "Attendance PUT accepted a missing JWT"; exit 1;
}
test "$(curl -sS -o /dev/null -w '%{http_code}' -X PUT \
  -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' \
  -d '{"status":"PRESENT"}' "$attendance_write")" = "403" || {
  echo "Attendance PUT accepted an ADMIN as recording CS"; exit 1;
}
test "$(curl -sS -o /dev/null -w '%{http_code}' -X PUT \
  -H "Authorization: Bearer ${cs_token}" -H 'Content-Type: application/json' \
  -d '{"status":"PRESENT"}' "$attendance_write")" = "403" || {
  echo "Attendance PUT accepted a CS with no exact support shift"; exit 1;
}
attendance_support="ms-attendance-cs-${GITHUB_RUN_ID:-local}"
"${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 \
  -c "INSERT INTO staff_schedules(staff_schedule_id,employee_id,class_id,date,start_time,end_time,work_type,status) VALUES('${attendance_support}','cs-001','class-0001','2026-10-06','18:30','20:30','CLASS_SUPPORT','ASSIGNED')"
# Phase 8f-7: real versioned Academic/teacher/CS precondition.
# Core is the only writer; Attendance only receives a checked HTTP token.
attendance_key="${ATTENDANCE_INTERNAL_API_KEY:-local-dev-attendance-key-change-before-deployment}"
lesson_facts_url="http://backend:8081/internal/attendance/lesson-facts?classId=class-0001&teachingScheduleId=teaching-001"
if "${COMPOSE[@]}" exec -T attendance-postgres wget -qO- \
  --header "Authorization: Bearer ${cs_token}" "$lesson_facts_url" >/dev/null 2>&1; then
  echo "Private Attendance Academic/CS facts accepted a missing service key"; exit 1
fi
if "${COMPOSE[@]}" exec -T attendance-postgres wget -qO- \
  --header "X-Internal-Key: ${attendance_key}" "$lesson_facts_url" >/dev/null 2>&1; then
  echo "Private Attendance Academic/CS facts accepted a missing JWT"; exit 1
fi
if [ "$(curl -sS -o /dev/null -w '%{http_code}' "${BASE}/internal/attendance/lesson-facts")" != "404" ]; then
  echo "Private Attendance Academic/CS facts were exposed through Gateway"; exit 1
fi
lesson_facts_before="$("${COMPOSE[@]}" exec -T attendance-postgres wget -qO- \
  --header "X-Internal-Key: ${attendance_key}" \
  --header "Authorization: Bearer ${cs_token}" "$lesson_facts_url")"
stale_lesson_token="$(FACTS="$lesson_facts_before" python3 -c \
  'import json,os; f=json.loads(os.environ["FACTS"]); assert f["sessionVersion"]>=1 and f["classVersion"]>=1 and f["teacherVersion"]>=1 and f["supportVersion"]>=1 and f["actorEmployeeId"]=="cs-001"; print(f["token"])')"

# A concurrent Core Scheduling change bumps the teacher/CS source revision.
# This fixtures changes only the support workplace, preserving assigned
# CS coverage so the stale token is rejected for VERSION, not missing CS.
"${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 \
  -c "UPDATE staff_schedules SET workplace_id='center-01' WHERE staff_schedule_id='${attendance_support}'"
lesson_facts_after="$("${COMPOSE[@]}" exec -T attendance-postgres wget -qO- \
  --header "X-Internal-Key: ${attendance_key}" \
  --header "Authorization: Bearer ${cs_token}" "$lesson_facts_url")"
FIRST_FACTS="$lesson_facts_before" SECOND_FACTS="$lesson_facts_after" python3 -c \
  'import os,json; a=json.loads(os.environ["FIRST_FACTS"]); b=json.loads(os.environ["SECOND_FACTS"]); assert a["token"]!=b["token"] and b["supportVersion"]>a["supportVersion"] and a["sessionVersion"]==b["sessionVersion"] and a["teacherVersion"]==b["teacherVersion"],(a,b)'
echo "PASS: Core live Academic/CS context token invalidates on independently revisioned support data"

# POST/PUT cannot be sent by BusyBox wget, so issue a raw HTTP/1.1 PUT
# from the disposable Core container. Body length and Connection: close
# are explicit, no public Gateway/private endpoint routing is added.
stale_command_key="ms-attendance-stale-${GITHUB_RUN_ID:-local}"
stale_reply="$("${COMPOSE[@]}" exec -T \
  -e IIG_CS_TOKEN="${cs_token}" -e IIG_PRIVATE_KEY="${attendance_key}" \
  -e IIG_STALE_TOKEN="${stale_lesson_token}" \
  -e IIG_STALE_KEY="${stale_command_key}" backend bash -c '
  set -e
  body="{\"status\":\"PRESENT\"}"
  exec 3<>/dev/tcp/127.0.0.1/8081
  printf "PUT /internal/attendance/commands/classes/class-0001/teacher-attendance/teaching-001 HTTP/1.1\r\nHost: localhost\r\nX-Internal-Key: %s\r\nAuthorization: Bearer %s\r\nIdempotency-Key: %s\r\nX-Attendance-Lesson-Token: %s\r\nContent-Type: application/json\r\nContent-Length: %d\r\nConnection: close\r\n\r\n%s" \
    "$IIG_PRIVATE_KEY" "$IIG_CS_TOKEN" "$IIG_STALE_KEY" "$IIG_STALE_TOKEN" \
    "${#body}" "$body" >&3
  cat <&3
' | tr -d '\r')"
if ! printf '%s' "$stale_reply" | grep -Eq '^HTTP/1[.]1 409|^HTTP/1[.]0 409'; then
  echo "Core accepted stale Attendance Academic/CS precondition:"
  printf '%s\n' "$stale_reply" | head -c 2000
  exit 1
fi
stale_attendance_rows="$("${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -tAc \
  "SELECT count(*) FROM teacher_session_attendance WHERE teaching_schedule_id='teaching-001'" | tr -d '[:space:]')"
stale_core_receipts="$("${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -tAc \
  "SELECT count(*) FROM teacher_attendance_command_receipts WHERE request_key='${stale_command_key}'" | tr -d '[:space:]')"
test "$stale_attendance_rows" = "0" && test "$stale_core_receipts" = "0" || {
  echo "Stale Attendance precondition wrote Core data or left a command receipt"; exit 1
}
echo "PASS: stale Academic/CS token rejects Core PUT transaction before an attendance commit"

receipt_key="ms-attendance-receipt-${GITHUB_RUN_ID:-local}"
audit_before="$("${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -tAc \
  "SELECT count(*) FROM audit_logs WHERE action='RECORD_TEACHER_ATTENDANCE_PRESENT' AND entity_id='teaching-001'" | tr -d '[:space:]')"
source_before="$("${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -tAc \
  "SELECT count(*) FROM teacher_attendance_source_outbox WHERE teaching_schedule_id='teaching-001'" | tr -d '[:space:]')"
write_response="$(curl -fsS -X PUT "$attendance_write" \
  -H "Authorization: Bearer ${cs_token}" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: ${receipt_key}" \
  -d '{"status":"PRESENT"}')"
WRITE_RESPONSE="$write_response" python3 -c \
  'import json,os; row=json.loads(os.environ["WRITE_RESPONSE"]); assert row["status"]=="PRESENT" and row["teachingScheduleId"]=="teaching-001",row'
actual_status="$("${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -tAc \
  "SELECT status FROM teacher_session_attendance WHERE teaching_schedule_id='teaching-001'" | tr -d '[:space:]')"
test "$actual_status" = "PRESENT" || { echo "Attendance private command did not commit at Core"; exit 1; }

# Same command after a lost response must return the identical Core receipt,
# not create another attendance update, audit or outbox event.
duplicate_response="$(curl -fsS -X PUT "$attendance_write" \
  -H "Authorization: Bearer ${cs_token}" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: ${receipt_key}" \
  -d '{"status":" present "}')"
FIRST_RESULT="$write_response" RETRY_RESULT="$duplicate_response" python3 -c \
  'import json,os; assert json.loads(os.environ["FIRST_RESULT"]) == json.loads(os.environ["RETRY_RESULT"])'
core_receipts="$("${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -tAc \
  "SELECT count(*) FROM teacher_attendance_command_receipts WHERE actor_username='cs001' AND request_key='${receipt_key}' AND response_payload->>'status'='PRESENT'" | tr -d '[:space:]')"
local_receipts="$("${COMPOSE[@]}" exec -T attendance-postgres psql -U iig_attendance -d iig_attendance -tAc \
  "SELECT count(*) FROM attendance_write_command_inbox WHERE actor_username='cs001' AND request_key='${receipt_key}' AND state='COMMITTED' AND response_payload->>'status'='PRESENT'" | tr -d '[:space:]')"
test "$core_receipts" = "1" && test "$local_receipts" = "1" || {
  echo "Transactional Core or Attendance command receipt missing"; exit 1;
}
audit_after="$("${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -tAc \
  "SELECT count(*) FROM audit_logs WHERE action='RECORD_TEACHER_ATTENDANCE_PRESENT' AND entity_id='teaching-001'" | tr -d '[:space:]')"
source_after="$("${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -tAc \
  "SELECT count(*) FROM teacher_attendance_source_outbox WHERE teaching_schedule_id='teaching-001'" | tr -d '[:space:]')"
test "$audit_after" -eq "$((audit_before+1))" && test "$source_after" -eq "$((source_before+1))" || {
  echo "Duplicate command emitted more than one audit/outbox event"; exit 1;
}
conflict_status="$(curl -sS -o /dev/null -w '%{http_code}' -X PUT "$attendance_write" \
  -H "Authorization: Bearer ${cs_token}" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: ${receipt_key}" -d '{"status":"ABSENT"}')"
test "$conflict_status" = "409" || { echo "Changed payload reused Attendance command key: $conflict_status"; exit 1; }

# Simulate the Attendance process losing its local completion acknowledgement
# after Core already committed. An intact key still recovers via Core's own
# immutable outcome rather than replaying the attendance write.
"${COMPOSE[@]}" exec -T attendance-postgres psql -U iig_attendance -d iig_attendance -v ON_ERROR_STOP=1 \
  -c "UPDATE attendance_write_command_inbox SET state='PENDING',response_payload=NULL,completed_at=NULL WHERE actor_username='cs001' AND request_key='${receipt_key}'"
recovered="$(curl -fsS -X PUT "$attendance_write" \
  -H "Authorization: Bearer ${cs_token}" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: ${receipt_key}" -d '{"status":"PRESENT"}')"
FIRST_RESULT="$write_response" RETRY_RESULT="$recovered" python3 -c \
  'import json,os; assert json.loads(os.environ["FIRST_RESULT"]) == json.loads(os.environ["RETRY_RESULT"])'
source_recovered="$("${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -tAc \
  "SELECT count(*) FROM teacher_attendance_source_outbox WHERE teaching_schedule_id='teaching-001'" | tr -d '[:space:]')"
test "$source_recovered" = "$source_after" || {
  echo "Recovery replayed committed Attendance business write"; exit 1;
}
local_recovered="$("${COMPOSE[@]}" exec -T attendance-postgres psql -U iig_attendance -d iig_attendance -tAc \
  "SELECT state FROM attendance_write_command_inbox WHERE actor_username='cs001' AND request_key='${receipt_key}'" | tr -d '[:space:]')"
test "$local_recovered" = "COMMITTED" || { echo "PENDING Attendance command was not reconciled"; exit 1; }
echo "PASS: Attendance/Core idempotent receipts, payload conflict, ambiguous completion recovery, no extra audit/outbox"
for i in $(seq 1 60); do
  observed="$("${COMPOSE[@]}" exec -T attendance-postgres psql -U iig_attendance \
    -d iig_attendance -tAc "SELECT payload->>'status' FROM attendance_teacher_source_records WHERE teaching_schedule_id='teaching-001' AND event_kind='UPSERT'" | tr -d '[:space:]')"
  test "$observed" = "PRESENT" && break
  sleep 2
done
test "$observed" = "PRESENT" || { echo "Attendance public write did not replicate its Core outbox"; exit 1; }
test "$(curl -sS -o /dev/null -w '%{http_code}' -X PUT \
  -H "Authorization: Bearer ${cs_token}" -H 'Content-Type: application/json' \
  -d '{"status":"PRESENT"}' "${BASE}/internal/attendance/commands/classes/class-0001/teacher-attendance/teaching-001")" = "404" || {
  echo "Private Attendance command exposed through public Gateway"; exit 1;
}
# Cleanup to leave later Payroll/assignment smoke fixtures unchanged.
"${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 \
  -c "DELETE FROM teacher_session_attendance WHERE teaching_schedule_id='teaching-001'; DELETE FROM staff_schedules WHERE staff_schedule_id='${attendance_support}'"
for i in $(seq 1 60); do
  observed="$("${COMPOSE[@]}" exec -T attendance-postgres psql -U iig_attendance \
    -d iig_attendance -tAc "SELECT event_kind FROM attendance_teacher_source_records WHERE teaching_schedule_id='teaching-001'" | tr -d '[:space:]')"
  test "$observed" = "DELETE" && break
  sleep 2
done
test "$observed" = "DELETE" || { echo "Attendance cleanup did not replicate tombstone"; exit 1; }
# A completed receipt cannot bypass a later loss of the exact CS support role.
revoked_replay="$(curl -sS -o /dev/null -w '%{http_code}' -X PUT "$attendance_write" \
  -H "Authorization: Bearer ${cs_token}" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: ${receipt_key}" -d '{"status":"PRESENT"}')"
test "$revoked_replay" = "403" || {
  echo "CS without exact support shift replayed a stale attendance receipt: ${revoked_replay}"; exit 1;
}
echo "PASS: Attendance ingress, revocable CS scope, idempotent private Core transaction and cleanup"





# Phase 8f-5: full source-manifest gate. It verifies acknowledged historical
# events and tombstones, not just an empty incremental feed or a matching
# read of one class. This is a READ-ONLY preflight, never a writer cutover.
attendance_verify_url="http://attendance-service:8100/internal/attendance/verify"
attendance_private_key="${ATTENDANCE_INTERNAL_API_KEY:-local-dev-attendance-key-change-before-deployment}"
verify_attendance() {
  "${COMPOSE[@]}" exec -T attendance-postgres wget -qO- \
    --header "X-Internal-Key: ${attendance_private_key}" "$attendance_verify_url"
}
if "${COMPOSE[@]}" exec -T attendance-postgres wget -qO- \
    "$attendance_verify_url" >/dev/null 2>&1; then
  echo "Attendance cutover verification accepted a request without the service key"; exit 1
fi
if [ "$(curl -sS -o /dev/null -w '%{http_code}' "${BASE}/internal/attendance/verify")" != "404" ]; then
  echo "Attendance private cutover verification leaked through public Gateway"; exit 1
fi
verified_attendance="$(verify_attendance)"
printf '%s' "$verified_attendance" | python3 -c \
  'import json,sys; r=json.load(sys.stdin); assert r["ready"] is True and r["state"]=="READY" and r["sourceRows"]>=1 and r["verifiedRows"]==r["sourceRows"],r'
echo "PASS: full Core Attendance manifest agrees with the independent PostgreSQL projection"

# Tampering with the local copy cannot yield a healthy cutover report even
# when Core has no pending events. Restore the disposable fixture afterward.
attendance_last_status="$("${COMPOSE[@]}" exec -T attendance-postgres psql \
  -U iig_attendance -d iig_attendance -tAc \
  "SELECT payload->>'status' FROM attendance_teacher_source_records WHERE teaching_schedule_id='teaching-001'" | tr -d '[:space:]')"
test -n "$attendance_last_status" || { echo "Missing Attendance manifest fixture"; exit 1; }
if [ "$attendance_last_status" = "ABSENT" ]; then
  attendance_corrupt_status="PRESENT"
else
  attendance_corrupt_status="ABSENT"
fi
"${COMPOSE[@]}" exec -T attendance-postgres psql \
  -U iig_attendance -d iig_attendance -v ON_ERROR_STOP=1 \
  -c "UPDATE attendance_teacher_source_records SET payload=jsonb_set(payload,'{status}',to_jsonb('${attendance_corrupt_status}'::text)) WHERE teaching_schedule_id='teaching-001'"
invalid_report="$("${COMPOSE[@]}" exec -T attendance-postgres wget -S -O /dev/null \
  --header "X-Internal-Key: ${attendance_private_key}" "$attendance_verify_url" 2>&1 || true)"
if ! printf '%s' "$invalid_report" | grep -Eq 'HTTP/[0-9.]+ 409|409 Conflict'; then
  echo "Attendance verification did not reject corrupted source projection:"
  printf '%s\n' "$invalid_report"
  exit 1
fi
"${COMPOSE[@]}" exec -T attendance-postgres psql \
  -U iig_attendance -d iig_attendance -v ON_ERROR_STOP=1 \
  -c "UPDATE attendance_teacher_source_records SET payload=jsonb_set(payload,'{status}',to_jsonb('${attendance_last_status}'::text)) WHERE teaching_schedule_id='teaching-001'"
verified_attendance="$(verify_attendance)"
printf '%s' "$verified_attendance" | python3 -c \
  'import json,sys; assert json.load(sys.stdin)["ready"] is True'
# A ghost tombstone with no matching Core source event also blocks readiness.
attendance_ghost_id="ms-ghost-${GITHUB_RUN_ID:-local}"
"${COMPOSE[@]}" exec -T attendance-postgres psql \
  -U iig_attendance -d iig_attendance -v ON_ERROR_STOP=1 \
  -c "INSERT INTO attendance_teacher_source_records(teaching_schedule_id,source_event_id,event_kind,payload) VALUES('${attendance_ghost_id}',9999999,'DELETE','{\"status\":\"PRESENT\"}'::jsonb)"
invalid_report="$("${COMPOSE[@]}" exec -T attendance-postgres wget -S -O /dev/null \
  --header "X-Internal-Key: ${attendance_private_key}" "$attendance_verify_url" 2>&1 || true)"
if ! printf '%s' "$invalid_report" | grep -Eq 'HTTP/[0-9.]+ 409|409 Conflict'; then
  echo "Attendance verification did not reject an orphaned projection"; exit 1
fi
"${COMPOSE[@]}" exec -T attendance-postgres psql \
  -U iig_attendance -d iig_attendance -v ON_ERROR_STOP=1 \
  -c "DELETE FROM attendance_teacher_source_records WHERE teaching_schedule_id='${attendance_ghost_id}'"
verified_attendance="$(verify_attendance)"
printf '%s' "$verified_attendance" | python3 -c \
  'import json,sys; assert json.load(sys.stdin)["ready"] is True'
echo "PASS: Attendance manifest fails closed on tampering, ghost data and unauthorized access"

# Phase 8f-8: Attendance owns an independent normalized teacher attendance
# ledger and an immutable, complete historical event timeline. Core is still
# the writer; do not count the mirror as an independent business write.
core_event_count="$("${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -tAc \
  "SELECT count(*) FROM teacher_attendance_source_outbox" | tr -d '[:space:]')"
core_latest_count="$("${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -tAc \
  "SELECT count(DISTINCT teaching_schedule_id) FROM teacher_attendance_source_outbox" | tr -d '[:space:]')"
local_history_count="$("${COMPOSE[@]}" exec -T attendance-postgres psql -U iig_attendance -d iig_attendance -tAc \
  "SELECT count(*) FROM attendance_teacher_event_history" | tr -d '[:space:]')"
local_ledger_count="$("${COMPOSE[@]}" exec -T attendance-postgres psql -U iig_attendance -d iig_attendance -tAc \
  "SELECT count(*) FROM attendance_teacher_ledger" | tr -d '[:space:]')"
local_receipt_count="$("${COMPOSE[@]}" exec -T attendance-postgres psql -U iig_attendance -d iig_attendance -tAc \
  "SELECT count(*) FROM attendance_import_receipts" | tr -d '[:space:]')"
test "$core_event_count" = "$local_history_count" && \
  test "$core_event_count" = "$local_receipt_count" && \
  test "$core_latest_count" = "$local_ledger_count" || {
  echo "Attendance ledger did not reconstruct complete Core source event history"; exit 1
}
tombstone_ledger="$("${COMPOSE[@]}" exec -T attendance-postgres psql -U iig_attendance -d iig_attendance -tAc \
  "SELECT count(*) FROM attendance_teacher_ledger WHERE teaching_schedule_id='teaching-001' AND event_kind='DELETE' AND status IS NULL AND attendance_id IS NULL AND recorded_by IS NULL" | tr -d '[:space:]')"
test "$tombstone_ledger" = "1" || {
  echo "Attendance normalized writer-ready ledger lost a deletion tombstone"; exit 1
}
historical_changes="$("${COMPOSE[@]}" exec -T attendance-postgres psql -U iig_attendance -d iig_attendance -tAc \
  "SELECT count(*) FROM attendance_teacher_event_history WHERE source_payload->>'attendance_id'='${attendance_fixture}'" | tr -d '[:space:]')"
test "$historical_changes" = "3" || {
  echo "Attendance immutable history lost an insert, update or delete"; exit 1
}
# History is append-only at the actual database layer; a reader must
# never be allowed to forge payroll evidence by rewriting old events.
history_mutation="$("${COMPOSE[@]}" exec -T attendance-postgres psql -U iig_attendance -d iig_attendance \
  -v ON_ERROR_STOP=1 -v VERBOSITY=verbose \
  -c "UPDATE attendance_teacher_event_history SET payload_hash='f'::text WHERE event_id=(SELECT MIN(event_id) FROM attendance_teacher_event_history)" 2>&1 || true)"
if ! printf '%s' "$history_mutation" | grep -q '55000'; then
  echo "Attendance historical evidence was mutable at PostgreSQL level"; exit 1
fi
# Ledger parity MUST detect local corruption even though the Core pending
# event queue is empty. Restore only the disposable mirror after 409.
ledger_digest="$("${COMPOSE[@]}" exec -T attendance-postgres psql -U iig_attendance -d iig_attendance -tAc \
  "SELECT source_hash FROM attendance_teacher_ledger WHERE teaching_schedule_id='teaching-001'" | tr -d '[:space:]')"
"${COMPOSE[@]}" exec -T attendance-postgres psql -U iig_attendance -d iig_attendance -v ON_ERROR_STOP=1 \
  -c "UPDATE attendance_teacher_ledger SET source_hash=repeat('f',64) WHERE teaching_schedule_id='teaching-001'"
invalid_report="$("${COMPOSE[@]}" exec -T attendance-postgres wget -S -O /dev/null \
  --header "X-Internal-Key: ${attendance_private_key}" "$attendance_verify_url" 2>&1 || true)"
if ! printf '%s' "$invalid_report" | grep -Eq 'HTTP/[0-9.]+ 409|409 Conflict'; then
  echo "Attendance full parity accepted a corrupted normalized ledger"; exit 1
fi
"${COMPOSE[@]}" exec -T attendance-postgres psql -U iig_attendance -d iig_attendance -v ON_ERROR_STOP=1 \
  -c "UPDATE attendance_teacher_ledger SET source_hash='${ledger_digest}' WHERE teaching_schedule_id='teaching-001'"
# Deliberately reset the SEPARATE backfill cursor to simulate a restart
# during historic replay after all new live events were already imported.
"${COMPOSE[@]}" exec -T attendance-postgres psql -U iig_attendance -d iig_attendance -v ON_ERROR_STOP=1 \
  -c "UPDATE attendance_history_backfill_cursor SET last_event_id=0 WHERE singleton=TRUE"
verified_attendance="$(verify_attendance)"
printf '%s' "$verified_attendance" | python3 -c \
  'import json,sys; r=json.load(sys.stdin); assert r["ready"] is True and r["state"]=="READY",r'
replay_count="$("${COMPOSE[@]}" exec -T attendance-postgres psql -U iig_attendance -d iig_attendance -tAc \
  "SELECT count(*) FROM attendance_teacher_event_history" | tr -d '[:space:]')"
test "$replay_count" = "$core_event_count" || {
  echo "Historical Core replay duplicated immutable Attendance events"; exit 1
}
echo "PASS: independent Attendance ledger/history, tombstones, immutable events, tamper detection and restart-safe backfill"

# Phase 8f-6: write-mode coordination with Scheduling. The normal live
# parity endpoint may return READY while Core is still writable; frozen
# verification MUST not do so until BOTH existing writer controls are held.
frozen_verify_url="${attendance_verify_url}/frozen"
frozen_http="$("${COMPOSE[@]}" exec -T attendance-postgres wget -S -O /dev/null \
  --header "X-Internal-Key: ${attendance_private_key}" "$frozen_verify_url" 2>&1 || true)"
if ! printf '%s' "$frozen_http" | grep -Eq 'HTTP/[0-9.]+ 409|409 Conflict'; then
  echo "Attendance frozen cutover preflight incorrectly accepted active Core writers"; exit 1
fi
if [ "$(curl -sS -o /dev/null -w '%{http_code}' "${BASE}/internal/attendance/verify/frozen")" != "404" ]; then
  echo "Attendance frozen preflight leaked through public Gateway"; exit 1
fi
schedule_mode_before="$("${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -tAc \
  "SELECT writer_mode FROM scheduling_assignment_writer_control WHERE singleton=TRUE" | tr -d '[:space:]')"
attendance_mode_before="$("${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -tAc \
  "SELECT writer_mode FROM teacher_attendance_writer_control WHERE singleton=TRUE" | tr -d '[:space:]')"
test "$schedule_mode_before" = "CORE" && test "$attendance_mode_before" = "CORE" || {
  echo "Unexpected writer control mode before isolated freeze smoke"; exit 1
}
# Enforce ordering even against a direct SQL operator mistake.
if "${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 \
  -c "UPDATE teacher_attendance_writer_control SET writer_mode='QUIESCED' WHERE singleton=TRUE" >/dev/null 2>&1; then
  echo "Attendance froze without quiescing Scheduling first"; exit 1
fi
fence_fixture="ms-fence-attendance-${GITHUB_RUN_ID:-local}"
"${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 \
  -c "INSERT INTO teacher_session_attendance(attendance_id,teaching_schedule_id,status,recorded_by) VALUES('${fence_fixture}','teaching-001','PRESENT','cs-001')"
schedule_generation_before="$("${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -tAc \
  "SELECT writer_generation FROM scheduling_assignment_writer_control WHERE singleton=TRUE" | tr -d '[:space:]')"
attendance_generation_before="$("${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -tAc \
  "SELECT generation FROM teacher_attendance_writer_control WHERE singleton=TRUE" | tr -d '[:space:]')"
"${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 \
  -c "UPDATE scheduling_assignment_writer_control SET writer_mode='QUIESCED' WHERE singleton=TRUE"
"${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 \
  -c "UPDATE teacher_attendance_writer_control SET writer_mode='QUIESCED' WHERE singleton=TRUE"
frozen_report="$("${COMPOSE[@]}" exec -T attendance-postgres wget -qO- \
  --header "X-Internal-Key: ${attendance_private_key}" "$frozen_verify_url")"
printf '%s' "$frozen_report" | python3 -c \
  'import json,sys; r=json.load(sys.stdin); assert r["ready"] is True and r["state"]=="READY" and r["sourceRows"]==r["verifiedRows"],r'

for blocked_sql in \
  "INSERT INTO teacher_session_attendance(attendance_id,teaching_schedule_id,status,recorded_by) VALUES('${fence_fixture}-new','teaching-001','ABSENT','cs-001')" \
  "UPDATE teacher_session_attendance SET status='ABSENT' WHERE attendance_id='${fence_fixture}'" \
  "DELETE FROM teacher_session_attendance WHERE attendance_id='${fence_fixture}'"; do
  blocked_output="$("${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db \
    -v ON_ERROR_STOP=1 -v VERBOSITY=verbose -c "$blocked_sql" 2>&1 || true)"
  if ! printf '%s' "$blocked_output" | grep -q '55000'; then
    echo "Quiesced Core Attendance accepted a row write or failed for the wrong reason: $blocked_sql"
    printf '%s\n' "$blocked_output"
    exit 1
  fi
done
# TRUNCATE is a distinct statement trigger, not a row-level DELETE.
truncate_trigger="$("${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -tAc \
  "SELECT count(*) FROM pg_trigger WHERE tgname='trg_iig_attendance_domain_truncate_fence' AND tgenabled='O'" | tr -d '[:space:]')"
test "$truncate_trigger" = "1" || {
  echo "Core Attendance TRUNCATE fence is not enabled"; exit 1
}
if "${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 \
  -c "UPDATE scheduling_assignment_writer_control SET writer_mode='CORE' WHERE singleton=TRUE" >/dev/null 2>&1; then
  echo "Scheduling resumed while Attendance was still frozen"; exit 1
fi
# Never automatically turn on an independent Attendance writer here: only
# return the disposable Core-owned stack to its original safe CORE state.
"${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 \
  -c "UPDATE teacher_attendance_writer_control SET writer_mode='CORE' WHERE singleton=TRUE"
"${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 \
  -c "UPDATE scheduling_assignment_writer_control SET writer_mode='CORE' WHERE singleton=TRUE"
schedule_generation_after="$("${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -tAc \
  "SELECT writer_generation FROM scheduling_assignment_writer_control WHERE singleton=TRUE" | tr -d '[:space:]')"
attendance_generation_after="$("${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -tAc \
  "SELECT generation FROM teacher_attendance_writer_control WHERE singleton=TRUE" | tr -d '[:space:]')"
test "$schedule_generation_after" -eq "$((schedule_generation_before+2))" && \
  test "$attendance_generation_after" -eq "$((attendance_generation_before+2))" || {
  echo "Attendance/Scheduling writer generation did not advance on both transitions"; exit 1
}
"${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 \
  -c "DELETE FROM teacher_session_attendance WHERE attendance_id='${fence_fixture}'"
frozen_http="$("${COMPOSE[@]}" exec -T attendance-postgres wget -S -O /dev/null \
  --header "X-Internal-Key: ${attendance_private_key}" "$frozen_verify_url" 2>&1 || true)"
if ! printf '%s' "$frozen_http" | grep -Eq 'HTTP/[0-9.]+ 409|409 Conflict'; then
  echo "Frozen preflight accepted resumed Core writers"; exit 1
fi
echo "PASS: coordinated Attendance/Scheduling writer freeze, SQL mutation fences and generation-bound frozen parity"

# Course Catalog is a separate Spring service with independent storage.
# The initial import must preserve all legacy course options for React.
catalog_ready=0
for i in $(seq 1 50); do
  courses="$(curl -fsS "${BASE}/api/classes/courses" -H "Authorization: Bearer ${TOKEN}" 2>/dev/null || true)"
  if printf '%s' "$courses" | grep -q '"course-ielts"' && \
     printf '%s' "$courses" | grep -q '"auto-course-20"'; then
    catalog_ready=1
    break
  fi
  sleep 2
done
test "$catalog_ready" = "1" || { echo "Catalog initial import failed"; exit 1; }
echo "PASS: existing class course options are served from catalog via Gateway"

unauthorized="$(curl -sS -o /dev/null -w '%{http_code}' "${BASE}/api/classes/courses")"
test "$unauthorized" = "401" || { echo "Catalog GET unexpectedly public: $unauthorized"; exit 1; }

course_id="ms-course-${GITHUB_RUN_ID:-local}"
course_status="$(curl -sS -o /tmp/iig-created-course.json -w '%{http_code}' \
  -X POST "${BASE}/api/catalog/courses" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H 'Content-Type: application/json' \
  -d "{\"courseId\":\"${course_id}\",\"name\":\"Microservice Catalog Test\",\"status\":\"ACTIVE\"}")"
test "$course_status" = "201" || { cat /tmp/iig-created-course.json; echo "Catalog POST: $course_status"; exit 1; }
course_projected=0
for i in $(seq 1 50); do
  projected="$("${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -tAc \
    "SELECT name FROM courses WHERE course_id='${course_id}'" | tr -d '\r\n')"
  if [ "$projected" = "Microservice Catalog Test" ]; then
    course_projected=1
    break
  fi
  sleep 2
done
test "$course_projected" = "1" || { echo "Core course reference projection unavailable"; exit 1; }
count="$("${COMPOSE[@]}" exec -T catalog-postgres psql -U iig_catalog -d iig_catalog -tAc \
  "SELECT COUNT(*) FROM catalog_courses WHERE course_id='${course_id}'" | tr -d '[:space:]')"
test "$count" = "1"
echo "PASS: catalog owns and synchronizes course updates to academic reference"


# Authoritative update and versioned replication are required for a true
# owned catalog, not just an HTTP facade over the monolith's course table.
updated_status="$(curl -sS -o /tmp/iig-updated-course.json -w '%{http_code}' \
  -X PUT "${BASE}/api/catalog/courses/${course_id}" \
  -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' \
  -d "{\"courseId\":\"${course_id}\",\"name\":\"Microservice Catalog Updated\",\"status\":\"ACTIVE\"}")"
test "$updated_status" = "200" || { cat /tmp/iig-updated-course.json; exit 1; }
updated_projected=0
for i in $(seq 1 50); do
  actual="$("${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -tAc \
    "SELECT name FROM courses WHERE course_id='${course_id}'" | tr -d '\r\n')"
  if [ "$actual" = "Microservice Catalog Updated" ]; then
    updated_projected=1
    break
  fi
  sleep 2
done
test "$updated_projected" = "1" || { echo "Catalog update did not reach academic read model"; exit 1; }
ack_version="$("${COMPOSE[@]}" exec -T catalog-postgres psql -U iig_catalog -d iig_catalog -tAc \
  "SELECT version FROM catalog_outbox WHERE course_id='${course_id}' AND delivered_at IS NOT NULL" | tr -d '[:space:]')"
test "$ack_version" = "2"
echo "PASS: catalog updates replicate, advance version and are acknowledged"

teacher_login="$(curl -fsS -X POST "${BASE}/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"teacher001","password":"123456"}')"
teacher_token="$(printf '%s' "$teacher_login" | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')"
denied="$(curl -sS -o /dev/null -w '%{http_code}' -X POST "${BASE}/api/catalog/courses" \
  -H "Authorization: Bearer ${teacher_token}" -H 'Content-Type: application/json' \
  -d '{"courseId":"ms-role-blocked","name":"Disallowed","status":"ACTIVE"}')"
test "$denied" = "403" || { echo "Non-admin was not rejected: $denied"; exit 1; }
echo "PASS: catalog rejects staff course changes without ADMIN role"

# Login is now issued by an isolated Identity service. Core must accept the
# exact JWT claims and enforce revocation after logout via the new route.
identity_count="$("${COMPOSE[@]}" exec -T identity-postgres psql -U iig_identity -d iig_identity -tAc \
  "SELECT COUNT(*) FROM identity_users WHERE username='admin' AND role_id='ADMIN'" | tr -d '[:space:]')"
test "$identity_count" = "1" || { echo "Identity did not persist admin credential projection"; exit 1; }
me_status="$(curl -sS -o /dev/null -w '%{http_code}' "${BASE}/api/me" \
  -H "Authorization: Bearer ${TOKEN}")"
test "$me_status" = "200" || { echo "Core rejected Identity signed JWT ($me_status)"; exit 1; }

logout_code="$(curl -sS -o /dev/null -w '%{http_code}' -X POST \
  "${BASE}/api/auth/logout" -H "Authorization: Bearer ${teacher_token}")"
test "$logout_code" = "204" || { echo "Identity logout failed: $logout_code"; exit 1; }
revoked_count="$("${COMPOSE[@]}" exec -T identity-postgres psql -U iig_identity -d iig_identity -tAc \
  "SELECT COUNT(*) FROM identity_revocations" | tr -d '[:space:]')"
test "$revoked_count" -ge 1
revoked_status="$(curl -sS -o /dev/null -w '%{http_code}' "${BASE}/api/me" \
  -H "Authorization: Bearer ${teacher_token}")"
test "$revoked_status" = "401" || { echo "Revoked token was accepted by core: $revoked_status"; exit 1; }
echo "PASS: Identity-issued JWT accepted and revoked by core"

# Never expose the internal credential bridge via public Gateway.
identity_internal_code="$(curl -sS -o /dev/null -w '%{http_code}' \
  "${BASE}/internal/identity/users/admin")"
test "$identity_internal_code" = "404"
echo "PASS: Identity credential bridge is private"

# Reporting is served by a separate Spring Boot process with its own database.
# It must preserve the existing statistics JSON contract and fail closed.
stats_file="/tmp/iig-stats.json"
stats_status="$(curl -sS -o "$stats_file" -w '%{http_code}' \
  "${BASE}/api/statistics?period=YEAR&year=2026" -H "Authorization: Bearer ${TOKEN}")"
test "$stats_status" = "200" || { cat "$stats_file"; echo "Statistics returned $stats_status"; exit 1; }
python3 - "$stats_file" <<'PYTHON'
import json, sys
with open(sys.argv[1], encoding="utf-8") as stream:
    value = json.load(stream)
assert value["period"] == "YEAR", value
assert "overview" in value and "employees" in value and "monthly" in value, value
PYTHON
snapshots="$("${COMPOSE[@]}" exec -T reporting-postgres psql -U iig_reporting -d iig_reporting -tAc \
  "SELECT COUNT(*) FROM statistics_snapshots WHERE snapshot_key='YEAR:2026'" | tr -d '[:space:]')"
test "$snapshots" = "1" || { echo "Reporting did not store the snapshot in its own DB"; exit 1; }
refresh_before="$("${COMPOSE[@]}" exec -T reporting-postgres psql -U iig_reporting -d iig_reporting -tAc \
  "SELECT refreshed_at FROM statistics_snapshots WHERE snapshot_key='YEAR:2026'" | tr -d '[:space:]')"
curl --fail --silent --show-error \
  "${BASE}/api/statistics?period=YEAR&year=2026" \
  -H "Authorization: Bearer ${TOKEN}" >/dev/null
refresh_after="$("${COMPOSE[@]}" exec -T reporting-postgres psql -U iig_reporting -d iig_reporting -tAc \
  "SELECT refreshed_at FROM statistics_snapshots WHERE snapshot_key='YEAR:2026'" | tr -d '[:space:]')"
test "$refresh_before" = "$refresh_after" || { echo "Fresh read-model snapshot was unnecessarily refreshed"; exit 1; }

report_denied="$(curl -sS -o /dev/null -w '%{http_code}' \
  "${BASE}/api/statistics?period=YEAR&year=2026")"
test "$report_denied" = "401"
staff_json="$(curl --fail --silent --show-error -X POST "${BASE}/api/auth/login" \
  -H 'Content-Type: application/json' -d '{"username":"teacher001","password":"123456"}')"
staff_token="$(printf '%s' "$staff_json" | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')"
staff_denied="$(curl -sS -o /dev/null -w '%{http_code}' \
  "${BASE}/api/statistics?period=YEAR&year=2026" -H "Authorization: Bearer ${staff_token}")"
test "$staff_denied" = "403" || { echo "Non-Admin accessed reporting: $staff_denied"; exit 1; }
invalid_period="$(curl -sS -o /dev/null -w '%{http_code}' \
  "${BASE}/api/statistics?period=WEEK&year=2026" -H "Authorization: Bearer ${TOKEN}")"
test "$invalid_period" = "400"
private_reporting="$(curl -sS -o /dev/null -w '%{http_code}' "${BASE}/internal/reporting/statistics")"
test "$private_reporting" = "404"
echo "PASS: Reporting snapshots stored independently with ADMIN-only authorization and stable API"

# Admin and teacher dashboards must be distinct, with separate read-model
# entries. A user must never receive another role's cached dashboard.
admin_dashboard="$(curl --fail --silent --show-error "${BASE}/api/dashboard" \
  -H "Authorization: Bearer ${TOKEN}")"
teacher_dashboard="$(curl --fail --silent --show-error "${BASE}/api/dashboard" \
  -H "Authorization: Bearer ${staff_token}")"
printf '%s' "$admin_dashboard" | python3 -c \
  'import json,sys; x=json.load(sys.stdin); assert x["role"]=="ADMIN" and isinstance(x["stats"],list),x'
printf '%s' "$teacher_dashboard" | python3 -c \
  'import json,sys; x=json.load(sys.stdin); assert x["role"]=="TEACHER" and isinstance(x["stats"],list),x'
dash_rows="$("${COMPOSE[@]}" exec -T reporting-postgres psql -U iig_reporting -d iig_reporting -tAc \
  "SELECT count(DISTINCT snapshot_key) FROM dashboard_snapshots WHERE snapshot_key LIKE 'DASH:%'" | tr -d '[:space:]')"
test "$dash_rows" -ge 2 || { echo "Dashboard snapshots are not role-scoped"; exit 1; }
no_dashboard="$(curl -sS -o /dev/null -w '%{http_code}' "${BASE}/api/dashboard")"
test "$no_dashboard" = "401"
private_dashboard="$(curl -sS -o /dev/null -w '%{http_code}' \
  "${BASE}/internal/reporting/dashboard?username=admin")"
test "$private_dashboard" = "404"
echo "PASS: role-scoped dashboards served from Reporting without cache leakage"

# HR Certificate writes are authoritative in HR's own PostgreSQL.
# Core retains an ordered, idempotent projection for legacy profile reads.
employee_id="$("${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -tAc \
  "SELECT employee_id FROM employees WHERE username='teacher001'" | tr -d '[:space:]')"
test -n "$employee_id" || { echo "Seed teacher employee was not found"; exit 1; }

cert_status="$(curl -sS -o /tmp/iig-hr-created.json -w '%{http_code}' \
  -X POST "${BASE}/api/employee-management/${employee_id}/certificates" \
  -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' \
  -d '{"certificateType":"IELTS","score":8.0,"scoreSW":null,"issueDate":"2026-01-01","expiryDate":"2028-01-01","issuer":"IIG CI","certificateUrl":"https://example.com/iig-ci","status":"ACTIVE"}')"
test "$cert_status" = "201" || { cat /tmp/iig-hr-created.json; echo "HR create $cert_status"; exit 1; }
certificate_id="$(python3 -c 'import json;print(json.load(open("/tmp/iig-hr-created.json"))["certificateId"])')"
test -n "$certificate_id"
cert_count="$("${COMPOSE[@]}" exec -T hr-postgres psql -U iig_hr -d iig_hr -tAc \
  "SELECT count(*) FROM hr_certificates WHERE certificate_id='${certificate_id}'" | tr -d '[:space:]')"
test "$cert_count" = "1" || { echo "HR database did not own certificate"; exit 1; }

core_cert_projected=0
for i in $(seq 1 50); do
  score="$("${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -tAc \
    "SELECT score FROM certificates WHERE certificate_id='${certificate_id}'" | tr -d '[:space:]')"
  if [ "$score" = "8" ]; then core_cert_projected=1; break; fi
  sleep 2
done
test "$core_cert_projected" = "1" || { echo "HR initial projection unavailable"; exit 1; }

update_status="$(curl -sS -o /tmp/iig-hr-updated.json -w '%{http_code}' \
  -X PUT "${BASE}/api/employee-management/${employee_id}/certificates/${certificate_id}" \
  -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' \
  -d '{"certificateType":"IELTS","score":8.5,"scoreSW":null,"issueDate":"2026-01-01","expiryDate":"2028-01-01","issuer":"IIG CI","certificateUrl":"https://example.com/iig-ci","status":"ACTIVE"}')"
test "$update_status" = "200" || { cat /tmp/iig-hr-updated.json; echo "HR update $update_status"; exit 1; }
core_hr_updated=0
for i in $(seq 1 50); do
  score="$("${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -tAc \
    "SELECT score FROM certificates WHERE certificate_id='${certificate_id}'" | tr -d '[:space:]')"
  if [ "$score" = "8.5" ]; then core_hr_updated=1; break; fi
  sleep 2
done
test "$core_hr_updated" = "1" || { echo "HR update did not reach core reference"; exit 1; }
version="$("${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -tAc \
  "SELECT version FROM hr_certificate_versions WHERE certificate_id='${certificate_id}'" | tr -d '[:space:]')"
test "$version" = "2"
acknowledged="$("${COMPOSE[@]}" exec -T hr-postgres psql -U iig_hr -d iig_hr -tAc \
  "SELECT version FROM hr_certificate_outbox WHERE certificate_id='${certificate_id}' AND delivered_at IS NOT NULL" | tr -d '[:space:]')"
test "$acknowledged" = "2"
echo "PASS: HR owns certificates and propagates versioned updates"

staff_hr_denied="$(curl -sS -o /dev/null -w '%{http_code}' \
  -X POST "${BASE}/api/employee-management/${employee_id}/certificates" \
  -H "Authorization: Bearer ${staff_token}" -H 'Content-Type: application/json' \
  -d '{"certificateType":"IELTS","status":"ACTIVE"}')"
test "$staff_hr_denied" = "403" || { echo "Teacher was able to write certificates: $staff_hr_denied"; exit 1; }
hr_private="$(curl -sS -o /dev/null -w '%{http_code}' "${BASE}/internal/hr/certificates/export")"
test "$hr_private" = "404"
echo "PASS: HR certificate permissions and private service contracts"

# HR now owns salary ranks in its own PostgreSQL, including versioned updates.
# The old salary_ranks table in core is an FK projection for existing payroll.
rank_options=""
for i in $(seq 1 60); do
  rank_options="$(curl -fsS "${BASE}/api/payroll/ranks?roleId=TEACHER" \
    -H "Authorization: Bearer ${TOKEN}" 2>/dev/null || true)"
  if printf '%s' "$rank_options" | grep -q '"auto-rank-20"'; then break; fi
  sleep 2
done
printf '%s' "$rank_options" | grep -q '"auto-rank-20"' || { echo "HR salary rank bootstrap failed"; exit 1; }

created_rank="$(curl -fsS -X POST "${BASE}/api/payroll/ranks" \
  -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' \
  -d '{"roleId":"TEACHER","rank":"Microservices smoke rank","amount":175000,"status":"ACTIVE"}')"
new_rank_id="$(printf '%s' "$created_rank" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')"
test -n "$new_rank_id"
rank_projected=0
for i in $(seq 1 50); do
  count="$("${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -tAc \
    "SELECT count(*) FROM salary_ranks WHERE salary_rank_id='${new_rank_id}' AND amount=175000" | tr -d '[:space:]')"
  if [ "$count" = "1" ]; then rank_projected=1; break; fi
  sleep 2
done
test "$rank_projected" = "1" || { echo "HR salary rank did not project to core"; exit 1; }
updated_rank="$(curl -fsS -X PUT "${BASE}/api/payroll/ranks/${new_rank_id}" \
  -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' \
  -d '{"rank":"Microservices smoke rank","amount":185000,"status":"ACTIVE"}')"
printf '%s' "$updated_rank" | grep -q '185000'
for i in $(seq 1 50); do
  rank_version="$("${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -tAc \
    "SELECT version FROM hr_salary_rank_versions WHERE salary_rank_id='${new_rank_id}'" | tr -d '[:space:]')"
  test "$rank_version" = "2" && break
  sleep 2
done
test "$rank_version" = "2" || { echo "HR rank update projection version lagged"; exit 1; }
rank_db_version="$("${COMPOSE[@]}" exec -T hr-postgres psql -U iig_hr -d iig_hr -tAc \
  "SELECT version FROM hr_salary_rank_outbox WHERE salary_rank_id='${new_rank_id}' AND delivered_at IS NOT NULL" | tr -d '[:space:]')"
test "$rank_db_version" = "2"
echo "PASS: HR salary ranks are independently owned with versioned core projection"

rank_forbidden="$(curl -sS -o /dev/null -w '%{http_code}' \
  "${BASE}/api/payroll/ranks" -H "Authorization: Bearer ${TOKEN}")"
test "$rank_forbidden" = "200"
teacher_login_again="$(curl -fsS -X POST "${BASE}/api/auth/login" \
  -H 'Content-Type: application/json' -d '{"username":"teacher001","password":"123456"}')"
teacher_again="$(printf '%s' "$teacher_login_again" | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')"
rank_forbidden="$(curl -sS -o /dev/null -w '%{http_code}' \
  "${BASE}/api/payroll/ranks" -H "Authorization: Bearer ${teacher_again}")"
test "$rank_forbidden" = "403" || { echo "Teacher accessed management rank table: $rank_forbidden"; exit 1; }
tc_login="$(curl -fsS -X POST "${BASE}/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"tc001","password":"123456"}')"
tc_token="$(printf '%s' "$tc_login" | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')"
tc_role_denied="$(curl -sS -o /dev/null -w '%{http_code}' \
  "${BASE}/api/payroll/ranks?roleId=CS" -H "Authorization: Bearer ${tc_token}")"
test "$tc_role_denied" = "403"
echo "PASS: HR salary ranks enforce Admin/TC/CM role boundaries"

# The two newer main-branch payroll/scheduling commits must remain functional
# after the Microservices stack synchronizes. Payroll ledger is still in Core
# during this transitional stage and must not be presented as extracted.
payroll_read="$(curl -fsS "${BASE}/api/payroll?period=2026-10" \
  -H "Authorization: Bearer ${TOKEN}")"
printf '%s' "$payroll_read" | python3 -c 'import json,sys; assert isinstance(json.load(sys.stdin),list)'
payroll_calc="$(curl -fsS -X POST "${BASE}/api/payroll/calculate?period=2026-10&employeeId=teacher-001" \
  -H "Authorization: Bearer ${TOKEN}")"
printf '%s' "$payroll_calc" | python3 -c 'import json,sys; rows=json.load(sys.stdin); assert isinstance(rows,list) and any(r.get("employeeId")=="teacher-001" for r in rows)'
# All externally visible payroll writes MUST land in Payroll's own database,
# not in the legacy employee_salaries table.
payroll_state="$("${COMPOSE[@]}" exec -T payroll-postgres psql -U iig_payroll -d iig_payroll -tAc \
  "SELECT status FROM payroll_ledgers WHERE employee_id='teacher-001' AND period='2026-10' AND source='EMPLOYEE'" | tr -d '[:space:]')"
test "$payroll_state" = "CALCULATED" || { echo "Payroll service did not commit its ledger"; exit 1; }
imported="$("${COMPOSE[@]}" exec -T payroll-postgres psql -U iig_payroll -d iig_payroll -tAc \
  "SELECT count(*) FROM payroll_meta WHERE key='legacy-import-complete'" | tr -d '[:space:]')"
test "$imported" = "1"
historical_count="$("${COMPOSE[@]}" exec -T payroll-postgres psql -U iig_payroll -d iig_payroll -tAc \
  "SELECT count(*) FROM payroll_ledgers" | tr -d '[:space:]')"
test "$historical_count" -ge 2 || { echo "Historical salaries were not imported"; exit 1; }
detail="$(curl -fsS "${BASE}/api/payroll/teacher-001/2026-10" \
  -H "Authorization: Bearer ${TOKEN}")"
printf '%s' "$detail" | python3 -c 'import sys,json; x=json.load(sys.stdin); assert x["employeeId"]=="teacher-001" and isinstance(x["items"],list)'
# The earlier Attendance suite deliberately deleted teaching-001 to verify
# tombstones. Create a NEW isolated payable lesson evidence fixture for this
# section, then remove it afterward so later regression state is unchanged.
payroll_evidence_fixture="ms-payroll-attendance-${GITHUB_RUN_ID:-local}"
payroll_preexisting_attendance="$("${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -tAc \
  "SELECT count(*) FROM teacher_session_attendance WHERE teaching_schedule_id='teaching-001'" | tr -d '[:space:]')"
test "$payroll_preexisting_attendance" = "0" || {
  echo "Unexpected attendance left by the previous tombstone smoke"; exit 1
}
"${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 \
  -c "INSERT INTO teacher_session_attendance(attendance_id,teaching_schedule_id,status,recorded_by) VALUES('${payroll_evidence_fixture}','teaching-001','PRESENT','cs-001')"
payroll_fixture_synced=0
for i in $(seq 1 60); do
  source_status="$("${COMPOSE[@]}" exec -T attendance-postgres psql -U iig_attendance -d iig_attendance -tAc \
    "SELECT status FROM attendance_teacher_ledger WHERE teaching_schedule_id='teaching-001' AND event_kind='UPSERT' AND attendance_id='${payroll_evidence_fixture}'" | tr -d '[:space:]')"
  undelivered="$("${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -tAc \
    "SELECT count(*) FROM teacher_attendance_source_outbox WHERE teaching_schedule_id='teaching-001' AND delivered_at IS NULL" | tr -d '[:space:]')"
  if [ "$source_status" = "PRESENT" ] && [ "$undelivered" = "0" ]; then
    payroll_fixture_synced=1; break
  fi
  sleep 2
done
test "$payroll_fixture_synced" = "1" || {
  echo "Payroll Attendance test fixture was not durably imported"; exit 1
}

# Phase 8f-9: Payroll may inspect independent Attendance evidence, but
# must keep the current Core-derived teacher/CS work facts and salary formula.
# The new contracts are private, service-key protected and NEVER Gateway API.
payroll_private_key="${PAYROLL_INTERNAL_API_KEY:-local-dev-payroll-key-change-before-deployment}"
payroll_evidence_url="http://attendance-service:8100/internal/attendance/payroll/evidence?ids=teaching-001"
payroll_ready_url="http://attendance-service:8100/internal/attendance/payroll/readiness"
payroll_reconcile_url="http://payroll-service:8097/internal/payroll/attendance-reconciliation?period=2026-10"
if "${COMPOSE[@]}" exec -T attendance-postgres wget -qO- "$payroll_evidence_url" >/dev/null 2>&1; then
  echo "Private Attendance Payroll evidence accepted missing service key"; exit 1
fi
if "${COMPOSE[@]}" exec -T attendance-postgres wget -qO- \
  --header "X-Internal-Key: ${attendance_private_key}" "$payroll_evidence_url" >/dev/null 2>&1; then
  echo "Attendance Payroll evidence accepted the wrong (Attendance) service key"; exit 1
fi
if "${COMPOSE[@]}" exec -T attendance-postgres wget -qO- "$payroll_reconcile_url" >/dev/null 2>&1; then
  echo "Private Payroll reconciliation accepted missing service key"; exit 1
fi
for private_path in "/internal/attendance/payroll/evidence?ids=teaching-001" \
  "/internal/payroll/attendance-reconciliation?period=2026-10"; do
  test "$(curl -sS -o /dev/null -w '%{http_code}' "${BASE}${private_path}")" = "404" || {
    echo "Private Attendance/Payroll evidence leaked via public Gateway"; exit 1
  }
done
payroll_reference="$("${COMPOSE[@]}" exec -T attendance-postgres wget -qO- \
  --header "X-Internal-Key: ${payroll_private_key}" "$payroll_evidence_url")"
PAYROLL_REFERENCE="$payroll_reference" python3 -c \
  'import os,json; r=json.loads(os.environ["PAYROLL_REFERENCE"]); x=r["evidence"]; assert r["sourceRevision"]>=1 and r["sourceEventCount"]>=3 and len(x)==1 and x[0]["teachingScheduleId"]=="teaching-001" and x[0]["eventKind"]=="UPSERT" and x[0]["status"]=="PRESENT" and len(x[0]["sourceHash"])==64,r'
payroll_global="$("${COMPOSE[@]}" exec -T attendance-postgres wget -qO- \
  --header "X-Internal-Key: ${payroll_private_key}" "$payroll_ready_url")"
PAYROLL_REFERENCE="$payroll_global" python3 -c \
  'import os,json; r=json.loads(os.environ["PAYROLL_REFERENCE"]); assert r["sourceRevision"]>=1 and r["evidence"]==[],r'
payroll_ledger_count_before="$("${COMPOSE[@]}" exec -T payroll-postgres psql -U iig_payroll -d iig_payroll -tAc \
  "SELECT count(*) FROM payroll_ledgers" | tr -d '[:space:]')"
payroll_item_count_before="$("${COMPOSE[@]}" exec -T payroll-postgres psql -U iig_payroll -d iig_payroll -tAc \
  "SELECT count(*) FROM payroll_items" | tr -d '[:space:]')"
payroll_reconciliation="$("${COMPOSE[@]}" exec -T attendance-postgres wget -qO- \
  --header "X-Internal-Key: ${payroll_private_key}" "$payroll_reconcile_url")"
PAYROLL_RECONCILIATION="$payroll_reconciliation" python3 -c \
  'import os,json; x=json.loads(os.environ["PAYROLL_RECONCILIATION"]); assert x["ready"] is True and x["state"]=="MATCH" and x["period"]=="2026-10" and x["teacherShifts"]>=1 and x["mismatches"]==0 and x["sourceRevision"]>=1,x'
echo "PASS: Payroll verifies Core teacher work against independently imported Attendance source evidence"

# Simulated corrupt local ledger must fail closed at BOTH service boundaries;
# no fallback to unaudited Payroll evidence is permitted.
payroll_original_digest="$("${COMPOSE[@]}" exec -T attendance-postgres psql -U iig_attendance -d iig_attendance -tAc \
  "SELECT source_hash FROM attendance_teacher_ledger WHERE teaching_schedule_id='teaching-001'" | tr -d '[:space:]')"
"${COMPOSE[@]}" exec -T attendance-postgres psql -U iig_attendance -d iig_attendance -v ON_ERROR_STOP=1 \
  -c "UPDATE attendance_teacher_ledger SET source_hash=repeat('f',64) WHERE teaching_schedule_id='teaching-001'"
broken_evidence="$("${COMPOSE[@]}" exec -T attendance-postgres wget -S -O /dev/null \
  --header "X-Internal-Key: ${payroll_private_key}" "$payroll_evidence_url" 2>&1 || true)"
if ! printf '%s' "$broken_evidence" | grep -Eq 'HTTP/[0-9.]+ 409|409 Conflict'; then
  echo "Attendance exposed tampered Payroll reference evidence as verified"; exit 1
fi
broken_reconciliation="$("${COMPOSE[@]}" exec -T attendance-postgres wget -S -O /dev/null \
  --header "X-Internal-Key: ${payroll_private_key}" "$payroll_reconcile_url" 2>&1 || true)"
if ! printf '%s' "$broken_reconciliation" | grep -Eq 'HTTP/[0-9.]+ 503|503 Service Unavailable'; then
  echo "Payroll accepted unavailable or corrupt Attendance source evidence"; exit 1
fi
"${COMPOSE[@]}" exec -T attendance-postgres psql -U iig_attendance -d iig_attendance -v ON_ERROR_STOP=1 \
  -c "UPDATE attendance_teacher_ledger SET source_hash='${payroll_original_digest}' WHERE teaching_schedule_id='teaching-001'"
payroll_reconciliation="$("${COMPOSE[@]}" exec -T attendance-postgres wget -qO- \
  --header "X-Internal-Key: ${payroll_private_key}" "$payroll_reconcile_url")"
PAYROLL_RECONCILIATION="$payroll_reconciliation" python3 -c \
  'import os,json; r=json.loads(os.environ["PAYROLL_RECONCILIATION"]); assert r["ready"] is True and r["mismatches"]==0,r'
payroll_ledger_count_after="$("${COMPOSE[@]}" exec -T payroll-postgres psql -U iig_payroll -d iig_payroll -tAc \
  "SELECT count(*) FROM payroll_ledgers" | tr -d '[:space:]')"
payroll_item_count_after="$("${COMPOSE[@]}" exec -T payroll-postgres psql -U iig_payroll -d iig_payroll -tAc \
  "SELECT count(*) FROM payroll_items" | tr -d '[:space:]')"
test "$payroll_ledger_count_before" = "$payroll_ledger_count_after" && \
  test "$payroll_item_count_before" = "$payroll_item_count_after" || {
    echo "Private reconciliation modified Payroll financial ledger"; exit 1
}
echo "PASS: Payroll fail-closed mismatch, recovery, key isolation, private API and financial non-mutation"
# Restore the Core lesson to its original unmarked/tombstone state.
"${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 \
  -c "DELETE FROM teacher_session_attendance WHERE attendance_id='${payroll_evidence_fixture}'"
verified_attendance="$(verify_attendance)"
printf '%s' "$verified_attendance" | python3 -c \
  'import json,sys; r=json.load(sys.stdin); assert r["ready"] is True and r["state"]=="READY",r'
echo "PASS: disposable Payroll Attendance fixture cleaned up without affecting later domain tests"

# Phase 8f-10: replay complete, independently verified Attendance source
# history into Payroll's own PostgreSQL. This is ONLY an evidence read model:
# salaries still use Core Work facts until an audited authority handoff.
payroll_events_url="http://attendance-service:8100/internal/attendance/payroll/events?after=0&limit=100"
payroll_reference_verify_url="http://payroll-service:8097/internal/payroll/attendance-reference/verify"
if "${COMPOSE[@]}" exec -T attendance-postgres wget -qO- "$payroll_events_url" >/dev/null 2>&1; then
  echo "Private Payroll source events accepted a missing key"; exit 1
fi
if "${COMPOSE[@]}" exec -T attendance-postgres wget -qO- "$payroll_reference_verify_url" >/dev/null 2>&1; then
  echo "Payroll reference verifier accepted a missing private key"; exit 1
fi
if [ "$(curl -sS -o /dev/null -w '%{http_code}' "${BASE}/internal/payroll/attendance-reference/verify")" != "404" ]; then
  echo "Payroll reference verifier leaked via public Gateway"; exit 1
fi
payroll_history_page="$("${COMPOSE[@]}" exec -T attendance-postgres wget -qO- \
  --header "X-Internal-Key: ${payroll_private_key}" "$payroll_events_url")"
PAYROLL_HISTORY="$payroll_history_page" python3 -c \
  'import os,json; p=json.loads(os.environ["PAYROLL_HISTORY"]); assert p["sourceRevision"]>0 and p["sourceEventCount"]>=4 and p["events"] and p["events"][0]["eventId"]>0 and len(p["events"][0]["sourceHash"])==64,p'
payroll_reference_proof="$("${COMPOSE[@]}" exec -T attendance-postgres wget -qO- \
  --header "X-Internal-Key: ${payroll_private_key}" "$payroll_reference_verify_url")"
PAYROLL_REFERENCE="$payroll_reference_proof" python3 -c \
  'import os,json; r=json.loads(os.environ["PAYROLL_REFERENCE"]); assert r["ready"] is True and r["eventCount"]>=4 and r["lastEventId"]>=r["eventCount"],r'
attendance_event_count="$("${COMPOSE[@]}" exec -T attendance-postgres psql -U iig_attendance -d iig_attendance -tAc \
  "SELECT count(*) FROM attendance_teacher_event_history" | tr -d '[:space:]')"
payroll_event_count="$("${COMPOSE[@]}" exec -T payroll-postgres psql -U iig_payroll -d iig_payroll -tAc \
  "SELECT count(*) FROM payroll_attendance_reference_events" | tr -d '[:space:]')"
test "$attendance_event_count" = "$payroll_event_count" || {
  echo "Payroll missing immutable source events after full catch-up"; exit 1
}
payroll_tombstone="$("${COMPOSE[@]}" exec -T payroll-postgres psql -U iig_payroll -d iig_payroll -tAc \
  "SELECT count(*) FROM payroll_attendance_reference_latest WHERE teaching_schedule_id='teaching-001' AND event_kind='DELETE' AND status IS NULL" | tr -d '[:space:]')"
test "$payroll_tombstone" = "1" || {
  echo "Payroll mirror resurrected a deleted teacher attendance"; exit 1
}
# Imported history is immutable at PostgreSQL SQL level, not merely in API.
history_rewrite="$("${COMPOSE[@]}" exec -T payroll-postgres psql -U iig_payroll -d iig_payroll \
  -v ON_ERROR_STOP=1 -v VERBOSITY=verbose \
  -c "UPDATE payroll_attendance_reference_events SET source_hash=repeat('f',64) WHERE event_id=(SELECT MIN(event_id) FROM payroll_attendance_reference_events)" 2>&1 || true)"
if ! printf '%s' "$history_rewrite" | grep -q '55000'; then
  echo "Payroll could rewrite its immutable teacher evidence history"; exit 1
fi
# Corrupt latest evidence, leaving immutable history intact. The private
# strong-readiness endpoint must reject even though counts and IDs match.
payroll_latest_hash="$("${COMPOSE[@]}" exec -T payroll-postgres psql -U iig_payroll -d iig_payroll -tAc \
  "SELECT source_hash FROM payroll_attendance_reference_latest WHERE teaching_schedule_id='teaching-001'" | tr -d '[:space:]')"
"${COMPOSE[@]}" exec -T payroll-postgres psql -U iig_payroll -d iig_payroll -v ON_ERROR_STOP=1 \
  -c "UPDATE payroll_attendance_reference_latest SET source_hash=repeat('f',64) WHERE teaching_schedule_id='teaching-001'"
broken_payroll_reference="$("${COMPOSE[@]}" exec -T attendance-postgres wget -S -O /dev/null \
  --header "X-Internal-Key: ${payroll_private_key}" "$payroll_reference_verify_url" 2>&1 || true)"
if ! printf '%s' "$broken_payroll_reference" | grep -Eq 'HTTP/[0-9.]+ 409|409 Conflict'; then
  echo "Payroll reference verifier accepted tampered independent latest status"; exit 1
fi
"${COMPOSE[@]}" exec -T payroll-postgres psql -U iig_payroll -d iig_payroll -v ON_ERROR_STOP=1 \
  -c "UPDATE payroll_attendance_reference_latest SET source_hash='${payroll_latest_hash}' WHERE teaching_schedule_id='teaching-001'"
# Rewind only Payroll's disposable cursor, not Core's or Attendance's:
# committed old events must replay idempotently without duplicate history.
"${COMPOSE[@]}" exec -T payroll-postgres psql -U iig_payroll -d iig_payroll -v ON_ERROR_STOP=1 \
  -c "UPDATE payroll_attendance_reference_cursor SET last_event_id=0 WHERE singleton=TRUE"
payroll_reference_proof="$("${COMPOSE[@]}" exec -T attendance-postgres wget -qO- \
  --header "X-Internal-Key: ${payroll_private_key}" "$payroll_reference_verify_url")"
PAYROLL_REFERENCE="$payroll_reference_proof" python3 -c \
  'import os,json; r=json.loads(os.environ["PAYROLL_REFERENCE"]); assert r["ready"] is True,r'
replayed_payroll_events="$("${COMPOSE[@]}" exec -T payroll-postgres psql -U iig_payroll -d iig_payroll -tAc \
  "SELECT count(*) FROM payroll_attendance_reference_events" | tr -d '[:space:]')"
test "$replayed_payroll_events" = "$attendance_event_count" || {
  echo "Payroll replay duplicated or lost independent Attendance source events"; exit 1
}
test "$payroll_ledger_count_before" = "$("${COMPOSE[@]}" exec -T payroll-postgres psql -U iig_payroll -d iig_payroll -tAc "SELECT count(*) FROM payroll_ledgers" | tr -d '[:space:]')" && \
  test "$payroll_item_count_before" = "$("${COMPOSE[@]}" exec -T payroll-postgres psql -U iig_payroll -d iig_payroll -tAc "SELECT count(*) FROM payroll_items" | tr -d '[:space:]')" || {
  echo "Payroll evidence replay mutated the financial ledger or line items"; exit 1
}
echo "PASS: Payroll-owned immutable teacher evidence, full historical replay, tombstones, tamper rejection and no financial writes"

echo "PASS: payroll ledger is owned by independent PostgreSQL and historical data preserved"

# Payroll calculation is a management action, not a worker self-service write.
teacher_login_payroll="$(curl -fsS -X POST "${BASE}/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"teacher001","password":"123456"}')"
teacher_payroll_token="$(printf '%s' "$teacher_login_payroll" | python3 -c 'import json,sys;print(json.load(sys.stdin)["token"])')"
worker_calc_code="$(curl -sS -o /dev/null -w '%{http_code}' -X POST \
  "${BASE}/api/payroll/calculate?period=2026-10" \
  -H "Authorization: Bearer ${teacher_payroll_token}")"
test "$worker_calc_code" = "403" || { echo "Worker calculated payroll: $worker_calc_code"; exit 1; }
other_salary_code="$(curl -sS -o /dev/null -w '%{http_code}' \
  "${BASE}/api/payroll?period=2026-10&employeeId=cs-001" \
  -H "Authorization: Bearer ${teacher_payroll_token}")"
test "$other_salary_code" = "403" || { echo "Worker viewed another payroll: $other_salary_code"; exit 1; }

# Duplicate calculations must be idempotent at the ledger identity boundary.
ledger_before="$("${COMPOSE[@]}" exec -T payroll-postgres psql -U iig_payroll -d iig_payroll -tAc \
  "SELECT id FROM payroll_ledgers WHERE employee_id='teacher-001' AND period='2026-10' AND source='EMPLOYEE'" | tr -d '[:space:]')"
curl -fsS -X POST "${BASE}/api/payroll/calculate?period=2026-10&employeeId=teacher-001" \
  -H "Authorization: Bearer ${TOKEN}" >/dev/null
ledger_after="$("${COMPOSE[@]}" exec -T payroll-postgres psql -U iig_payroll -d iig_payroll -tAc \
  "SELECT id FROM payroll_ledgers WHERE employee_id='teacher-001' AND period='2026-10' AND source='EMPLOYEE'" | tr -d '[:space:]')"
test -n "$ledger_before" && test "$ledger_before" = "$ledger_after"
legacy_calculated="$("${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -tAc \
  "SELECT count(*) FROM employee_salaries WHERE employee_id='teacher-001' AND period='2026-10' AND status='CALCULATED'" | tr -d '[:space:]')"
test "$legacy_calculated" = "0" || { echo "Payroll unexpectedly wrote the old Core payroll database"; exit 1; }
echo "PASS: repeated payroll calculation preserves ledger identity, Core payroll stays untouched"

# Immutable finalized payroll: duplicated calculate command must not rewrite
# approved payments. Test only against disposable Docker CI database.
"${COMPOSE[@]}" exec -T payroll-postgres psql -U iig_payroll -d iig_payroll -v ON_ERROR_STOP=1 -c \
  "UPDATE payroll_ledgers SET status='FINALIZED' WHERE employee_id='teacher-001' AND period='2026-10' AND source='EMPLOYEE'"
frozen_code="$(curl -sS -o /dev/null -w '%{http_code}' -X POST \
  "${BASE}/api/payroll/calculate?period=2026-10&employeeId=teacher-001" \
  -H "Authorization: Bearer ${TOKEN}")"
test "$frozen_code" = "409" || { echo "Finalized ledger could be recalculated: $frozen_code"; exit 1; }
echo "PASS: Payroll authorization and finalized-ledger immutability"

# Reporting's financial figures must now come from the authoritative Payroll
# ledger, not the legacy Core employee_salaries or staff_salaries tables.
source_sum="$("${COMPOSE[@]}" exec -T payroll-postgres psql -U iig_payroll -d iig_payroll -tAc \
  "SELECT COALESCE(SUM(total_amount),0) FROM payroll_ledgers WHERE period='2026-10'" | tr -d '[:space:]')"
source_records="$("${COMPOSE[@]}" exec -T payroll-postgres psql -U iig_payroll -d iig_payroll -tAc \
  "SELECT COUNT(*) FROM payroll_ledgers WHERE period='2026-10'" | tr -d '[:space:]')"
reported="$(curl -fsS "${BASE}/api/statistics?period=MONTH&year=2026&month=10" \
  -H "Authorization: Bearer ${TOKEN}")"
printf '%s' "$reported" | COST_SUM="$source_sum" COST_COUNT="$source_records" python3 -c '
import json,os,sys
from decimal import Decimal
p=json.load(sys.stdin,parse_float=Decimal)
cost=Decimal(os.environ["COST_SUM"])
assert Decimal(str(p["financial"]["salaryCost"]))==cost, "Reporting salary total differs from Payroll"
assert int(p["financial"]["payrollRecords"])==int(os.environ["COST_COUNT"])
october=next(m for m in p["monthly"] if m["month"]==10)
assert Decimal(str(october["salaryCost"]))==cost, "Monthly salary chart uses stale core data"
'
private_payroll_financial="$(curl -sS -o /dev/null -w '%{http_code}' \
  "${BASE}/internal/payroll/financial?from=2026-01&to=2026-12")"
test "$private_payroll_financial" = "404"
echo "PASS: Reporting financial statistics use the independent Payroll ledger"

# Phase 7: Scheduling owns availability registration, approvals, updates and
# tombstones in its independent PostgreSQL; Core holds a reference read copy.
schedule_imported=0
for i in $(seq 1 70); do
  imported="$("${COMPOSE[@]}" exec -T scheduling-postgres psql -U iig_scheduling -d iig_scheduling -tAc \
    "SELECT count(*) FROM scheduling_metadata WHERE key='availability-imported'" 2>/dev/null | tr -d '[:space:]' || true)"
  if [ "$imported" = "1" ]; then schedule_imported=1; break; fi
  sleep 2
done
test "$schedule_imported" = "1" || { echo "Scheduling historical availability import failed"; exit 1; }
legacy_slot="$("${COMPOSE[@]}" exec -T scheduling-postgres psql -U iig_scheduling -d iig_scheduling -tAc \
  "SELECT count(*) FROM scheduling_availabilities WHERE availability_id='auto-avail-1'" | tr -d '[:space:]')"
test "$legacy_slot" = "1" || { echo "Existing availability lost in migration"; exit 1; }

teacher_avail_login="$(curl -fsS -X POST "${BASE}/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"teacher001","password":"123456"}')"
teacher_avail_token="$(printf '%s' "$teacher_avail_login" | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')"
avail_request='{"availableDate":"2026-12-08","dayOfWeek":"tue","shift":"MORNING","startTime":"06:00","endTime":"07:00","availabilityType":"WEEKLY","campus":"Cơ sở chính","note":"Scheduling microservices smoke"}'
new_slot="$(curl -fsS -X POST "${BASE}/api/availabilities" \
  -H "Authorization: Bearer ${teacher_avail_token}" \
  -H 'Content-Type: application/json' -d "$avail_request")"
slot_id="$(printf '%s' "$new_slot" | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])')"
test -n "$slot_id"
own_slots="$(curl -fsS "${BASE}/api/availabilities/me?weekStart=2026-12-07" \
  -H "Authorization: Bearer ${teacher_avail_token}")"
printf '%s' "$own_slots" | SLOT_ID="$slot_id" python3 -c '
import sys,json,os
rows=json.load(sys.stdin)
assert any(x["id"]==os.environ["SLOT_ID"] and x["status"]=="PENDING" for x in rows)
'
duplicate_code="$(curl -sS -o /dev/null -w '%{http_code}' -X POST "${BASE}/api/availabilities" \
  -H "Authorization: Bearer ${teacher_avail_token}" \
  -H 'Content-Type: application/json' -d "$avail_request")"
test "$duplicate_code" = "409" || { echo "Concurrent/duplicate availability overlap not blocked: $duplicate_code"; exit 1; }

review_unauthorized="$(curl -sS -o /dev/null -w '%{http_code}' -X PATCH \
  "${BASE}/api/availabilities/${slot_id}/review" \
  -H "Authorization: Bearer ${teacher_avail_token}" \
  -H 'Content-Type: application/json' -d '{"status":"APPROVED"}')"
test "$review_unauthorized" = "403" || { echo "Teacher incorrectly reviewed own availability"; exit 1; }
reviewed="$(curl -fsS -X PATCH "${BASE}/api/availabilities/${slot_id}/review" \
  -H "Authorization: Bearer ${tc_token}" -H 'Content-Type: application/json' \
  -d '{"status":"APPROVED"}')"
printf '%s' "$reviewed" | python3 -c 'import json,sys; assert json.load(sys.stdin)["status"]=="APPROVED"'

projected=0
for i in $(seq 1 50); do
  projection="$("${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -tAc \
    "SELECT v.version||':'||a.status FROM scheduling_availability_versions v JOIN availabilities a ON a.availability_id=v.availability_id WHERE v.availability_id='${slot_id}'" | tr -d '[:space:]')"
  if [ "$projection" = "2:APPROVED" ]; then projected=1; break; fi
  sleep 2
done
test "$projected" = "1" || { echo "Approved Scheduling status not projected to Core (last=$projection)"; exit 1; }
echo "PASS: Scheduling owns availabilities and projects versioned updates"

# Simulate an assignment holding a temporary reservation; the same isolation
# mechanism is used by SchedulingReservationClient during a Core transaction.
"${COMPOSE[@]}" exec -T scheduling-postgres psql -U iig_scheduling -d iig_scheduling -v ON_ERROR_STOP=1 -c \
  "INSERT INTO scheduling_availability_reservations(reservation_id,availability_id,employee_id,expires_at) VALUES('smoke-inflight','${slot_id}','teacher-001',NOW()+interval '3 minutes')"
busy_code="$(curl -sS -o /dev/null -w '%{http_code}' -X PUT \
  "${BASE}/api/availabilities/${slot_id}" \
  -H "Authorization: Bearer ${teacher_avail_token}" -H 'Content-Type: application/json' \
  -d "$avail_request")"
test "$busy_code" = "409" || { echo "In-flight teacher assignment did not protect availability: $busy_code"; exit 1; }
delete_busy="$(curl -sS -o /dev/null -w '%{http_code}' -X DELETE \
  "${BASE}/api/availabilities/${slot_id}" -H "Authorization: Bearer ${teacher_avail_token}")"
test "$delete_busy" = "409" || { echo "Availability deletion bypassed reservation: $delete_busy"; exit 1; }
"${COMPOSE[@]}" exec -T scheduling-postgres psql -U iig_scheduling -d iig_scheduling -v ON_ERROR_STOP=1 -c \
  "DELETE FROM scheduling_availability_reservations WHERE reservation_id='smoke-inflight'"

edited="$(curl -fsS -X PUT "${BASE}/api/availabilities/${slot_id}" \
  -H "Authorization: Bearer ${teacher_avail_token}" -H 'Content-Type: application/json' \
  -d "$avail_request")"
printf '%s' "$edited" | python3 -c 'import sys,json; assert json.load(sys.stdin)["status"]=="PENDING"'
delete_code="$(curl -sS -o /dev/null -w '%{http_code}' -X DELETE \
  "${BASE}/api/availabilities/${slot_id}" -H "Authorization: Bearer ${teacher_avail_token}")"
test "$delete_code" = "204" || { echo "Availability delete failed: $delete_code"; exit 1; }

tombstone=0
for i in $(seq 1 50); do
  vers="$("${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -tAc \
    "SELECT version FROM scheduling_availability_versions WHERE availability_id='${slot_id}'" | tr -d '[:space:]')"
  count="$("${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -tAc \
    "SELECT COUNT(*) FROM availabilities WHERE availability_id='${slot_id}'" | tr -d '[:space:]')"
  if [ "$vers" = "4" ] && [ "$count" = "0" ]; then tombstone=1; break; fi
  sleep 2
done
test "$tombstone" = "1" || { echo "Availability tombstone projection failed: version=$vers, count=$count"; exit 1; }
audit_delivered=0
for i in $(seq 1 50); do
  count="$("${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -tAc \
    "SELECT count(*) FROM audit_logs WHERE entity_id='${slot_id}' AND entity_type='AVAILABILITY'" | tr -d '[:space:]')"
  if [ "$count" = "4" ]; then audit_delivered=1; break; fi
  sleep 2
done
test "$audit_delivered" = "1" || { echo "Scheduling audit outbox delivery failed: $count/4"; exit 1; }
echo "PASS: Scheduling reservations block mutations, tombstones never resurrect, audit is durable"

private_scheduling_code="$(curl -sS -o /dev/null -w '%{http_code}' \
  "${BASE}/internal/scheduling/availability/export")"
test "$private_scheduling_code" = "404"
echo "PASS: scheduling migration internals are unavailable via public Gateway"

# The independent Audit Service must eventually receive all four distinct
# availability events, not just the last version of the state projection.
audit_events_delivered=0
for i in $(seq 1 60); do
  result="$("${COMPOSE[@]}" exec -T audit-postgres psql -U iig_audit -d iig_audit -tAc \
    "SELECT count(*) FROM audit_events WHERE entity_id='${slot_id}' AND entity_type='AVAILABILITY'" 2>/dev/null | tr -d '[:space:]' || true)"
  if [ "$result" = "4" ]; then audit_events_delivered=1; break; fi
  sleep 2
done
test "$audit_events_delivered" = "1" || { echo "Independent Audit Service did not receive scheduling history ($result/4)"; exit 1; }
echo "PASS: all four Scheduling mutations reached the independent Audit database"

# Phase 8a: Core still owns assignment writes, while Scheduling serves the
# browser GETs from its versioned independent database.
for i in $(seq 1 70); do
  imported="$("${COMPOSE[@]}" exec -T scheduling-postgres psql -U iig_scheduling -d iig_scheduling -tAc "SELECT count(*) FROM scheduling_metadata WHERE key='assignments-imported'" 2>/dev/null | tr -d '[:space:]' || true)"
  test "$imported" = "1" && break
  sleep 2
done
test "$imported" = "1" || { echo "Assignment read-model bootstrap failed"; exit 1; }
legacy_count="$("${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -tAc "SELECT count(*) FROM teaching_schedules" | tr -d '[:space:]')"
projected_count="$("${COMPOSE[@]}" exec -T scheduling-postgres psql -U iig_scheduling -d iig_scheduling -tAc "SELECT count(*) FROM scheduling_assignment_read_model WHERE source_type='TEACHING' AND deleted=FALSE" | tr -d '[:space:]')"
test "$legacy_count" = "$projected_count" || { echo "Initial teacher schedule count mismatch"; exit 1; }

# Disposable CI data only: insert through SQL to prove the trigger catches
# every Core write path, including ClassStaffingRangeService bulk SQL.
fixture="ms-assign-${GITHUB_RUN_ID:-local}"
"${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 -c "INSERT INTO teaching_schedules(teaching_schedule_id,teacher_id,class_id,date,start_time,end_time,status) VALUES('${fixture}-t','teacher-001','${one}','2027-02-11','09:00','11:00','ASSIGNED')"
"${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 -c "INSERT INTO staff_schedules(staff_schedule_id,employee_id,class_id,date,start_time,end_time,work_type,status) VALUES('${fixture}-s','cs-001','${one}','2027-02-11','09:00','11:00','CLASS_SUPPORT','ASSIGNED')"
for i in $(seq 1 70); do
  projected="$("${COMPOSE[@]}" exec -T scheduling-postgres psql -U iig_scheduling -d iig_scheduling -tAc "SELECT count(*) FROM scheduling_assignment_read_model WHERE schedule_id IN ('${fixture}-t','${fixture}-s') AND version=1 AND deleted=FALSE" | tr -d '[:space:]')"
  test "$projected" = "2" && break
  sleep 2
done
test "$projected" = "2" || { echo "Teacher/CS assignment projection did not arrive"; exit 1; }
teacher_view="$(curl -fsS "${BASE}/api/teaching-schedules?weekStart=2027-02-08" -H "Authorization: Bearer ${tc_token}")"
printf '%s' "$teacher_view" | FIXTURE_ID="${fixture}-t" python3 -c 'import os,sys,json; assert any(x.get("id")==os.environ["FIXTURE_ID"] and x.get("teacherId")=="teacher-001" and x.get("status")=="ASSIGNED" for x in json.load(sys.stdin))'
cm_login="$(curl -fsS -X POST "${BASE}/api/auth/login" -H 'Content-Type: application/json' -d '{"username":"cm001","password":"123456"}')"
cm_token="$(printf '%s' "$cm_login" | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')"
staff_view="$(curl -fsS "${BASE}/api/staff-schedules?weekStart=2027-02-08&role=CS" -H "Authorization: Bearer ${cm_token}")"
printf '%s' "$staff_view" | FIXTURE_ID="${fixture}-s" python3 -c 'import os,sys,json; assert any(x.get("id")==os.environ["FIXTURE_ID"] and x.get("employeeId")=="cs-001" and x.get("staffRole")=="CS" for x in json.load(sys.stdin))'
forbidden="$(curl -sS -o /dev/null -w '%{http_code}' "${BASE}/api/staff-schedules?weekStart=2027-02-08" -H "Authorization: Bearer ${teacher_avail_token}")"
test "$forbidden" = "403" || { echo "Worker bypassed assignment read role scope: $forbidden"; exit 1; }
echo "PASS: Scheduling read model preserves FE GET shapes and current role boundaries"

# A class rename is a separate Core write path, but must still invalidate
# both assignment summaries because the FE displays their class labels.
"${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 -c \
  "UPDATE classes SET name='Assignment read model updated' WHERE class_id='${one}'"
for i in $(seq 1 60); do
  renamed="$("${COMPOSE[@]}" exec -T scheduling-postgres psql -U iig_scheduling -d iig_scheduling -tAc \
    "SELECT count(*) FROM scheduling_assignment_read_model WHERE schedule_id IN ('${fixture}-t','${fixture}-s') AND version=2" | tr -d '[:space:]')"
  test "$renamed" = "2" && break
  sleep 2
done
test "$renamed" = "2" || { echo "Class metadata changes did not invalidate assignment copies"; exit 1; }
class_name="$("${COMPOSE[@]}" exec -T scheduling-postgres psql -U iig_scheduling -d iig_scheduling -tAc \
  "SELECT payload->>'className' FROM scheduling_assignment_read_model WHERE schedule_id='${fixture}-t'" | tr -d '\r\n')"
test "$class_name" = "Assignment read model updated" || { echo "Teacher class display label was stale"; exit 1; }
echo "PASS: Core class metadata updates invalidate independent schedule read models"

"${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 -c "UPDATE teaching_schedules SET status='CANCELLED' WHERE teaching_schedule_id='${fixture}-t'"
"${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 -c "UPDATE staff_schedules SET status='CANCELLED' WHERE staff_schedule_id='${fixture}-s'"
# Read through Gateway immediately after Core commits, without waiting for
# the background relay. This is the user-visible cancel-then-refresh contract.
teacher_view="$(curl -fsS "${BASE}/api/teaching-schedules?weekStart=2027-02-08" -H "Authorization: Bearer ${tc_token}")"
printf '%s' "$teacher_view" | FIXTURE_ID="${fixture}-t" python3 -c 'import os,sys,json; assert any(x.get("id")==os.environ["FIXTURE_ID"] and x.get("status")=="CANCELLED" for x in json.load(sys.stdin)), "Teacher cancellation read stale"'
staff_view="$(curl -fsS "${BASE}/api/staff-schedules?weekStart=2027-02-08&role=CS" -H "Authorization: Bearer ${cm_token}")"
printf '%s' "$staff_view" | FIXTURE_ID="${fixture}-s" python3 -c 'import os,sys,json; assert any(x.get("id")==os.environ["FIXTURE_ID"] and x.get("status")=="CANCELLED" for x in json.load(sys.stdin)), "Staff cancellation read stale"'
echo "PASS: first GET after committed cancellation sees current teacher and CS status"
for i in $(seq 1 70); do
  changed="$("${COMPOSE[@]}" exec -T scheduling-postgres psql -U iig_scheduling -d iig_scheduling -tAc "SELECT count(*) FROM scheduling_assignment_read_model WHERE schedule_id IN ('${fixture}-t','${fixture}-s') AND status='CANCELLED' AND version=3" | tr -d '[:space:]')"
  test "$changed" = "2" && break
  sleep 2
done
test "$changed" = "2" || { echo "Cancelled assignment version didn't sync"; exit 1; }
"${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 -c "DELETE FROM teaching_schedules WHERE teaching_schedule_id='${fixture}-t'"
"${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 -c "DELETE FROM staff_schedules WHERE staff_schedule_id='${fixture}-s'"
# Tombstones must also be visible on the first authorized GET, not after a
# later browser reload or the 5-second asynchronous relay.
teacher_view="$(curl -fsS "${BASE}/api/teaching-schedules?weekStart=2027-02-08" -H "Authorization: Bearer ${tc_token}")"
printf '%s' "$teacher_view" | FIXTURE_ID="${fixture}-t" python3 -c 'import os,sys,json; assert all(x.get("id")!=os.environ["FIXTURE_ID"] for x in json.load(sys.stdin)), "Deleted teacher schedule resurfaced"'
staff_view="$(curl -fsS "${BASE}/api/staff-schedules?weekStart=2027-02-08&role=CS" -H "Authorization: Bearer ${cm_token}")"
printf '%s' "$staff_view" | FIXTURE_ID="${fixture}-s" python3 -c 'import os,sys,json; assert all(x.get("id")!=os.environ["FIXTURE_ID"] for x in json.load(sys.stdin)), "Deleted CS shift resurfaced"'
echo "PASS: first GET after hard delete excludes old teacher and CS schedules"
for i in $(seq 1 70); do
  deleted="$("${COMPOSE[@]}" exec -T scheduling-postgres psql -U iig_scheduling -d iig_scheduling -tAc "SELECT count(*) FROM scheduling_assignment_read_model WHERE schedule_id IN ('${fixture}-t','${fixture}-s') AND deleted=TRUE AND version=4" | tr -d '[:space:]')"
  test "$deleted" = "2" && break
  sleep 2
done
test "$deleted" = "2" || { echo "Hard delete tombstones missing"; exit 1; }
for i in $(seq 1 40); do
  acked="$("${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -tAc "SELECT count(*) FROM scheduling_assignment_outbox WHERE schedule_id IN ('${fixture}-t','${fixture}-s') AND version=4 AND delivered_at IS NOT NULL" | tr -d '[:space:]')"
  test "$acked" = "2" && break
  sleep 2
done
test "$acked" = "2" || { echo "Exact-version Core acknowledgements missing"; exit 1; }
echo "PASS: all Core assignment writes relay idempotently, including deletion tombstones"

# Phase 8b-1: SQL-backed worker-time intent reservation semantics. Use only
# disposable Scheduling PostgreSQL data and prove overlap / adjacency / scope,
# plus ON DELETE CASCADE when Core releases a lease or expiry cleanup runs.
intent_id="${fixture}-intent"
"${COMPOSE[@]}" exec -T scheduling-postgres psql -U iig_scheduling -d iig_scheduling -v ON_ERROR_STOP=1 -c \
  "INSERT INTO scheduling_availability_reservations(reservation_id,availability_id,employee_id,expires_at) SELECT '${intent_id}',availability_id,employee_id,NOW()+interval '10 minutes' FROM scheduling_availabilities WHERE availability_id='auto-avail-1'"
"${COMPOSE[@]}" exec -T scheduling-postgres psql -U iig_scheduling -d iig_scheduling -v ON_ERROR_STOP=1 -c \
  "INSERT INTO scheduling_assignment_intent_reservations(reservation_id,employee_id,work_date,start_time,end_time) VALUES ('${intent_id}','teacher-001','2027-02-11','09:00','11:00')"
overlap_query="SELECT count(*) FROM scheduling_assignment_intent_reservations i JOIN scheduling_availability_reservations lease ON lease.reservation_id=i.reservation_id WHERE i.employee_id='teacher-001' AND i.work_date='2027-02-11' AND i.start_time<'10:00' AND i.end_time>'09:30' AND lease.expires_at>NOW()"
overlap_count="$("${COMPOSE[@]}" exec -T scheduling-postgres psql -U iig_scheduling -d iig_scheduling -tAc "$overlap_query" | tr -d '[:space:]')"
test "$overlap_count" = "1" || { echo "Scheduling assignment intent overlap SQL failed"; exit 1; }
adjacent_count="$("${COMPOSE[@]}" exec -T scheduling-postgres psql -U iig_scheduling -d iig_scheduling -tAc \
  "SELECT count(*) FROM scheduling_assignment_intent_reservations i JOIN scheduling_availability_reservations lease ON lease.reservation_id=i.reservation_id WHERE i.employee_id='teacher-001' AND i.work_date='2027-02-11' AND i.start_time<'12:00' AND i.end_time>'11:00' AND lease.expires_at>NOW()" | tr -d '[:space:]')"
test "$adjacent_count" = "0" || { echo "Adjacent assignment intent incorrectly overlaps"; exit 1; }
other_count="$("${COMPOSE[@]}" exec -T scheduling-postgres psql -U iig_scheduling -d iig_scheduling -tAc \
  "SELECT count(*) FROM scheduling_assignment_intent_reservations WHERE employee_id='cs-001' AND work_date='2027-02-11'" | tr -d '[:space:]')"
test "$other_count" = "0" || { echo "Worker intent scope test invalid"; exit 1; }
"${COMPOSE[@]}" exec -T scheduling-postgres psql -U iig_scheduling -d iig_scheduling -v ON_ERROR_STOP=1 -c \
  "DELETE FROM scheduling_availability_reservations WHERE reservation_id='${intent_id}'"
orphan_count="$("${COMPOSE[@]}" exec -T scheduling-postgres psql -U iig_scheduling -d iig_scheduling -tAc \
  "SELECT count(*) FROM scheduling_assignment_intent_reservations WHERE reservation_id='${intent_id}'" | tr -d '[:space:]')"
test "$orphan_count" = "0" || { echo "Assignment intent survived lease release"; exit 1; }
echo "PASS: Scheduling-owned worker/time intents reject overlap and follow lease lifecycle"

# Phase 8b-2: true API-level command retry through Gateway. The disposable class
# is DRAFT, so the CS support cancellation is allowed; its previous assignment
# is injected with SQL only to avoid changing the project's business fixtures.
# Both DELETE requests must be 204, with exactly one audit and one persisted
# successful command receipt. No extra schedule mutation is permitted.
idem_shift="${fixture}-idem-s"
idem_key="smoke-${GITHUB_RUN_ID:-local}-cancel"
"${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 -c \
  "INSERT INTO staff_schedules(staff_schedule_id,employee_id,class_id,date,start_time,end_time,work_type,status) VALUES ('${idem_shift}','cs-001','${one}','2027-02-11','09:00','11:00','CLASS_SUPPORT','ASSIGNED')"
for i in 1 2; do
  code="$(curl -sS -o /dev/null -w '%{http_code}' -X DELETE \
    "${BASE}/api/staff-schedules/${idem_shift}" \
    -H "Authorization: Bearer ${cm_token}" \
    -H "Idempotency-Key: ${idem_key}")"
  test "$code" = "204" || { echo "Idempotent cancel request $i returned $code"; exit 1; }
done
state="$("${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -tAc \
  "SELECT status FROM staff_schedules WHERE staff_schedule_id='${idem_shift}'" | tr -d '[:space:]')"
test "$state" = "CANCELLED" || { echo "Idempotent cancel was not persisted"; exit 1; }
audit_count_idem="$("${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -tAc \
  "SELECT count(*) FROM audit_logs WHERE action='CANCEL_STAFF_SHIFT' AND entity_id='${idem_shift}'" | tr -d '[:space:]')"
test "$audit_count_idem" = "1" || { echo "Retry duplicated cancellation audit ($audit_count_idem)"; exit 1; }
receipt_count="$("${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -tAc \
  "SELECT count(*) FROM scheduling_command_receipts WHERE actor_username='cm001' AND command_type='DELETE /api/staff-schedules/${idem_shift}' AND request_key='${idem_key}' AND completed_at IS NOT NULL AND response_payload='null'::jsonb" | tr -d '[:space:]')"
test "$receipt_count" = "1" || { echo "Command receipt not atomically persisted ($receipt_count)"; exit 1; }
echo "PASS: Gateway assignment cancellation retries return 204 with one write, audit, and receipt"

# Phase 8b-3a: only the private network can invoke the strict cutover gate.
# Use the existing postgres-alpine BusyBox wget as a Compose-network client.
# Obtain the configured key from the running container without logging it.
scheduling_id="$("${COMPOSE[@]}" ps -q scheduling-service)"
scheduling_key="$(docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$scheduling_id" | sed -n 's/^SCHEDULING_INTERNAL_API_KEY=//p')"
test -n "$scheduling_key" || { echo "Scheduling internal key unavailable"; exit 1; }
verification_url="http://scheduling-service:8098/internal/scheduling/assignments/verify"
if "${COMPOSE[@]}" exec -T scheduling-postgres wget -qO- "$verification_url" >/dev/null 2>&1; then
  echo "Private assignment cutover check accepted missing key"; exit 1
fi
verified=0
for i in $(seq 1 50); do
  report="$("${COMPOSE[@]}" exec -T scheduling-postgres wget -qO- \
    --header "X-Internal-Key: ${scheduling_key}" "$verification_url" 2>/dev/null || true)"
  if printf '%s' "$report" | python3 -c 'import json,sys; d=json.load(sys.stdin); assert d["ready"] and d["state"]=="READY" and d["sourceRows"]==d["localRows"]' >/dev/null 2>&1; then
    verified=1
    break
  fi
  sleep 2
done
test "$verified" = "1" || { echo "Scheduling cutover manifest failed strict parity after projection drained"; exit 1; }
echo "PASS: complete private Core-vs-Scheduling assignment manifest is consistent"

# Inject a Scheduling-only extra tombstone with no corresponding Core outbox key:
# the readiness gate must fail closed and recover after fixing the local drift.
ghost_id="${fixture}-ghost"
"${COMPOSE[@]}" exec -T scheduling-postgres psql -U iig_scheduling -d iig_scheduling -v ON_ERROR_STOP=1 -c \
  "INSERT INTO scheduling_assignment_read_model(source_type,schedule_id,version,deleted,payload) VALUES('STAFF','${ghost_id}',1,TRUE,'{}'::jsonb)"
if "${COMPOSE[@]}" exec -T scheduling-postgres wget -qO- \
  --header "X-Internal-Key: ${scheduling_key}" "$verification_url" >/dev/null 2>&1; then
  echo "Scheduling cutover accepted orphaned projection"; exit 1
fi
"${COMPOSE[@]}" exec -T scheduling-postgres psql -U iig_scheduling -d iig_scheduling -v ON_ERROR_STOP=1 -c \
  "DELETE FROM scheduling_assignment_read_model WHERE source_type='STAFF' AND schedule_id='${ghost_id}'"
report="$("${COMPOSE[@]}" exec -T scheduling-postgres wget -qO- \
  --header "X-Internal-Key: ${scheduling_key}" "$verification_url")"
printf '%s' "$report" | python3 -c 'import json,sys; assert json.load(sys.stdin)["ready"]'
echo "PASS: cutover gate detects divergent projection and confirms recovery"

# Phase 8b-3b: every migration row must also be backed by the normalized
# Scheduling write-shadow, including already-cancelled and tombstoned rows.
source_rows="$("${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -tAc \
  "SELECT count(*) FROM scheduling_assignment_outbox" | tr -d '[:space:]')"
shadow_rows="$("${COMPOSE[@]}" exec -T scheduling-postgres psql -U iig_scheduling -d iig_scheduling -tAc \
  "SELECT count(*) FROM scheduling_assignment_write_shadow" | tr -d '[:space:]')"
test "$source_rows" = "$shadow_rows" || {
  echo "Normalized Scheduling shadow does not cover Core history ($shadow_rows/$source_rows)"
  exit 1
}
echo "PASS: normalized assignment write-shadow includes every Core outbox revision"

# In a single DO transaction, verify that the database fence blocks even SQL
# issued outside the HTTP controllers. Restore CORE before committing; any
# failure automatically rolls back this test's fence change.
"${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 <<SQL
DO \$\$
BEGIN
  UPDATE scheduling_assignment_writer_control
     SET writer_mode='QUIESCED', changed_at=NOW() WHERE singleton=TRUE;
  BEGIN
    UPDATE staff_schedules SET status=status
      WHERE staff_schedule_id='${idem_shift}';
    RAISE EXCEPTION 'Core assignment update bypassed the write fence';
  EXCEPTION WHEN SQLSTATE '55000' THEN
    NULL; -- Expected PostgreSQL fence exception
  END;
  BEGIN
    -- BEFORE INSERT must reject this even before the FK check: attendance
    -- writes are frozen together with teaching assignments.
    INSERT INTO teacher_session_attendance
      (attendance_id,teaching_schedule_id,status,recorded_by)
    VALUES ('${fixture}-fence-att','nonexistent-cutover-shift','PRESENT','teacher-001');
    RAISE EXCEPTION 'Attendance insert bypassed the Core write fence';
  EXCEPTION WHEN SQLSTATE '55000' THEN
    NULL;
  END;
  UPDATE scheduling_assignment_writer_control
     SET writer_mode='CORE',changed_at=NOW() WHERE singleton=TRUE;
END
\$\$;
SQL
echo "PASS: database write fence blocks direct Core assignment SQL in QUIESCED mode"

# Core may only resume after a verified handback. The disposable fixture below
# simulates a committed independent assignment reference and clears it first.
"${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 <<SQL
DO \$\$
BEGIN
  UPDATE scheduling_assignment_writer_control
    SET writer_mode='QUIESCED',changed_at=NOW() WHERE singleton=TRUE;
  INSERT INTO scheduling_assignment_reference_receipts
    (source_type,schedule_id,last_revision,last_event_id,payload_hash,actor_username)
  VALUES ('STAFF','${fixture}-handback',1,'${fixture}-handback-event',
          repeat('a',64),'cm001');
  BEGIN
    UPDATE scheduling_assignment_writer_control
      SET writer_mode='CORE',changed_at=NOW() WHERE singleton=TRUE;
    RAISE EXCEPTION 'Core resumed without verified Scheduling handback';
  EXCEPTION WHEN SQLSTATE '55000' THEN NULL;
  END;
  DELETE FROM scheduling_assignment_reference_receipts
    WHERE source_type='STAFF' AND schedule_id='${fixture}-handback';
  UPDATE scheduling_assignment_writer_control
    SET writer_mode='CORE',changed_at=NOW() WHERE singleton=TRUE;
END
\$\$;
SQL
echo "PASS: Core blocks unsafe writer handback after Scheduling reference events"

# A durable writer claim prevents Core handback BEFORE the independent
# Scheduling transaction commits or its outbox event reaches Core. A failed
# local write intentionally leaves a claim for reviewed recovery.
"${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 <<SQL
DO \$\$
BEGIN
  UPDATE scheduling_assignment_writer_control
    SET writer_mode='QUIESCED',changed_at=NOW() WHERE singleton=TRUE;
  INSERT INTO scheduling_assignment_writer_claims
    (actor_username,operation,request_key,manager_employee_id)
  VALUES ('cm001','ASSIGN_STAFF','${fixture}-handoff-claim','cm-001');
  BEGIN
    UPDATE scheduling_assignment_writer_control
      SET writer_mode='CORE',changed_at=NOW() WHERE singleton=TRUE;
    RAISE EXCEPTION 'Core resumed while Scheduling command admission is pending';
  EXCEPTION WHEN SQLSTATE '55000' THEN NULL;
  END;
  DELETE FROM scheduling_assignment_writer_claims
    WHERE actor_username='cm001' AND operation='ASSIGN_STAFF'
      AND request_key='${fixture}-handoff-claim';
  UPDATE scheduling_assignment_writer_control
    SET writer_mode='CORE',changed_at=NOW() WHERE singleton=TRUE;
END
\$\$;
SQL
claim_url="http://backend:8081/internal/scheduling/assignments/claims"
claim_json="$(printf '{"actorUsername":"cm001","managerEmployeeId":"cm-001","operation":"ASSIGN_STAFF","requestKey":"%s-claim-denied"}' "$fixture")"
claim_response="$("${COMPOSE[@]}" exec -T scheduling-postgres wget -S -O- \
  --header "X-Internal-Key: ${scheduling_key}" \
  --header "Content-Type: application/json" \
  --post-data "$claim_json" "$claim_url" 2>&1 || true)"
if ! printf '%s' "$claim_response" | grep -Eq 'HTTP/[0-9.]+[[:space:]]+409'; then
  current_mode="$("${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db \
    -tAc "SELECT writer_mode FROM scheduling_assignment_writer_control WHERE singleton=TRUE;" | tr -d '[:space:]')"
  echo "Core writer claim must return HTTP 409 while mode=CORE; actual mode=${current_mode}"
  echo "Internal test response (no bearer token included):"
  printf '%s\n' "$claim_response" | tail -25
  exit 1
fi
echo "PASS: Core handback is fenced by durable command claims; CORE mode cannot admit claims"



# Phase 8b-3b: future Scheduling commands may only be staged through a private
# service key AND a live Core JWT. Repeating a command never inserts an actual
# teacher/CS/Sale shift or invokes a Core assignment transaction.
stage_key="stage-${GITHUB_RUN_ID:-local}-001"
stage_json="{\"operation\":\"ASSIGN_STAFF\",\"requestKey\":\"${stage_key}\",\"payload\":{\"employeeId\":\"cs-001\",\"staffRole\":\"CS\"}}"
stage_url="http://scheduling-service:8098/internal/scheduling/assignments/commands/stage"
for i in 1 2; do
  staged="$("${COMPOSE[@]}" exec -T scheduling-postgres wget -qO- \
    --header "X-Internal-Key: ${scheduling_key}" \
    --header "Authorization: Bearer ${cm_token}" \
    --header "Content-Type: application/json" \
    --post-data "$stage_json" "$stage_url")"
  printf '%s' "$staged" | python3 -c 'import json,sys; assert json.load(sys.stdin)["state"]=="STAGED"'
done
staged_count="$("${COMPOSE[@]}" exec -T scheduling-postgres psql -U iig_scheduling -d iig_scheduling -tAc \
  "SELECT count(*) FROM scheduling_assignment_command_inbox WHERE actor_username='cm001' AND operation='ASSIGN_STAFF' AND request_key='${stage_key}' AND state='STAGED'" | tr -d '[:space:]')"
test "$staged_count" = "1" || { echo "Staged command inbox was not idempotent"; exit 1; }
public_staging="$(curl -sS -o /dev/null -w '%{http_code}' "${BASE}/internal/scheduling/assignments/commands/stage")"
test "$public_staging" = "404" || { echo "Private command stage API was publicly routed"; exit 1; }
echo "PASS: private Scheduling inbox stages repeated commands without business writes"

# The independent standalone writer is deliberately not registered in the
# default Compose stack. Normal operations still use Core assignment writes.
core_writer_mode="$("${COMPOSE[@]}" exec -T scheduling-postgres wget -qO- \
  --header "X-Internal-Key: ${scheduling_key}" \
  "http://backend:8081/internal/scheduling/assignments/writer-state")"
printf '%s' "$core_writer_mode" | python3 -c 'import sys,json; assert json.load(sys.stdin)["mode"]=="CORE"'
standalone_executor_url="http://scheduling-service:8098/internal/scheduling/assignments/commands/standalone/${stage_key}/execute"
if "${COMPOSE[@]}" exec -T scheduling-postgres wget -qO- \
  --header "X-Internal-Key: ${scheduling_key}" \
  --header "Authorization: Bearer ${cm_token}" \
  --post-data '' "$standalone_executor_url" >/dev/null 2>&1; then
  echo "Standalone Scheduling executor became active without a cutover"; exit 1
fi
echo "PASS: Core remains writer and private standalone executor is disabled by default"

# Phase 8g: despite independently implemented teacher writer and reference
# receiver, the default Core-authoritative microservices stack must never
# register either mutation endpoint. This is checked over the container
# network, not just through the public Gateway.
teacher_execute_url="http://scheduling-service:8098/internal/scheduling/assignments/commands/teacher/${stage_key}/execute"
teacher_preflight_url="http://scheduling-service:8098/internal/scheduling/assignments/commands/teacher/${stage_key}/preflight"
core_teacher_reference_url="http://backend:8081/internal/scheduling/assignments/reference/teaching"
for private_url in "$teacher_execute_url" "$teacher_preflight_url" "$core_teacher_reference_url"; do
  if "${COMPOSE[@]}" exec -T scheduling-postgres wget -qO- \
    --header "X-Internal-Key: ${scheduling_key}" \
    --header "Authorization: Bearer ${cm_token}" \
    --header "Content-Type: application/json" \
    --post-data '{}' "$private_url" >/dev/null 2>&1; then
    echo "Teacher migration-only mutation route active without cutover: $private_url"
    exit 1
  fi
done
teacher_gateway_status="$(curl -sS -o /dev/null -w '%{http_code}' \
  "${BASE}/internal/scheduling/assignments/commands/teacher/${stage_key}/execute")"
test "$teacher_gateway_status" = "404" || {
  echo "Private TEACHING command unexpectedly exposed through public Gateway"
  exit 1
}
teacher_abandon_url="http://scheduling-service:8098/internal/scheduling/assignments/reconciliation/teacher/abandon"
core_revoke_url="http://backend:8081/internal/scheduling/assignments/claims/teacher/revoke"
for private_url in "$teacher_abandon_url" "$core_revoke_url"; do
  if "${COMPOSE[@]}" exec -T scheduling-postgres wget -qO- \
    --header "X-Internal-Key: ${scheduling_key}" \
    --header "Content-Type: application/json" \
    --post-data '{}' "$private_url" >/dev/null 2>&1; then
    echo "Orphan claim recovery endpoint active without operator opt-in"
    exit 1
  fi
done
echo "PASS: private TEACHING preflight, writer, reference and audited recovery OFF by default"

cancel_executor_url="http://scheduling-service:8098/internal/scheduling/assignments/commands/standalone/${stage_key}/cancel"
if "${COMPOSE[@]}" exec -T scheduling-postgres wget -qO- \
  --header "X-Internal-Key: ${scheduling_key}" \
  --header "Authorization: Bearer ${cm_token}" \
  --post-data '' "$cancel_executor_url" >/dev/null 2>&1; then
  echo "Standalone Scheduling cancellation unexpectedly active without a cutover"; exit 1
fi
echo "PASS: private standalone create/cancel mutation routes remain disabled"
edit_executor_url="http://scheduling-service:8098/internal/scheduling/assignments/commands/standalone/${stage_key}/edit"
if "${COMPOSE[@]}" exec -T scheduling-postgres wget -qO- \
  --header "X-Internal-Key: ${scheduling_key}" \
  --header "Authorization: Bearer ${cm_token}" \
  --post-data '' "$edit_executor_url" >/dev/null 2>&1; then
  echo "Standalone Scheduling edit endpoint active without a cutover"; exit 1
fi
recovery_url="http://scheduling-service:8098/internal/scheduling/assignments/reconciliation/no-such-event/verify"
if "${COMPOSE[@]}" exec -T scheduling-postgres wget -qO- \
  --header "X-Internal-Key: ${scheduling_key}" \
  --post-data '' "$recovery_url" >/dev/null 2>&1; then
  echo "Scheduling reconciliation endpoint is active without operator opt-in"; exit 1
fi
core_receipt_url="http://backend:8081/internal/scheduling/assignments/reference/receipt?sourceType=STAFF&scheduleId=no-such-schedule"
if "${COMPOSE[@]}" exec -T scheduling-postgres wget -qO- \
  "$core_receipt_url" >/dev/null 2>&1; then
  echo "Core assignment receipt lookup did not require private service key"; exit 1
fi
if "${COMPOSE[@]}" exec -T scheduling-postgres wget -qO- \
  --header "X-Internal-Key: ${scheduling_key}" \
  "$core_receipt_url" >/dev/null 2>&1; then
  echo "Missing Core assignment receipt was incorrectly treated as successful"; exit 1
fi
echo "PASS: standalone edit and admin recovery routes are opt-in, receipt lookup fails closed"



# A staged command is unfinished migration work: even if source/JSON/shadow
# parity holds, cutover verification must return 409 rather than READY.
if "${COMPOSE[@]}" exec -T scheduling-postgres wget -qO- \
  --header "X-Internal-Key: ${scheduling_key}" "$verification_url" >/dev/null 2>&1; then
  echo "Cutover verifier accepted unresolved staged assignment command"; exit 1
fi
echo "PASS: cutover gate rejects unresolved command inbox state"

# Rejecting a STAGED-only request is an idempotent administrative/actor-safe
# operation: nothing was executed so there is no distributed compensation.
reject_url="http://scheduling-service:8098/internal/scheduling/assignments/commands/ASSIGN_STAFF/${stage_key}/reject"
for i in 1 2; do
  rejected="$("${COMPOSE[@]}" exec -T scheduling-postgres wget -qO- \
    --header "X-Internal-Key: ${scheduling_key}" \
    --header "Authorization: Bearer ${cm_token}" \
    --post-data '' "$reject_url")"
  printf '%s' "$rejected" | python3 -c 'import sys,json; assert json.load(sys.stdin)["state"]=="REJECTED"'
done
recovered="$("${COMPOSE[@]}" exec -T scheduling-postgres wget -qO- \
  --header "X-Internal-Key: ${scheduling_key}" "$verification_url")"
printf '%s' "$recovered" | python3 -c 'import sys,json; assert json.load(sys.stdin)["ready"]'
# The Scheduling PostgreSQL guard (not just a Java service) must prevent
# a forged TEACHING outbox event after an irreversible rejection. This
# also fences SQL paths created by later migration stages.
"${COMPOSE[@]}" exec -T scheduling-postgres psql -U iig_scheduling -d iig_scheduling -v ON_ERROR_STOP=1 <<SQL
DO \$\$
BEGIN
  INSERT INTO scheduling_assignment_command_inbox
    (actor_username,operation,request_key,request_hash,payload,state)
  VALUES('tc001','ASSIGN_TEACHER','${fixture}-teacher-rejected',
         repeat('d',64),'{}'::jsonb,'REJECTED');
  BEGIN
    INSERT INTO scheduling_assignment_domain_outbox
      (event_id,actor_username,operation,request_key,source_type,
       schedule_id,revision,event_kind,payload,payload_hash)
    VALUES ('${fixture}-late-teach','tc001','ASSIGN_TEACHER',
            '${fixture}-teacher-rejected','TEACHING',
            '${fixture}-late-shift',1,'UPSERT','{}'::jsonb,repeat('a',64));
    RAISE EXCEPTION 'A rejected teacher command published a new source event';
  EXCEPTION WHEN SQLSTATE '55000' THEN NULL;
  END;
  DELETE FROM scheduling_assignment_command_inbox
    WHERE actor_username='tc001' AND operation='ASSIGN_TEACHER'
      AND request_key='${fixture}-teacher-rejected';
END
\$\$;
SQL
echo "PASS: PostgreSQL forbids late TEACHING event insertion after command rejection"
echo "PASS: actor-scoped staged rejection is idempotent and restores cutover readiness"

# Phase 8d: outbox tables and the future Core receipt table exist, but
# staging or rejecting a command must never produce an assignment event.
# Core reference writes and the Scheduling->Core relay are both opt-in/off
# in the normal microservices stack.
shadow_events="$("${COMPOSE[@]}" exec -T scheduling-postgres psql -U iig_scheduling -d iig_scheduling -tAc \
  "SELECT count(*) FROM scheduling_assignment_domain_outbox WHERE actor_username='cm001' AND request_key='${stage_key}'" | tr -d '[:space:]')"
test "$shadow_events" = "0" || { echo "Unexecuted command generated an outbound assignment event"; exit 1; }
reference_receipts="$("${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -tAc \
  "SELECT count(*) FROM scheduling_assignment_reference_receipts WHERE actor_username='cm001'" | tr -d '[:space:]')"
test "$reference_receipts" = "0" || { echo "Disabled Core receiver unexpectedly applied assignment events"; exit 1; }
echo "PASS: event outbox and Core reference consumer are idle before independent write cutover"

# Immutable history must be enforced by each owning PostgreSQL database,
# not just by repository Java methods. Both fixtures are transactionally
# rolled back so the normal smoke environment stays unchanged.
"${COMPOSE[@]}" exec -T scheduling-postgres psql -U iig_scheduling -d iig_scheduling -v ON_ERROR_STOP=1 <<SQL
BEGIN;
DO \$\$
BEGIN
  INSERT INTO scheduling_assignment_command_inbox
    (actor_username,operation,request_key,request_hash,payload,state)
  VALUES('cm001','ASSIGN_STAFF','${fixture}-immut',repeat('a',64),
         '{}'::jsonb,'STAGED');
  INSERT INTO scheduling_assignment_domain_outbox
    (event_id,actor_username,operation,request_key,source_type,schedule_id,
     revision,event_kind,payload,payload_hash)
  VALUES('${fixture}-immut-event','cm001','ASSIGN_STAFF','${fixture}-immut',
         'STAFF','${fixture}-immut-shift',1,'UPSERT','{}'::jsonb,repeat('b',64));
  UPDATE scheduling_assignment_command_inbox SET state='PENDING_REFERENCE'
    WHERE actor_username='cm001' AND operation='ASSIGN_STAFF'
      AND request_key='${fixture}-immut';
  BEGIN
    UPDATE scheduling_assignment_domain_outbox
      SET payload='{"unexpected":true}'::jsonb
      WHERE event_id='${fixture}-immut-event';
    RAISE EXCEPTION 'Outbox event payload was overwritten';
  EXCEPTION WHEN SQLSTATE '55000' THEN NULL;
  END;
  BEGIN
    DELETE FROM scheduling_assignment_domain_outbox
      WHERE event_id='${fixture}-immut-event';
    RAISE EXCEPTION 'An already committed outbox event was deleted';
  EXCEPTION WHEN SQLSTATE '55000' THEN NULL;
  END;
  UPDATE scheduling_assignment_domain_outbox
    SET attempt_count=attempt_count+1,last_error='test retry'
    WHERE event_id='${fixture}-immut-event';
END
\$\$;
ROLLBACK;
SQL
"${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 <<SQL
BEGIN;
DO \$\$
BEGIN
  INSERT INTO scheduling_assignment_reference_event_receipts
    (event_id,source_type,schedule_id,revision,payload_hash,actor_username,
     operation,request_key)
  VALUES('${fixture}-core-immut','STAFF','${fixture}-core-shift',1,
         repeat('c',64),'cm001','ASSIGN_STAFF','${fixture}-command');
  BEGIN
    UPDATE scheduling_assignment_reference_event_receipts
      SET payload_hash=repeat('d',64)
      WHERE event_id='${fixture}-core-immut';
    RAISE EXCEPTION 'Core historical receipt digest was rewritten';
  EXCEPTION WHEN SQLSTATE '55000' THEN NULL;
  END;
  BEGIN
    DELETE FROM scheduling_assignment_reference_event_receipts
      WHERE event_id='${fixture}-core-immut';
    RAISE EXCEPTION 'Core historical receipt was removed';
  EXCEPTION WHEN SQLSTATE '55000' THEN NULL;
  END;
END
\$\$;
ROLLBACK;
SQL
echo "PASS: both Scheduling outbox and Core receipt history reject SQL tampering"


# The PostgreSQL state guard is the final safety net: an HTTP/controller
# bug cannot mark an unexecuted command COMMITTED or pretend that a domain
# outbox event exists. Test inside the isolated smoke database and clean up.
"${COMPOSE[@]}" exec -T scheduling-postgres psql -U iig_scheduling -d iig_scheduling -v ON_ERROR_STOP=1 <<SQL
DO \$\$
BEGIN
  INSERT INTO scheduling_assignment_command_inbox
    (actor_username,operation,request_key,request_hash,payload,state)
  VALUES('cm001','ASSIGN_STAFF','${fixture}-state-fence',
         repeat('a',64),'{}'::jsonb,'STAGED');
  BEGIN
    UPDATE scheduling_assignment_command_inbox SET state='COMMITTED'
    WHERE actor_username='cm001' AND operation='ASSIGN_STAFF'
      AND request_key='${fixture}-state-fence';
    RAISE EXCEPTION 'Unexecuted command reached COMMITTED without outbox events';
  EXCEPTION WHEN SQLSTATE '55000' THEN NULL;
  END;
  BEGIN
    UPDATE scheduling_assignment_command_inbox SET state='PENDING_REFERENCE'
    WHERE actor_username='cm001' AND operation='ASSIGN_STAFF'
      AND request_key='${fixture}-state-fence';
    RAISE EXCEPTION 'Command entered reference pending without domain events';
  EXCEPTION WHEN SQLSTATE '55000' THEN NULL;
  END;
  UPDATE scheduling_assignment_command_inbox SET state='REJECTED'
  WHERE actor_username='cm001' AND operation='ASSIGN_STAFF'
    AND request_key='${fixture}-state-fence';
  BEGIN
    UPDATE scheduling_assignment_command_inbox SET state='STAGED'
    WHERE actor_username='cm001' AND operation='ASSIGN_STAFF'
      AND request_key='${fixture}-state-fence';
    RAISE EXCEPTION 'Rejected command was reopened';
  EXCEPTION WHEN SQLSTATE '55000' THEN NULL;
  END;
  DELETE FROM scheduling_assignment_command_inbox
  WHERE actor_username='cm001' AND operation='ASSIGN_STAFF'
    AND request_key='${fixture}-state-fence';
END
\$\$;
SQL
echo "PASS: database prevents false success and illegal command state regressions"





# Versioned Academic session facts are private, too. They are a read
# contract only; a later write cutover needs a real version-checked lease.
session_url="http://backend:8081/internal/scheduling/academic/session?classId=${one}&date=2027-02-11&startTime=09:00&endTime=11:00"
academic_facts="$("${COMPOSE[@]}" exec -T scheduling-postgres wget -qO- \
  --header "X-Internal-Key: ${scheduling_key}" "$session_url")"
printf '%s' "$academic_facts" | python3 -c 'import json,sys; d=json.load(sys.stdin); assert d["sessionStatus"]=="PLANNED" and d["classVersion"]>=1 and d["sessionVersion"]>=1'
echo "PASS: Scheduling can read a versioned Academic planned-session contract over private HTTP"

# Phase 8b-3c: exercise the Academic lease mutation fences entirely inside
# one disposable Core DB transaction, so even an assertion failure rolls the
# QUIESCED state back automatically. The API has separate private-key tests.
"${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 <<SQL
BEGIN;
-- The smoke-created class is DRAFT by design. Prepare an eligible
-- Academic class state only inside this disposable transaction.
UPDATE classes SET status='READY' WHERE class_id='${one}';
DO \$\$
DECLARE
  lesson_id VARCHAR(50);
BEGIN
  SELECT class_session_id INTO lesson_id FROM class_sessions
    WHERE class_id='${one}' AND date='2027-02-11' LIMIT 1;
  IF lesson_id IS NULL THEN
    RAISE EXCEPTION 'Academic lease smoke requires a planned session';
  END IF;
  UPDATE scheduling_assignment_writer_control
     SET writer_mode='QUIESCED',changed_at=NOW() WHERE singleton=TRUE;
  INSERT INTO scheduling_academic_session_leases(
    lease_id,class_id,class_session_id,expected_class_version,
    expected_session_version,command_key,expires_at,expected_writer_generation)
  SELECT '${fixture}-acad','${one}',lesson_id,
         c.scheduling_version,s.scheduling_version,'smoke-academic-command',NOW()+interval '5 minutes',
         (SELECT writer_generation FROM scheduling_assignment_writer_control WHERE singleton=TRUE)
  FROM classes c JOIN class_sessions s ON s.class_id=c.class_id
  WHERE c.class_id='${one}' AND s.class_session_id=lesson_id;
  -- A raw SQL mutation cannot forge the Academic evidence envelope,
  -- extend leases indefinitely, or bypass the exact lesson versions.
  BEGIN
    UPDATE scheduling_academic_session_leases
       SET command_key='forged-command' WHERE lease_id='${fixture}-acad';
    RAISE EXCEPTION 'Academic lease command key was rewritten';
  EXCEPTION WHEN SQLSTATE '55000' THEN NULL;
  END;
  BEGIN
    UPDATE scheduling_academic_session_leases
       SET expected_session_version=expected_session_version+1
     WHERE lease_id='${fixture}-acad';
    RAISE EXCEPTION 'Academic lesson version in a lease was rewritten';
  EXCEPTION WHEN SQLSTATE '55000' THEN NULL;
  END;
  BEGIN
    UPDATE scheduling_academic_session_leases
       SET expires_at=NOW()+interval '1 day'
     WHERE lease_id='${fixture}-acad';
    RAISE EXCEPTION 'Academic lease could be extended without bound';
  EXCEPTION WHEN SQLSTATE '55000' THEN NULL;
  END;
  BEGIN
    INSERT INTO scheduling_academic_session_leases(
      lease_id,class_id,class_session_id,expected_class_version,
      expected_session_version,command_key,expires_at,expected_writer_generation)
    SELECT '${fixture}-expired','${one}',lesson_id,
           c.scheduling_version,s.scheduling_version,
           'expired-command',NOW()-interval '1 second',
           (SELECT writer_generation FROM scheduling_assignment_writer_control
            WHERE singleton=TRUE)
    FROM classes c JOIN class_sessions s ON s.class_id=c.class_id
    WHERE c.class_id='${one}' AND s.class_session_id=lesson_id;
    RAISE EXCEPTION 'Newly inserted Academic lease may already be expired';
  EXCEPTION WHEN SQLSTATE '55000' THEN NULL;
  END;
  BEGIN
    UPDATE class_sessions SET status=status WHERE class_session_id=lesson_id;
    RAISE EXCEPTION 'Active Academic lease failed to block timetable mutation';
  EXCEPTION WHEN SQLSTATE '55000' THEN NULL;
  END;
  BEGIN
    UPDATE scheduling_assignment_writer_control SET writer_mode='CORE'
      WHERE singleton=TRUE;
    RAISE EXCEPTION 'Core write mode resumed while Academic lease active';
  EXCEPTION WHEN SQLSTATE '55000' THEN NULL;
  END;
  DELETE FROM scheduling_academic_session_leases
   WHERE lease_id='${fixture}-acad';
  UPDATE scheduling_assignment_writer_control
    SET writer_mode='CORE',changed_at=NOW() WHERE singleton=TRUE;
  BEGIN
    INSERT INTO scheduling_academic_session_leases(
      lease_id,class_id,class_session_id,expected_class_version,
      expected_session_version,command_key,expires_at,expected_writer_generation)
    SELECT '${fixture}-invalid-mode','${one}',lesson_id,
           c.scheduling_version,s.scheduling_version,
           'writer-active-command',NOW()+interval '5 minutes',
           (SELECT writer_generation FROM scheduling_assignment_writer_control
            WHERE singleton=TRUE)
    FROM classes c JOIN class_sessions s ON s.class_id=c.class_id
    WHERE c.class_id='${one}' AND s.class_session_id=lesson_id;
    RAISE EXCEPTION 'Academic lease was issued while Core writer was active';
  EXCEPTION WHEN SQLSTATE '55000' THEN NULL;
  END;
END
\$\$;
ROLLBACK;
SQL
echo "PASS: Academic SQL blocks lease tampering, stale versions, active-Core writes and unbounded renewal"





