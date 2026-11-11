# IIG — Microservice data ownership, invariants & extraction gates

> Architecture status: **Phase 8f-10 (Payroll owns a separately replayed immutable teacher attendance evidence history; financial calculation still Core-sourced)**. Do not claim full migration before every
> domain below meets its exit criteria and the existing FE/BE E2E suite passes.

## Ownership matrix

| Domain | Owned entities / tables | State |
| --- | --- | --- |
| Identity & Access | `roles`, `app_functions`, `permissions`, `users` | Legacy core remains **write owner**; standalone Authentication Service authenticates and holds an isolated `identity_users` projection, **not full Identity extraction** |
| HR & People | `employees` | Employee lifecycle remains in Core; HR owns certificates and salary rank writes |
| HR certificates | **`hr_certificates`, `hr_certificate_outbox`** | HR Service owns certificate writes; Core `certificates` is a versioned reference projection |
| HR salary ranks | **`hr_salary_ranks`, `hr_salary_rank_outbox`** | HR Service owns salary-rank writes; Core `salary_ranks` is a versioned FK reference projection |
| Academic | `classes`, `lessons`, `students`, `student_targets`, `class_target_requirements`, `class_students`, `class_access_scopes`, `assignments`, `exams`, `student_results`, `class_sessions` | Legacy core |
| Scheduling availability | **`scheduling_availabilities`, `scheduling_availability_outbox`, `scheduling_availability_reservations`, `scheduling_availability_audit_outbox`** | Independent Scheduling database owns registration, approval, active leases and append-only audit |
| Scheduling assignment/attendance writes | `teaching_schedules`, `staff_schedules`, `teacher_session_attendance` | Core remains **exclusive write owner**. Gateway forwards attendance PUT to Attendance, which stores durable PENDING/COMMITTED inbox entries and privately delegates to Core's idempotent transactional receipt; no database writer cutover |
| Scheduling assignment read model | **`scheduling_assignment_read_model`** | Scheduling PostgreSQL stores teacher/staff/worker GET snapshots, not authoritative writes |
| Core assignment relay | `scheduling_assignment_outbox` | Transactional change trigger and versioned HTTP delivery to Scheduling; metadata changes also enqueue updates |
| Availability reference | `availabilities`, `scheduling_availability_versions` | Core reference projection only; versioned updates and deletion tombstones from Scheduling |
| Payroll write model | **`payroll_ledgers`, `payroll_items`, `payroll_meta`** | Payroll Service owns calculations and payslip history in exclusive PostgreSQL |
| Payroll legacy/source | `employee_salaries`, `employee_salary_details`, `staff_salaries`, `salary_rank_history`, `salary_review_requests`, `salary_calculation_items` | Core keeps historical rollback data and still owns rate history/reviews; it is NOT the active pay ledger |
| Authentication projection | **`identity_users`, `identity_revocations`** | **Isolated Identity database; live credentials still originate from core** |
| Course Catalog | **`catalog_courses`, `catalog_outbox`, `catalog_meta`** | **Isolated Catalog database (extracted)** |
| Course FK projection | `courses` | Legacy academic read/reference copy; Catalog is authoritative |
| Audit | **`audit_events`** | **Isolated Audit database (extracted)** |
| Audit relay source | `audit_logs`, `audit_outbox_deliveries` | Transactional source in legacy core |
| Dashboard / Statistics | `statistics_snapshots` read model | **Reporting owns role-scoped snapshots**; Academic/Scheduling metrics still come from Core, while salary costs and payroll counts are read from Payroll over private HTTP |

The above is the **target ownership design** for remaining entities, not a
claim that the corresponding services already exist. Legacy FK links and SQL
joins make direct table-by-table migration unsafe.

## Current real network/deployment boundary

