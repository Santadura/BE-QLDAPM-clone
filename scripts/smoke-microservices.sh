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
