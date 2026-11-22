#!/usr/bin/env python3
"""P2-M4B1 evidence correlation is read-only and never grants enrollment."""
import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    "m4b_correlate", Path(__file__).with_name("stage17_p2m4b_lab_correlate.py")
)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


def observation(stage="before"):
    return {
        "stage": stage,
        "observed_utc": ("2026-11-21T10:00:00+00:00" if stage == "before"
                         else "2026-11-21T10:02:00+00:00"),
        "device_serial": "device123",
        "apk_sha256": "a" * 64,
        "apk_application_id": m.LAB,
        "apk_signer_sha256": m.QA_CERT_SHA256,
        "lab_installed": stage == "after",
        "legacy_qa_installed_observed_only": True,
        "outcome": ("PREINSTALL_ABSENCE_OBSERVED" if stage == "before"
                    else "POSTINSTALL_PRESENCE_OBSERVED"),
        "never_armed_proven": False,
        "owner_seed_authorized": False,
        "ipc_admission_authorized": False,
        "fault_injection_authorized": False,
    }


class LabCorrelationTest(unittest.TestCase):
    def test_correlated_is_not_an_enrollment(self):
        result = m.correlate(observation(), observation("after"))
        self.assertEqual("OBSERVATIONS_CORRELATED_NOT_AN_ENROLLMENT", result["verdict"])
        self.assertEqual(m.QA_CERT_SHA256, result["apk_signer_sha256"])
        for field in m.NO_AUTHORITY:
            self.assertIs(result[field], False)

    def test_mismatched_or_malformed_evidence_is_denied(self):
        changes = [
            ("device_serial", "other"),
            ("apk_sha256", "b" * 64),
            ("apk_sha256", "malformed"),
            ("apk_application_id", "io.github.ioannes78.voica.qa"),
            ("apk_signer_sha256", "b" * 64),
            ("apk_signer_sha256", None),
            ("lab_installed", False),
            ("outcome", "UNKNOWN"),
            ("observed_utc", "2026-11-21T09:00:00+00:00"),
            ("observed_utc", "2026-11-21T10:02:00"),
            ("never_armed_proven", True),
            ("owner_seed_authorized", True),
            ("ipc_admission_authorized", True),
            ("fault_injection_authorized", True),
            ("fault_injection_authorized", None),
            ("legacy_qa_installed_observed_only", None),
        ]
        for key, value in changes:
            with self.subTest(key=key, value=value):
                after = observation("after")
                after[key] = value
                with self.assertRaises(ValueError):
                    m.correlate(observation(), after)

    def test_wrong_stage_or_missing_authority_is_denied(self):
        before = observation("after")
        with self.assertRaises(ValueError):
            m.correlate(before, observation("after"))
        for field in m.NO_AUTHORITY:
            with self.subTest(field=field):
                after = observation("after")
                del after[field]
                with self.assertRaises(ValueError):
                    m.correlate(observation(), after)

    def test_non_object_and_missing_evidence_denied(self):
        for value in (None, [], {}, "", 7):
            with self.subTest(value=value), self.assertRaises(ValueError):
                m.correlate(value, observation("after"))


if __name__ == "__main__":
    unittest.main()