```text
React
  |
Spring Cloud Gateway (:8080 public)
  |-- POST /api/auth/login, /api/auth/logout --> Auth (:8094) --> identity-postgres
  |     (live credential lookup & logout revocation --> Core private API)
  |-- /api/classes/courses, /api/catalog/** --> Catalog (:8093) --> catalog-postgres
  |-- /api/statistics and /api/dashboard -----> Reporting (:8095) --> reporting-postgres
  |-- /api/employee-management/*/certificates -> HR (:8096) --> hr-postgres
  |-- /api/payroll/ranks/** ----------------> HR (:8096) --> hr-postgres
  |-- /api/payroll (list/calculate/detail) --> Payroll (:8097) --> payroll-postgres
  |-- /api/availabilities/** ----------------> Scheduling (:8098) --> scheduling-postgres
  |-- GET /api/teaching-schedules,/api/staff-schedules,/api/schedules/me --> Scheduling read model
  |-- POST/PUT/DELETE teaching/staff assignment APIs --> Core transactional write model
  |      (live JWT authorization + key-protected snapshot via Core)
  |-- /api/** -------------------------------> Core (:8081) --> core-postgres
                                                |   |
                                                |   +-- catalog reference projection (internal API)
                                                |
                                                +-- audit event delivery --> Audit (:8092) --> audit-postgres
```

All cross-service communication uses HTTP. **No service connects directly to
another service's PostgreSQL**. In the core, the old foreign-key `courses`
table is deliberately a local reference copy, updated only through a
service-key-protected projection API after bootstrap.

## Critical cross-domain business invariants

1. **Student enrollment schedule overlap:** a student must not become ACTIVE
   in two overlapping class sessions. Today `ClassManagementService`,
   `StudentManagementService`, `ClassSessionService` and
   `StudentClassScheduleGuard` rely on the same SQL transaction. After
   separation, reserve a student/time interval through Scheduling before
   Academic commits enrollment; use idempotency, expiry and explicit
   cancellation/compensation. Recheck when moving lesson times.
2. **Teacher and staff availability/conflicts:** do not allow assigning one
   person to two overlapping ACTIVE intervals; use per-employee serialization
   in Scheduling, not a cross-database SELECT-before-INSERT check.
3. **Last CS support on RUNNING lessons:** cancellation must not orphan the
   final CS support shift without an authorized override. Scheduling needs a
   versioned Academic class/session state contract; stale responses cannot
   authorize cancellation.
4. **Class lifecycle:** DRAFT, READY, RUNNING, COMPLETED, CLOSED and related
   transitions preserve planned-session, staffing, and attendance constraints
   across service boundaries. Failed downstream commands must leave a
   recoverable state (saga/event log), not half-migrated records.
5. **Account lock / password reset / logout:** all services must reject a
   revoked JWT or disabled/role-changed user promptly. Signed JWT alone is
   insufficient because core currently checks credential stamp, current
   role/status and `JWT_SESSION` revocation markers.
6. **Payroll:** salary calculations must use idempotent finalized attendance
   events, with immutable ledger entries; do not read Scheduling tables
   directly from the Payroll database.
7. **Statistics:** an eventual-consistency projection must expose data
   freshness and stop pretending to have synchronized distributed snapshots.

## Extraction order (must satisfy every exit gate)

### A. Identity/Access (partial authentication extraction already implemented)

- [x] Standalone authentication process with JWT issuance using the exact
  legacy-compatible claims, a private credential-lookup API and an exclusive
  credential snapshot/revocation database.
- [x] Gateway routes login/logout to that process; revocation must be accepted
  by the authoritative core before logout reports success. CI verifies core
  rejects the revoked token and the browser remains compatible.
- [ ] Separate user credentials, roles, account status and session revocation
  **ownership**. The current `users` table is still owned by core.
- Migrate legacy users **once**, preserving BCrypt hashes and unique usernames.
- Replace core's direct `users` lookups with authenticated identity contracts or
  explicit subscribed read models; eliminate writes from core to identity data.
