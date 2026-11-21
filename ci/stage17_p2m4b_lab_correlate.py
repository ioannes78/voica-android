#!/usr/bin/env python3
"""Correlate P2-M4B1 host read-only observations. NEVER enrolls or arms a device."""
import argparse
import json
from datetime import datetime, timezone
from pathlib import Path

LAB = "io.github.ioannes78.voica.m4blab"
NO_AUTHORITY = (
    "never_armed_proven", "owner_seed_authorized",
    "ipc_admission_authorized", "fault_injection_authorized",
)


def parse_observation(value, stage):
    if not isinstance(value, dict):
        raise ValueError("Observation must be a JSON object")
    if value.get("stage") != stage:
        raise ValueError(f"Wrong stage: expected {stage}")
    if value.get("apk_application_id") != LAB:
        raise ValueError("Wrong Lab APK identity")
    outcome = ("PREINSTALL_ABSENCE_OBSERVED" if stage == "before"
               else "POSTINSTALL_PRESENCE_OBSERVED")
    if value.get("outcome") != outcome:
        raise ValueError("Unexpected observation outcome")
    if value.get("lab_installed") is not (stage == "after"):
        raise ValueError("Inconsistent Lab installation state")
    if not isinstance(value.get("legacy_qa_installed_observed_only"), bool):
        raise ValueError("Missing typed historical QA observation")
    for field in NO_AUTHORITY:
        if value.get(field) is not False:
            raise ValueError(f"Forbidden or absent authority field: {field}")
    serial = value.get("device_serial")
    if not isinstance(serial, str) or not serial or serial != serial.strip():
        raise ValueError("Missing or malformed device serial")
    digest = value.get("apk_sha256")
    if not isinstance(digest, str) or len(digest) != 64 or any(
        ch not in "0123456789abcdef" for ch in digest
    ):
        raise ValueError("Invalid APK SHA-256")
    raw = value.get("observed_utc")
    if not isinstance(raw, str):
        raise ValueError("Missing timestamp")
    try:
        stamp = datetime.fromisoformat(raw.replace("Z", "+00:00"))
    except ValueError as exc:
        raise ValueError("Invalid timestamp") from exc
    if stamp.tzinfo is None or stamp.utcoffset().total_seconds() != 0:
        raise ValueError("Timestamp must contain UTC offset")
    return serial, digest, stamp.astimezone(timezone.utc)


def correlate(before, after):
    bserial, bdigest, btime = parse_observation(before, "before")
    aserial, adigest, atime = parse_observation(after, "after")
    if bserial != aserial:
        raise ValueError("Device serial mismatch")
    if bdigest != adigest:
        raise ValueError("APK SHA-256 mismatch")
    if btime >= atime:
        raise ValueError("Observations are not chronologically ordered")
    return {
        "verdict": "OBSERVATIONS_CORRELATED_NOT_AN_ENROLLMENT",
        "apk_application_id": LAB,
        "apk_sha256": bdigest,
        "device_serial": bserial,
        "before_observed_utc": btime.isoformat(),
        "after_observed_utc": atime.isoformat(),
        "never_armed_proven": False,
        "owner_seed_authorized": False,
        "ipc_admission_authorized": False,
        "fault_injection_authorized": False,
        "note": "Observations do not prove installation history or clean owner state.",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--before", type=Path, required=True)
    parser.add_argument("--after", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = correlate(json.loads(args.before.read_text(encoding="utf-8")),
                           json.loads(args.after.read_text(encoding="utf-8")))
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        parser.exit(2, f"DENY_M4B_LAB_EVIDENCE_CORRELATION: {exc}\n")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
