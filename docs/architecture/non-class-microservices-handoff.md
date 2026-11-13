# IIG — Remaining non-class microservice ownership handoff

Updated: 2026-11-13. Scope: work that other team members can own while
the class/student/role-dependent Academic ownership and
Scheduling–Attendance transaction-finality milestones remain deliberately deferred.

## Frozen boundaries (DO NOT silently cut over)

- Academic authoritative class, roster, student, planned-session and reschedule writes.
- Class-linked Teacher/CS assignments, native attendance/lesson finality, payroll attendance facts.
- Existing Core writer fences and OPEN claims; never force-reset to CORE or turn on production writer flags.
- Public FE /api paths must preserve payload, ACL, date/time zone and response compatibility.

## Already owning live data (do not reimplement)

| Service | Already authoritative | Remaining source/authority dependencies |
| --- | --- | --- |
| Catalog | Course catalog data and writes | Core local course FK reference projection |
| Audit | Independent audit event database | Source outboxes remain in originating services |
| HR | Employee certificates and salary rank definitions | Employees, employment status, salary rank assignment and reviews still Core-owned |
| Payroll | Payroll ledger and calculate, current payroll salary list and history of calculated work | Salary reviews/rates, finalized Attendance/Scheduling references and actor/employee scope still Core-sourced |
| Scheduling | Worker availability registration, approval, leases | Class-linked assignment writes deliberately deferred |
| Identity | Login/logout process and credential projection | User/password/account/role mutations and active-token authority still Core-owned |
| Reporting | Admin statistics and role-specific dashboard snapshots | Most raw class/person statistics and live role authorization still Core-sourced |

## Team-ready, non-class extraction work

### Identity owner: separate account write authority

Current Core files: AuthService, AccountManagementService, ProfileController,
CustomUserDetailsService, user/role tables, JWT session revocation.
Current service: services/identity-service; Gateway routes login/logout only.

Acceptance: initial full user/role/credential migration with unique identity constraints;
password/role/status/revocation events committed atomically with the new writer;
Core subscribes a versioned authorization projection, no direct Core credential UPDATE;
logout/lock/revoked-role JWT denied by ALL services after a restart;
private sessions never leak through Gateway. Preserve Admin/TC/CM/Teacher/CS/Sale contracts.

### HR owner: employee lifecycle and cross-service account lock

Current Core files: EmployeeManagementService and EmployeeManagementController.
Current service: services/hr-service; certificates/salary ranks already independently written.

Acceptance: owned employee profile/status history in HR PostgreSQL and immutable outbox;
migrate email identity writes and employee lock without cross-database transaction;
explicit identity-account lock acknowledgment or unresolved saga state, never
auto-unlock on reactivation; versioned Core reference projections for old FK users;
preserve employee read/write ACL and deactivation self-protection; carry historic data.

### HR/Payroll owner: salary-rank assignments, review/decision and rates

Current Core: PayrollService.assignRank/createReview/reviews/decideReview/history/
salaryProfile; salary_rank_history and salary_review_requests.
Salary-rank definitions already belong to HR; calculated pay ledgers belong to Payroll.

Acceptance: independent review/rank-assignment authority and immutable effective-dated
rate ledger; approved decisions must be exactly-once and reflected in the employee
reference with a durable event/hash receipt; read and migrate historical decisions;
preserve six-month tenure and rejected-resubmission rules, Admin-only decisions,
TC/CM worker scope and cannot change finalized/paid payroll retrospectively.

### Payroll owner: eliminate remaining source facts in Core

Current PayrollCoreClient.people/facts/actor/historical still calls Core;
Attendance -> Payroll independent historical status reference exists but financial
calculations still use Core work snapshots. Fully replacing this part depends on
the frozen Academic/Attendance ownership milestones.

Can do now: versioned salary rate/rank and employee-profile subscriptions from HR;
independent identity role-verification contract; immutable wage/period source history.
Blocked: switching teacher/CS work-fact calculation to independent Attendance
until that domain has finalized writer and settlement guarantees.

### Reporting owner: remove non-academic Core SQL snapshots

Current ReportingCoreClient.currentStatistics/currentDashboard and currentPrincipal
still depend on Core. ReportingPayrollClient already reads Payroll monthly costs.

Can do now: independent HR/Identity/Payroll domain event projections for
employee counts/role and salary, event cursors, freshness and replay;
compare legacy output on disposable multi-DB CI. Blocked: class/student/session
aggregates cannot become independent of Core until Academic ownership is complete.

## Verified increment in this branch

- GET /api/employee-management/{employeeId}/salaries is routed to Payroll's
  independent PostgreSQL salary ledger, retaining source/id/period/units/
  totalHours/rate/amount/status/detailRate/detailAmount for FE.
- A private Payroll-key Core endpoint checks the exact CURRENT employee
  management ACL; it does not export salary data. Anonymous, revoked,
  out-of-scope and Core-outage calls must fail closed.
- Core salary detail-rate/amount history is imported into Payroll once.
  Previously bootstrapped Payroll databases require a separate tracked backfill
  before the new route serves data; no post-migration Core salary table writes.
- This is a salary READ extraction, not HR employee ownership or rank-review cutover.

## Production acceptance and team workflow

1. One authoritative writer per bounded context (DB fence), with the legacy
   Core route disabled to prevent two simultaneous writes.
2. Immutable, versioned source/outbox/receipt and replay on an existing DB
   as well as a clean DB. Complete reconciliation before rollback/handback.
3. Live JWT role/account revocation checks, exact Admin/TC/CM/CS/Teacher scope,
   and no unauthorized /internal/** Gateway route.
4. FE Playwright parity + Docker fault injection before/after each commit,
   cold restart, migrated production-like historical data, no missing events.
5. Do NOT mark service fully extracted simply because a thin Gateway route
   forwards a request back to Core. Document remaining Core dependencies.
6. Contributions should be narrowly scoped to the domain service, its
   versioned Core reference adapter, and route/tests. Integrate through PR
   review into the microservices feature branch; keep main untouched.

Progress distinction: 70/72 is the original Academic/Scheduling acceptance
checkpoint counter, NOT a percentage of all Core code migrated. It remains
unchanged until the separately deferred milestones meet their acceptance gates.
