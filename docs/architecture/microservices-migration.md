# IIG Spring Microservices migration — architecture decision and implementation status

## Status (2026-11-09)

This branch is **migration phase 6 (Payroll ledger extraction)**, not a claim
that all IIG business modules are independently deployable microservices.
Core still owns Academic, Students, Scheduling, HR employee lifecycle,
salary review workflows and credential writes. **Audit, Course Catalog,
Authentication, Reporting, HR and Payroll** run as independent Spring Boot services
with different processes and exclusive PostgreSQL databases.

The legacy `docker-compose.yml` and FE `/api/**` contract are unchanged.
The opt-in `docker-compose.microservices.yml` runs the new stack:

```text
React -> Spring Cloud Gateway :8080
  |-- /api/auth/login, /api/auth/logout -> Identity :8094 -> identity-postgres
  |-- /api/classes/courses, /api/catalog/** -> Catalog :8093 -> catalog-postgres
  |-- /api/statistics, /api/dashboard -> Reporting :8095 -> reporting-postgres
  |-- /api/employee-management/*/certificates/** -> HR :8096 -> hr-postgres
  |-- /api/payroll/ranks/** -> HR :8096 -> hr-postgres
  |-- /api/payroll (read/detail/calculate) -> Payroll :8097 -> payroll-postgres
  +-- remaining /api/** -> Core :8081 -> core-postgres
                                   +-- audit events -> Audit :8092 -> audit-postgres
```

Core, Audit, Catalog, Identity, Reporting, HR and Payroll use **seven independent PostgreSQL instances**, not just two schemas in
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

## Phase 4: Reporting statistics read model (not yet event-driven reporting)

`services/reporting-service` is a separately deployed Spring Boot process
and owns the `iig_reporting` PostgreSQL database. The Gateway routes the
existing Admin `GET /api/statistics` endpoint to Reporting; response
properties (`period`, `overview`, `employees`, `schedules`, `financial`,
`monthly`) remain unchanged for React.

- Reporting validates the caller's current JWT and `ROLE_ADMIN` using
  `GET http://backend:8081/api/me` **on every call**, including cache hits.
  A disabled or revoked account cannot access a cached snapshot.
- Snapshot keys are canonicalized by DAY/MONTH/YEAR. JSON payloads are
  persisted to `statistics_snapshots` only in Reporting's own database.
  A configurable 30-second TTL avoids repeated cross-service calculations,
  with a `X-Reporting-Refreshed-At` response header and `no-store`.
- Misses call a service-key-protected, **private** legacy endpoint,
  `GET /internal/reporting/statistics`. The Core computes the response from
  its current SQL tables during this transition. It cannot be called through
  public Gateway.
- A source outage results in an explicit 503 (after current auth has been
  checked), not fabricated values. Reports can be stale up to the TTL;
  this is an intentional **eventual consistency** contract.
- **Additional dashboard extraction:** Gateway routes `GET /api/dashboard`
  to Reporting. The service independently stores `dashboard_snapshots`
  keyed by verified username, ROLE, and Asia/Ho_Chi_Minh business day.
  Every request revalidates JWT and role with Core. Cached responses are
  limited to a configurable 10-second TTL and expose their freshness timestamp.
  The private `GET /internal/reporting/dashboard` adapter remains protected
  by a service key and the public Gateway cannot access it.
- **Not yet extracted:** the source dashboard and statistics SQL calculations. Those move to domain-event subscriptions
  after Academic, Scheduling and Payroll own their respective tables.
- Architecture now uses **five separate PostgreSQL containers**, with no
  cross-service JDBC access. Do not call this full reporting extraction.

## Phase 5: HR certificate write ownership (not full HR)

`services/hr-service` and its private `iig_hr` PostgreSQL database
own employee **certificate** creation, updates and reads. The public
`/api/employee-management/{id}/certificates` GET/POST/PUT routes now go
to HR through Gateway, preserving the prior JSON fields and response codes.

- HR verifies each request's JWT and employee management scope against
  the existing Core `EmployeeManagementService.detail` via a private
  service-key-protected API. TC/CM restrictions and Admin access remain
  enforced by the same business rules.
- Existing certificates are imported once from Core into `hr_certificates`.
  HR creates certificate events in `hr_certificate_outbox` in the same
  database transaction as each write; retries propagate the latest version
  to Core.
- Core's `certificates` table remains a read-only reference projection
  for older profile/salary dependencies. Per-certificate
  `hr_certificate_versions` prevents stale/out-of-order deliveries from
  overwriting newer records. Direct Core writes are blocked in the new
  Compose topology; the original monolith Compose retains its old behavior.
- All HR service APIs stay on the Compose private network, except the
  routes explicitly exposed through Gateway. Internal import/access/project
  APIs are **not** routed publicly.
- **Not yet migrated in phase 5a:** employee/role lifecycle and salary ranks
  (salary ranks are extracted in phase 5b below), payroll ledgers,
  HR authentication independence and other HR workflows.
  Core still owns the `employees` table, so this is a safe incremental
  bounded-context extraction, not the end of the HR migration.

The six PostgreSQL containers are logically isolated: core, audit,
catalog, identity, reporting and HR.

## Phase 5b: HR salary ranks and latest Core Payroll compatibility

The same **independent HR service and database** that owns certificates
now owns salary-rank creation, updates and management queries.

- Gateway routes `/api/payroll/ranks` and
  `/api/payroll/ranks/{id}` to the HR service, preserving the existing
  rank JSON contract. Current JWTs and active role are checked against the
  protected Core `/api/me` endpoint on every request. Admin can write;
  TC sees TEACHER ranks and CM sees CS/SALE ranks.
