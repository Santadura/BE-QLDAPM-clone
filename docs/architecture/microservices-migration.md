# IIG Spring Microservices migration — architecture decision and implementation status

## Status (2026-11-09)

This branch is **migration phase 8f-2 (Attendance-owned status reads with Core event barrier)**, not
a claim that all IIG business modules are independently deployable microservices.
Core still owns Academic, Students, assignments/attendance, HR employee lifecycle,
salary review workflows and credential writes. **Audit, Course Catalog,
Authentication, Reporting, HR, Payroll and Scheduling** run as independent Spring Boot services
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
  |-- /api/availabilities/** -> Scheduling :8098 -> scheduling-postgres
  |-- GET /api/teaching-schedules, /api/staff-schedules, /api/schedules/me -> Scheduling :8098
  |-- POST/PUT/DELETE /api/teaching-schedules*, /api/staff-schedules* -> Core :8081
  +-- remaining /api/** -> Core :8081 -> core-postgres
                                   +-- audit events -> Audit :8092 -> audit-postgres
```

Core, Audit, Catalog, Identity, Reporting, HR, Payroll and Scheduling use **eight independent PostgreSQL instances**, not just two schemas in
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

## Phase 7: Scheduling availability ownership with transaction leases

`services/scheduling-service` runs on the Compose-only port **8098** and
writes authoritative `scheduling_availabilities` to its **exclusive** database.
Spring Cloud Gateway routes all existing `/api/availabilities/**` endpoints to
this service with unchanged JSON field names. Class/teacher/CS assignments and
attendance **remain in Core** for this migration phase.

- One-time bootstrap imports the Core `availabilities` table, preserving
  existing IDs, workers, review status and history. `scheduling_metadata`
  prevents repeat imports from overwriting live rows. A transactionally updated,
  versioned `scheduling_availability_outbox` synchronizes the Core read/reference
  table. `scheduling_availability_versions` rejects stale revisions, including
  deleted tombstones that must never be resurrected.
- Scheduler registration and updates use **per-employee PostgreSQL advisory
  transaction locks**, preventing two concurrent writes from bypassing the
  no-overlap check. Date-specific and recurring weekday slots, rejected slots,
  role restrictions and approval status are preserved. TC approves teachers;
  CM approves CS/Sale.
- Before any Core teacher or support assignment, Core requests a **live**
  approved-slot reservation from Scheduling using a private service key.
  The reservation is atomically created with the same per-employee database
  lock used by Scheduling's mutations. Core holds it **until the local
  assignment transaction completes**, then releases it. If the process dies,
  the server expires the lease (10-minute maximum) instead of blocking edits
  indefinitely. Mutations reject outstanding reservations, and Core's private
  edit guard also rejects existing ASSIGNED schedules.
- Consequently, a mutation cannot invalidate an approved slot during an
  in-flight assignment, nor can a Core assignment rely solely on stale
  asynchronous availability projections. If Scheduling cannot be reached,
  Core fails closed rather than making an unsafe assignment.
- REGISTER, UPDATE, DELETE and REVIEW are appended to a separate
  **durable Scheduling audit outbox** in the same local transaction. A relay
  sends idempotently to Core's private audit ingest, which feeds the already
  independent Audit Service. A latest-state projection cannot collapse
  intermediate audit events.
- All internal APIs are reachable only within the Docker Compose network
  under a unique `SCHEDULING_INTERNAL_API_KEY`. Gateway does not publish
  `/internal/**`.
- **Remaining extraction gates:** class sessions, teaching/staff assignment,
  attendance and atomic employee/conflict reservations still reside in Core.
  Source authentication/employee metadata also remains Core-owned pending
  Identity/HR migration. Student enrollment/timetable cross-domain invariants
  still require an Academic service and explicit compensation contracts.

Regression now uses **8 isolated PostgreSQL instances**, tests legacy import,
public API compatibility, conflict detection, approvals, worker/manager role
restrictions, active-reservation edit/delete protection, versioned tombstone
projection, durable audit and the existing React Playwright suite.

## Phase 8a: independent assignment query model (writes remain in Core)

The independent Scheduling PostgreSQL now stores a **READ model** of teacher
assignments, CS/Sale shifts, and worker-visible schedules. It uses exactly
the JSON key shapes that React already expects.

- Gateway routes **only GET** `/api/teaching-schedules`,
  `/api/staff-schedules`, and `/api/schedules/me` to Scheduling.
  **POST/PUT/DELETE and batch/range assignment commands stay in Core.**
  Its ClassSession, ClassStaffingRange and TeacherSessionAttendance code
  still requires an atomic local database transaction, especially the
  rule preventing cancellation of the final CS support shift on a
  RUNNING lesson.
- The Prisma migration installs `scheduling_assignment_outbox` in Core,
  with PostgreSQL AFTER INSERT/UPDATE/DELETE triggers on teacher and staff
  assignments. Triggers also invalidate the snapshot when employee names,
  staff roles or class names/codes change. Any Core write path therefore
  creates an outbox record **within its original transaction**, even for
  bulk SQL that bypasses `AssignmentService`.
- Core exposes private service-key-protected **bootstrap, changes, and exact
  version acknowledgement** endpoints. The independent Scheduling process
  imports existing schedules once, marking the import in its own DB. Core
  then sends updated snapshots over internal HTTP (at-least-once); a record
  with a version lower than or equal to the stored one cannot overwrite a
  newer projection. Hard deletions become durable tombstones.
- Scheduling validates the **current JWT role/status on each GET** using a
  protected Core viewer endpoint. Admin can browse management schedules
  without having an employee profile; TC sees teacher schedules, CM sees
  CS/Sale shifts, and workers see only their own active schedules.
- Queries **must not join Core DB**. Scheduling stores the projection with
  JSONB response payload and typed indexed date, employee, source and role
  columns in `scheduling_assignment_read_model`.
- **Consistency boundary (phase 8a):** the outbox is delivered asynchronously
  by Core, but each authorized Scheduling GET first pulls pending committed
  changes from Core over private HTTP, applies them transactionally to the
  independent read database, then acknowledges the exact versions as a batch.
  It repeats the feed check even after a short nonempty batch, since a second
  Core transaction may commit during synchronization. A GET only serves the
  projection after observing an empty pending feed. This reduces stale cards
  immediately after assigning/cancelling a shift; it does **not** make reads
  linearizable with writes committed after that final empty observation.
  A backlog that cannot be drained within the bounded loop returns 503.
  Reads fail with 503 if Core cannot supply pending changes. This transitional
  Core dependency is intentional until assignment writes and attendance
  invariants move together; the projection is **never** used to authorize
  assignments or academic operations.
- CI checks bootstrap of existing schedules, GET field/role compatibility,
  Core-side inserts, status transitions, class-name invalidation, tombstones,
  exact-version outbox acknowledgement, and all existing FE Playwright tests.

## Phase 8b-1: Scheduling-owned assignment intent reservations

Scheduling's independent database now owns an additional *short-lived worker /
time assignment intent* table, `scheduling_assignment_intent_reservations`.
The private reservation API still returns the same lease ID to Core. In one
Scheduling transaction it:

1. Takes the existing PostgreSQL employee advisory transaction lock (shared
   with availability create/update/delete/review), then checks a live approved
   availability slot.
2. Rejects overlapping **in-flight** intents for that employee/date/time with
   HTTP 409. Adjacent intervals are allowed. Different employees do not block
   one another; expired intents do not count.
3. Inserts both the availability lease and the assignment intent atomically.
   Releasing the lease after Core commits/rolls back deletes the intent using
   a PostgreSQL foreign-key cascade. The existing 10-minute expiry cleanup
   also removes both if Core crashes.
4. Core preserves HTTP 409 as a business conflict instead of translating it
   to a misleading HTTP 503 outage.

The table is created through the Scheduling service's idempotent startup
`schema.sql`, including for existing preserved database volumes. Neither the
Gateway public routes nor the React request/response contracts change.

**Scope and safety:** The reservation only blocks *simultaneous in-flight
commands*. It is NOT a check against already committed assignments: that
check, the last-support-CS rule, teacher attendance association, timetable
rules, and the authoritative assignment writes still execute in Core's local
transaction. There is no cross-database transaction. Core remains the sole
writer; assignment reservations must NOT be advertised as completion of
phase 8b or as permission to route POST/PUT/DELETE to Scheduling yet.

Next gates: define durable idempotent assignment commands and a
versioned Academic session/coverage contract; then migrate ownership of
teaching/CS/Sale assignment **and** attendance writes only with replay,
compensation, reference projection and verified FE regression.

## Phase 8b-2: transactional retry-safe assignment commands (Core write boundary)

A new Core PostgreSQL `scheduling_command_receipts` table records optional
`Idempotency-Key` results for the existing POST/PUT/DELETE assignment APIs:
single Teacher / CS-Sale shifts, 50-session batches, and the
`/api/class-planning/{classId}/staffing` 400-session range operation.
No FE JSON contract, role permission or routing path is changed.

- If a client omits the header, existing operations behave as before.
- A provided key (1-128 safe ASCII chars) is scoped to the authenticated
  **username plus HTTP operation/path**. On every attempt the current TC/CM
  role is rechecked *before* querying a stored receipt; a revoked/disabled
  user cannot bypass current authorization with a stale key.
- PostgreSQL's primary-key conflict serializes overlapping retries. The
  SHA-256 request-body fingerprint must match, otherwise the server returns
  HTTP 409 without running the domain command. A committed matching receipt
  returns the original JSON result (or JSON null for a successful HTTP 204).
- The receipt INSERT, existing class/employee locks, scheduling writes,
  attendance/CS coverage guards and audit rows participate in **one Core
  transaction**. An exception rolls back both the receipt and the domain
  write, allowing a later retry to execute normally. There is no pending
  receipt visible after a rollback, and no need for a cross-database commit.
- This allows a safe command replay after a client timeout or lost HTTP
  response, but clients must **reuse the original key only for retries of the
  same logical operation**. To issue another intentional operation, use a
  different key. Already-created schedules continue to be protected by Core
  conflict checks.
- Schema and the real two-request cancellation/replay test are included in
  Prisma migrations and the disposable Docker/Gateway smoke suite.

**This is deliberately a transition gate, not assignment write extraction:**
authoritative assignment tables, attendance data and the command receipt
still live in Core. Scheduling owns only approved availability, in-flight
worker intents and the assignment query projection. Do not route any write
endpoint to Scheduling until its authoritative write store, idempotent command
inbox, Academic session versions and compensation/reconciliation are ready.

## Phase 8b-3a: verified assignment projection before future write cutover

This phase adds an explicit **fail-closed cutover guard**; it does **not** move
assignment writes away from Core.

- Core now exposes private, service-key-protected
  `/internal/scheduling/assignments/manifest` with stable keyset pagination
  and `/manifest/summary`. Unlike the incremental `/changes` outbox feed,
  the manifest includes **already acknowledged assignment versions**, so a
  missing Scheduling row cannot be hidden by an empty pending queue.
- Core's summary includes counts of the actual `teaching_schedules` and
  `staff_schedules` tables *and* how many live rows have an outbox entry.
  This detects missing triggers/incomplete backfills before transferring write
  ownership, even when two outbox-based projections superficially agree.
- Scheduling compares every Core assignment and tombstone against its own
  PostgreSQL read model: composite key, version, deletion flag, indexed
  metadata, and the **complete FE-visible JSON payload** (including names,
  class codes, dates, attendance-relevant status and work type).
- The private `GET /internal/scheduling/assignments/verify` responds with
  `200 {ready:true,state:"READY",...}` only when the Core source manifest is
  unchanged throughout the scan, there are no pending outbox updates, the
  actual source tables are fully tracked, every row matches and there are no
  orphaned Scheduling rows. Otherwise it returns `409` with a bounded list
  of mismatched keys. Core unavailability and oversized scans fail closed
  with `503`, not an optimistic success.
- The endpoint is private on the Docker Compose network, requires
  `SCHEDULING_INTERNAL_API_KEY` and has **no Gateway /api routing**. It is
  read-only: it never blindly overwrites same-version divergent data.
  The keyset scan is currently capped at 25,000 records (100 pages × 250);
  larger deployments need a background incremental verification job.
- Unit tests cover missing/tampered/stale rows, ghost tombstones, concurrent
  changes, pending events, source outbox incompleteness and internal-key
  authorization. Disposable Docker smoke checks a healthy manifest, injects
  a ghost projection, verifies the rejection, then removes the ghost and
  confirms recovery.

**Remaining before actual Phase 8b write cutover:** move Academic class/session
authority behind versioned private contracts, independently own assignment
and attendance writes, implement an idempotent Scheduling command inbox with
transactional events and compensation/reconciliation, verify employee/time
conflicts with committed data (not only in-flight leases), quiesce old Core
writers, and run full-stack browser and concurrency regressions. A passing
snapshot report alone never authorizes the switch.

## Phase 8b-3b: dry-run cutover primitives (NOT live write ownership)

The **Core PostgreSQL writer fence** installs BEFORE INSERT/UPDATE/DELETE
triggers on `teaching_schedules`, `staff_schedules` and
`teacher_session_attendance`, including bulk SQL paths from class planning
and attendance. The singleton
`scheduling_assignment_writer_control` defaults to `CORE`. Its alternative
`QUIESCED` state blocks every Core assignment row write with SQLSTATE 55000.
The trigger obtains a row `FOR SHARE` lock, so changing mode waits for
in-flight assignment SQL transactions to commit. There is intentionally NO
`SCHEDULING` writer mode yet: turning it on prematurely would result in two
uncoordinated writers or lost Academic/Payroll effects.

Scheduling now maintains a versioned normalized
`scheduling_assignment_write_shadow` in its own PostgreSQL. It is populated
in the same local transaction as the FE JSON read projection and can safely
backfill existing projections at startup. Its primary key and indexed typed
worker/date/start/end/class/status fields prepare an independent write model;
its revisions/tombstones are still derived solely from **Core's outbox**.
Strict cutover verification now compares **both** the FE read copy and this
candidate write-shadow with Core, including row counts, indexed fields and
JSON values. Neither projection is authoritative for mutations yet.
The gate also refuses `READY` while the private Scheduling command inbox
contains unresolved `STAGED` or `COMPENSATING` requests, even if all
projected rows match. Until the command executor and saga recovery exist,
these entries are incomplete work, not successful assignments.
An authenticated actor can explicitly reject their own **STAGED-only**
command through the private service-key-protected
`POST /internal/scheduling/assignments/commands/{operation}/{requestKey}/reject`.
Rejection is idempotent, cannot touch a `COMMITTED` or `COMPENSATING` command,
and makes no business write or attendance change. Replaying the original
request key will return `REJECTED` instead of staging a fresh command.
This supports a controlled cleanup of test/stale unexecuted commands; future
saga failures still require proper compensation rather than this endpoint.

A new transaction-scoped `AssignmentWriteConflictGuard` is available for
the future writer. While holding the same employee advisory lock used for
availability, it requires an unexpired corresponding reservation and rejects
overlapping **committed** worker slots from the normalized shadow. Teacher
assignment also locks the class and rejects an already-covered class slot.
Adjacency is allowed; CS coverage replacement and teacher attendance still
need Academic coordination. The guard is a component, not an enabled API:
it cannot be used as authoritative permission until that shadow becomes
the sole writable Scheduling assignment state inside one database transaction.

A new private `POST /internal/scheduling/assignments/commands/stage`
validates the service key plus a *currently active* Core JWT. An independent
Scheduling-owned `scheduling_assignment_command_inbox` records
actor-scoped operation/key/normalized-payload hashes. Retries return the
same `STAGED` receipt; the same key with changed data returns 409.
**No command execution is connected**, and nothing from this inbox writes
an actual assignment or reaches the public Gateway. The staging boundary is
for future safe command processing, not a substitute for a completed saga.

Academic (still in Core) now versions class status/date changes and planned
session timetable/status updates with `scheduling_version`. A protected
`GET /internal/scheduling/academic/session` returns both entity versions
and exact planned-session metadata. This is a reference contract, **not**
a cross-DB lease and not sufficient to authorize a write: between lookup and
commit, Academic may change unless a later version-checked reservation locks
the session. Existing Core class and attendance invariants remain intact.

### Phase 8b-3c: versioned Academic session leases

Academic (still in Core) has a private, service-key-protected
`/internal/scheduling/academic/leases` endpoint for acquiring a short-lived
lease against a specific planned class session and expected class/session
versions. Core only issues one when its assignment writer control is
`QUIESCED`, not in normal production `CORE` mode. To serialize competing
requests, the Core transaction locks the control row, class and lesson.

- `POST /internal/scheduling/academic/leases` requires
  `{classId,sessionId,classVersion,sessionVersion,commandKey}`. The key
  identifies retries of the **same logical command**, so a lost response can
  be replayed without creating a duplicate lease. A reused key with different
  expected versions is rejected.
- `POST /{id}/renew` checks lease expiry, class/session versions and statuses
  under SQL row locks before extending expiry. `DELETE /{id}` is an
  idempotent release. Leases expire after five minutes unless renewed.
- Core database triggers reject modification/deletion of a leased session,
  overlapping new lessons and changes to the class dates/status while an
  active lease exists. Restarting Core assignment writes from `QUIESCED`
  to `CORE` is forbidden until all active Academic leases expire or are
  released. This prevents replay/cutover races from silently invalidating
  a protected Academic lesson.
- A protected Scheduling HTTP client can acquire/renew/release these
  leases, **but no public write route uses it yet**. It is not an end-to-end
  distributed transaction. Future command execution must coordinate lease
  renewal, worker reservations, Attendance and Payroll compensation safely.

The dry-run script from 8b-3b must not be used while any active leases
exist: its exit handler tries to restore Core but the database deliberately
blocks that restoration. A maintenance operator must wait for lease expiry
or release the correct command lease, and verify `writer_mode='CORE'`
afterwards. See below for manual recovery instructions.

### Safe verification procedure

A maintenance-window dry-run (NOT a live migration) can be invoked with:

```bash
IIG_ALLOW_CUTOVER_CHECK=1 bash scripts/check-scheduling-cutover.sh
```

The script verifies the starting state is `CORE`, changes to `QUIESCED`,
reads the private strict manifest over the Docker network, then restores
`CORE` through an EXIT trap even on a failed check. It must only be used
with an intentional maintenance window because all assignment writes stop
while the scan runs. SIGKILL, host power failure or an unavailable database
can still interrupt cleanup: restore manually if necessary:

```bash
docker compose -f docker-compose.microservices.yml exec -T postgres \
  psql -U postgres -d qlapm_db -c "UPDATE scheduling_assignment_writer_control SET writer_mode='CORE',changed_at=NOW() WHERE singleton=TRUE"
```

**Not yet complete in milestone 8:** Scheduling-owned *executed* assignment
commands and atomic local outbox, Core/Academic idempotent reference projection
and teacher attendance coordination, Payroll replay, robust cross-DB
compensation/reconciliation, promotion of conflict checks from Core-backed
shadow into independently authoritative writes, public Gateway mutation
routing cutover and passing Core/Scheduling/Gateway/FE concurrency and
integrity regressions. Existing Academic lease primitives are available
but not integrated into an executable distributed saga. Do not mark Phase 8
or the full migration complete on a shadow-data parity report alone.

## Phase 8d-1: durable assignment events and Core reference acknowledgements

The Scheduling service now has a durable
`scheduling_assignment_domain_outbox` table. A future write transaction must
change its local authoritative schedule, append immutable source/revision/JSON
events and move the matching command inbox from `STAGED` to
`PENDING_REFERENCE` **in the SAME Scheduling PostgreSQL transaction**.
The outbox repository enforces an outer transaction for insertion, checks the
staged command and refuses duplicate source/version events with different
hashes. A `COMMITTED` receipt is not issued until **every** event of a batch
has a Core acknowledgement. Outbox delivery only selects command rows already
in `PENDING_REFERENCE` and retains failed messages for at-least-once retries.

The new private Core endpoint
`POST /internal/scheduling/assignments/reference` is explicitly
**disabled by default**. When deliberately enabled AND Core assignment writes
are `QUIESCED`, it can idempotently mirror only **standalone STAFF UPSERT**
events (CS operations/front desk or Sale shifts that are **not** linked to
classes). A single Core transaction:
- validates the authenticated service key, immutable canonical JSON digest,
  source revision and allowed work type;
- checks the Core writer fence and serializes the target assignment;
- updates Core `staff_schedules`, its independent
  `scheduling_assignment_reference_receipts` and the Core assignment
  reference outbox with an exact matching version;
- suppresses Core's legacy assignment-change trigger only for this
  transaction-local reference projection, preventing feedback loops;
- returns `APPLIED` or `ALREADY_APPLIED` for idempotent HTTP retries, or
  fails closed on hash/version conflicts.

Scheduling's `AssignmentReferenceRelay` retries the immutable event over
private HTTP. It marks an event delivered and advances its command to
`COMMITTED` **only after** Core responds with matching event ID/revision,
and never compensates blindly for a timeout (Core may already have committed).
A Core DB rollback leaves the Scheduling event outstanding.
Only commands in `PENDING_REFERENCE` can be delivered. After the tenth
failed delivery attempt, the relay flags the command `COMPENSATING` for
explicit reconciliation, stops automatically re-sending its events, and
keeps all immutable records for inspection. It does **not** assume a failed
HTTP response means Core rolled back. The cutover verifier also blocks on
`PENDING_REFERENCE`, not only `STAGED` / `COMPENSATING`.

Both sides remain explicitly opt-in, **OFF** in normal Compose:

```text
SCHEDULING_REFERENCE_WRITES_ENABLED=false  # Core internal consumer
SCHEDULING_REFERENCE_RELAY_ENABLED=false   # Scheduling event delivery
```

**This is not a live write migration.** No public assignment POST/PUT/DELETE
routes have moved from Core; the independent Scheduling command executor
does not yet produce real assignment events. The reference receiver does NOT
support teaching schedules, CS CLASS_SUPPORT, bulk staffing, attendance
writes, tombstone deletion or multi-service compensation. Enabling only
these two flags is NOT sufficient or safe: a controlled cutover needs an
independent producer, full Academic/session and worker lease integration,
payroll/attendance reconciliation, split-brain fencing and green regression
suites. The legacy `CORE` mode and all FE API contracts remain unchanged.

## Phase 8f-3: single protected public Attendance PUT ingress (Core still owns writes)

In the opt-in microservices stack, the Gateway sends
`PUT /api/classes/{classId}/teacher-attendance/{teachingScheduleId}`
to Attendance Service. It checks the caller's **live** Core JWT and CS role,
then invokes the private Core Attendance command endpoint with **both**
the original JWT and the Attendance-only `X-Internal-Key`. Core's JWT filter
and existing `TeacherSessionAttendanceService.mark` re-check the live
employee identity, exact assigned CS support shift, planned teacher/lesson,
lesson start time and transactional class/teacher/CS row locks. Core's direct
public PUT is explicitly **403** in this Compose; its private command is
not routed by Gateway. Missing/invalid JWT, wrong role, wrong service key
and unavailable Core all fail closed; private commands cannot be called
with the service key alone.

The standalone/legacy Compose keeps its prior direct Core PUT
(`ATTENDANCE_PUBLIC_WRITE_ENABLED=true`). The Attendance public PUT ingress
is disabled by default outside the microservices stack. The FE success JSON
remains unchanged, and Attendance does not connect to Core's PostgreSQL.
This is **write-ingress fencing, NOT Attendance write-data ownership**:
the source `teacher_session_attendance` table, source outbox, transaction
and Payroll evidence still belong to Core. A durable independent Attendance
command inbox, versioned teacher/CS/session leases, writer-mode handoff,
idempotent receipts and Payroll compensation/reconciliation are required
before claiming authoritative Attendance writes.

## Phase 8f-2: independently owned teacher-attendance status reads

Attendance now uses its own `attendance_teacher_source_records` to
resolve the FE `attendanceStatus` field. The Core role-scoped GET still
returns the live teaching/session roster and enforces the assigned-CS or
managing-CS class visibility. Before serving the roster, Attendance
requires its private Core event feed to be **fully drained**, including
checking that a final short/empty batch is really empty. It then verifies
that the current Core status equals the locally imported Attendance
status; on success it emits the Attendance-owned status with the existing
FE JSON shape and saves the scoped read snapshot.

A write racing between Core roster lookup and the relay barrier triggers
one fresh read+barrier retry. Persistent mismatch, source outage, or an
unacknowledged event causes HTTP 503, never a stale attendance result.
Delete tombstones are interpreted as `null` attendance status. The Docker
smoke asserts PRESENT/LATE/null values after real source events and
deliberately corrupts a disposable Attendance projection to verify
fail-closed behavior before restoring it.

This does NOT make the class roster, lesson facts, role checks or teacher
attendance PUT independently owned. They remain Core dependencies until
the fenced Attendance writer saga and Payroll evidence handoff exist.

## Phase 8f-1: durable Core-to-Attendance write-event replication

The independent Attendance service now ingests a Core-owned, transactionally
written **teacher attendance source outbox**. PostgreSQL triggers capture
INSERT, UPDATE and DELETE of `teacher_session_attendance`, including
tombstones and all existing Core mutation paths. The initial migration locks
the source table while it backfills preexisting rows, preventing a
snapshot/trigger gap. The outbox payload and event ID cannot be mutated or
deleted; `delivered_at` advances only after an exact receipt.

A service-key-protected Core feed `GET /internal/attendance/events` returns
bounded pending events with SHA-256 hashes. Attendance validates each
`eventId`, source schedule, status, JSON and hash, applies it to a
per-schedule source projection, and persists an immutable import receipt in
its own PostgreSQL transaction. Only **after that transaction commits**
does it call `POST /internal/attendance/events/ack` using the exact
event ID and digest. Core checks the stored JSONB source against the digest
under a row lock before acknowledging. Retries are idempotent and a delayed
older event can never replace a newer committed local revision. A missing
Core service, bad hash or failed local DB transaction fails closed; the
source event remains pending. The opt-in scheduled relay is enabled only
in the microservices Compose, disabled by default in the service's
standalone configuration.

The disposable Docker smoke exercises actual INSERT, UPDATE and DELETE
of teacher attendance, waits for independent Attendance projections,
verifies the three Core delivery receipts, and checks the private feed is
not reachable through Gateway.

This phase **does not** route teacher-attendance PUT to Attendance. Core
continues to enforce the active assigned teacher, exact CS support shift,
planned lesson and lesson-start restrictions, and still holds salary/
Payroll evidence. Transfer of write authority requires a Core-approved
command lease or equivalent fencing, atomic outcome receipts,
cross-service reconciliation and verified compatibility before routing
the FE PUT away from Core.

## Attendance read-service extraction (Core still owns writes)

The standalone Spring Boot `services/attendance-service` and private
`iig_attendance` PostgreSQL materialize an actor-and-class-scoped teacher
attendance snapshot. Only existing `GET
/api/classes/{classId}/teacher-attendance` routes through this service;
PUT of teacher attendance is intentionally left in Core. Each GET calls
Core `/api/me` for current identity/role and then Core's existing
CS-only GET endpoint, which validates the full managing-CS or active
CLASS_SUPPORT scope. A response is served **only** after the live
source succeeds; stale local snapshots never grant authorization or
override recent attendance changes. Independent smoke compares the
teacher attendance JSON for seeded CS against Core, checks both 401
and 403 paths, and verifies the Gateway sends teacher-attendance
mutations back to Core.

Still needed: transfer teacher-attendance write authority into its own
transaction boundary, project teaching-session/CS support snapshots
and Payroll evidence, implement durable versioned events and end-to-end
sagas before Core can be retired. This read boundary alone is not a
completed microservice migration.

## Academic read-service extraction (Core still owns writes)

Academic now has a separate Spring Boot application
`services/academic-service`, private `iig_academic` PostgreSQL and
actor/role-scoped durable JSON read snapshots. Gateway routes only the
FE-compatible GETs `/api/class-planning` and
`/api/classes/{classId}/planned-sessions` to Academic. Academic validates
the current bearer against Core `/api/me` on every call, then asks the
existing Core GET for the *fresh* source document and writes the result to
its own read database. Core still evaluates all managing-CS, assigned-CS
and teacher permissions for a specific class. Authentication failures,
source unavailability and malformed JSON fail closed: the snapshot is
never served as an unauthorized or stale fallback. Core POST/PUT/PATCH
paths are unchanged.

This is a **transitional read boundary, not independent Academic write
ownership**. The next step requires versioned class/session/attendance
events, ownership of class and student timetables, and a proven saga
for class-linked teacher and CS staffing before the Core Academic
transactions can be removed. No access to Core PostgreSQL is configured
from Academic. Docker smoke asserts identical Core/Academic JSON for
existing and newly created class session schedules, live authorization
and durable snapshot persistence in the separate Academic database.

## Phase 8e: immutable end-to-end assignment event history

The Core reference projection previously retained only the most recent
receipt per staff assignment. Once a later revision committed, a delayed
HTTP response or a manual retry for an earlier *already applied* event
could not be verified. Never accepting stale events was safe, but left
legitimate cross-service recovery permanently blocked.

Core now maintains `scheduling_assignment_reference_event_receipts`: a
durable, append-only record of every applied source-type/schedule/revision
with its event ID, payload SHA-256, actor, operation and command key.
The historical receipt is inserted in the **same Core transaction** as
the staff reference mutation, latest-revision receipt and writer-claim
confirmation. Core still requires the current `QUIESCED` writer fence,
the durable original writer claim and exactly matching command identity.
A replay of an older revision is acknowledged as `ALREADY_APPLIED` only
when the historical record proves the exact event ID, hash and actor
command. Missing or mismatched history still fails closed; no event
is ever automatically superseded or acknowledged merely because a higher
revision exists.

A read-only, key-protected Core endpoint allows an administrator's
Scheduling saga recovery to verify **one exact event**:

```text
GET /internal/scheduling/assignments/reference/receipt/event/{eventId}
    ?sourceType=STAFF&scheduleId={scheduleId}
X-Internal-Key: <private service key>
```

This replaces the previous recovery dependence on the latest receipt
alone; a batch may now recover an earlier proven event even if other
events for the same schedule subsequently committed.

Historical receipts created before this migration cannot be reconstructed
from data that Core already collapsed. The migration backfills only the
**last known receipt** for each assignment and leaves its unknown
operation/request identity blank. Those legacy backfills are intentionally
not accepted as historical replay proof. No fabricated history is added.

PostgreSQL now rejects UPDATE/DELETE of Core historical receipts and
rewriting/deleting Scheduling immutable domain events. Scheduling still
permits delivery metadata updates (retry count, errors, acknowledgement)
without changing the original event. Docker smoke tests exercise both
database guards within transactions that are rolled back.

**Still incomplete:** this is a reliability improvement, not the
teacher/Academic/Attendance cutover. The Academic and Attendance bounded
contexts do not yet run as fully independent services, class-support
rules and attendance/payroll coordination remain Core-owned, and there
is no verified live E2E green result. Feature flags remain disabled.

## Phase 8d-5: durable Core writer claims before independent commits

Previously an executor checked `Core.writer_mode=QUIESCED` via HTTP,
then later committed a separate Scheduling database transaction. An operator
could switch Core back to `CORE` **between those two steps**, even if
Scheduling had not yet delivered any event or generated a reference receipt.
The earlier receipt-only handback guard cannot fence that gap.

Core now persists `scheduling_assignment_writer_claims`. Before any
standalone `ASSIGN_STAFF`, `EDIT_STAFF` or `CANCEL_STAFF` mutation,
Scheduling must successfully call the service-key-protected private endpoint

```text
POST /internal/scheduling/assignments/claims
{actorUsername, managerEmployeeId, operation, requestKey}
```

Core serializes the claim transaction against writer handback with
`SELECT ... FROM scheduling_assignment_writer_control FOR UPDATE`. It
requires `QUIESCED`, validates the active CM identity and persists a stable
actor/operation/key claim; a lost response can retry the same claim.
Scheduling **must not write** its local assignment, read projection or domain
outbox unless this claim was acknowledged.

A new Core reference event must prove the matching claim belongs to the
same CM/operation/command. Core updates the reference row, outbox receipt and
the claim's `CONFIRMED` status atomically; exact event replays still return
`ALREADY_APPLIED`. A confirmed claim cannot be reused to create another
schedule or advance another revision.

Both `PENDING` and `CONFIRMED` claims block an ordinary
`QUIESCED -> CORE` handback, independently from Academic leases and
reference receipts. Claims **have no automatic expiry**: even when a local
Scheduling transaction fails after the Core claim was durably saved, it is
unsafe to assume no Scheduling write happened. An operator must inspect the
Core claims, Scheduling inbox/outbox and reference receipts, then use an
explicitly audited, separately reviewed handback/cleanup migration. The
normal dry-run script is unsuitable while claims exist. This conservative
design can leave the writer quiesced after a crash; it intentionally prefers
manual reconciliation over silent split-brain assignment writes.

This milestone is **still not a general assignment cutover**. The three
private executor/relay/reference flags remain disabled by default; Core
public APIs continue to own classroom teaching, class-support and
attendance mutations. A production-grade automatic claim cleanup/lease
handoff protocol and E2E concurrency verification are still outstanding.

## Phase 8d-4: exact-proof recovery of exhausted reference deliveries

A lost HTTP response is **not** evidence that Core rolled back. When repeated
private relay attempts leave a Scheduling command in `COMPENSATING`,
Scheduling now has a *separately opt-in* ADMIN-only verification endpoint:

```text
SCHEDULING_RECOVERY_ENABLED=false # remains OFF in normal Compose
POST /internal/scheduling/assignments/reconciliation/{eventId}/verify
X-Internal-Key: <private scheduling service key>
Authorization: Bearer <currently active ADMIN token>
```

Recovery first confirms Core's assignment writer is still `QUIESCED`,
then loads the immutable undelivered event from the independent Scheduling
outbox. A read-only Core endpoint (available independently of the Core
reference WRITE feature flag) returns the **last durable Core receipt**
under service-key authorization:

```text
GET /internal/scheduling/assignments/reference/receipt?sourceType=STAFF&scheduleId=...
X-Internal-Key: <private scheduling service key>
```

Only an **exact event ID + source revision + SHA-256 payload hash match** may
advance the `COMPENSATING` command. The acknowledgement, outbox delivered
marker and final command status are updated transactionally in Scheduling.
A multi-event command remains `COMPENSATING` until every required event has
a durable matching receipt. No public route is exposed, and there is **no**
operator-controlled "force success" or blind rollback action.

A missing receipt, a different hash, a later Core revision that has
superseded the last visible receipt, stale identity, or an unavailable Core
service **fails closed**. Such cases still need a human-reviewed
reconciliation/compensation plan; the state is not silently cleared. The
normal relay still never processes `COMPENSATING` commands automatically.
A disposable smoke test checks that the new recovery endpoint is disabled
by default and that private Core receipt reads refuse unauthenticated
requests and missing receipts.

## Phase 8d-3: worker-preserving staff edits and handback protection

A third PRIVATE executor handles `EDIT_STAFF` for standalone CS/Sale
assignments originally created by Scheduling. It requires the same opt-in
`SCHEDULING_ASSIGNMENT_EXECUTOR_ENABLED` flag, Core `QUIESCED` mode,
service key, **live CM token**, an actor-scoped staged command, and a newly
approved worker/time reservation.

The edit payload must provide `{date,startTime,endTime,workType,reservationId,
workplaceId?}` and the staging envelope must contain `targetId`. Employee,
role, class, status and assigning manager cannot be altered by the request.
The executor verifies that the original Scheduling revision-1 event was
acknowledged by Core, that the *current* shift revision was also delivered,
and that the shift is still active and not linked to a class. It locks the
row and employee, checks against committed and in-flight overlapping shifts,
and advances the read projection, write shadow, immutable event outbox and
`PENDING_REFERENCE` receipt inside one Scheduling transaction. Retry of
the same command key returns its original shift ID and revision.

The Core private reference receiver accepts `EDIT_STAFF` only as the next
contiguous revision of a previously committed reference. It validates the
currently active worker/CM again, forbids employee changes and class-linked
rows, and updates only the new time/date, work type, workplace and assigning
CM. Cancellation now also checks the exact Core row before touching its
status; it cannot silently rewrite the original assignment's identity or
time using stale cancellation content.

**Explicit handback fence:** as soon as even one Scheduling-origin
reference receipt exists, the Core database denies an ordinary
`QUIESCED -> CORE` mode change (PostgreSQL SQLSTATE 55000). Academic lease
fencing continues independently. An operator must first perform a reviewed
bidirectional reconciliation and a dedicated handback/rollback migration;
simply resetting the database writer-mode flag is unsafe. This guard is
covered by a disposable Docker smoke test and does not affect the normal
initial `CORE` mode when there are no independent reference receipts.

The third executor does **not** imply that Scheduling is already the public
write owner. The three cutover flags remain off in default Compose. Teacher,
CS class-support, bulk staffing, attendance/Payroll, independent Academic
ownership, cross-service recovery and verified E2E tests are outstanding.

## Phase 8d-2: scoped standalone CS/Sale command execution (PRIVATE, OFF)

The first independent Scheduling **command executor** is now implemented for
**CREATE and CANCEL of standalone CS/Sale shifts only**. It does not authorize
teaching schedules, class-linked CS coverage, attendance, edits, bulk staffing
or public business writes. The existing React API paths and Gateway mutations
remain routed to Core by default.

The operator must explicitly opt in to THREE flags in a disposable
maintenance/cutover environment:

```text
SCHEDULING_ASSIGNMENT_EXECUTOR_ENABLED=false  # private execution controller
SCHEDULING_REFERENCE_RELAY_ENABLED=false       # Scheduling -> Core relay
SCHEDULING_REFERENCE_WRITES_ENABLED=false      # Core reference receiver
```

Even with the flags enabled, a command requires a private service key, a
currently active CM bearer token and `Core.writer_mode=QUIESCED`. The worker
must have an approved availability reservation for the exact date/time.
Scheduling validates worker role against its own approved availability,
locks the active lease and checks both in-flight intents and previously
committed worker overlaps in its own normalized assignment store.

The private workflow is:

1. `POST /internal/scheduling/assignments/commands/stage` with
   `operation="ASSIGN_STAFF"`, a unique `requestKey`, and payload
   `{employeeId,staffRole,date,startTime,endTime,workType,reservationId,
   workplaceId?}`. These are standalone, non-class CS operations/front-desk
   or Sale shifts only.
2. `POST /internal/scheduling/assignments/commands/standalone/{requestKey}/execute`
   executes that *previously staged* command in one independent Scheduling
   PostgreSQL transaction. It writes the FE read projection, normalized
   assignment row, immutable revision-1 outbox event and
   `PENDING_REFERENCE` command receipt, then releases the availability
   reservation with an expiry recheck.
3. The private relay delivers only locally committed
   `PENDING_REFERENCE` events. Core checks the employee and CM are active,
   that no legacy assignment ID is overwritten, and that event revisions
   are sequential; it atomically records the reference row and immutable
   digest receipt.
4. Only after Core acknowledges the exact event and revision does Scheduling
   mark the original command `COMMITTED`. Retrying the executor with the
   same command key returns its original schedule ID, not a duplicate shift.
5. To cancel an independently created standalone shift, stage
   `operation="CANCEL_STAFF"` with `targetId` and a new request key,
   then call
   `POST /internal/scheduling/assignments/commands/standalone/{requestKey}/cancel`.
   It locks the existing independent row, rejects class-linked shifts
   and **any legacy Core-owned row**: the original Scheduling-created
   revision-1 event must have been delivered and its command committed
   in Core. Only then may the cancellation change status to `CANCELLED`,
   increment its version and emit a new immutable Core reference UPSERT.
   It preserves the cancelled row and provides idempotent retries.

Read routes are deliberately fail-closed (503) while any independently
committed command remains `PENDING_REFERENCE` or `COMPENSATING`, rather than
showing a shift as already synchronized into Core when the reference write
is still outstanding. An unexecuted STAGED request does not block reads.

**Cutover is not yet approved.** There is no fully automatic cross-database
rollback or end-to-end successful Docker/Playwright result for these private
executors. Core remains the only PUBLIC assignment write owner. Its
`QUIESCED` mode can only be entered during an explicitly coordinated
maintenance window. Never enable the three flags on a normal production
stack or run the 8b-3b dry-run script while these command executors are in
use; returning to CORE prematurely could leave unresolved independent
reference events. Further work must extend Academic/attendance/Payroll
transactions and their reconciliation, then switch public Gateway routes
only after verified live end-to-end test results.

**Still required for full Scheduling extraction:** move the assignment and
teacher attendance write model together with its worker-conflict
reservations, class lifecycle APIs and compensating commands. Move all
Academic/Student ownership out of Core before removing the legacy backend.

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
mvn -B -ntp -f services/scheduling-service/pom.xml test
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
3. **Scheduling** — availability ownership and live assignment leases are
   extracted; next move staff/teacher assignment and attendance into the same
   service. Build Academic session/conflict APIs with reservation and
   compensation semantics before eliminating Core's scheduling writes.
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
