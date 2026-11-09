# IIG — Microservice data ownership, invariants & extraction gates

> Architecture status: **Phase 2**. Do not claim full migration before every
> domain below meets its exit criteria and the existing FE/BE E2E suite passes.

## Ownership matrix

| Domain | Owned entities / tables | State |
| --- | --- | --- |
| Identity & Access | `roles`, `app_functions`, `permissions`, `users` | Legacy core, **not extracted** |
| HR & People | `employees`, `certificates`, `salary_ranks` | Legacy core |
| Academic | `classes`, `lessons`, `students`, `student_targets`, `class_target_requirements`, `class_students`, `class_access_scopes`, `assignments`, `exams`, `student_results`, `class_sessions` | Legacy core |
| Scheduling | `availabilities`, `teaching_schedules`, `staff_schedules`, `teacher_session_attendance` | Legacy core |
| Payroll | `employee_salaries`, `employee_salary_details`, `staff_salaries` | Legacy core |
| Course Catalog | **`catalog_courses`, `catalog_outbox`, `catalog_meta`** | **Isolated Catalog database (extracted)** |
| Course FK projection | `courses` | Legacy academic read/reference copy; Catalog is authoritative |
| Audit | **`audit_events`** | **Isolated Audit database (extracted)** |
| Audit relay source | `audit_logs`, `audit_outbox_deliveries` | Transactional source in legacy core |
| Dashboard / Statistics | Future asynchronous projection DB | Legacy core; no independent read model yet |

The above is the **target ownership design** for remaining entities, not a
claim that the corresponding services already exist. Legacy FK links and SQL
joins make direct table-by-table migration unsafe.

## Current real network/deployment boundary

```text
React
  |
Spring Cloud Gateway (:8080 public)
  |-- /api/classes/courses, /api/catalog/** --> Catalog (:8093) --> catalog-postgres
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

### A. Identity/Access

- Separate user credentials, roles, account status and session revocation.
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

- Introduce stable commands for availability, staff/teacher assignment,
  attendance and session reservation; no direct Academic SQL.
- Make conflict reservation atomic within Scheduling's database, with
  idempotency keys, version checks and compensation on failed enrollment.
- Test high-concurrency schedule races as well as role/override boundaries.

### D. HR and Payroll

- Separate staff lifecycle, certificates and salary ranks from authenticated
  identity; HR owns `employee` reference versions and publishes changes.
- Payroll consumes finalized attendance events and maintains a separate ledger.
- Check deactivating an assigned employee, salary aggregation by month,
  historical data after a role change, and duplicate attendance deliveries.

### E. Read models & operational readiness

- Move Dashboard/Statistics into independent projection storage via events.
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

Phase 2 has deliberately satisfied **only** the Audit and Catalog extraction
gates, with core still present.