- Verify register, admin account creation, profile password changes, lock/unlock,
  role-scoped authorization, login/logout, and credential-stamp invalidation.
- Block unknown/disabled users fail-closed when identity service is unavailable.

### B. Academic + Students

- Cohesively extract classes, roster, assessments, sessions and student targets
  into one initial Academic bounded context to preserve enrollment invariants.
- Own its own database and migration/seed lifecycle.
- HR/Identity/Catalog references are local **event-sourced projections** or
  validated via APIs; there can be no cross-database foreign keys.
- Test the existing class and student Playwright flows unchanged via Gateway.

### C. Scheduling

- [x] Move availability registration/review ownership to Scheduling DB and
  block Core assignment writes unless they obtain a live approved-slot lease.
- [x] Phase 8b-1: persist overlapping in-flight employee/time assignment
  intents within Scheduling's local reservation transaction. Employee advisory
  locks serialize reservations; committed Core assignments remain authoritative.
- [x] Phase 8b-2: add an opt-in Idempotency-Key on single/batch/range
  assignment, edit and cancel endpoints. Core persists the command receipt
  and the business mutation in the same transaction, including attendance
  and class-support rules. This prevents replaying successful writes; it
  does not make the Scheduling read model authoritative for mutation.
- [x] Phase 8b-3a: add a private manifest and strict cross-service
  reconciliation gate (source rows/outbox completeness, full paged assignment
  metadata/JSON, tombstones, version parity, empty pending queue). A 200
  readiness report is only a *precondition*, never permission by itself to
  move writes while Academic and attendance still depend on Core.
- [x] Phase 8b-3b: fence every Core assignment table INSERT/UPDATE/DELETE
  with a transactional `CORE/QUIESCED` guard; no Scheduling writer mode yet.
- [x] Build a normalized, versioned, tombstone-safe Scheduling write-shadow,
  backfilled from existing projection and maintained in its local DB transaction.
  It remains a Core-backed mirror until explicit ownership transfer.
- [x] Build a transaction-scoped worker/class conflict guard on this normalized
  shadow with expiry-checked availability leases and PostgreSQL advisory locks.
  It is **not** enabled as authoritative writes until Academic and attendance
  coordination are fully migrated.
- [x] Persist private, actor-scoped **STAGED-only** command identities and
  normalized hashes in the Scheduling database; reject reused keys with
  different input. These commands currently have NO executable handler.
- [x] Version Core Academic class/session state and expose private HTTP
  session facts. This is not a transactional reservation across service DBs.
- [x] Phase 8b-3c: expose version-checked Academic session lease acquire,
  renew and release, including a stable command retry key and PostgreSQL
  mutation fences. Leases are disabled while CORE is the live writer.
- [ ] Integrate lease renewal through the **entire** Scheduling command/saga
  (including crashes and compensation); enforce resulting reference versions
  in Core attendance/Payroll consumers. The five-minute lease primitive
  alone is not an atomic distributed transaction.
- [x] Phase 8d-1: durable immutable Scheduling assignment domain outbox
  and a local STAGED -> PENDING_REFERENCE -> COMMITTED lifecycle (only after
  all Core reference acknowledgements). The relay never publishes STAGED
  work, and all failed deliveries are retained for retry.
- [x] Provide a **disabled-by-default** Core reference consumer for standalone,
  non-class STAFF UPSERTs with a receipt, exact version, hash checks and
  an audited Core write-fence bypass confined to the reference transaction.
- [x] Phase 8e: persist immutable per-event Core reference receipts
  (source/revision/event ID/actor/command/digest), enabling exact
  acknowledgement of an older event even after later revisions.
- [x] Add read-only per-event receipt lookup for ADMIN recovery,
  Scheduling outbox and Core receipt immutability triggers, and
  transactional smoke tests to prove SQL tampering is rejected.
- [ ] Verify the full event replay/recovery flow against real PostgreSQL,
  then finish independent Academic/Attendance and class-support mutation
  ownership before public API cutover.
