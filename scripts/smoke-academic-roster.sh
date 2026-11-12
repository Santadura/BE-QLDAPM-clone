#!/usr/bin/env bash
# Disposable-only proof of Core -> independent Academic student/membership
# historical replay. No public writer migration or Core handback is performed.
set -euo pipefail
if [ "${GITHUB_ACTIONS:-}" != "true" ] || [ "${IIG_ROSTER_SMOKE:-}" != "true" ]; then
  echo "Academic roster smoke requires disposable GitHub CI" >&2
  exit 2
fi
[[ "${GITHUB_RUN_ID:-}" =~ ^[0-9]+$ ]] || exit 2
compose() { docker compose -f docker-compose.microservices.yml "$@"; }
core_sql() {
  compose exec -T postgres psql -U postgres -d qlapm_db -tAc "$1" | tr -d '[:space:]'
}
academic_sql() {
  compose exec -T academic-postgres psql -U iig_academic -d iig_academic -tAc "$1" | tr -d '[:space:]'
}
require_equal() {
  local label="$1" actual="$2" expected="$3"
  if [ "$actual" != "$expected" ]; then
    echo "$label expected=$expected actual=$actual" >&2
    exit 1
  fi
}
# Explicitly enable ONLY a passive private source consumer, on disposable DBs.
ACADEMIC_ROSTER_CANDIDATE_ENABLED=true ACADEMIC_ROSTER_SOURCE_RELAY_ENABLED=true ACADEMIC_STUDENT_LIST_READ_ENABLED=true ACADEMIC_STUDENT_TARGETS_READ_ENABLED=true ACADEMIC_STUDENT_RESULTS_READ_ENABLED=true compose up -d --no-deps --force-recreate academic-service
ready=false
for attempt in $(seq 1 80); do
  if compose exec -T scheduling-postgres wget -qO- \
      "http://academic-service:8099/actuator/health" 2>/dev/null | grep -q '"UP"'; then
    ready=true;break
  fi
  sleep 2
done
test "$ready" = true || { echo "Academic roster importer failed to start" >&2; exit 1; }

# Initial student/membership backfill must include every source event, not
# just the number of live rows.
complete=false
for attempt in $(seq 1 90); do
  core_events="$(core_sql "SELECT COUNT(*) FROM academic_roster_source_outbox")"
  local_events="$(academic_sql "SELECT COUNT(*) FROM academic_roster_source_receipts")"
  core_last="$(core_sql "SELECT COALESCE(MAX(event_id),0) FROM academic_roster_source_outbox")"
  local_last="$(academic_sql "SELECT last_event_id FROM academic_roster_history_cursor WHERE singleton=TRUE")"
  students="$(core_sql "SELECT COUNT(*) FROM students")"
  local_students="$(academic_sql "SELECT COUNT(*) FROM academic_students_shadow WHERE event_kind='UPSERT'")"
  memberships="$(core_sql "SELECT COUNT(*) FROM class_students")"
  local_memberships="$(academic_sql "SELECT COUNT(*) FROM academic_class_memberships_shadow WHERE event_kind='UPSERT'")"
  if [ "$core_events" = "$local_events" ] && [ "$core_last" = "$local_last" ] &&
      [ "$students" = "$local_students" ] && [ "$memberships" = "$local_memberships" ]; then
    complete=true;break
  fi
  sleep 2
done
test "$complete" = true || {
  echo "Academic initial roster parity failed: source=$core_events/$core_last local=$local_events/$local_last students=$students/$local_students members=$memberships/$local_memberships" >&2
  exit 1
}

# Full proof requires an authenticated live Admin and a private service key.
# Neither the source history nor independent historical receipts are public.
admin_login="$(curl -fsS -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"123456"}')"
admin_token="$(printf '%s' "$admin_login" | python3 -c \
  'import json,sys; print(json.load(sys.stdin)["token"])')"
teacher_login="$(curl -fsS -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"teacher001","password":"123456"}')"
teacher_token="$(printf '%s' "$teacher_login" | python3 -c \
  'import json,sys; print(json.load(sys.stdin)["token"])')"
