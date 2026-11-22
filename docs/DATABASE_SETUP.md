# Database setup — PostgreSQL / Supabase + Prisma

Backend runtime remains **Spring Boot + Spring Data JPA**. Prisma is used for
**schema definition, migration and seed management**.

## Sources of truth

The database is not designed from FE alone.

The current schema is built from two sources, in this order:

1. The approved project ERD/database design.
2. The current `main` branch of `Santadura/FE-QLDAPM-clone`, used to refine
   the ERD where implemented business rules are now more specific.

The original ERD remains the structural foundation, including:

- Roles / Functions / Permissions
- Users / Employees / Certificates
- Courses / Classes / Lessons
- Availabilities
- TeachingSchedules / StaffSchedules
- Students / ClassStudents
- Assignments / Exams / StudentResults
- SalaryRanks / EmployeeSalaries / EmployeeSalaryDetail / StaffSalaries
- AuditLogs

## FE-main refinements

The current FE implementation adds business detail that the original ERD did
not model fully. The database therefore extends the ERD with:

### Student targets

The old `Students.rl_target` and `Students.sw_target` columns are kept for
compatibility with the original design, but the current FE supports targets per
course and target type. The normalized table `student_targets` is the active
model for this workflow.

Examples:

- TOEIC: `LR_TOTAL`, `SW_TOTAL`
- IELTS: `OVERALL_BAND`
- SAT: `TOTAL`
- TOEFL iBT: `OVERALL_1_6`

### Class target requirements

`class_target_requirements` stores the minimum target required by each class.
This supports FE eligibility checks before a student is added to a class.

### Class access scope

`class_access_scopes` represents the FE rule that Admin can manage all
classes while CS only manages assigned classes.

### Class-student history

`class_students` keeps a single historical membership and adds
`inactive_reason` / `inactivated_at` instead of deleting the relationship.

### Availability

The original ERD's availability table is retained and extended with fields
needed by FE main for recurring/weekly availability:

- `day_of_week`
- `availability_type`
- `campus`
- review metadata

### Activity/result rules

Database constraints support:

- Class lifecycle: `DRAFT -> READY -> RUNNING -> COMPLETED -> CLOSED`
- Student statuses: `Active`, `On Leave`, `Graduated`
- Assignment statuses: `OPEN`, `CLOSED`, `CANCELLED`
- Exam statuses: `SCHEDULED`, `COMPLETED`, `CANCELLED`
- A StudentResult references exactly one Assignment or Exam.
- One student has at most one result for the same Assignment or Exam.
- Schedule end time must be after start time.
- Class end date must be on or after start date.

Cross-table workflow rules such as target eligibility, teacher assignment before
grading, or pending teaching activities before class completion are enforced by
the application service layer in addition to database constraints.

## Local PostgreSQL

Requirements:

- Java 17+
- Maven 3.9+
- Node.js 20+
- Docker

```bash
cp .env.example .env
docker compose up -d
npm install
npm run prisma:generate
npm run db:migrate:deploy
npm run db:seed
mvn spring-boot:run
```

Spring Boot reads the root `.env` file.

## Refresh realistic demo data

The demo seed is safe to run against an existing development database:

```bash
npm install
npm run prisma:generate
npm run db:seed
```

The seed now removes only the legacy generated fixtures whose identifiers start
with `auto-*`, then loads realistic `demo-*` fixtures. Records created manually
through the application are not reset by this cleanup.

The realistic fixture set includes additional teachers, CS and Sale staff,
approved recurring availabilities, classes in several lifecycle states, planned
class sessions, teacher/CS/Sale schedules, students with course targets, class
memberships, assignments, exams and payroll examples.

`docker compose up` still uses `scripts/seed-if-empty.js`, so an existing
database is preserved on container restart. Run `npm run db:seed` explicitly
when you intentionally want to refresh the demo fixtures.

## Supabase

Fill the project-specific values in local `.env`:

```properties
DB_URL=jdbc:postgresql://<supabase-host>:5432/postgres?sslmode=require
DB_USERNAME=<database-user>
DB_PASSWORD=<database-password>

DATABASE_URL=postgresql://<pooler-user>:<password>@<pooler-host>:6543/postgres?pgbouncer=true
DIRECT_URL=postgresql://<direct-user>:<password>@<direct-host>:5432/postgres?sslmode=require
```

Then run:

```bash
npm install
npm run prisma:generate
npm run db:migrate:deploy
npm run db:seed
mvn spring-boot:run
```

Never commit real Supabase credentials.

## Migration ownership

Prisma migrations own the physical database schema. Hibernate is configured
with `ddl-auto: validate`, so Spring Boot validates mappings instead of
silently changing tables.

When the Prisma schema changes:

```bash
npm run db:migrate -- --name <migration_name>
```

Commit both `schema.prisma` and the generated migration.

## Development account

Seed data creates:

```text
username: admin
password: 123456
```

Development only; remove or change it before deployment.