- [x] Phase 8f-2: GET teacher attendance status is now read from the
  independently replicated Attendance PostgreSQL projection, guarded by
  a complete Core event relay barrier and live CS/class source parity.
  An event race can retry; persistent divergence fails closed with 503.
- [x] Smoke tests intentionally corrupt a test-only Attendance status,
  prove it cannot be served as current, then restore the fixture and
  verify deletion tombstone status is null.
- [x] Phase 8f-1: durable transactional Core outbox for teacher attendance
  insert/update/delete, including migration backfill and immutable deletion
  tombstones, with a service-key private pending/ack protocol.
- [x] Attendance independently persists per-schedule source state and
  import receipts; source hashes are verified and only committed local
  writes can receive a Core ack, with replay and out-of-order protection.
- [x] Phase 8f-5: verify all acknowledged Core Attendance events and deletion tombstones against the independent Attendance PostgreSQL by private paged manifest, source-table completeness and pre/post mutation snapshots; mismatches and outages fail closed.
- [x] Phase 8f-6: PostgreSQL-level Attendance writer freeze coordinated with Scheduling's existing fence; reversible only in a safe order, with monotonic generations and a private frozen full-manifest verification gate.
- [x] Phase 8f-7: live, actor-scoped Academic lesson + teacher/CS assignment version precondition forwarded from Attendance to Core, revalidated after business locks inside the original attendance/audit/outbox transaction; stale evidence must roll back.
- [x] Phase 8f-8: Attendance-owned normalized ledger and immutable complete Core event history, including all previously acknowledged events and tombstones; backfill cursors, parity checks and tamper protection are covered by CI.
- [x] Phase 8f-9: independent Attendance teacher-payroll evidence via Payroll-key internal API and complete source parity; private Payroll reconciliation of teacher work IDs against status/tombstones, revisions and writer fences. Public Payroll calculation remains unchanged.
- [x] Phase 8f-10: Payroll PostgreSQL imports all Attendance-projected Core teacher events into immutable history and versioned latest statuses using a durable, restart-safe cursor; no salary source switch.
- [ ] Attendance PUT cutover: relocate exact-CS, lesson-start and teacher
  assignment guards with a versioned Academic/Scheduling lease plus
  Core/Payroll reference projection and safe handback.
- [x] Attendance teacher-attendance GET routed to a standalone service with
  private actor/class-scoped PostgreSQL snapshots and live Core CS class-access
  verification. FE response shape unchanged. PUT remains in Core, so this
  is read separation, **not** Attendance mutation ownership.
- [ ] Move attendance event ownership and finalized evidence for Payroll to
  Attendance, preserving the lesson-start and assigned-CS-only gates.
- [x] Phase 8k: private read-only Academic cutover readiness proof requires
  all three Scheduling/Attendance/Academic writers QUIESCED with unchanged
  generation stamps, no source events awaiting acknowledgement, full Core
  live-row/outbox parity, exact per-event hash and receipt parity across the
  COMPLETE historical feed, complete backfill cursor, equal latest session
  and DELETE tombstone projections, and stable source evidence after scan.
  Immutable Academic receipts are guarded against raw SQL UPDATE/DELETE/TRUNCATE.
  This proves a snapshot ONLY; full Academic write saga, lifecycle ownership,
  and compensation must be implemented before independent writes may begin.
- [x] Phase 8j hardening: reject TRUNCATE on Core class_sessions,
  immutable source outbox and Academic writer-control tables, independent
  of current writer mode. Attendance cannot resume CORE while Academic is
  still QUIESCED; smoke tests prove both guards and reverse-order handback.
- [x] Phase 8j: PostgreSQL Academic timetable write fence defaults to CORE.
  A CORE/QUIESCED transition is generation-stamped and requires Scheduling
  and Attendance already QUIESCED. All class_sessions INSERT/UPDATE/DELETE
  paths hold the singleton row FOR SHARE so active Core transactions finish
  before freeze, and SQL writes fail closed while QUIESCED. Resume reverses
  the order. There is deliberately no ACADEMIC mode or automatic cutover:
  class and student lifecycle ownership and cross-service compensation
  remain unresolved.
