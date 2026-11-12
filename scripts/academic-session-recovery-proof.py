#!/usr/bin/env python3
"""Construct EXACT real Academic Session candidate proof for disposable CI.

The Core request digest and the Academic immutable event SHA-256 are distinct
hashes. This generator reproduces Java's canonical request and ObjectNode
field order from a validated typed source row. It never contacts or mutates
either database. Caller provides the output as parameters to two independent
PostgreSQL transactions and must verify their exact handback.
"""
import hashlib
import json
import os
import re
from datetime import date, time

def required(name: str) -> str:
    value = os.environ[name]
    if not value:
        raise ValueError(f"Missing {name}")
    return value

def sha256(s: str) -> str:
    return hashlib.sha256(s.encode("utf-8")).hexdigest()

def java_time(value: str) -> str:
    parsed = time.fromisoformat(value)
    if parsed.microsecond != 0:
        raise ValueError("TIME(0) cannot represent fractional seconds")
    return parsed.isoformat(timespec="seconds" if parsed.second else "minutes")

def main() -> None:
    source = json.loads(required("IIG_SOURCE_REQUEST"))
    actor = required("IIG_RECOVERY_ACTOR")
    key = required("IIG_RECOVERY_KEY")
    command = required("IIG_RECOVERY_COMMAND")
    for name, value, max_len in (
        ("actor", actor, 100), ("key", key, 128),
        ("class", source["classId"], 50), ("session", source["sessionId"], 50)
    ):
        if len(value) > max_len or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:-]*", value):
            raise ValueError(f"Unsafe {name}")
    if not re.fullmatch(r"[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}", command):
        raise ValueError("Unsafe command UUID")
    ag = int(required("IIG_AG"))
    tg = int(required("IIG_TG"))
    sg = int(required("IIG_SG"))
    event_id = int(source["expectedEventId"])
    revision = int(source["expectedRevision"])
    if min(ag, tg, sg, event_id, revision) < 1:
        raise ValueError("Writer generations and source versions must be positive")
    day = date.fromisoformat(source["date"]).isoformat()
    start = java_time(source["startTime"])
    end = java_time(source["endTime"])
    if time.fromisoformat(end) <= time.fromisoformat(start):
        raise ValueError("Invalid candidate time interval")
    if source["action"] != "RESCHEDULE" or source["status"] != "PLANNED":
        raise ValueError("Disposable recovery fixture must be a no-op rehearsal")

    request = "\n".join((
        "academic-candidate-v1", source["classId"], source["sessionId"],
        str(event_id), str(revision), "RESCHEDULE", day, start, end, "PLANNED"
    ))
    # Insertion order is deliberately identical to AcademicSessionCandidateRepository.
    event = {
        "commandId": command,
        "operator": actor,
        "requestKey": key,
        "classId": source["classId"],
        "sessionId": source["sessionId"],
        "sourceEventId": event_id,
        "sourceRevision": revision,
        "action": "RESCHEDULE",
        "date": day,
        "startTime": start,
        "endTime": end,
        "status": "PLANNED",
        "academicGeneration": ag,
        "attendanceGeneration": tg,
        "schedulingGeneration": sg,
    }
    canonical = json.dumps(event, ensure_ascii=False, separators=(",", ":"))
    print(json.dumps({
        "requestHash": sha256(request),
        "outboxHash": sha256(canonical),
        "payloadCanonical": canonical,
    }, ensure_ascii=False, separators=(",", ":")))

if __name__ == "__main__":
    main()
