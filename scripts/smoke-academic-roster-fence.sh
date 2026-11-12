#!/usr/bin/env bash
# Disposable PostgreSQL ONLY: prove Core student/roster writer exclusion
# and Academic's read-only frozen cutover prerequisite (NOT ownership).
set -euo pipefail
if [ "${GITHUB_ACTIONS:-}" != "true" ] || [ "${IIG_ROSTER_FENCE_SMOKE:-}" != "true" ]; then
  echo "Refusing Academic roster writer-fence smoke outside disposable GitHub CI" >&2
  exit 2
fi
[[ "${GITHUB_RUN_ID:-}" =~ ^[0-9]+$ ]] || exit 2

compose() { docker compose -f docker-compose.microservices.yml "$@"; }
core_sql() {
  compose exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 -tAc "$1" | tr -d '[:space:]'
}
scalar() {
  compose exec -T academic-postgres psql -U iig_academic -d iig_academic -v ON_ERROR_STOP=1 -tAc "$1" | tr -d '[:space:]'
}
fail() { echo "Academic roster fence: $*" >&2; exit 1; }

# Always restore the Core roster writer before subsequent teacher/claim
# regression (the terminal orphan probe has its OWN deliberate frozen state).
restore_writer() {
  local rc=$?
  if [ "${session_frozen:-false}" = true ]; then
    compose exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 \
      -c "BEGIN;
        UPDATE academic_session_writer_control SET writer_mode='CORE'
          WHERE singleton=TRUE AND writer_mode='QUIESCED';
        UPDATE teacher_attendance_writer_control SET writer_mode='CORE'
          WHERE singleton=TRUE AND writer_mode='QUIESCED';
        UPDATE scheduling_assignment_writer_control SET writer_mode='CORE'
          WHERE singleton=TRUE AND writer_mode='QUIESCED';
        COMMIT;" >/dev/null ||
      echo "WARN: disposable cross-domain writer handback failed" >&2
  fi
  if [ "${did_freeze:-false}" = true ]; then
    core_sql "UPDATE academic_roster_writer_control
      SET writer_mode='CORE' WHERE singleton=TRUE AND writer_mode='QUIESCED'" \
      >/dev/null || echo "WARN: disposable Core roster writer handback failed" >&2
  fi
  exit "$rc"
}
trap restore_writer EXIT

test "$(core_sql "SELECT writer_mode FROM academic_roster_writer_control WHERE singleton=TRUE")" = "CORE" ||
  fail "Core student/roster writer did not start in CORE mode"
start_gen="$(core_sql "SELECT generation FROM academic_roster_writer_control WHERE singleton=TRUE")"
test "$start_gen" = "1" || fail "Expected first disposable Academic roster generation=1"

key="${ACADEMIC_INTERNAL_API_KEY:-local-dev-academic-key-change-before-deployment}"
admin_token="$(curl -fsS -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"123456"}' | \
  python3 -c 'import sys,json; print(json.load(sys.stdin)["token"])')"
teacher_token="$(curl -fsS -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"teacher001","password":"123456"}' | \
  python3 -c 'import sys,json; print(json.load(sys.stdin)["token"])')"

fence_url="http://backend:8081/internal/academic/student-roster/events/fence"
cutover_url="http://academic-service:8099/internal/academic/student-roster/cutover/readiness"
check_http() {
  local expected="$1" url="$2" auth="$3" service_key="$4"
  local output
  output="$(compose exec -T scheduling-postgres wget -S -O- \
    --header "X-Internal-Key: $service_key" \
    --header "Authorization: Bearer $auth" "$url" 2>&1 || true)"
  printf '%s' "$output" | grep -q "$expected" ||
    fail "HTTP $expected was not returned at private endpoint: $output"
}
get_fence() {
  compose exec -T scheduling-postgres wget -qO- \
    --header "X-Internal-Key: $key" "$fence_url"
}
get_cutover() {
  compose exec -T scheduling-postgres wget -qO- \
    --header "X-Internal-Key: $key" \
    --header "Authorization: Bearer $admin_token" "$cutover_url"
}
CHECK="$(get_fence)" python3 -c '
import json,os
f=json.loads(os.environ["CHECK"])
assert f["mode"]=="CORE" and f["generation"]==1,f
'
check_http 401 "$fence_url" "$admin_token" "incorrect-key"
check_http 401 "$cutover_url" "$admin_token" "incorrect-key"
check_http 403 "$cutover_url" "$teacher_token" "$key"
check_http 409 "$cutover_url" "$admin_token" "$key"
test "$(curl -sS -o /dev/null -w '%{http_code}' \
  http://localhost:8080/internal/academic/student-roster/cutover/readiness)" = "404" ||
  fail "Private cutover readiness leaked through Gateway"

# The earlier roster smoke already committed every source event and its
# corresponding independent Academic receipt. This is a clean snapshot.
baseline_core="$(core_sql "SELECT COUNT(*) FROM academic_roster_source_outbox")"
baseline_academic="$(scalar "SELECT COUNT(*) FROM academic_roster_source_receipts")"
test "$baseline_core" = "$baseline_academic" ||
  fail "Core and Academic roster history differ BEFORE freeze"