- [x] Phase 8i: Core PostgreSQL now records immutable, revision-stamped
  class-session INSERT/UPDATE/DELETE events and initial backfill, with SHA-256
  checked acknowledgements. Academic imports the complete session event feed,
  DELETE tombstones and durable source receipts into its OWN PostgreSQL,
  keeping a monotonic latest-session projection and replay cursor. This is
  an independently persisted source/restore foundation, not an Academic
  domain writer; public timetable writes still commit under Core locks.
- [x] Phase 8h: route public timetable PUT and reschedule POST to Academic
  Service while retaining the original locked Core transaction as the ONLY
  authoritative writer. The Core private bridge enforces X-Internal-Key,
  live JWT, current class-manager scope, and an atomic Core command receipt.
  Academic PostgreSQL stores a separate PENDING/COMMITTED ingress ledger.
  A retry always consults Core with the same key; local receipts never bypass
  current authorization. This is a transitional write INGRESS, not independent
  Academic domain write ownership. Full Academic/Scheduling/Attendance sagas
  and Core write-fence handback still remain.
- [x] Phase 8g: independently persist per-class planned-session JSON in Academic
  PostgreSQL under a Core transactional session revision clock. Core checks
  exact current CS/Teacher class visibility on every revision lookup, including
  cached reads; retries refuse concurrent version changes, and older projections
  cannot overwrite newer. Core still owns all session mutations.
- [x] Academic class-planning and planned-session GET routes now pass
  through an independently deployed Academic service with isolated
  `iig_academic` PostgreSQL read snapshots. Authorization and responses
  are checked against LIVE Core on every GET; source outages fail closed.
  Public session writes remain Core-owned and are NOT yet a cross-DB saga.
- [ ] Transfer Academic class/session write authority and event ownership;
  mirror teacher/CS coverage, classroom access scopes, attendance and
  student timetable constraints before stopping the Core Academic writer.
- [x] Phase 8d-5: reserve a durable, actor-scoped claim in Core before
  independently committing a standalone Scheduling create/edit/cancel command.
  Claims serialize with Core writer-mode changes and prevent a race between
  the preflight QUIESCED read and the Scheduling DB transaction.
- [x] Require the matching claim to apply a Core assignment reference;
  confirm it atomically with the receipt. Claims fence unsafe handback even
  before a first reference event exists and do not auto-expire.
- [ ] Deliver an auditable, carefully coordinated orphan-claim cleanup,
  handback and disaster recovery protocol once the full Academic/teacher/CS
  assignment saga is independently authoritative and tested.
- [x] Phase 8d-4: implement Core read-only exact reference receipt lookup,
  plus feature-gated ADMIN-only reconciliation of exhausted Scheduling events.
  The event ID, version and digest must match the latest committed Core
  receipt; Scheduling can only advance COMPENSATING when confirmed, and
  multi-event commands remain unresolved until all events are acknowledged.
  No automatic compensation or manual force-success bypass exists.
- [ ] Provide audited disaster-recovery and explicit handback migrations
  for missing/older Core receipts and genuine Core/Scheduling divergence.
- [x] Phase 8d-3: add PRIVATE `EDIT_STAFF` for already Core-confirmed
  Scheduling-owned standalone shifts using a fresh approved reservation,
  employee/class immutability and a sequential versioned Core reference event.
- [x] Core reference edits cannot reassign workers or convert class support,
  and cancellation validates the exact existing shift rather than rewriting
  employee, dates or work type. Stale historical events are never considered
  acknowledged without a matching receipt.
- [x] Prevent unsafe `QUIESCED -> CORE` handback once an independent
  Scheduling reference receipt exists, until a separate reviewed
  reconciliation/rollback path is established.
