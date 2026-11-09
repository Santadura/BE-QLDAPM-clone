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
    expected_session_version,command_key,expires_at)
  SELECT '${fixture}-acad','${one}',lesson_id,
         c.scheduling_version,s.scheduling_version,'smoke-academic-command',NOW()+interval '5 minutes'
  FROM classes c JOIN class_sessions s ON s.class_id=c.class_id
  WHERE c.class_id='${one}' AND s.class_session_id=lesson_id;
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
END
\$\$;
SQL
echo "PASS: leased Academic sessions block timetable edits and premature Core writer resume"





