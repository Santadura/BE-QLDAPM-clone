#!/usr/bin/env bash
# Disposable-only cross-database Session rehearsal closure + Core handback.
# Proves real private HTTP, SQL immutability, idempotent audit, a positive
# FULL HISTORY readiness check after closure, and safe writer rollback to Core.
# NO authoritative Academic/Scheduling/Attendance writer is ever enabled.
set -euo pipefail
if [[ "${GITHUB_ACTIONS:-}" != true ||
      "${IIG_SESSION_DISPOSITION_SMOKE:-}" != true ||
      ! "${GITHUB_RUN_ID:-}" =~ ^[0-9]+$ ]]; then
  echo "Refusing session cutover/rollback smoke outside disposable GitHub CI" >&2
  exit 2
fi
COMPOSE=(docker compose -f docker-compose.microservices.yml)
sql_core() {
  "${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db \
    -v ON_ERROR_STOP=1 -tAc "$1" | tr -d '[:space:]'
}
sql_academic() {
  "${COMPOSE[@]}" exec -T academic-postgres psql -U iig_academic \
    -d iig_academic -v ON_ERROR_STOP=1 -tAc "$1" | tr -d '[:space:]'
}
fail() { echo "Academic Session rollback smoke FAILED: $*" >&2; exit 1; }
internal_key="${ACADEMIC_INTERNAL_API_KEY:-local-dev-academic-key-change-before-deployment}"
admin="${IIG_ADMIN_TOKEN:?Live Admin JWT required}"
cs="${IIG_CS_TOKEN:?Live CS JWT required}"
keys=("${IIG_SESSION_CANDIDATE_KEY:?}" "${IIG_SESSION_RACE_KEY:?}" "${IIG_SESSION_RECOVERY_KEY:?}")
for key in "${keys[@]}"; do
  [[ "$key" =~ ^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$ ]] || fail "Malformed CI candidate key"
done
url="http://academic-service:8099/internal/academic/cutover/sessions/rehearsals/discard"
readiness="http://academic-service:8099/internal/academic/cutover/sessions/readiness"
frozen=false

restore() {
  local rc=$?
  trap - EXIT
  set +e
  if [ "$frozen" = true ]; then
    "${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db \
      -v ON_ERROR_STOP=1 >/dev/null <<SQL
BEGIN;
UPDATE academic_session_writer_control SET writer_mode='CORE'
  WHERE singleton=TRUE AND writer_mode='QUIESCED';
UPDATE teacher_attendance_writer_control SET writer_mode='CORE'
  WHERE singleton=TRUE AND writer_mode='QUIESCED';
UPDATE scheduling_assignment_writer_control SET writer_mode='CORE'
  WHERE singleton=TRUE AND writer_mode='QUIESCED';
COMMIT;
SQL
  fi
  ACADEMIC_SESSION_DISPOSITION_ENABLED=false "${COMPOSE[@]}" up -d \
    --no-deps --force-recreate academic-service >/dev/null ||
    echo "WARN: disposable private disposition flag restore failed" >&2
  exit "$rc"
}
trap restore EXIT

modes="$(sql_core "SELECT
  (SELECT writer_mode FROM academic_session_writer_control WHERE singleton)
  ||':'||(SELECT writer_mode FROM teacher_attendance_writer_control WHERE singleton)
  ||':'||(SELECT writer_mode FROM scheduling_assignment_writer_control WHERE singleton)")"
test "$modes" = "CORE:CORE:CORE" ||
  fail "Expected Core-owned session, attendance and scheduling before disposal"
test "$(sql_core "SELECT count(*) FROM academic_candidate_claims WHERE state='OPEN'")" = 0 ||
  fail "Core has unresolved Session candidate claims"
before_core="$(sql_core "SELECT md5(COALESCE(string_agg(to_jsonb(t)::text,'|' ORDER BY t.class_session_id),''))
  FROM class_sessions t")"
before_academic="$(sql_academic "SELECT md5(COALESCE(string_agg(to_jsonb(t)::text,'|' ORDER BY t.class_session_id),''))
  FROM academic_class_sessions_shadow t")"

# No public route, and private disposition must be OFF without opt-in.
test "$(curl -sS -o /dev/null -w '%{http_code}' \
  http://localhost:8080/internal/academic/cutover/sessions/rehearsals/discard)" = 404 ||
  fail "Internal Session disposition exposed through Gateway"
disabled="$("${COMPOSE[@]}" exec -T scheduling-postgres wget -S -O- \
  --header "X-Internal-Key: $internal_key" \
  --header "Authorization: Bearer $admin" --header "Content-Type: application/json" \
  --post-data '{}' "$url" 2>&1 || true)"
printf '%s' "$disabled" | grep -q 404 ||
  fail "Session disposition endpoint not OFF by default"