- [x] Phase 8d-2: implement opt-in, PRIVATE standalone CS/Sale create
  and cancel executors; use live CM identity, Core QUIESCED mode, approved
  Scheduling lease, committed shift conflicts, revisioned local projection,
  immutable event outbox and PENDING_REFERENCE state in one local transaction.
  The public routes remain in Core and all three cutover flags default off.
- [ ] Expand independent write ownership to class support/teachers, edits,
  attendance, bulk staffing and Payroll, with Academic versioned leases,
  compensating commands and end-to-end verified CI before public routing.
- [ ] Extend Core reference projection to teacher, CS class coverage,
  cancellation/tombstones, attendance and payroll without cross-DB FKs.
- [ ] Enforce strict ownership handoff, compensation and disaster recovery
  before enabling the two reference feature flags.
- [ ] Introduce idempotent, durable commands for staff/teacher assignment,
  attendance and Academic session reservation; remove direct Academic SQL.
- [ ] Check existing committed assignment conflicts in the new authoritative
  Scheduling write model (the current asynchronous copy is insufficient).
- [ ] Add version checks, idempotency keys, expiry and compensation for failed
  cross-service enrollment/assignment, while keeping last-CS-coverage and
  teacher attendance constraints correct.
- [ ] Test high-concurrency cross-service schedule races and role/override boundaries.

### D. HR and Payroll

- [x] Extract certificate create/update/read to HR's separate Spring Boot
  process and PostgreSQL with the existing FE routes, Core-validated
  role/employee scope and versioned outbox. Core certificates are reference
  projections for profile screens, not a second writer.
- [ ] Extract the rest of employees, salary ranks, salary history and staff
  lifecycle into HR.
- [ ] Separate staff lifecycle, certificates and salary ranks from authenticated
  identity; HR owns `employee` reference versions and publishes changes.
- Payroll consumes finalized attendance events and maintains a separate ledger.
- Check deactivating an assigned employee, salary aggregation by month,
  historical data after a role change, and duplicate attendance deliveries.

### E. Read models & operational readiness

- [x] Extract Admin statistics GET API to standalone Reporting service with an
  exclusive PostgreSQL snapshot store; validate current ADMIN session on
  every read; expose `X-Reporting-Refreshed-At`; tolerate at most a configurable
  30-second stale response while the source is healthy.
- [ ] Replace the temporary Core snapshot endpoint with domain event streams
  (Academic/Scheduling/HR/Payroll), including idempotent backfill and replay.
- [x] Move role-specific Dashboard HTTP API to Reporting, with per-user,
  per-role, per-business-day read models and live JWT revalidation. Cache
  freshness is 10 seconds by default to reduce exposure to stale staffing.
- [ ] Replace the temporary Core Dashboard snapshot calculation with
  event-driven read models of Academic, Scheduling and HR data.
- [ ] Move all statistics computation out of the Core monolith.
- Add correlation IDs, distributed tracing, readiness endpoints, retry limits,
  dead-letter handling and alerting on unsent outbox records.
- Run all FE Playwright tests plus BE integrity tests on the composed stack.
- Practice recovery and rolling independent deploys; document rollback/migration
  for existing non-empty databases. No `down -v` against real data.

## Definition of done

**Do not label this system “fully migrated” until** all core business
controllers are routed to domain services (the legacy core is removed),
each domain service has an exclusive write model and deployment artifact,
there are no cross-service SQL joins or shared writable tables,
distributed business invariants and rollback are proven by automated tests,
and actual FE flows continue to work through Gateway.

Phase 8a has independent Audit, Catalog, HR salary-rank, Payroll-ledger and Scheduling-availability ownership plus the Scheduling assignment query projection and separately
deployed Authentication and Reporting read-model processes. **It is not a
fully migrated system**: Core is still the source of credential updates,
reporting computations, dashboard queries and domain transactions.
