# IIG Spring Microservices migration — architecture decision and implementation status

## Status (2026-11-09)

This branch is **migration phase 3 (authentication extraction)**, not a claim that the entire IIG system
has become independently deployable microservices. The existing monolith
still owns Auth, HR, Classes, Students, Scheduling, Payroll and Statistics.
**Audit, Course Catalog, and Authentication** now run as independent Spring Boot services
with different processes and exclusive PostgreSQL databases.

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

Core, Audit and Catalog use **different PostgreSQL instances**, not just two schemas in
the same logical database. The API gateway is a separately started Spring
application, not an Nginx reverse proxy or a folder named gateway.

## Phase 2: Course Catalog ownership

The Course Catalog bounded context has been extracted into
`services/catalog-service`, with a standalone database `iig_catalog`.

- Spring Cloud Gateway routes the existing FE endpoint
  `GET /api/classes/courses` to Catalog, preserving the response
  `[{ courseId, name }]` and requiring the same valid JWT.
- Catalog exposes `GET/POST /api/catalog/courses` and
  `PUT /api/catalog/courses/{courseId}`. Mutations require ADMIN.
- During its **first** startup, Catalog imports existing rows using
  `GET http://backend:8081/internal/catalog/export`, protected with
  `X-Internal-Key`. This endpoint cannot be called through Gateway.
  A durable `catalog_meta` record prevents repeated overwrite after import.
- Catalog writes `catalog_courses` in its own database. Each write also
  creates/updates a versioned `catalog_outbox` row in the *same transaction*.
- A background relay sends the latest catalog state over HTTP to
  `POST /internal/catalog/project` in the Academic core. The core's existing
  `courses` table remains a **reference projection** because the current
  Prisma schema has foreign keys from classes, lessons and student targets.
  It is not the authoritative course write model.
- Projection is **at least once**, idempotent at core (`INSERT ... ON CONFLICT`).
  The relay only acknowledges the version it actually sent, so concurrent
  updates cannot be lost by an older acknowledgement.
- Enrollment of a class in a newly added course can be delayed until the
  projection arrives (normally one polling interval). The API intentionally
  does not pretend these are strongly consistent cross-service transactions.
- Catalog checks each caller's current JWT via core `GET /api/me`;
  it does not access core's `users` table. Consequently, while databases
  and deployments are independent, authentication is not yet independent
  from the legacy core. The future Identity service will remove this dependency.

Deployment topology now contains **Gateway + Core + Audit + Catalog**,
and **four distinct PostgreSQL instances** in the phase 3 stack. Catalog listens on the Compose
network only (`catalog-service:8093`). The legacy Compose stack is unchanged.

## Phase 3: Authentication (not yet full Identity ownership)

The opt-in architecture adds `services/identity-service` and a fourth
PostgreSQL instance. Gateway routes **only** `POST /api/auth/login` and
`POST /api/auth/logout` to this new Spring Boot service, preserving the
existing React contract.

- Login verifies the user password and status using a **current** private
  credential snapshot from core, then persists a projection and issues JWT
  with exactly the claims accepted by the existing Spring Security filter.
  The internal snapshot includes a BCrypt hash and requires a confidential
  service key, with no public Gateway route for `/internal/identity/**`.
- Logout waits for core to revoke the session before returning 204. Core's
  existing request filter still validates live role, account status, password
  stamp and revocation.
- CI verifies independently deployed login/logout, cross-service JWT
  compatibility, revocation after logout, private API isolation, and the
  existing React browser E2E suite.
- **Not yet extracted:** user/role/permission write ownership and
  registration/profile/password change. Those remain in core, so this is
  *authentication-service extraction* rather than an autonomous Identity
  bounded context. This is intentional until the credential migration,
  profile writes and session invalidation contracts can be proven.
- The direct core dependency on every login makes login unavailable if core
  is offline. Do not misrepresent this as an independently resilient
  identity authority.

See `docs/architecture/service-data-ownership.md` for subsequent extraction
gates and distributed business invariants.

## Architecture decisions

| Concern | Design | Reason |
| --- | --- | --- |
| Edge | Spring Cloud Gateway (Boot 3.3.13 + Cloud 2023.0.6) | Preserve all existing FE `/api/**` routes |
| Authentication | Existing JWT + Spring Security in core | No disruptive role/security behavior change in phase 1 |
| Audit ownership | New Audit Service / independent DB | Clear bounded context and deployment unit |
| Course Catalog | Standalone service + DB, versioned projection into Academic references | No cross-service database writes; keep FK integrity and FE schema |
| Domain event source | Existing committed `audit_logs` | Already written in the domain transactions |
| Reliable transport | Poll committed logs, HTTP POST, acknowledgement table | Retry after audit-service outage or container restart |
| Delivery semantics | At least once | Audit DB's unique `event_id` makes retries idempotent |
| Security | `X-Internal-Key` between core and Audit | Audit service has no externally published port |
| Service discovery | Compose DNS: `backend`, `audit-service` | Eureka not needed for a fixed Docker Compose topology |
| Rollback | Use original `docker-compose.yml` | Original service entrypoint and DB migration support retained |

### Limits of the current migration

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
   and Audit is at `http://audit-service:8092`, Catalog is at
   `http://catalog-service:8093` only on that network.
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
mvn -B -ntp -f services/catalog-service/pom.xml test
mvn -B -ntp -f services/identity-service/pom.xml test
npm ci && npx prisma validate
docker compose -f docker-compose.microservices.yml config --quiet
docker compose -f docker-compose.microservices.yml up --build -d
bash scripts/smoke-microservices.sh
```

CI runs these checks in separate jobs with **disposable** PostgreSQL data.
Smoke asserts JWT login over Gateway; internal API isolation; real class
creation; audit projection; business continuity while Audit is unavailable;
and successful replay after restart. It also validates catalog initial import,
ADMIN-only writes, versioned course projection and all FE Playwright E2E
scenarios through the Gateway.

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
