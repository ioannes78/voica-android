#!/usr/bin/env python3
"""P2-M4B1 non-native, non-destructive QA fixture boundary (source-level)."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
main = root / "app/src/main"
qa = root / "app/src/qa/AndroidManifest.xml"
releaseqa = root / "app/src/releaseQa/AndroidManifest.xml"
lab = root / "app/src/m4bLab/AndroidManifest.xml"
sources = [main / "java/io/github/ioannes78/voica" / f"Stage17P2M4B{f}.kt"
           for f in ("Contract", "ParentService", "ChildService", "Journal", "ControllerJournal", "Controller")]
for path in sources:
    assert path.is_file(), f"Missing QA fixture: {path}"
    data = path.read_text()
    for forbidden in ("com.google.ai.edge.litertlm", "System.loadLibrary",
                      "killProcess(", "Engine(", "Conversation("):
        assert forbidden not in data, f"Forbidden M4B native/fault behavior: {path}: {forbidden}"
ack_policy = main / "java/io/github/ioannes78/voica/Stage17P2M4BChildAckPolicy.kt"
assert ack_policy.is_file()
ack = ack_policy.read_text()
for required in ("senderUid == actualUid", "!alreadyAccepted", "admissionSent",
                 "suppliedModel == Stage17P2M4BContract.FAKE_MODEL",
                 "childProcess(packageName)", "suppliedNonce == nonce"):
    assert required in ack, f"Missing one-shot child ACK guard: {required}"
assert "Stage17P2M4BChildAckPolicy.accepts(" in sources[1].read_text()
assert "Stage17P2M4BChildAckPolicyTest" in (
    root / "app/src/test/java/io/github/ioannes78/voica/Stage17P2M4BChildAckPolicyTest.kt"
).read_text()
preflight = (root / "ci/stage17_p2m4b_lab_preflight.py").read_text()
for expected in ('PREINSTALL_ABSENCE_OBSERVED', 'POSTINSTALL_PRESENCE_OBSERVED',
                 '"never_armed_proven": False', '"owner_seed_authorized": False',
                 '"ipc_admission_authorized": False', '"fault_injection_authorized": False',
                 '"manifest", "application-id"', '"pm", "path"'):
    assert expected in preflight, f"Missing non-authoritative Lab preflight boundary: {expected}"
assert 'read_apk_signer(apk, signer)' in preflight
assert '[signer, "verify", "--print-certs", str(apk)]' in preflight
assert 'signer_digest = read_apk_signer(apk, signer)' in preflight
assert '"apk_signer_sha256": signer_digest' in preflight
for forbidden in ('"install"', '"uninstall"', '"clear"', 'KEY_SEEDED', 'VERIFIED_NEVER_ARMED'):
    assert forbidden not in preflight, f"Preflight must not modify device or grant ownership: {forbidden}"
correlator = (root / "ci/stage17_p2m4b_lab_correlate.py").read_text()
for expected in ('OBSERVATIONS_CORRELATED_NOT_AN_ENROLLMENT',
                 '"owner_seed_authorized": False', '"ipc_admission_authorized": False',
                 '"fault_injection_authorized": False', '"never_armed_proven": False',
                 'Device serial mismatch', 'APK SHA-256 mismatch',
                 'Observations are not chronologically ordered'):
    assert expected in correlator, f"Missing fail-closed observation correlation: {expected}"
assert 'value.get("apk_signer_sha256") != QA_CERT_SHA256' in correlator
assert '"apk_signer_sha256": QA_CERT_SHA256' in correlator
for forbidden in ('subprocess', 'adb ', 'KEY_SEEDED', 'VERIFIED_NEVER_ARMED', 'install(', 'uninstall('):
    assert forbidden not in correlator, f"Correlator must not mutate device or Journal: {forbidden}"
assert 'test_stage17_p2m4b_lab_correlate.py' in (
    root / ".github/workflows/android-ci.yml"
).read_text()
assert "FAULT_INJECTION_ENABLED = false" in sources[0].read_text()
assert "IPC_ADMISSION_ENABLED = false" in sources[0].read_text()
for idx in (1, 2, 5):
    assert "sendingUid" in sources[idx].read_text(), "Messenger sender UID must be checked"
admission_policy = main / "java/io/github/ioannes78/voica/Stage17P2M4BAdmissionPolicy.kt"
assert admission_policy.is_file()
admission = admission_policy.read_text()
for required in ('isLabPackage(appId)', 'processName == appId',
                 'ipcAdmissionEnabled', 'trustedEnrollment',
                 '!Stage17P2M4BCodec.blocked(ownerRecord)'):
    assert required in admission, f"Missing Lab-only owner admission gate: {required}"
assert (root / "app/src/test/java/io/github/ioannes78/voica/Stage17P2M4BAdmissionPolicyTest.kt").is_file()
controller = sources[-1].read_text()
assert 'Stage17P2M4BAdmissionPolicy.canAdmit(' in controller
assert 'trustedEnrollment = false' in controller, "B1 must not infer enrollment from journal or package"
journal = sources[-2].read_text()
codec = sources[-3].read_text()
for required in ("commitAndVerify(", "val restored = try { recovered() }", "writeUncertain = true", "decodePersistedSnapshot(snapshot)"):
    assert required in journal, f"Missing fail-closed durable readback: {required}"
assert "decodePersistedSnapshot(fields:" in codec
for required in ("linkToDeath(", "PARENT_BINDER_DIED", "journal.claim(", "journal.transition(", "onServiceDisconnected", "DENY_NO_DURABLE_OWNER_AUTHORIZATION"):
    assert required in controller, f"Missing fail-closed controller IPC contract: {required}"
read_only = main / "java/io/github/ioannes78/voica/Stage17P2M4BReadOnlyActivity.kt"
assert read_only.is_file()
page = read_only.read_text()
for forbidden in ("requestAdmission(", "bindService(", "killProcess(", "putBoolean(", "KEY_SEEDED", ".clear(", ".remove("):
    assert forbidden not in page, f"Read-only QA page must not mutate ownership: {forbidden}"
assert "readOnlyDryRun()" in page
probe_source = main / "java/io/github/ioannes78/voica/Stage17P2M4BBinderProbeActivity.kt"
probe_policy = main / "java/io/github/ioannes78/voica/Stage17P2M4BProbePolicy.kt"
probe_test = root / "app/src/test/java/io/github/ioannes78/voica/Stage17P2M4BProbePolicyTest.kt"
assert all(p.is_file() for p in (probe_source, probe_policy, probe_test))
probe_page = probe_source.read_text()
probe_rules = probe_policy.read_text()
assert "Stage17P2M4BBinderProbeActivity::class.java" in page
assert "isLabPackage(packageName)" in page
assert "Stage17P2M4BParentService::class.java" in probe_page
assert "WHAT_PROBE_PING" in probe_page and "WHAT_PROBE_PONG" in probe_page
assert "Stage17P2M4BProbePolicy.acceptsPong(" in probe_page
assert "Stage17P2M4BProbePolicy.acceptsPing(" in sources[1].read_text()
assert 'reportedParentPid = data.getInt(contract.EXTRA_PID, -1)' in probe_page
assert 'controllerPid = Process.myPid()' in probe_page
assert 'P2-M4B1_LAB_BINDER_PROBE_V1' in probe_page
assert 'proof_scope=ONE_OBSERVED_BINDER_EXCHANGE_ONLY' in probe_page
assert 'Copy local probe report' in probe_page
assert 'ui.removeCallbacks(timeout)' in probe_page
assert 'reportedParentPid > 0 && controllerPid != reportedParentPid' in probe_rules
assert "WHAT_PROBE_PING" in sources[0].read_text()
assert "WHAT_PROBE_PONG" in sources[0].read_text()
for required in ("isLabPackage(packageName)", "senderUid == actualUid",
                 "reportedUid == actualUid", "token == expectedToken",
                 "model == Stage17P2M4BContract.FAKE_MODEL", "!parentArmed"):
    assert required in probe_rules, f"Missing read-only Binder probe identity requirement: {required}"
for forbidden in ("Stage17P2M4BControllerJournal", "Stage17P2M4BController(",
                  "requestAdmission(", "WHAT_ARM", "WHAT_CHILD_ADMIT",
                  "killProcess(", "System.loadLibrary", ".clear(", "KEY_SEEDED"):
    assert forbidden not in probe_page, f"Lab liveness probe must not arm or mutate owner: {forbidden}"
forbidden_prod = qa.read_text() + releaseqa.read_text()
assert "Stage17P2M4BBinderProbeActivity" not in forbidden_prod
assert 'android:name=".Stage17P2M4BBinderProbeActivity"' in lab.read_text()
assert 'android:exported="false"' in lab.read_text()
assert "Stage17P2M4BReadOnlyActivity" in (main / "java/io/github/ioannes78/voica/Stage16BQaHubActivity.kt").read_text()
for path in (qa, releaseqa, lab):
    data = path.read_text()
    assert 'android:name=".Stage17P2M4BReadOnlyActivity"' in data
    assert 'android:exported="false"' in data
    for name, process in (("Stage17P2M4BParentService", "stage17_m4b_parent"),
                          ("Stage17P2M4BChildService", "stage17_m4b_child")):
        assert f'android:name=".{name}"' in data
        assert f'android:process=":{process}"' in data
        assert 'android:exported="false"' in data
gradle = (root / "app/build.gradle.kts").read_text()
assert 'create("m4bLab")' in gradle
assert 'applicationIdSuffix = ".m4blab"' in gradle
assert '"io.github.ioannes78.voica.m4blab"' in sources[0].read_text()
assert '":stage17_m4b_parent"' in lab.read_text()
lab_data = lab.read_text()
assert 'android:name=".MainActivity" tools:node="remove"' in lab_data
assert 'android:name=".Stage17P2M4BReadOnlyActivity"' in lab_data
assert 'android:exported="true"' in lab_data
assert lab_data.count('android.intent.category.LAUNCHER') == 1
assert 'Stage16BQaHubActivity' not in lab_data
assert 'isLabPackage(applicationId) && processName == applicationId' in (main / "java/io/github/ioannes78/voica/VoicaApplication.kt").read_text()
lab_verify = (root / "ci/verify-stage17-p2m4b-lab-apk.sh").read_text()
assert 'io.github.ioannes78.voica.m4blab' in lab_verify
assert 'Stage17P2M4BReadOnlyActivity' in lab_verify
assert 'NO UPLOAD' in lab_verify
ci_workflow = (root / ".github/workflows/android-ci.yml").read_text()
assert ':app:assembleM4bLab' in ci_workflow
assert 'ci/verify-stage17-p2m4b-lab-apk.sh' in ci_workflow
assert 'IPC_ADMISSION_ENABLED = false' in sources[0].read_text()
assert 'FAULT_INJECTION_ENABLED = false' in sources[0].read_text()
production = (main / "AndroidManifest.xml").read_text()
assert "Stage17P2M4BParentService" not in production
assert "Stage17P2M4BChildService" not in production
assert "Stage17P2M4BReadOnlyActivity" not in production
application = (main / "java/io/github/ioannes78/voica/VoicaApplication.kt").read_text()
for suffix in ("parentProcess(applicationId)", "childProcess(applicationId)"):
    assert suffix in application, "Lightweight QA process must not load AppContainer"
assert 'stage17_p2m1_mock_owner_journal' not in "\n".join(p.read_text() for p in sources)
print("P2-M4B1 static process/native/manifest/poison-isolation gate: PASS")
