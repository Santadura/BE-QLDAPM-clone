#!/usr/bin/env python3
"""Fast preflight for Scheduling's PostgreSQL schema and Docker flag wiring.

Spring's ScriptUtils defaults to splitting on every semicolon. PL/pgSQL
requires semicolons inside dollar-quoted functions, so this service uses the
custom @@ separator ONLY between top-level SQL statements. Fail CI early if
someone reintroduces a single dollar sign, a top-level semicolon, or moves
Scheduling control flags to an unrelated container.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCHEMA = ROOT / "services/scheduling-service/src/main/resources/schema.sql"
APPLICATION = ROOT / "services/scheduling-service/src/main/resources/application.yml"
COMPOSE = ROOT / "docker-compose.microservices.yml"
FLAGS = (
    "SCHEDULING_REFERENCE_RELAY_ENABLED",
    "SCHEDULING_ASSIGNMENT_EXECUTOR_ENABLED",
    "SCHEDULING_ACADEMIC_LEASE_COORDINATOR_ENABLED",
    "SCHEDULING_TEACHER_PREFLIGHT_ENABLED",
    "SCHEDULING_TEACHER_EXECUTOR_ENABLED",
    "SCHEDULING_RECOVERY_ENABLED",
)


def statements(source: str) -> list[str]:
    # Simple PostgreSQL SQL lexer: preserve function bodies and quoted strings.
    parts = []
    buf = []
    i, n = 0, len(source)
    mode, dollar = "normal", ""
    while i < n:
        c = source[i]
        nxt = source[i + 1] if i + 1 < n else ""
        if mode == "line-comment":
            buf.append(c)
            if c == "\n":
                mode = "normal"
        elif mode == "block-comment":
            buf.append(c)
            if c == "*" and nxt == "/":
                buf.append("/")
                i += 1
                mode = "normal"
        elif mode in ("single", "double"):
            quote = "'" if mode == "single" else '"'
            buf.append(c)
            if c == quote and nxt == quote:
                buf.append(nxt)
                i += 1
            elif c == quote:
                mode = "normal"
        elif mode == "dollar":
            if source.startswith(dollar, i):
                buf.append(dollar)
                i += len(dollar) - 1
                mode = "normal"
            else:
                buf.append(c)
        else:
            if c == "-" and nxt == "-":
                buf.extend(("-", "-"))
                i += 1
                mode = "line-comment"
            elif c == "/" and nxt == "*":
                buf.extend(("/", "*"))
                i += 1
                mode = "block-comment"
            elif c in ("'", '"'):
                buf.append(c)
                mode = "single" if c == "'" else "double"
            elif c == "$":
                import re
                match = re.match(r"\$[A-Za-z0-9_]*\$", source[i:])
                if not match:
                    raise AssertionError(
                        f"Unpaired PostgreSQL dollar at byte offset {i}"
                    )
                dollar = match.group(0)
                buf.append(dollar)
                i += len(dollar) - 1
                mode = "dollar"
            elif source.startswith("@@", i):
                statement = "".join(buf).strip()
                if statement:
                    parts.append(statement)
                buf.clear()
                i += 1
            elif c == ";":
                raise AssertionError(
                    f"Top-level SQL semicolon at byte offset {i}; use @@"
                )
            else:
                buf.append(c)
        i += 1

    if mode not in ("normal", "line-comment"):
        raise AssertionError(f"Unterminated SQL {mode!r} block")
    if "".join(buf).strip():
        raise AssertionError("Unterminated SQL statement without @@ separator")
    return parts


def main() -> None:
    schema = SCHEMA.read_text(encoding="utf-8")
    app = APPLICATION.read_text(encoding="utf-8")
    compose = COMPOSE.read_text(encoding="utf-8")
    if 'separator: "@@"' not in app:
        raise AssertionError("Scheduling Spring SQL initializer must use @@")
    parsed = statements(schema)
    if len(parsed) < 20:
        raise AssertionError(f"Expected >=20 schema SQL statements, found {len(parsed)}")
    if "iig_guard_assignment_event_immutable" not in schema:
        raise AssertionError("Immutable outbox trigger is missing")
    if "iig_guard_assignment_command_state" not in schema:
        raise AssertionError("Command-inbox state trigger is missing")
    service = compose.split("\n  scheduling-service:\n", 1)[1].split(
        "\n  api-gateway:\n", 1
    )[0]
    catalog = compose.split("\n  catalog-service:\n", 1)[1].split(
        "\n  identity-postgres:\n", 1
    )[0]
    backend = compose.split("\n  backend:\n", 1)[1].split(
        "\n  audit-postgres:\n", 1
    )[0]
    for flag in FLAGS:
        if f"      {flag}:" not in service:
            raise AssertionError(f"{flag} is not configured in scheduling-service")
        if f"      {flag}:" in catalog:
            raise AssertionError(f"{flag} is incorrectly configured in catalog-service")
        if f"      {flag}: ${{{flag}:-false}}" not in service:
            raise AssertionError(f"{flag} must be disabled by default in Compose")
    if (
        "      SCHEDULING_TEACHER_REFERENCE_WRITES_ENABLED: "
        "${SCHEDULING_TEACHER_REFERENCE_WRITES_ENABLED:-false}"
    ) not in backend:
        raise AssertionError("Core TEACHING receiver flag must be OFF by default")
    if "SCHEDULING_TEACHER_EXECUTOR_ENABLED:false" not in app:
        raise AssertionError("Scheduling TEACHING executor default is not OFF")
    if (
        "      SCHEDULING_TEACHER_CLAIM_RECOVERY_ENABLED: "
        "${SCHEDULING_TEACHER_CLAIM_RECOVERY_ENABLED:-false}"
    ) not in backend:
        raise AssertionError("Core TC claim recovery must be OFF by default")
    if "iig_guard_teacher_claim_abandon_audit" not in schema:
        raise AssertionError("Immutable Scheduling TC abandonment audit is missing")
    print(
        f"PASS: Scheduling SQL parser: {len(parsed)} top-level SQL statements; "
        "PL/pgSQL function bodies preserved; control flags wired to Scheduling"
    )


if __name__ == "__main__":
    main()
