#!/usr/bin/env bash
# LAST CI read-only probe after the terminal Core-only orphan was created.
# Neither Core nor Academic is modified. Disposable volumes are destroyed
# by the workflow's unconditional final cleanup.
set -euo pipefail
if [[ "${GITHUB_ACTIONS:-}" != true || "${IIG_TERMINAL_ORPHAN_PROBE:-}" != true ||
      ! "${GITHUB_RUN_ID:-}" =~ ^[0-9]+$ ]]; then
  echo "Refusing Core-only roster diagnosis outside disposable CI" >&2
  exit 2
fi
compose() { docker compose -f docker-compose.microservices.yml "$@"; }
core_sql() {
  compose exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 -tAc "$1" | tr -d '[:space:]'
}
fail() { echo "Core-only roster diagnosis: $*" >&2; exit 1; }
key="ci-orphan-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}-roster"
test "$(core_sql "SELECT COUNT(*) FROM academic_roster_candidate_claims
  WHERE actor_username='ci-crash' AND request_key='$key' AND state='OPEN'")" = 1 ||
  fail "Terminal smoke did not leave the expected Core-only OPEN roster claim"
private_key="${ACADEMIC_INTERNAL_API_KEY:-local-dev-academic-key-change-before-deployment}"
admin="$(curl -fsS -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' -d '{"username":"admin","password":"123456"}' |
  python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')"
url="http://academic-service:8099/internal/academic/student-roster/candidates/reconciliation?actor=ci-crash&requestKey=$key"
result="$(compose exec -T scheduling-postgres wget -qO- \
  --header "X-Internal-Key: $private_key" \
  --header "Authorization: Bearer $admin" "$url")" ||
  fail "Academic read-only diagnostic unavailable after service restart"
RESULT="$result" python3 -c '
import json,os
r=json.loads(os.environ["RESULT"])
assert r["state"]=="CORE_ONLY_ORPHAN",r
assert r["coreState"]=="OPEN",r
assert r["requiresManualReview"] is True,r
assert r["hasCompleteAcademicEvidence"] is False,r
assert r["hasLocalHandoffReceipt"] is False,r
'
test "$(core_sql "SELECT COUNT(*) FROM academic_roster_candidate_claims
  WHERE actor_username='ci-crash' AND request_key='$key' AND state='OPEN'")" = 1 ||
  fail "Diagnostic falsely released a Core-only roster claim"
test "$(core_sql "SELECT COUNT(*) FROM academic_roster_candidate_claim_audit
  WHERE actor_username='ci-crash' AND request_key='$key'
  AND event_kind='RELEASED'")" = 0 ||
  fail "Read-only diagnosis appended an unauthorized release audit"
echo "PASS: Core-only Target claim survives Academic restart and is classified as an unreleasable orphan"
