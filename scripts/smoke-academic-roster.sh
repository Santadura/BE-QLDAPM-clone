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
ACADEMIC_ROSTER_SOURCE_RELAY_ENABLED=true compose up -d --no-deps --force-recreate academic-service
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

# Retry all recent historical events after JVM restart: no duplicates and
# no resurrection from the older UPSERT versions.
ACADEMIC_ROSTER_SOURCE_RELAY_ENABLED=true compose restart academic-service
sleep 20
require_equal "student tombstone" "$(academic_sql "SELECT event_kind FROM academic_students_shadow WHERE student_id='$key'")" "DELETE"
require_equal "membership tombstone" "$(academic_sql "SELECT event_kind FROM academic_class_memberships_shadow WHERE class_student_id='$member'")" "DELETE"
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
