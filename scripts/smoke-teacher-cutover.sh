#!/usr/bin/env bash
# ISOLATED CI ONLY. Runs after all default-off Gateway/FE smoke tests.
# The Docker project and PostgreSQL volumes are destroyed by the workflow.
# Never use this script against real customer data.
set -euo pipefail
COMPOSE=(docker compose -f docker-compose.microservices.yml)
BASE="http://localhost:8080"
run="${GITHUB_RUN_ID:-local}"
class_id="tc-e2e-class-${run}"
lesson_id="tc-e2e-lesson-${run}"
code="TC-E2E-${run}"
avail_id="tc-e2e-avail-${run}"
key="tc-e2e-command-${run}"
date="2027-04-29"
start="13:00"
end="15:00"
service_key="${SCHEDULING_INTERNAL_API_KEY:-local-dev-scheduling-key-change-before-deployment}"

core_sql() {
  "${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -tAc "$1" | tr -d '\r\n'
}
scheduling_sql() {
  "${COMPOSE[@]}" exec -T scheduling-postgres psql -U iig_scheduling -d iig_scheduling -tAc "$1" | tr -d '\r\n'
}
private_get() {
  "${COMPOSE[@]}" exec -T scheduling-postgres wget -qO- \
    --header "X-Internal-Key: ${service_key}" "$1"
}
private_post() {
  "${COMPOSE[@]}" exec -T scheduling-postgres wget -qO- \
    --header "X-Internal-Key: ${service_key}" \
    --header "Authorization: Bearer ${tc_token}" \
    --header "Content-Type: application/json" \
    --post-data "$2" "$1"
}

# Explicitly isolate this exercise from any production-like dataset.
test "${CI:-false}" = "true" || {
  echo "Independent teacher writer smoke requires CI disposable databases"
  exit 1
}
teacher_id="$(core_sql "SELECT e.employee_id FROM employees e JOIN users u ON e.username=u.username WHERE e.username='teacher001' AND e.status='ACTIVE' AND u.role_id='TEACHER' AND u.status='ACTIVE';")"
teacher_name="$(core_sql "SELECT e.full_name FROM employees e WHERE e.employee_id='${teacher_id}';")"
tc_id="$(core_sql "SELECT e.employee_id FROM employees e JOIN users u ON e.username=u.username WHERE e.username='tc001' AND e.status='ACTIVE' AND u.role_id='TC' AND u.status='ACTIVE';")"
test -n "$teacher_id" && test -n "$teacher_name" && test -n "$tc_id"
case "$teacher_name" in *"'"*) echo "Fixture teacher name contains unsupported SQL quote"; exit 1;; esac

# Seed a FUTURE Academic lesson using the real Core PostgreSQL schema.
# Test-only fixture is inserted while Core still owns all class writes.
"${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 <<SQL
BEGIN;
INSERT INTO classes (class_id,course_id,class_code,name,start_date,end_date,status)
VALUES ('${class_id}','course-ielts','${code}','Teacher cutover CI',
        '2027-04-27','2027-05-03','DRAFT');
INSERT INTO class_sessions(class_session_id,class_id,date,start_time,end_time,status)
VALUES ('${lesson_id}','${class_id}','${date}','${start}','${end}','PLANNED');
UPDATE classes SET status='READY' WHERE class_id='${class_id}';
COMMIT;
SQL

# A teacher's approved availability is owned by Scheduling PostgreSQL.
# The fixture is a single validated reservation source, not a legacy copy.
"${COMPOSE[@]}" exec -T scheduling-postgres \
  psql -U iig_scheduling -d iig_scheduling -v ON_ERROR_STOP=1 <<SQL
INSERT INTO scheduling_availabilities
 (availability_id,employee_id,full_name,role_id,available_date,day_of_week,
  start_time,end_time,availability_type,status,version,deleted)