private_key="${ACADEMIC_INTERNAL_API_KEY:-local-dev-academic-key-change-before-deployment}"
readiness_url="http://academic-service:8099/internal/academic/student-roster/readiness"
readiness="$(compose exec -T scheduling-postgres wget -qO- \
  --header "X-Internal-Key: $private_key" \
  --header "Authorization: Bearer $admin_token" "$readiness_url")"
READINESS="$readiness" python3 -c \
  'import json,os; d=json.loads(os.environ["READINESS"]); assert d["sourceEvents"]>=0 and d["students"]>=0,d'
invalid_key="$(compose exec -T scheduling-postgres wget -S -O- \
  --header "X-Internal-Key: incorrect" \
  --header "Authorization: Bearer $admin_token" "$readiness_url" 2>&1 || true)"
printf '%s' "$invalid_key" | grep -q '401' || {
  echo "Academic full roster proof accepted an invalid private key" >&2;exit 1;
}
denied_teacher="$(compose exec -T scheduling-postgres wget -S -O- \
  --header "X-Internal-Key: $private_key" \
  --header "Authorization: Bearer $teacher_token" "$readiness_url" 2>&1 || true)"
printf '%s' "$denied_teacher" | grep -q '403' || {
  echo "Teacher received private cross-database student history proof" >&2;exit 1;
}
test "$(curl -sS -o /dev/null -w '%{http_code}' \
  http://localhost:8080/internal/academic/student-roster/readiness)" = "404"

key="ci-roster-${GITHUB_RUN_ID}"
member="ci-roster-member-${GITHUB_RUN_ID}"
class_id="$(core_sql "SELECT class_id FROM classes ORDER BY class_id LIMIT 1")"
test -n "$class_id" || { echo "CI has no seeded class" >&2; exit 1; }
compose exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 >/dev/null <<SQL
BEGIN;
INSERT INTO students(student_id,student_code,full_name,status)
  VALUES('$key','$key','Academic CI student','Active');
INSERT INTO class_students(class_student_id,class_id,student_id,status)
  VALUES('$member','$class_id','$key','ACTIVE');
COMMIT;
SQL

# Both rows must be copied from committed Core events, not fabricated locally.
copied=false
for attempt in $(seq 1 80); do
  s="$(academic_sql "SELECT COUNT(*) FROM academic_students_shadow WHERE student_id='$key' AND event_kind='UPSERT' AND full_name='Academic CI student'")"
  m="$(academic_sql "SELECT COUNT(*) FROM academic_class_memberships_shadow WHERE class_student_id='$member' AND event_kind='UPSERT' AND class_id='$class_id' AND student_id='$key'")"
  if [ "$s" = "1" ] && [ "$m" = "1" ]; then copied=true;break; fi
  sleep 2
done
test "$copied" = true || { echo "Academic missed committed Core student/membership events" >&2; exit 1; }

# Admin FE list must be served from the independent typed Academic projection.
# Teacher must retain the Core's original assigned-class visibility.
admin_students="$(curl -fsS \
  -H "Authorization: Bearer $admin_token" http://localhost:8080/api/students)"
ADMIN_STUDENTS="$admin_students" STUDENT_KEY="$key" python3 -c \
  'import json,os; rows=json.loads(os.environ["ADMIN_STUDENTS"]); assert isinstance(rows,list) and any(x.get("studentId")==os.environ["STUDENT_KEY"] for x in rows),rows'
teacher_students="$(curl -fsS \
  -H "Authorization: Bearer $teacher_token" http://localhost:8080/api/students)"
teacher_core="$(compose exec -T scheduling-postgres wget -qO- \
  --header "Authorization: Bearer $teacher_token" "http://backend:8081/api/students")"
TEACHER_STUDENTS="$teacher_students" TEACHER_CORE="$teacher_core" python3 -c \
  'import json,os; a=json.loads(os.environ["TEACHER_STUDENTS"]); b=json.loads(os.environ["TEACHER_CORE"]); assert isinstance(a,list) and a==b,(a,b)'
bad_auth="$(curl -sS -o /dev/null -w '%{http_code}' \
  http://localhost:8080/api/students)"
test "$bad_auth" = "401" || { echo "Student list exposed without live JWT: $bad_auth" >&2;exit 1; }

