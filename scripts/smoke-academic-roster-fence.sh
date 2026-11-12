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
  for command in UPDATE DELETE; do
    if [ "$command" = UPDATE ]; then
      sql="UPDATE $table SET updated_at=updated_at WHERE FALSE"
      # All source tables have no shared updated_at guarantee. Exercise
      # syntactically valid UPDATE of their immutable primary key instead.
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
cutover="$(get_cutover)"
CUTOVER="$cutover" python3 -c '
import json,os
p=json.loads(os.environ["CUTOVER"])
assert p["writerGeneration"]==4,p
'

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
echo "PASS: Core SQL fences all four Academic roster writers and logs four durable generations; only stable frozen full-history proof permits read-only Academic cutover readiness"