VALUES ('${avail_id}','${teacher_id}','${teacher_name}','TEACHER','${date}','thu',
        '${start}','${end}','WEEKLY','APPROVED',1,FALSE);
SQL
class_version="$(core_sql "SELECT scheduling_version FROM classes WHERE class_id='${class_id}';")"
session_version="$(core_sql "SELECT scheduling_version FROM class_sessions WHERE class_session_id='${lesson_id}';")"
test -n "$class_version" && test -n "$session_version"

# Recreate only the two participating JVM containers with explicit private
# TEACHING flags. Base CI already proved every flag defaults to OFF.
SCHEDULING_TEACHER_REFERENCE_WRITES_ENABLED=true \
  "${COMPOSE[@]}" up -d --no-deps --force-recreate backend
for attempt in $(seq 1 60); do
  if private_get "http://backend:8081/internal/scheduling/assignments/writer-state" \
       >/dev/null 2>&1; then break; fi
  if [ "$attempt" = "60" ]; then echo "Core did not restart for teacher CI"; exit 1; fi
  sleep 2
done
SCHEDULING_TEACHER_EXECUTOR_ENABLED=true \
SCHEDULING_RECOVERY_ENABLED=true \
SCHEDULING_TEACHER_PREFLIGHT_ENABLED=true \
SCHEDULING_ACADEMIC_LEASE_COORDINATOR_ENABLED=true \
SCHEDULING_REFERENCE_RELAY_ENABLED=true \
  "${COMPOSE[@]}" up -d --no-deps --force-recreate scheduling-service
for attempt in $(seq 1 60); do
  if private_get "http://scheduling-service:8098/actuator/health" \
       >/dev/null 2>&1; then break; fi
  if [ "$attempt" = "60" ]; then echo "Scheduling did not restart for teacher CI"; exit 1; fi
  sleep 2
done

# Wait until Core's committed assignments are fully synchronized. No
# independent writer is allowed to start on a partial migration snapshot.
ready=false
for attempt in $(seq 1 45); do
  result="$(private_get "http://scheduling-service:8098/internal/scheduling/assignments/verify" 2>/dev/null || true)"
  if printf '%s' "$result" | python3 -c \
    'import json,sys; d=json.load(sys.stdin); assert d["ready"] is True' \
    2>/dev/null; then
    ready=true; break
  fi
  sleep 2
done
test "$ready" = true || {
  echo "Core/Scheduling assignment parity was not ready for isolated TEACHING cutover"
  echo "$result"
  exit 1
}

tc_login="$(curl -fsS -X POST "${BASE}/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"tc001","password":"123456"}')"
tc_token="$(printf '%s' "$tc_login" | python3 -c \
  'import json,sys; print(json.load(sys.stdin)["token"])')"

stage_body="$(printf \
 '{"operation":"ASSIGN_TEACHER","requestKey":"%s","payload":{"teacherId":"%s","classId":"%s","sessionId":"%s","classVersion":%s,"sessionVersion":%s,"date":"%s","startTime":"%s","endTime":"%s"}}' \
 "$key" "$teacher_id" "$class_id" "$lesson_id" "$class_version" "$session_version" "$date" "$start" "$end")"
stage_url="http://scheduling-service:8098/internal/scheduling/assignments/commands/stage"
staged="$(private_post "$stage_url" "$stage_body")"
printf '%s' "$staged" | python3 -c \
  'import json,sys; assert json.load(sys.stdin)["state"]=="STAGED"'

# The Core singleton is the write-fencing authority; the teacher claim
# becomes valid only after this explicit maintenance transition.
"${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 \
  -c "UPDATE scheduling_assignment_writer_control SET writer_mode='QUIESCED',changed_at=NOW() WHERE singleton=TRUE;"