# Count parity can remain green while the typed student payload has drifted.
# The Admin API must refuse the corrupted row, never serve stale PII.
compose exec -T academic-postgres psql -U iig_academic -d iig_academic -v ON_ERROR_STOP=1 \
  -c "UPDATE academic_students_shadow SET full_name='TAMPERED' WHERE student_id='$key'" >/dev/null
drift_code="$(curl -sS -o /dev/null -w '%{http_code}' \
  -H "Authorization: Bearer $admin_token" http://localhost:8080/api/students)"
test "$drift_code" = "409" || {
  echo "Academic served divergent student fields: $drift_code" >&2;exit 1;
}
compose exec -T academic-postgres psql -U iig_academic -d iig_academic -v ON_ERROR_STOP=1 \
  -c "UPDATE academic_students_shadow SET full_name='Academic CI student' WHERE student_id='$key'" >/dev/null
admin_recovered="$(curl -fsS \
  -H "Authorization: Bearer $admin_token" http://localhost:8080/api/students)"
ADMIN_STUDENTS="$admin_recovered" STUDENT_KEY="$key" python3 -c \
  'import json,os; rows=json.loads(os.environ["ADMIN_STUDENTS"]); assert any(x.get("studentId")==os.environ["STUDENT_KEY"] for x in rows),rows'
echo "PASS: private full-history parity, Admin Academic reads, Teacher Core ACL, and fail-closed drift"

# Real PostgreSQL domain writes for both newly extracted kinds. Core's
# primary keys and FKs are honored: a seeded course, class and employee
# are referenced, and only Core's triggers generate history events.
target_id="ci-target-${GITHUB_RUN_ID}"
result_id="ci-result-${GITHUB_RUN_ID}"
course_id="$(core_sql "SELECT course_id FROM classes WHERE class_id='$class_id'")"
evaluator_id="$(core_sql "SELECT employee_id FROM employees ORDER BY employee_id LIMIT 1")"
# Core requires an evaluated result to refer to exactly one activity:
# assignment_id XOR exam_id. Reuse the seeded assignment for this class.
assignment_id="$(core_sql "SELECT assignment_id FROM assignments WHERE class_id='$class_id' ORDER BY assignment_id LIMIT 1")"
test -n "$course_id" && test -n "$evaluator_id" && test -n "$assignment_id" || {
  echo "Missing seeded course, employee or class assignment for student target/result fixture" >&2;exit 1;
}
compose exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 >/dev/null <<SQL
BEGIN;
INSERT INTO student_targets(student_target_id,student_id,course_id,target_type,target_value)
  VALUES('$target_id','$key','$course_id','READING',6.50);
INSERT INTO student_results(student_result_id,student_id,class_id,assignment_id,score,evaluated_by,evaluated_at)
  VALUES('$result_id','$key','$class_id','$assignment_id',8.25,'$evaluator_id',NOW());
COMMIT;
SQL
metrics_copied=false
for attempt in $(seq 1 60); do
  t="$(academic_sql "SELECT COUNT(*) FROM academic_student_targets_shadow
    WHERE student_target_id='$target_id' AND event_kind='UPSERT' AND target_value=6.50")"
  r="$(academic_sql "SELECT COUNT(*) FROM academic_student_results_shadow
    WHERE student_result_id='$result_id' AND event_kind='UPSERT' AND score=8.25")"
  if [ "$t" = "1" ] && [ "$r" = "1" ];then metrics_copied=true;break;fi
  sleep 2
done
test "$metrics_copied" = true || {
  echo "Academic failed to import real StudentTarget/StudentResult events" >&2;exit 1;
}

# Exact FE StudentDetail contract now includes Academic-owned target/result
# arrays, while Core continues authorizing the entire student and serving
# class descriptions + audit. Check all projected fields, not merely counts.
admin_detail="$(curl -fsS -H "Authorization: Bearer $admin_token" \
  "http://localhost:8080/api/students/$key")"
core_detail="$(compose exec -T scheduling-postgres wget -qO- \
  --header "Authorization: Bearer $admin_token" \
  "http://backend:8081/api/students/$key")"
