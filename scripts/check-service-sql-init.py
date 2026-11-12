#!/usr/bin/env python3
"""Fast CI guard for the default Spring semicolon SQL initializer.

Attendance schema.sql deliberately uses semicolon separators. Other services
may use '@@' with explicit Spring custom configuration; do not generalize this
rule to arbitrary files or SQL dollar-quoted PL/pgSQL migrations.
"""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
path = root / "services/attendance-service/src/main/resources/schema.sql"
content = path.read_text(encoding="utf-8")
if "@@" in content:
    raise SystemExit(f"{path}: invalid '@@' delimiter with default ';' Spring SQL init")
if not content.rstrip().endswith(";"):
    raise SystemExit(f"{path}: final SQL statement must be terminated by ';'")
if "CREATE TABLE IF NOT EXISTS attendance_native_recovery_attempts" not in content:
    raise SystemExit(f"{path}: missing native finality retry state")
print("Attendance Spring schema delimiter preflight: OK")