- On initial startup HR imports existing rank records from
  `GET /internal/hr/salary-ranks/export`. It stores authoritative
  `hr_salary_ranks` with transactionally written
  `hr_salary_rank_outbox`. A versioned HTTP projection updates Core's
  local `salary_ranks` reference table, and `hr_salary_rank_versions`
  prevents a stale event overwriting a newer rank.
- The newer `main` branch introduced payroll endpoints and new
  `SchedulingAccess.requireActiveRole`. These files were carried into
  the **same** Microservices branch without replacing the protected
  CS-cancellation logic or removing the planned-session seed fixture.
- Core Prisma migrations now create salary rank histories, salary review
  requests and calculated work items expected by the new Payroll API.
- **Payroll is not independently owned yet.** Those new tables,
  calculation transactions and review workflows are still in the Core DB
  and must later move to a dedicated Payroll ledger.
- Four business-write ownership boundaries have been established:
  Course Catalog, Audit, HR Certificates, HR Salary Ranks. Reporting
  owns snapshots, and Authentication owns its local credential projection
  but still depends on Core for authoritative account state.

There are still **six PostgreSQL instances**, not seven: HR Certificates
and HR Salary Ranks are two bounded contexts in the same HR deployment.

## Phase 6: independently owned Payroll ledger and calculation

`services/payroll-service` is a separate Spring Boot application with its
**own exclusive** `iig_payroll` PostgreSQL instance and a locally transactional
`payroll_ledgers` + `payroll_items` write model.

- Gateway routes GET `/api/payroll`, GET `/api/payroll/{employeeId}/{period}`
  and POST `/api/payroll/calculate` to Payroll. Routes for HR salary ranks
  remain on HR. Salary rank assignments, salary reviews, salary history
  and employee salary profile remain on Core for now.
- The first startup imports legacy `employee_salaries`, `staff_salaries` and
  existing calculated items through a private, service-key-validated Core
  endpoint. The `payroll_meta` import marker is committed in the same local
  transaction. Repeat startups never overwrite Payroll's live ledger.
- Payroll retrieves **current** username, active role, employee scope, dated
  salary rate, eligible teaching attendance and assigned staff shifts over
  private HTTP contracts. The service has **no database credentials** for
  Core, HR, Identity or Scheduling.
- A calculation writes summary and item rows in one Payroll-only transaction.
  `(source,employee_id,period)` is unique and locked during recalculation;
  duplicate commands preserve the ledger ID instead of creating new
  paychecks. A previously APPROVED/PAID/FINALIZED ledger cannot be
  recalculated.
- Every list/detail/calculate request checks the current JWT and employee
  role via Core. Admin can manage all, TC Teacher only, CM CS/Sale only,
  and workers may read their own records but cannot calculate.
- The original Core salary tables remain as **historical rollback data**.
  There is no back-projection from Payroll to these tables; the Gateway
  no longer lets clients write calculated salary rows in Core.
- **Not yet complete:** schedule/attendance source facts and historical salary
  rank review/assignment transactions still originate in Core. The facts
  HTTP snapshot is eventual-consistent, not a distributed transaction.
  Independent Academic/Scheduling and HR/Identity ownership will allow
  a future versioned event stream with finalized attendance and rate snapshots.

**Reporting financial consistency:** Payroll now serves
`GET /internal/payroll/financial?from=YYYY-MM&to=YYYY-MM`, protected by
`PAYROLL_INTERNAL_API_KEY`. Reporting requests monthly salary totals and
record counts from Payroll's authoritative ledger and overlays those
fields on the legacy Core statistics snapshot. It stores the completed
JSON in its own read-model database with the existing 30-second TTL.
The monthly salary-cost series and summary no longer read Core's stale
`employee_salaries` tables. If Payroll is unavailable, refresh returns
503 instead of inventing or silently retaining old financial amounts.
Revenue remains unavailable as in the existing contract.

CI uses seven isolated PostgreSQL instances, tests first-time import,
calculation in Payroll's own database, role restrictions, finalized-payroll
protection and the existing FE Playwright suite.

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
- Classes, students, scheduling and employee lifecycle are **not yet independently
  deployed bounded contexts**. The Payroll ledger has its own service, but
  still depends on Core-sourced attendance/rates and salary-review operations. Calling phase 1 a completed full migration is
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
mvn -B -ntp -f services/reporting-service/pom.xml test
mvn -B -ntp -f services/hr-service/pom.xml test
mvn -B -ntp -f services/payroll-service/pom.xml test
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
4. **HR/Payroll** — HR must own employee lifecycle; Payroll already owns its
   ledger but must consume versioned, finalized attendance/rate events instead
   of synchronous Core facts, and move review/rank-assignment workflows out.
5. **Dashboard/Statistics** — Reporting now has standalone dashboard/statistics snapshots
   read model with TTL/freshness metadata, but must still replace Core's SQL
   snapshot source with independently consumed domain events and migrate Dashboard.
6. Add contract tests, idempotency on commands, distributed tracing, retry
   budgets/timeouts, observability and resilience tests across all services.

**Acceptance criteria for the final architecture:** each domain service can
be built/deployed and stopped independently; owns its data; nobody reads
another service's tables; FE uses Gateway; identity and role policies remain
correct; class/student/schedule invariant E2E tests continue to pass; and
failure of one service cannot silently corrupt another service's data.