ADMIN_DETAIL="$admin_detail" CORE_DETAIL="$core_detail" \
  TARGET_KEY="$target_id" RESULT_KEY="$result_id" \
  ASSIGNMENT_KEY="$assignment_id" python3 -c '
import json,os
a=json.loads(os.environ["ADMIN_DETAIL"]); c=json.loads(os.environ["CORE_DETAIL"])
assert a["studentId"]==c["studentId"]
assert a["classes"]==c["classes"] and a["auditLogs"]==c["auditLogs"]
t=[x for x in a["targets"] if x["id"]==os.environ["TARGET_KEY"]]
r=[x for x in a["results"] if x["id"]==os.environ["RESULT_KEY"]]
assert len(t)==1 and len(r)==1,(t,r)
assert t[0]["targetType"]=="READING" and float(t[0]["targetValue"])==6.5
assert float(r[0]["score"])==8.25 and r[0]["assignmentId"]==os.environ["ASSIGNMENT_KEY"]
assert r[0]["examId"] is None and r[0]["evaluatedBy"] is not None
assert r[0]["evaluatedAt"]
print("PASS: independent Academic Target and Result arrays retain Core StudentDetail FE contract")
'
# A Teacher receives Core-scoped class/course data, never unfiltered local
# targets/results. A forbidden Core read stays forbidden at the Gateway.
teacher_core_status="$(compose exec -T scheduling-postgres wget -S -O- \
  --header "Authorization: Bearer $teacher_token" \
  "http://backend:8081/api/students/$key" 2>&1 || true)"
teacher_gateway_code="$(curl -sS -o /dev/null -w '%{http_code}' \
  -H "Authorization: Bearer $teacher_token" \
  "http://localhost:8080/api/students/$key")"
if printf '%s' "$teacher_core_status" | grep -q '403'; then
  test "$teacher_gateway_code" = "403" || {
    echo "Academic bypassed Core Teacher class scope: $teacher_gateway_code" >&2;exit 1;
  }
elif printf '%s' "$teacher_core_status" | grep -q '200'; then
  test "$teacher_gateway_code" = "200" || {
    echo "Gateway changed authorized Teacher student access" >&2;exit 1;
  }
  teacher_core="$(compose exec -T scheduling-postgres wget -qO- \
    --header "Authorization: Bearer $teacher_token" \
    "http://backend:8081/api/students/$key")"
  teacher_gateway="$(curl -fsS -H "Authorization: Bearer $teacher_token" \
    "http://localhost:8080/api/students/$key")"
  TEACHER_CORE="$teacher_core" TEACHER_GATEWAY="$teacher_gateway" python3 -c \
    'import json,os; assert json.loads(os.environ["TEACHER_CORE"])==json.loads(os.environ["TEACHER_GATEWAY"])'
else
  echo "Unrecognized Core Teacher student-detail HTTP result" >&2
  printf '%s\\n' "$teacher_core_status" >&2
  exit 1
fi
test "$(curl -sS -o /dev/null -w '%{http_code}' \
  "http://localhost:8080/api/students/$key")" = "401" || {
    echo "Academic student detail exposed without a JWT" >&2;exit 1;
}

# Mutations must move the source revision and change the typed latest row.
compose exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 >/dev/null <<SQL
BEGIN;
UPDATE student_targets SET target_value=7.50 WHERE student_target_id='$target_id';
UPDATE student_results SET score=9.00 WHERE student_result_id='$result_id';
COMMIT;
SQL
metrics_updated=false
for attempt in $(seq 1 60); do
  t="$(academic_sql "SELECT COUNT(*) FROM academic_student_targets_shadow
    WHERE student_target_id='$target_id' AND event_kind='UPSERT' AND target_value=7.50")"
  r="$(academic_sql "SELECT COUNT(*) FROM academic_student_results_shadow
    WHERE student_result_id='$result_id' AND event_kind='UPSERT' AND score=9.00")"
  if [ "$t" = "1" ] && [ "$r" = "1" ];then metrics_updated=true;break;fi
  sleep 2
done
test "$metrics_updated" = true || {
  echo "Academic missed StudentTarget/StudentResult update events" >&2;exit 1;
}
updated_detail="$(curl -fsS -H "Authorization: Bearer $admin_token" \
  "http://localhost:8080/api/students/$key")"