# Freeze Core in one PostgreSQL transaction. The SQL statement trigger's
# FOR SHARE row lock prevents a racing Core writer from committing afterwards.
core_sql "UPDATE academic_roster_writer_control SET writer_mode='QUIESCED'
  WHERE singleton=TRUE AND writer_mode='CORE'" >/dev/null
did_freeze=true
frozen_gen="$(core_sql "SELECT generation FROM academic_roster_writer_control WHERE singleton=TRUE")"
test "$frozen_gen" = "2" || fail "Core freeze did not advance generation to 2"
test "$(core_sql "SELECT COUNT(*) FROM academic_roster_writer_audit
  WHERE generation=2 AND from_mode='CORE' AND to_mode='QUIESCED'")" = "1" ||
  fail "Missing durable Core roster freeze audit"

CHECK="$(get_fence)" python3 -c '
import json,os
f=json.loads(os.environ["CHECK"])
assert f["mode"]=="QUIESCED" and f["generation"]==2,f
'
# All four tables must block ALL THREE DML statement kinds, including
# UPDATE/DELETE touching zero rows. Neither FK nor outbox triggers may be
# relied upon as the only ownership fence.
for table in students class_students student_targets student_results; do
  for command in INSERT UPDATE DELETE; do
    if [ "$command" = INSERT ]; then
      # BEFORE STATEMENT executes before NOT NULL/FK constraints.
      # If the fence fails to intercept this, the exact error differs,
      # so the strict message assertion below catches it.
      sql="INSERT INTO $table DEFAULT VALUES"
    elif [ "$command" = UPDATE ]; then
      # Syntactically valid UPDATE of the existing primary key; even
      # an update matching no rows MUST be rejected under QUIESCED.
      case "$table" in
        students) sql="UPDATE students SET student_id=student_id WHERE FALSE";;
        class_students) sql="UPDATE class_students SET class_student_id=class_student_id WHERE FALSE";;
        student_targets) sql="UPDATE student_targets SET student_target_id=student_target_id WHERE FALSE";;
        student_results) sql="UPDATE student_results SET student_result_id=student_result_id WHERE FALSE";;
      esac
    else
      sql="DELETE FROM $table WHERE FALSE"
    fi
    if compose exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 \
        -c "$sql" > /tmp/iig-roster-blocked.log 2>&1; then
      fail "Core $command was allowed on $table while QUIESCED"
    fi
    grep -q "Core Academic roster writer is QUIESCED" /tmp/iig-roster-blocked.log ||
      fail "Unexpected $command failure on $table: $(cat /tmp/iig-roster-blocked.log)"
  done
done
test "$(core_sql "SELECT COUNT(*) FROM academic_roster_source_outbox")" = "$baseline_core" ||
  fail "Core published a roster event while QUIESCED"

# Every source receipt must be identical to the independent Academic
# projection, and the cutover proof must include this frozen generation.
cutover="$(get_cutover)"
CUTOVER="$cutover" python3 -c '
import json,os
p=json.loads(os.environ["CUTOVER"])
assert p["writerGeneration"]==2 and p["sourceEvents"]>=0
assert p["lastEventId"]>=0 and p["distinctEntities"]>=0,p
'

# Cross-service full-cutover preflight is READ ONLY and must not pass while
# any Scheduling, Attendance or Academic session writer remains CORE.
# The three extra writers are frozen in one Core SQL transaction and then
# restored BEFORE handing the roster writer back (and before Teacher smoke).
full_url="http://academic-service:8099/internal/academic/cutover/full-readiness"
check_http 409 "$full_url" "$admin_token" "$key"
check_http 401 "$full_url" "$admin_token" "incorrect-key"
check_http 403 "$full_url" "$teacher_token" "$key"
test "$(curl -sS -o /dev/null -w '%{http_code}' \
  http://localhost:8080/internal/academic/cutover/full-readiness)" = "404" ||
  fail "Cross-domain internal proof exposed through public Gateway"

initial_modes="$(core_sql "SELECT a.writer_mode || ':' || t.writer_mode || ':' || s.writer_mode
  FROM academic_session_writer_control a
  CROSS JOIN teacher_attendance_writer_control t
  CROSS JOIN scheduling_assignment_writer_control s
  WHERE a.singleton=TRUE AND t.singleton=TRUE AND s.singleton=TRUE")"
test "$initial_modes" = "CORE:CORE:CORE" || {
  echo "Cross-domain cutover probe needs three Core-owned session writers, got $initial_modes" >&2
  exit 1
}
compose exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 >/dev/null <<SQL
BEGIN;
UPDATE scheduling_assignment_writer_control
  SET writer_mode='QUIESCED' WHERE singleton=TRUE AND writer_mode='CORE';
UPDATE teacher_attendance_writer_control
  SET writer_mode='QUIESCED' WHERE singleton=TRUE AND writer_mode='CORE';
UPDATE academic_session_writer_control
  SET writer_mode='QUIESCED' WHERE singleton=TRUE AND writer_mode='CORE';
