# IIG Spring Microservices migration — architecture decision and implementation status

## Status (2026-11-09)

This branch is **migration phase 1**, not a claim that the entire IIG system
has become independently deployable microservices. The existing monolith
remains the system of record for Auth, HR, Classes, Students, Scheduling, Payroll,
and Statistics. The Audit bounded context has been separated into a real
Spring Boot service with its **own process and its own database**.

The legacy `docker-compose.yml` and FE `/api/**` contract are unchanged.
The opt-in `docker-compose.microservices.yml` runs the new stack:

```text
React (localhost:5173)
  |
  | /api/** with JWT
  v
Spring Cloud Gateway :8080         [public]
  |
  v
IIG Core Spring Boot :8081         [private container network]
  |                              \\
  v                               \\ durable audit event delivery (HTTP)
PostgreSQL core_db                   v
[classes/students/rosters/etc.]   Audit Service :8092 [private]
                                    |
                                    v
                                 PostgreSQL audit_db [private]
```

Core and Audit use **different PostgreSQL instances**, not just two schemas in
the same logical database. The API gateway is a separately started Spring
application, not an Nginx reverse proxy or a folder named gateway.

## Architecture decisions

| Concern | Design | Reason |
| --- | --- | --- |
| Edge | Spring Cloud Gateway (Boot 3.3.13 + Cloud 2023.0.6) | Preserve all existing FE `/api/**` routes |
| Authentication | Existing JWT + Spring Security in core | No disruptive role/security behavior change in phase 1 |
| Audit ownership | New Audit Service / independent DB | Clear bounded context and deployment unit |
| Domain event source | Existing committed `audit_logs` | Already written in the domain transactions |
| Reliable transport | Poll committed logs, HTTP POST, acknowledgement table | Retry after audit-service outage or container restart |
| Delivery semantics | At least once | Audit DB's unique `event_id` makes retries idempotent |
| Security | `X-Internal-Key` between core and Audit | Audit service has no externally published port |
| Service discovery | Compose DNS: `backend`, `audit-service` | Eureka not needed for a fixed Docker Compose topology |
| Rollback | Use original `docker-compose.yml` | Original service entrypoint and DB migration support retained |

### Limits of phase 1

- The gateway delegates authorization of legacy endpoints to core. It does not
  validate JWTs or act as the authoritative identity service yet.
- The audit relay uses a durable acknowledgement for **committed** audit rows;
  events without rows in `audit_logs` cannot be propagated. An outbox-wide
  cleanup/retention policy and alarm for delivery lag are still needed.
- Gateway and core remain a single point of failure for classroom workflows.
- Classes, students, scheduling, HR and payroll are **not yet independently
  deployed bounded contexts**. Calling phase 1 a completed full migration is
  incorrect.
- Local default passwords in the Compose file are for the disposable demo only,
  not for Internet-facing deployment.

## Reproducible local startup

Requirements: Docker Engine/Compose. No local Java, Maven or Node required.

1. Review `microservices.env.example`. Set strong secrets in `.env`.
2. Start: `docker compose -f docker-compose.microservices.yml up --build -d`
3. Open `http://localhost:8080/actuator/health` — gateway health.
4. Login unchanged: `POST http://localhost:8080/api/auth/login`.
5. The core API is at `http://backend:8081` **inside** the Compose network,
   and Audit is at `http://audit-service:8092` only on that network.
6. Examine projection via
   `docker compose -f docker-compose.microservices.yml exec audit-postgres psql -U iig_audit -d iig_audit -c "SELECT event_id,action,entity_type,occurred_at FROM audit_events ORDER BY occurred_at DESC LIMIT 10;"`
7. Normal shutdown: `docker compose -f docker-compose.microservices.yml down`.
   **Do not use `down -v` on data you intend to keep.**

The original `docker compose up --build -d` remains the legacy deployment;
do not run it on the same host at the same time as the new stack if ports
overlap.

## Automated verification

```bash
mvn -B -ntp test
mvn -B -ntp -f services/api-gateway/pom.xml test
mvn -B -ntp -f services/audit-service/pom.xml test
npm ci && npx prisma validate
docker compose -f docker-compose.microservices.yml config --quiet
docker compose -f docker-compose.microservices.yml up --build -d
bash scripts/smoke-microservices.sh
```

CI runs these checks in separate jobs with **disposable** PostgreSQL data.
Smoke asserts JWT login over Gateway; internal API isolation; real class
creation; audit projection; business continuity while Audit is unavailable;
and successful replay after restart.

## Required follow-on migration before calling IIG fully microservices

1. **Identity** — split credentials, role policies and revocation, introduce
   service-specific identity storage. Define token validation and user sync
   without sharing the `users` table.
2. **Academics** — classes, student membership, course targets and assessments
   belong together initially. Isolate write model and public contracts.
3. **Scheduling** — own availability, assignments and staff shifts, avoid
   cross-service SQL joins. Build a schedule-conflict API and request/response
   contracts with Academics. The current enrollment conflict transaction must
   be redesigned before either database is separated.
4. **HR/Payroll** — own employee lifecycle and payroll ledgers. Consume
   attendance events rather than joining scheduling tables across databases.
5. **Dashboard/Statistics** — move to an eventual-consistency read model with
   explicit refresh policy and monitoring.
6. Add contract tests, idempotency on commands, distributed tracing, retry
   budgets/timeouts, observability and resilience tests across all services.

**Acceptance criteria for the final architecture:** each domain service can
be built/deployed and stopped independently; owns its data; nobody reads
another service's tables; FE uses Gateway; identity and role policies remain
correct; class/student/schedule invariant E2E tests continue to pass; and
failure of one service cannot silently corrupt another service's data.
