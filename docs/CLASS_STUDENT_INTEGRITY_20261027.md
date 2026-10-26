# Class and Student Management — integrity corrections

This patch changes only Class/Student scheduling, CS class access and per-lesson teacher attendance. It does not calculate payroll or change global authentication.

## Confirmed business rules

1. **Enrollment:** An ACTIVE student may join two classes only if their effective `PLANNED` lessons do not overlap. Time intervals are half-open: 18:00–20:00 and 20:00–22:00 are compatible; 18:00–20:00 and 19:00–21:00 conflict. Ignore finished/closed classes and inactive memberships. Candidate eligibility exposes the same conflict reason as enrollment. The BE rechecks inside an enrollment transaction with a student row lock.
2. **Plan change / reschedule:** Existing member schedules must not become overlapping when new lessons are added or future lessons rescheduled. The BE checks every *new* slot; a historical overlap from old data does not prevent an unrelated unchanged plan from saving.
3. **READY → RUNNING:** Every planned lesson must have an active assigned teacher **and** at least one active CS `CLASS_SUPPORT` covering that exact date and start/end time. Course-wide coverage can be assigned through CM's existing range staffing API.
4. **RUNNING → COMPLETED:** The last lesson must have ended and every active teaching assignment to a planned, already-started lesson must have a saved teacher attendance (PRESENT, ABSENT or LATE). An absent original teacher's archived attendance stays recorded after replacement; the substitute's new assignment must also receive attendance before completion.
5. **Teacher replacement / history:** Historical `CANCELLED` teaching assignments with recorded attendance remain visible, labelled **history/read-only**. CS cannot rewrite an archived assignment. Payroll may join the archived original and active replacement as separate records.
6. **Lesson reschedule / class rights:** If rescheduling a future lesson cancels the course manager's *final* active CS support shift, revoke course-wide manager rights. If that CS was also the class creator, restore `CREATED_BY_CS` scope rather than discarding creator visibility.

## Test coverage

BE unit regression:
- `StudentClassScheduleGuardTest`: overlaps and non-overlap boundaries, eligibility SQL, timetable clash, ordered row locks.
- `ClassLifecycleIntegrityServiceTest`: CS coverage prerequisite and teacher attendance prerequisite.
- `TeacherSessionAttendanceHistoryTest`: archived attendance is returned and cannot be edited.

BE ephemeral PostgreSQL integration:
- `scripts/test-class-integrity.mjs`: real seeded API calls for conflicting student enrollment, adjacent allowed schedule, timetable rollback, missing CS blocking RUNNING, missing teacher attendance blocking COMPLETED, and absent-teacher replacement retaining history with read-only archived entry.
- `.github/workflows/iig-class-integrity.yml`: `mvn test`, Prisma schema validation, Compose migration/seed/bootstrap, integration test, logs on failure. All DB volumes are temporary and destroyed only within GitHub's CI runner.

FE:
- `TeacherAttendanceTab` displays historical attendance without controls.
- `ClassesView` explains RUNNING and COMPLETED prerequisites to the operator.
- FE workflow executes unit tests, lint and production build.

## Expected rollout behavior

There is no database migration in this patch. Existing RUNNING classes do not automatically revert status; however, newly entering RUNNING classes must be fully staffed. Existing classes with missing teacher attendance may fail the COMPLETED transition until CS backfills past lesson attendance. Correct those records through the attendance screen, not directly in database.

**Before merge:** review integration workflow outcome and retry or investigate any reported environment failure. CI does not prove UI layout or every payroll integration path. FE #17 / BE #8 are separate, currently unmerged browser E2E CI PRs; integrate the new scenarios with those after review.
