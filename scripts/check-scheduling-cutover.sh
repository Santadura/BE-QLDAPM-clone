#!/usr/bin/env bash
# PHASE 8 SAFETY CHECK ONLY. This does NOT switch assignment write ownership.
# Set IIG_ALLOW_CUTOVER_CHECK=1 and use a maintenance window (QUIESCED blocks
# all assignment SQL, including direct writes from class lifecycle services).
set -euo pipefail

if [ "${IIG_ALLOW_CUTOVER_CHECK:-}" != "1" ]; then
  echo "Refusing to quiesce Core. Run explicitly with IIG_ALLOW_CUTOVER_CHECK=1 in a maintenance window."
  exit 2
fi
COMPOSE=(docker compose -f docker-compose.microservices.yml)
resume_required=0
resume_core() {
  if [ "$resume_required" = "1" ]; then
    "${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 -c \
      "UPDATE scheduling_assignment_writer_control SET writer_mode='CORE',changed_at=NOW() WHERE singleton=TRUE" \
      || echo "URGENT: Failed to resume Core assignment writes. Restore writer_mode=CORE manually."
  fi
}
trap resume_core EXIT

before="$("${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -tAc \
  "SELECT writer_mode FROM scheduling_assignment_writer_control WHERE singleton=TRUE" | tr -d '[:space:]')"
if [ "$before" != "CORE" ]; then
  echo "Unexpected writer mode: $before. No database changes were made."
  exit 1
fi

# This UPDATE waits for all currently active teacher/staff SQL transaction
# triggers holding FOR SHARE on the control row to finish.
"${COMPOSE[@]}" exec -T postgres psql -U postgres -d qlapm_db -v ON_ERROR_STOP=1 -c \
  "UPDATE scheduling_assignment_writer_control SET writer_mode='QUIESCED',changed_at=NOW() WHERE singleton=TRUE"
resume_required=1

service_id="$("${COMPOSE[@]}" ps -q scheduling-service)"
key="$(docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$service_id" | sed -n 's/^SCHEDULING_INTERNAL_API_KEY=//p')"
if [ -z "$key" ]; then
  echo "Cannot read internal Scheduling key; Core will be resumed."
  exit 1
fi

url="http://scheduling-service:8098/internal/scheduling/assignments/verify"
ready=0
for i in $(seq 1 30); do
  response="$("${COMPOSE[@]}" exec -T scheduling-postgres wget -qO- \
    --header "X-Internal-Key: ${key}" "$url" 2>/dev/null || true)"
  if printf '%s' "$response" | grep -Eq '"ready"[[:space:]]*:[[:space:]]*true'; then
    ready=1
    break
  fi
  sleep 2
done
if [ "$ready" != "1" ]; then
  echo "NOT READY: Core/Scheduling assignment parity did not pass. No cutover performed."
  exit 1
fi

echo "PASS: Core is quiesced and read/shadow assignment data matches the current manifest."
echo "IMPORTANT: This is NOT a distributed cutover and does not enable Scheduling writes."
echo "Core assignment writes will be resumed immediately by the EXIT handler."
