#!/usr/bin/env bash
set -euo pipefail
# The test uses only a disposable PostgreSQL transaction. No public writer
# cutover, and no fixture is allowed to survive ROLLBACK.
if [[ "${GITHUB_ACTIONS:-}" != true ||
      "${IIG_NATIVE_SQL_SMOKE:-}" != true ||
      ! "${GITHUB_RUN_ID:-}" =~ ^[0-9]+$ ]]; then
  echo "Refusing native SQL smoke outside disposable GitHub CI" >&2
  exit 2
fi
id="ci-native-${GITHUB_RUN_ID}"
compose=(docker compose -f docker-compose.microservices.yml)
"${compose[@]}" exec -T academic-postgres \
  psql -U iig_academic -d iig_academic -v ON_ERROR_STOP=1 <<SQL
BEGIN;
INSERT INTO academic_native_session_activations
  (class_id,writer_generation,roster_generation,source_last_event_id,
   class_status,start_date,end_date,activated_by)
VALUES ('${id}',2,2,0,'READY','2027-03-01','2027-03-31','ci');
INSERT INTO academic_native_session_rows
  (class_session_id,class_id,session_date,start_time,end_time,status,
   source_imported,version)
VALUES ('${id}-a','${id}','2027-03-10','09:00','11:00','PLANNED',FALSE,1);
DO \$\$
BEGIN
  BEGIN
    INSERT INTO academic_native_session_rows
      (class_session_id,class_id,session_date,start_time,end_time,status,
       source_imported,version)
    VALUES ('${id}-collision','${id}','2027-03-10',
      '10:00','12:00','PLANNED',FALSE,1);
    RAISE EXCEPTION 'Overlapping native Academic lesson admitted';
  EXCEPTION WHEN exclusion_violation THEN NULL;
  END;
END;
\$\$;
INSERT INTO academic_native_session_rows
  (class_session_id,class_id,session_date,start_time,end_time,status,
   source_imported,version)
VALUES ('${id}-b','${id}','2027-03-10','11:00','12:00','PLANNED',FALSE,1);
UPDATE academic_native_session_rows SET status='CANCELLED',
  version=2,updated_at=NOW() WHERE class_session_id='${id}-a';
DO \$\$
BEGIN
  BEGIN
    UPDATE academic_native_session_rows SET status='PLANNED',version=3
      WHERE class_session_id='${id}-a';
    RAISE EXCEPTION 'Cancelled native lesson reactivated';
  EXCEPTION WHEN SQLSTATE '55000' THEN NULL;
  END;
  BEGIN
    DELETE FROM academic_native_session_rows
      WHERE class_session_id='${id}-b';
    RAISE EXCEPTION 'Native lesson history deleted';
  EXCEPTION WHEN SQLSTATE '55000' THEN NULL;
  END;
  BEGIN
    UPDATE academic_native_session_activations
      SET writer_generation=3 WHERE class_id='${id}';
    RAISE EXCEPTION 'Native writer activation tampered';
  EXCEPTION WHEN SQLSTATE '55000' THEN NULL;
  END;
END;
\$\$;
INSERT INTO academic_native_session_rows
  (class_session_id,class_id,session_date,start_time,end_time,status,
   source_imported,version)
VALUES ('${id}-import','${id}','2027-03-11','08:00','09:00',
  'PLANNED',TRUE,0);
DO \$\$
BEGIN
  BEGIN
    UPDATE academic_native_session_rows SET status='CANCELLED',version=1
      WHERE class_session_id='${id}-import';
    RAISE EXCEPTION 'Core-imported Academic lesson mutated';
  EXCEPTION WHEN SQLSTATE '55000' THEN NULL;
  END;
END;
\$\$;
ROLLBACK;
SQL
left="$("${compose[@]}" exec -T academic-postgres \
  psql -U iig_academic -d iig_academic -v ON_ERROR_STOP=1 -tAc \
  "SELECT COUNT(*) FROM academic_native_session_activations
   WHERE class_id='${id}'" | tr -d '[:space:]')"
test "$left" = 0 || { echo "Native SQL test leaked committed state" >&2; exit 1; }
echo "PASS: Academic Postgres exclusion/immutability and rollback invariants"