COMMIT;
SQL
session_frozen=true
full_proof="$(compose exec -T scheduling-postgres wget -qO- \
  --header "X-Internal-Key: $key" \
  --header "Authorization: Bearer $admin_token" "$full_url")" ||
  fail "Full cross-domain Academic cutover proof did not pass after four freezes"
FULL_PROOF="$full_proof" ROSTER_GENERATION="$frozen_gen" python3 -c '
import os,json
p=json.loads(os.environ["FULL_PROOF"])
assert p["rosterGeneration"]==int(os.environ["ROSTER_GENERATION"])
assert all(p[k]>=2 for k in ["sessionGeneration","attendanceGeneration","schedulingGeneration"])
assert p["rosterEvents"]>=0 and p["sessionEvents"]>=0
assert p["rosterLastEventId"]>=0 and p["sessionLastEventId"]>=0
assert p["rosterClaimAuditEvents"]>=4
print("PASS: four stable writer generations and two complete Academic histories")
'
# Both candidate mutations are deliberately REHEARSAL_ONLY. Even after
# their Core claim is RELEASED, neither was applied as a domain write, so
# both combined and roster-only cutover must refuse them.
bash scripts/smoke-academic-roster-candidates.sh
check_http 409 "$cutover_url" "$admin_token" "$key"
check_http 409 "$full_url" "$admin_token" "$key"
# Must not be possible to consider cross-domain cutover ready once ANY
# one of three related writers has been handed back to Core.
compose exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 >/dev/null <<SQL
BEGIN;
UPDATE academic_session_writer_control
  SET writer_mode='CORE' WHERE singleton=TRUE AND writer_mode='QUIESCED';
UPDATE teacher_attendance_writer_control
  SET writer_mode='CORE' WHERE singleton=TRUE AND writer_mode='QUIESCED';
UPDATE scheduling_assignment_writer_control
  SET writer_mode='CORE' WHERE singleton=TRUE AND writer_mode='QUIESCED';
COMMIT;
SQL
session_frozen=false
check_http 409 "$full_url" "$admin_token" "$key"
echo "PASS: cross-domain cutover is read-only, Admin-only and fails closed after writer handback"

# Hand back, verify admission and return to QUIESCED. A naive proof looking
# at writerMode alone cannot detect this ABA; only generation 4 is valid.
core_sql "UPDATE academic_roster_writer_control SET writer_mode='CORE'
  WHERE singleton=TRUE AND writer_mode='QUIESCED'" >/dev/null
did_freeze=false
test "$(core_sql "SELECT generation FROM academic_roster_writer_control WHERE singleton=TRUE")" = "3" ||
  fail "Core writer handback did not advance generation"
check_http 409 "$cutover_url" "$admin_token" "$key"
core_sql "UPDATE academic_roster_writer_control SET writer_mode='QUIESCED'
  WHERE singleton=TRUE AND writer_mode='CORE'" >/dev/null
did_freeze=true
test "$(core_sql "SELECT generation FROM academic_roster_writer_control WHERE singleton=TRUE")" = "4" ||
  fail "Second freeze did not advance generation"
# Re-freezing after Core handback does NOT make staged proposals disappear.
# Explicit audited REHEARSAL_DISCARDED acknowledgement is required before
# a later full cutover may be considered.
check_http 409 "$cutover_url" "$admin_token" "$key"

# PostgreSQL—not the Java controller—manages the fence generation.
if compose exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 \
    -c "UPDATE academic_roster_writer_control SET generation=1 WHERE singleton=TRUE" \
    >/tmp/iig-roster-generation.log 2>&1; then
  fail "Core SQL allowed a forged roster generation"
fi
grep -q "Academic roster generation is database-managed" /tmp/iig-roster-generation.log ||
  fail "Incorrect response to forged Core roster writer generation"

if compose exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 \
    -c "DELETE FROM academic_roster_writer_audit WHERE generation=2" \
    >/tmp/iig-roster-audit.log 2>&1; then
  fail "Core SQL allowed deletion of an immutable roster handback audit"
fi
grep -q "Academic roster writer audit is immutable" /tmp/iig-roster-audit.log ||
  fail "Incorrect response to Core roster audit tampering"

core_sql "UPDATE academic_roster_writer_control SET writer_mode='CORE'
  WHERE singleton=TRUE AND writer_mode='QUIESCED'" >/dev/null
did_freeze=false
test "$(core_sql "SELECT generation FROM academic_roster_writer_control WHERE singleton=TRUE")" = "5" ||
  fail "Final Core handback generation must be 5"
test "$(core_sql "SELECT COUNT(*) FROM academic_roster_writer_audit")" = "4" ||
  fail "Expected four immutable Core roster writer transitions"
test "$(core_sql "SELECT COUNT(*) FROM academic_roster_source_outbox")" = "$baseline_core" ||
  fail "Core roster source history unexpectedly changed during read-only rehearsal"
echo "PASS: frozen four-writer full history preflight and Core handback; non-authoritative Target/Result rehearsals block all later cutover until explicit audited disposition"