execute_url="http://scheduling-service:8098/internal/scheduling/assignments/commands/teacher/${key}/execute"
executed="$(private_post "$execute_url" '')"
schedule_id="$(printf '%s' "$executed" | python3 -c \
  'import json,sys; d=json.load(sys.stdin); assert d["state"]=="PENDING_REFERENCE" and d["revision"]==1; print(d["scheduleId"])')"
test -n "$schedule_id"
echo "PASS: TC created a Scheduling-owned TEACHING source and immutable pending Core event"

# The relay may timeout after Core COMMIT. Poll BOTH stores for durable
# exact success, never accept the Scheduled HTTP 202 as business success.
complete=false
for attempt in $(seq 1 75); do
  core_ok="$(core_sql "SELECT count(*) FROM teaching_schedules t JOIN scheduling_assignment_reference_receipts r ON r.source_type='TEACHING' AND r.schedule_id=t.teaching_schedule_id JOIN scheduling_assignment_writer_claims c ON c.actor_username='tc001' AND c.operation='ASSIGN_TEACHER' AND c.request_key='${key}' AND c.status='CONFIRMED' WHERE t.teaching_schedule_id='${schedule_id}' AND t.teacher_id='${teacher_id}' AND t.class_id='${class_id}' AND t.status='ASSIGNED' AND r.last_revision=1;")"
  source_ok="$(scheduling_sql "SELECT count(*) FROM scheduling_assignment_command_inbox i JOIN scheduling_assignment_domain_outbox o ON o.actor_username=i.actor_username AND o.operation=i.operation AND o.request_key=i.request_key WHERE i.actor_username='tc001' AND i.operation='ASSIGN_TEACHER' AND i.request_key='${key}' AND i.state='COMMITTED' AND o.source_type='TEACHING' AND o.schedule_id='${schedule_id}' AND o.delivered_at IS NOT NULL;")"
  if [ "$core_ok" = "1" ] && [ "$source_ok" = "1" ]; then complete=true; break; fi
  sleep 2
done
test "$complete" = true || {
  echo "Core and Scheduling did not both commit the TEACHING reference"
  echo "Core=$core_ok Scheduling=$source_ok"
  exit 1
}

# Retry must return exactly the same schedule with no duplicate Core row,
# outbox fact or TC claim; only one confirmed reference is legitimate.
replayed="$(private_post "$execute_url" '')"
REPLAYED="$replayed" SCHEDULE_ID="$schedule_id" python3 -c \
 'import os,json; d=json.loads(os.environ["REPLAYED"]); assert d["scheduleId"]==os.environ["SCHEDULE_ID"] and d["state"]=="COMMITTED"'
test "$(core_sql "SELECT count(*) FROM teaching_schedules WHERE teaching_schedule_id='${schedule_id}';")" = "1"
test "$(scheduling_sql "SELECT count(*) FROM scheduling_assignment_domain_outbox WHERE source_type='TEACHING' AND schedule_id='${schedule_id}';")" = "1"
test "$(core_sql "SELECT count(*) FROM scheduling_assignment_reference_event_receipts WHERE source_type='TEACHING' AND schedule_id='${schedule_id}';")" = "1"

# Querying the existing public route still uses its Scheduling read model;
# private command and Core reference endpoints remain absent from Gateway.
visible="$(curl -fsS "${BASE}/api/teaching-schedules?weekStart=2027-04-26" \
  -H "Authorization: Bearer ${tc_token}")"
VISIBLE="$visible" SCHEDULE_ID="$schedule_id" python3 -c \
  'import os,json; rows=json.loads(os.environ["VISIBLE"]); assert any(x.get("id")==os.environ["SCHEDULE_ID"] for x in rows)'
test "$(curl -sS -o /dev/null -w '%{http_code}' \
  "${BASE}/internal/scheduling/assignments/commands/teacher/${key}/execute")" = "404"
echo "PASS: isolated Scheduling TEACHING write, Core idempotent reference, TC claim, read route and replay"

