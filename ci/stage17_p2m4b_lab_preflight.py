#!/usr/bin/env python3
"""P2-M4B1 host-only READ-ONLY device pre/post-install observations.

Never installs/uninstalls APKs, clears app data, seeds owner records, or arms IPC.
Pre-install 'absent' is evidence of observation, NOT proof of never-armed history.
"""
import argparse
import hashlib
import json
import re
import subprocess
from datetime import datetime, timezone
from pathlib import Path

LAB = "io.github.ioannes78.voica.m4blab"
LEGACY_QA = "io.github.ioannes78.voica.qa"
QA_CERT_SHA256 = "3df9c619ad0d06f2fabb24232420bf056ca2d8ea9413e7f776669a591974f288"


def call(argv):
    done = subprocess.run(argv, capture_output=True, text=True, timeout=20, check=False)
    if done.returncode:
        raise RuntimeError(f"Read-only check failed: {argv[0]} (exit={done.returncode})")
    return done.stdout.strip()


def read_apk_signer(apk, signer):
    # apksigner validates the APK signature before displaying certificate data.
    output = call([signer, "verify", "--print-certs", str(apk)])
    matches = re.findall(
        r"^Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F: ]+)$",
        output, flags=re.MULTILINE,
    )
    if len(matches) != 1:
        raise ValueError("Missing or ambiguous Lab APK signing certificate")
    digest = re.sub(r"[:\s]", "", matches[0]).lower()
    if digest != QA_CERT_SHA256:
        raise ValueError("Lab APK signer mismatch; expected QA-only certificate")
    return digest


def observe(stage, serial, apk, adb="adb", analyzer="apkanalyzer",
            signer="apksigner"):
    if not serial or not serial.strip() or serial.startswith("-"):
        raise ValueError("Explicit ADB device serial is required")
    if not apk.is_file() or not apk.stat().st_size:
        raise ValueError("Exact M4B Lab APK file must exist")
    application_id = call([analyzer, "manifest", "application-id", str(apk)])
    if application_id != LAB:
        raise ValueError("APK application ID mismatch; refusing legacy .qa or production APK")
    signer_digest = read_apk_signer(apk, signer)
    sha256 = hashlib.sha256(apk.read_bytes()).hexdigest()
    state = call([adb, "-s", serial, "get-state"])
    if state != "device":
        raise ValueError("Device must be online and authorized")
    def installed(package):
        paths = call([adb, "-s", serial, "shell", "pm", "path", package])
        if paths and not all(line.startswith("package:") for line in paths.splitlines()):
            raise ValueError("Unexpected pm path response; fail closed")
        return bool(paths)
    lab_installed = installed(LAB)
    legacy_installed = installed(LEGACY_QA)
    if stage == "before" and lab_installed:
        raise ValueError("Lab already installed: cannot attest pre-install absence")
    if stage == "after" and not lab_installed:
        raise ValueError("Lab not installed: cannot observe post-install identity")
    return {
        "stage": stage,
        "observed_utc": datetime.now(timezone.utc).isoformat(),
        "device_serial": serial,
        "apk_sha256": sha256,
        "apk_application_id": LAB,
        "apk_signer_sha256": signer_digest,
        "lab_installed": lab_installed,
        "legacy_qa_installed_observed_only": legacy_installed,
        "outcome": ("PREINSTALL_ABSENCE_OBSERVED" if stage == "before"
                    else "POSTINSTALL_PRESENCE_OBSERVED"),
        "never_armed_proven": False,
        "owner_seed_authorized": False,
        "ipc_admission_authorized": False,
        "fault_injection_authorized": False,
        "note": "Observational evidence only; prior install/data restore cannot be excluded.",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stage", required=True, choices=("before", "after"))
    parser.add_argument("--serial", required=True, help="Exact adb -s device serial")
    parser.add_argument("--lab-apk", required=True, type=Path)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--apkanalyzer", default="apkanalyzer")
    parser.add_argument("--apksigner", default="apksigner")
    args = parser.parse_args()
    try:
        result = observe(args.stage, args.serial, args.lab_apk, args.adb, args.apkanalyzer, args.apksigner)
    except (ValueError, RuntimeError, OSError, subprocess.TimeoutExpired) as error:
        parser.exit(2, f"DENY_P2_M4B_LAB_PREFLIGHT: {error}\n")
    print(json.dumps(result, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
