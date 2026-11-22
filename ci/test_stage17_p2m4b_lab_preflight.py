#!/usr/bin/env python3
"""Unit-level tests: M4B Lab host preflight performs read-only observations only."""
import importlib.util
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("m4b_lab_preflight", HERE / "stage17_p2m4b_lab_preflight.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class LabPreflightTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.apk = Path(self.temp.name) / "app-m4bLab.apk"
        self.apk.write_bytes(b"test-fixture-not-a-real-apk")
        self.commands = []

    def fake_call(self, lab_installed=False, qa_installed=True, actual_id=module.LAB,
                  certificate=module.QA_CERT_SHA256):
        def run(argv):
            self.commands.append(argv)
            if argv[0] == "apkanalyzer":
                return actual_id
            if argv[0] == "apksigner":
                return "Signer #1 certificate SHA-256 digest: " + certificate
            if argv[-1] == "get-state":
                return "device"
            if argv[-2:] == ["path", module.LAB]:
                return "package:/data/app/lab/base.apk" if lab_installed else ""
            if argv[-2:] == ["path", module.LEGACY_QA]:
                return "package:/data/app/qa/base.apk" if qa_installed else ""
            raise AssertionError(f"Unexpected command: {argv}")
        return run

    def assert_only_reads(self):
        for argv in self.commands:
            self.assertTrue(
                argv[0] in ("apkanalyzer", "apksigner") or
                argv[1:4] == ["-s", "serial1", "get-state"] or
                argv[1:4] == ["-s", "serial1", "shell"] and argv[4:6] == ["pm", "path"],
                argv,
            )
        self.assertEqual(5, len(self.commands))

    def test_before_absence_records_observation_but_never_authorizes(self):
        with patch.object(module, "call", side_effect=self.fake_call()):
            result = module.observe("before", "serial1", self.apk)
        self.assertFalse(result["lab_installed"])
        self.assertTrue(result["legacy_qa_installed_observed_only"])
        self.assertFalse(result["never_armed_proven"])
        self.assertFalse(result["owner_seed_authorized"])
        self.assertFalse(result["ipc_admission_authorized"])
        self.assertFalse(result["fault_injection_authorized"])
        self.assert_only_reads()

    def test_after_presence_is_not_never_armed_proof(self):
        with patch.object(module, "call", side_effect=self.fake_call(lab_installed=True)):
            result = module.observe("after", "serial1", self.apk)
        self.assertEqual("POSTINSTALL_PRESENCE_OBSERVED", result["outcome"])
        self.assertFalse(result["never_armed_proven"])
        self.assert_only_reads()

    def test_before_rejects_preexisting_lab_without_mutation(self):
        with patch.object(module, "call", side_effect=self.fake_call(lab_installed=True)):
            with self.assertRaisesRegex(ValueError, "already installed"):
                module.observe("before", "serial1", self.apk)
        self.assert_only_reads()

    def test_after_rejects_missing_lab_without_mutation(self):
        with patch.object(module, "call", side_effect=self.fake_call()):
            with self.assertRaisesRegex(ValueError, "not installed"):
                module.observe("after", "serial1", self.apk)
        self.assert_only_reads()

    def test_rejects_legacy_qa_apk_before_contacting_device(self):
        with patch.object(module, "call", side_effect=self.fake_call(actual_id=module.LEGACY_QA)):
            with self.assertRaisesRegex(ValueError, "application ID mismatch"):
                module.observe("before", "serial1", self.apk)
        self.assertEqual(1, len(self.commands))

    def test_wrong_signer_is_rejected_before_device_read(self):
        with patch.object(module, "call", side_effect=self.fake_call(certificate="b" * 64)):
            with self.assertRaisesRegex(ValueError, "signer mismatch"):
                module.observe("before", "serial1", self.apk)
        self.assertEqual(["apkanalyzer", "apksigner"], [cmd[0] for cmd in self.commands])

    def test_signer_is_required_and_must_be_unambiguous(self):
        for certificate in ("", "garbage", module.QA_CERT_SHA256 + "\\nSigner #1 certificate SHA-256 digest: " + module.QA_CERT_SHA256):
            self.commands.clear()
            with self.subTest(certificate=certificate):
                with patch.object(module, "call", side_effect=self.fake_call(certificate=certificate)):
                    with self.assertRaises(ValueError):
                        module.observe("before", "serial1", self.apk)
                self.assertEqual(["apkanalyzer", "apksigner"], [cmd[0] for cmd in self.commands])

    def test_serial_required(self):
        with self.assertRaisesRegex(ValueError, "serial"):
            module.observe("before", "-s", self.apk)
        self.assertFalse(self.commands)


if __name__ == "__main__":
    unittest.main()