UPDATED_DETAIL="$updated_detail" TARGET_KEY="$target_id" RESULT_KEY="$result_id" python3 -c '
import json,os
d=json.loads(os.environ["UPDATED_DETAIL"])
t=next(x for x in d["targets"] if x["id"]==os.environ["TARGET_KEY"])
r=next(x for x in d["results"] if x["id"]==os.environ["RESULT_KEY"])
assert float(t["targetValue"])==7.5 and float(r["score"])==9.0,(t,r)
'

# A count-only proof misses altered target values. The private full proof
# must reject this tampering even though the number of rows is unchanged.
compose exec -T academic-postgres psql -U iig_academic -d iig_academic -v ON_ERROR_STOP=1 \
  -c "UPDATE academic_student_targets_shadow SET target_value=1.00
      WHERE student_target_id='$target_id'" >/dev/null
target_drift="$(compose exec -T scheduling-postgres wget -S -O- \
  --header "X-Internal-Key: $private_key" \
  --header "Authorization: Bearer $admin_token" "$readiness_url" 2>&1 || true)"
printf '%s' "$target_drift" | grep -q '409' || {
  echo "Academic accepted a corrupted StudentTarget shadow" >&2;exit 1;
}
target_detail_code="$(curl -sS -o /dev/null -w '%{http_code}' \
  -H "Authorization: Bearer $admin_token" \
  "http://localhost:8080/api/students/$key")"
test "$target_detail_code" = "409" || {
  echo "StudentDetail leaked divergent Academic target: $target_detail_code" >&2;exit 1;
}
compose exec -T academic-postgres psql -U iig_academic -d iig_academic -v ON_ERROR_STOP=1 \
  -c "UPDATE academic_student_targets_shadow SET target_value=7.50
      WHERE student_target_id='$target_id'" >/dev/null
# Corrupt the separately owned Result projection; Admin detail must also
# fail closed rather than silently falling back to the Core score.
compose exec -T academic-postgres psql -U iig_academic -d iig_academic -v ON_ERROR_STOP=1 \
  -c "UPDATE academic_student_results_shadow SET score=1.00
      WHERE student_result_id='$result_id'" >/dev/null
result_detail_code="$(curl -sS -o /dev/null -w '%{http_code}' \
  -H "Authorization: Bearer $admin_token" \
  "http://localhost:8080/api/students/$key")"
test "$result_detail_code" = "409" || {
  echo "StudentDetail leaked divergent Academic result: $result_detail_code" >&2;exit 1;
}
compose exec -T academic-postgres psql -U iig_academic -d iig_academic -v ON_ERROR_STOP=1 \
  -c "UPDATE academic_student_results_shadow SET score=9.00
      WHERE student_result_id='$result_id'" >/dev/null

# DELETE tombstones for both kinds must be retained before the existing
# membership/student fixture is removed (referential integrity).
compose exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 >/dev/null <<SQL
BEGIN;
DELETE FROM student_results WHERE student_result_id='$result_id';
DELETE FROM student_targets WHERE student_target_id='$target_id';
COMMIT;
SQL
metrics_deleted=false
for attempt in $(seq 1 60); do
  t="$(academic_sql "SELECT COUNT(*) FROM academic_student_targets_shadow
    WHERE student_target_id='$target_id' AND event_kind='DELETE'")"
  r="$(academic_sql "SELECT COUNT(*) FROM academic_student_results_shadow
    WHERE student_result_id='$result_id' AND event_kind='DELETE'")"
  if [ "$t" = "1" ] && [ "$r" = "1" ];then metrics_deleted=true;break;fi
  sleep 2
done
test "$metrics_deleted" = true || {
  echo "Academic failed to preserve StudentTarget/StudentResult tombstones" >&2;exit 1;
}
deleted_detail="$(curl -fsS -H "Authorization: Bearer $admin_token" \
  "http://localhost:8080/api/students/$key")"