# The operational boundary must distinguish a lost Core response from
# an orphan TC claim caused by a Scheduling rollback. This diagnostic
# is ADMIN-only, is not routed through Gateway, and performs NO writes.
admin_login="$(curl -fsS -X POST "${BASE}/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"123456"}')"
admin_token="$(printf '%s' "$admin_login" | python3 -c \
  'import json,sys; print(json.load(sys.stdin)["token"])')"
teacher_diagnose_url="http://scheduling-service:8098/internal/scheduling/assignments/reconciliation/teacher/diagnose?actorUsername=tc001&requestKey=${key}"
teacher_diagnosis="$("${COMPOSE[@]}" exec -T scheduling-postgres wget -qO- \
  --header "X-Internal-Key: ${service_key}" \
  --header "Authorization: Bearer ${admin_token}" \
  "$teacher_diagnose_url")"
printf '%s' "$teacher_diagnosis" | python3 -c \
  'import json,sys; d=json.load(sys.stdin); assert d["state"]=="CONSISTENT_COMMITTED" and d["coreTeacherRowExists"] is True'
if "${COMPOSE[@]}" exec -T scheduling-postgres wget -qO- \
  --header "X-Internal-Key: ${service_key}" \
  --header "Authorization: Bearer ${tc_token}" \
  "$teacher_diagnose_url" >/dev/null 2>&1; then
  echo "Non-ADMIN TC unexpectedly accessed distributed claim diagnosis"
  exit 1
fi

# Fault injection: Core accepted a writer claim but the Scheduling source
# has not committed anything. The claim MUST remain for human review;
# diagnostic is never an automatic claim cleanup or writer handback.
orphan_key="${key}-orphan"
orphan_body="${stage_body/${key}/${orphan_key}}"
staged_orphan="$(private_post "$stage_url" "$orphan_body")"
printf '%s' "$staged_orphan" | python3 -c \
 'import json,sys; assert json.load(sys.stdin)["state"]=="STAGED"'
orphan_claim_body="$(printf \
 '{"actorUsername":"tc001","managerEmployeeId":"%s","operation":"ASSIGN_TEACHER","requestKey":"%s"}' \
 "$tc_id" "$orphan_key")"
claim_result="$("${COMPOSE[@]}" exec -T scheduling-postgres wget -qO- \
  --header "X-Internal-Key: ${service_key}" \
  --header "Content-Type: application/json" \
  --post-data "$orphan_claim_body" \
  "http://backend:8081/internal/scheduling/assignments/claims")"
printf '%s' "$claim_result" | python3 -c \
 'import json,sys; assert json.load(sys.stdin)["status"]=="PENDING"'
orphan_diagnose_url="http://scheduling-service:8098/internal/scheduling/assignments/reconciliation/teacher/diagnose?actorUsername=tc001&requestKey=${orphan_key}"
orphan_diagnosis="$("${COMPOSE[@]}" exec -T scheduling-postgres wget -qO- \
  --header "X-Internal-Key: ${service_key}" \
  --header "Authorization: Bearer ${admin_token}" \
  "$orphan_diagnose_url")"
printf '%s' "$orphan_diagnosis" | python3 -c \
 'import json,sys; d=json.load(sys.stdin); assert d["state"]=="ORPHAN_CORE_CLAIM" and d["localEventId"] is None and d["coreClaimState"]=="PENDING"'
test "$(core_sql "SELECT count(*) FROM scheduling_assignment_writer_claims WHERE actor_username='tc001' AND operation='ASSIGN_TEACHER' AND request_key='${orphan_key}' AND status='PENDING';")" = "1"
test "$(scheduling_sql "SELECT count(*) FROM scheduling_assignment_domain_outbox WHERE actor_username='tc001' AND operation='ASSIGN_TEACHER' AND request_key='${orphan_key}';")" = "0"
echo "PASS: admin-only, non-mutating distributed claim diagnosis distinguishes committed teaching from orphan writer claims"
