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
assert "FAULT_INJECTION_ENABLED = false" in sources[0].read_text()
assert "IPC_ADMISSION_ENABLED = false" in sources[0].read_text()
for idx in (1, 2, 5):
    assert "sendingUid" in sources[idx].read_text(), "Messenger sender UID must be checked"
controller = sources[-1].read_text()
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