DELETED_DETAIL="$deleted_detail" TARGET_KEY="$target_id" RESULT_KEY="$result_id" python3 -c '
import json,os
d=json.loads(os.environ["DELETED_DETAIL"])
assert all(x["id"]!=os.environ["TARGET_KEY"] for x in d["targets"])
assert all(x["id"]!=os.environ["RESULT_KEY"] for x in d["results"])
'
echo "PASS: independent StudentTarget/StudentResult create/update/delete and typed parity"

# Exercise status transition, membership tombstone, then student tombstone.
# A deleted row must NOT be resurrected by replay or container restart.
compose exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 >/dev/null <<SQL
BEGIN;
UPDATE class_students SET status='INACTIVE' WHERE class_student_id='$member';
COMMIT;
BEGIN;
DELETE FROM class_students WHERE class_student_id='$member';
DELETE FROM students WHERE student_id='$key';
COMMIT;
SQL

deleted=false
for attempt in $(seq 1 80); do
  s="$(academic_sql "SELECT COUNT(*) FROM academic_students_shadow WHERE student_id='$key' AND event_kind='DELETE'")"
  m="$(academic_sql "SELECT COUNT(*) FROM academic_class_memberships_shadow WHERE class_student_id='$member' AND event_kind='DELETE'")"
  if [ "$s" = "1" ] && [ "$m" = "1" ]; then deleted=true;break; fi
  sleep 2
done
test "$deleted" = true || { echo "Academic roster tombstones were not imported" >&2; exit 1; }
deleted_students="$(curl -fsS \
  -H "Authorization: Bearer $admin_token" http://localhost:8080/api/students)"
ADMIN_STUDENTS="$deleted_students" STUDENT_KEY="$key" python3 -c \
  'import json,os; rows=json.loads(os.environ["ADMIN_STUDENTS"]); assert all(x.get("studentId")!=os.environ["STUDENT_KEY"] for x in rows),rows'

# Retry all recent historical events after JVM restart: no duplicates and
# no resurrection from the older UPSERT versions.
ACADEMIC_ROSTER_SOURCE_RELAY_ENABLED=true compose restart academic-service
sleep 20
require_equal "student tombstone" "$(academic_sql "SELECT event_kind FROM academic_students_shadow WHERE student_id='$key'")" "DELETE"
require_equal "membership tombstone" "$(academic_sql "SELECT event_kind FROM academic_class_memberships_shadow WHERE class_student_id='$member'")" "DELETE"
require_equal "target tombstone" "$(academic_sql "SELECT event_kind FROM academic_student_targets_shadow WHERE student_target_id='$target_id'")" "DELETE"
require_equal "result tombstone" "$(academic_sql "SELECT event_kind FROM academic_student_results_shadow WHERE student_result_id='$result_id'")" "DELETE"
require_equal "source-event count" "$(academic_sql "SELECT COUNT(*) FROM academic_roster_source_receipts")" \
  "$(core_sql "SELECT COUNT(*) FROM academic_roster_source_outbox")"

# Immutable source and independent receipts both reject direct tampering.
compose exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 >/dev/null <<'SQL'
DO $$
BEGIN
  BEGIN
    DELETE FROM academic_roster_source_outbox
      WHERE event_id=(SELECT MIN(event_id) FROM academic_roster_source_outbox);
    RAISE EXCEPTION 'Core student roster history was mutable';
  EXCEPTION WHEN SQLSTATE '55000' THEN NULL;
  END;
END;
$$;
SQL
compose exec -T academic-postgres psql -U iig_academic -d iig_academic -v ON_ERROR_STOP=1 >/dev/null <<'SQL'
DO $$
BEGIN
  BEGIN
    UPDATE academic_roster_source_receipts SET payload_hash=repeat('e',64)
      WHERE event_id=(SELECT MIN(event_id) FROM academic_roster_source_receipts);
    RAISE EXCEPTION 'Academic student roster history was mutable';
  EXCEPTION WHEN SQLSTATE '55000' THEN NULL;
  END;
END;
$$;
SQL

test "$(curl -sS -o /dev/null -w '%{http_code}' \
  "http://localhost:8080/internal/academic/student-roster/events/history")" = "404"
echo "PASS: Core student/membership updates and deletes replay to independent Academic DB; latest tombstones and immutable receipts survive restart"
