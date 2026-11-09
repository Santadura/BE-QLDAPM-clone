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