ACADEMIC_SESSION_DISPOSITION_ENABLED=true "${COMPOSE[@]}" up -d \
  --no-deps --force-recreate academic-service >/dev/null
ready=false
for _ in $(seq 1 80); do
  if "${COMPOSE[@]}" exec -T scheduling-postgres wget -qO- \
    http://academic-service:8099/actuator/health 2>/dev/null | grep -q '"UP"'; then
    ready=true
    break
  fi
  sleep 2
done
test "$ready" = true || fail "Private Academic disposition service failed to start"

status() {
  "${COMPOSE[@]}" exec -T scheduling-postgres wget -S -O- \
    --header "X-Internal-Key: $1" --header "Authorization: Bearer $2" \
    --header "Content-Type: application/json" --post-data "$3" \
    "$url" 2>&1 || true
}
post() {
  "${COMPOSE[@]}" exec -T scheduling-postgres wget -qO- \
    --header "X-Internal-Key: $internal_key" \
    --header "Authorization: Bearer $admin" \
    --header "Content-Type: application/json" --post-data "$1" "$url"
}
first_command=""
for key in "${keys[@]}"; do
  cmd="$(sql_academic "SELECT command_id FROM academic_session_candidate_commands
    WHERE operator_username='admin' AND request_key='$key'")"
  [[ "$cmd" =~ ^[0-9a-f-]{36}$ ]] || fail "Missing Session command for $key"
  if [ -z "$first_command" ]; then
    first_command="$cmd"
    first_key="$key"
  fi
  body="$(KEY="$key" COMMAND="$cmd" python3 -c '
import json,os
print(json.dumps({"requestKey":os.environ["KEY"],"commandId":os.environ["COMMAND"]}))
')"
  printf '%s' "$(status invalid "$admin" "$body")" | grep -q 401 ||
    fail "Invalid service key accepted for $key"
  printf '%s' "$(status "$internal_key" "$cs" "$body")" | grep -q 403 ||
    fail "Non-Admin CS allowed to dispose $key"

  original="$(post "$body")" || fail "Cannot close exact immutable candidate $key"
  replay="$(post "$body")" || fail "Idempotent disposal retry failed for $key"
  RESULT="$original" REPLAY="$replay" KEY="$key" CMD="$cmd" python3 -c '
import json,os
a=json.loads(os.environ["RESULT"]);b=json.loads(os.environ["REPLAY"])
assert a==b and a["requestKey"]==os.environ["KEY"] and a["commandId"]==os.environ["CMD"],(a,b)
assert a["disposition"]=="REHEARSAL_DISCARDED",a
' || fail "Replayed Session disposition identity diverged"
  test "$(sql_academic "SELECT count(*) FROM academic_session_candidate_dispositions d
    JOIN academic_session_candidate_outbox o ON o.command_id=d.command_id
    JOIN academic_candidate_handoff_receipts h ON h.command_id=d.command_id
    WHERE d.command_id='$cmd' AND d.actor_username='admin'
      AND d.request_key='$key' AND d.disposed_by='admin'
      AND d.outbox_hash=o.payload_hash AND d.outbox_hash=h.core_outbox_hash")" = 1 ||
    fail "Disposition has no exact Core handback and original Academic outbox"
  test "$(sql_core "SELECT count(*) FROM academic_candidate_claim_audit
    WHERE actor_username='admin' AND request_key='$key' AND event_kind='RELEASED'")" = 1 ||
    fail "Disposition changed the Core claim release journal"
done

# Wrong command under a valid key cannot be used to close another candidate.
wrong="$(KEY="$first_key" python3 -c '
import json,os
print(json.dumps({"requestKey":os.environ["KEY"],
    "commandId":"00000000-0000-4000-8000-000000000199"}))
')"
printf '%s' "$(status "$internal_key" "$admin" "$wrong")" | grep -q 409 ||
  fail "Wrong Session command UUID bypassed exact Core/Academic handback"

test "$(sql_academic "SELECT count(*) FROM academic_session_candidate_commands")" = 3 ||
  fail "Unexpected Session command count before strict cutover verification"
test "$(sql_academic "SELECT count(*) FROM academic_session_candidate_dispositions")" = 3 ||
  fail "All three real candidate rehearsals were not closed"
test "$(sql_academic "SELECT count(*) FROM academic_session_candidate_commands c
  LEFT JOIN academic_session_candidate_dispositions d ON d.command_id=c.command_id
  WHERE d.command_id IS NULL")" = 0 ||
  fail "Undisposed Session rehearsal remains"

# SQL-level no-update/no-delete/no-truncate audit assurance on a committed
# disposition, not a transaction that rolls back the original evidence.
"${COMPOSE[@]}" exec -T academic-postgres psql -U iig_academic -d iig_academic \
  -v ON_ERROR_STOP=1 >/dev/null <<SQL
DO \$\$
BEGIN
  BEGIN
    UPDATE academic_session_candidate_dispositions
      SET disposed_by='forged' WHERE command_id='$first_command'::uuid;
    RAISE EXCEPTION 'Session disposition audit was editable';
  EXCEPTION WHEN SQLSTATE '55000' THEN NULL;
  END;
  BEGIN
    DELETE FROM academic_session_candidate_dispositions
      WHERE command_id='$first_command'::uuid;
    RAISE EXCEPTION 'Session disposition audit was erasable';
  EXCEPTION WHEN SQLSTATE '55000' THEN NULL;
  END;
  BEGIN
    TRUNCATE academic_session_candidate_dispositions;
    RAISE EXCEPTION 'Session disposition audit was truncatable';
  EXCEPTION WHEN SQLSTATE '55000' THEN NULL;
  END;
END \$\$;
SQL

# Full Core/Academic parity must now succeed with exact published history
# after every REHEARSAL_ONLY proposal has an immutable discard. No live
# ownership moves: freeze briefly and restore all three Core writer fences.
"${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db \
  -v ON_ERROR_STOP=1 >/dev/null <<SQL
BEGIN;
UPDATE scheduling_assignment_writer_control SET writer_mode='QUIESCED'
  WHERE singleton=TRUE AND writer_mode='CORE';
UPDATE teacher_attendance_writer_control SET writer_mode='QUIESCED'
  WHERE singleton=TRUE AND writer_mode='CORE';
UPDATE academic_session_writer_control SET writer_mode='QUIESCED'
  WHERE singleton=TRUE AND writer_mode='CORE';
COMMIT;
SQL
frozen=true

proof="$("${COMPOSE[@]}" exec -T scheduling-postgres wget -qO- \
  --header "X-Internal-Key: $internal_key" "$readiness")" ||
  fail "Strict Session readiness failed after immutable candidate closure"
PROOF="$proof" python3 -c '
import json,os
p=json.loads(os.environ["PROOF"])
assert p["sourceEvents"]>0 and p["lastEventId"]>0,p
assert min(p["academicGeneration"],p["attendanceGeneration"],
           p["schedulingGeneration"])>1,p
' || fail "Strict positive readiness lacks complete source and writer generation evidence"

# The operation is forbidden while the writer is frozen, even when already
# discarded. No actor can use a rehearsal endpoint to take writer ownership.
frozen_body="$(KEY="$first_key" COMMAND="$first_command" python3 -c '
import json,os
print(json.dumps({"requestKey":os.environ["KEY"],"commandId":os.environ["COMMAND"]}))
')"
printf '%s' "$(status "$internal_key" "$admin" "$frozen_body")" | grep -q 409 ||
  fail "Session disposition ignored a QUIESCED Core writer fence"

# Writer handback is the rollback proof: all three Core writers become CORE
# again in inverse order and no source/receipt/outbox data has changed.
"${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db \
  -v ON_ERROR_STOP=1 >/dev/null <<SQL
BEGIN;
UPDATE academic_session_writer_control SET writer_mode='CORE'
  WHERE singleton=TRUE AND writer_mode='QUIESCED';
UPDATE teacher_attendance_writer_control SET writer_mode='CORE'
  WHERE singleton=TRUE AND writer_mode='QUIESCED';
UPDATE scheduling_assignment_writer_control SET writer_mode='CORE'
  WHERE singleton=TRUE AND writer_mode='QUIESCED';
COMMIT;
SQL
frozen=false
test "$(sql_core "SELECT
  (SELECT writer_mode FROM academic_session_writer_control WHERE singleton)
  ||':'||(SELECT writer_mode FROM teacher_attendance_writer_control WHERE singleton)
  ||':'||(SELECT writer_mode FROM scheduling_assignment_writer_control WHERE singleton)")" = "CORE:CORE:CORE" ||
  fail "Core writer handback failed"
test "$(sql_core "SELECT md5(COALESCE(string_agg(to_jsonb(t)::text,'|' ORDER BY t.class_session_id),''))
  FROM class_sessions t")" = "$before_core" ||
  fail "Session rehearsal or disposition mutated authoritative Core source"
test "$(sql_academic "SELECT md5(COALESCE(string_agg(to_jsonb(t)::text,'|' ORDER BY t.class_session_id),''))
  FROM academic_class_sessions_shadow t")" = "$before_academic" ||
  fail "Session rehearsal disposition mutated Academic source projection"
echo "PASS: Session Core crash/restart claim handback, immutable audited closure, fully frozen cutover readiness and safe Core rollback with zero source mutation"
