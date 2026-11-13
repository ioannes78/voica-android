#!/usr/bin/env python3
"""R17C P2-E: fail-closed static source invariants. Never runs an Android GPU.

This intentionally proves *source contracts*, not native thread affinity,
graphics-fence causality, runtime termination or real-device stability.
"""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
errors = []


def source(relative):
    path = ROOT / relative
    if not path.is_file():
        errors.append(f"missing source file: {relative}")
        return ""
    return path.read_text(encoding="utf-8")


def require(name, ok):
    if not ok:
        errors.append(name)


def ordered(haystack, *needles):
    offset = 0
    for needle in needles:
        where = haystack.find(needle, offset)
        if where < 0:
            return False
        offset = where + len(needle)
    return True


qa = source("app/src/main/java/io/github/ioannes78/voica/Stage17P1LocalLlmActivity.kt")
quarantine = source("app/src/main/java/io/github/ioannes78/voica/Stage17R17CP2DAnrClosure.kt")
app = source("app/src/main/java/io/github/ioannes78/voica/AppLocalLlmRuntime.kt")
native = source("engine/local-llm/src/main/kotlin/io/github/ioannes78/voica/localllm/LiteRtLocalLlmRuntime.kt")
coordinator = source("engine/local-llm/src/main/kotlin/io/github/ioannes78/voica/localllm/LocalLlmRuntimeCoordinator.kt")
backend = source("engine/local-llm/src/main/kotlin/io/github/ioannes78/voica/localllm/LocalLlmBackendPolicy.kt")
gradle = source("engine/local-llm/build.gradle.kts")
p2f_mock = source(
    "engine/local-llm/src/test/kotlin/io/github/ioannes78/voica/localllm/"
    "Stage17P2FMockJniThreadAffinityTest.kt"
)
p2h_mock = source(
    "engine/local-llm/src/test/kotlin/io/github/ioannes78/voica/localllm/"
    "Stage17P2HMockSubmissionBoundaryTest.kt"
)

require("P2-F test-only fake must not import a real LiteRT-LM/JNI API",
        "class Stage17P2FMockJniThreadAffinityTest" in p2f_mock and
        "import com.google.ai.edge.litertlm" not in p2f_mock and
        "Executors.newSingleThreadExecutor" in p2f_mock and
        "onOwnerNonCancellable" in p2f_mock and
        "withContext(NonCancellable) {" in p2f_mock and
        "withContext(dispatcher) { operation() }" in p2f_mock and
        "withContext(NonCancellable + dispatcher) {" not in p2f_mock)
require("P2-H callback boundary mock must stay JVM-test-only and content free",
        "class Stage17P2HMockSubmissionBoundaryTest" in p2h_mock and
        "private enum class P2HPhase" in p2h_mock and
        "SEND_ENTERED" in p2h_mock and "SEND_RETURNED" in p2h_mock and
        "TERMINAL_AWAIT_ENTERED" in p2h_mock and
        "FIRST_EMISSION" in p2h_mock and "RELEASE_BLOCKED" in p2h_mock and
        "JNI_SUBMISSION_NOT_RETURNED_BUT_CALLBACK_OBSERVED" in p2h_mock and
        "class P2HMockSubmission" in p2h_mock and
        "Collections.synchronizedList" in p2h_mock and
        "import com.google.ai.edge.litertlm" not in "\\n".join(
            line for line in p2h_mock.splitlines()
            if line.lstrip().startswith("import ")
        ))
p2i_native = source(
    "engine/local-llm/src/main/kotlin/io/github/ioannes78/voica/localllm/"
    "LocalLlmCpuBoundaryDiagnostic.kt"
)
p2i_test = source(
    "engine/local-llm/src/test/kotlin/io/github/ioannes78/voica/localllm/"
    "Stage17P2ICpuBoundaryLedgerTest.kt"
)
p2i_store = source("app/src/main/java/io/github/ioannes78/voica/Stage17P2ICpuBoundaryStore.kt")
require("P2-I explicit CPU-only instrumentation and JNI boundary order",
        "request.config.backendPolicy == LocalLlmBackendPolicy.CPU" in native and
        ordered(native, "LocalLlmCpuBoundaryPhase.SEND_ENTERED",
                "liveConversation.sendMessageAsync(",
                "LocalLlmCpuBoundaryPhase.SEND_RETURNED",
                "LocalLlmCpuBoundaryPhase.TERMINAL_AWAIT_ENTERED",
                "terminal.await()") and
        "LocalLlmCpuBoundaryPhase.FIRST_EMISSION" in native and
        "LocalLlmCpuBoundaryPhase.TERMINAL_DONE" in native and
        "LocalLlmCpuBoundaryPhase.TERMINAL_ERROR" in native)
require("P2-I content-free bounded ledger and JVM race/cancellation coverage",
        "class LocalLlmCpuBoundaryLedger" in p2i_native and
        "capacity: Int = 32" in p2i_native and
        "enum class LocalLlmCpuBoundaryPhase" in p2i_native and
        "class Stage17P2ICpuBoundaryLedgerTest" in p2i_test and
        "callbackMayRunBeforeSynchronousSendReturns" in p2i_test and
        "cancelWithoutTerminalMustRetainReleaseBlockedEvidence" in p2i_test and
        "import com.google.ai.edge.litertlm" not in p2i_test)
require("P2-I QA-only async persistence and no per-token callback disk/PSS",
        "stage17-p2i-qa-persist" in p2i_store and
        "worker.execute { drain() }" in p2i_store and
        "capacity" in p2i_native and
        "LocalLlmCpuBoundaryObserverRegistry.install(p2iBoundaryStore::record)" in qa and
        "event.phase != LocalLlmRuntimePhase.GENERATION_PROGRESS" in qa and
        "LocalLlmCpuBoundaryObserverRegistry.install(null)" in qa)
p2l_protocol = source("app/src/main/java/io/github/ioannes78/voica/Stage17P2LMockProtocol.kt")
p2l_service = source("app/src/main/java/io/github/ioannes78/voica/Stage17P2LMockProbeService.kt")
p2l_parent = source("app/src/main/java/io/github/ioannes78/voica/Stage17P2LMockCoordinator.kt")
p2l_boot = source("app/src/main/java/io/github/ioannes78/voica/VoicaApplication.kt")
p2l_test = source("app/src/test/java/io/github/ioannes78/voica/Stage17P2LMockProtocolTest.kt")
require("P2-L is fake-only and cannot call any real LiteRT JNI/Engine entrypoint",
        "const val NATIVE_EXECUTION_ENABLED = false" in p2l_protocol and
        "class Stage17P2LMockProbeService" in p2l_service and
        "class Stage17P2LMockCoordinator" in p2l_parent and
        all("import com.google.ai.edge.litertlm" not in part and
            "LiteRtLocalLlmRuntime(" not in part and
            "Engine(" not in part and
            "createConversation(" not in part and
            "sendMessageAsync(" not in part
            for part in (p2l_protocol, p2l_service, p2l_parent)))
require("P2-L main-process parent evidence durable before Service bind",
        ordered(p2l_parent, 'prefs.edit().putString("last", initial).commit()',
                "if (!durable || closed)", "Stage17P2LMockPhase.BIND_REQUESTED",
                "app.bindService(") and
        'writer.execute { prefs.edit().putString("last", current).commit() }' in p2l_parent and
        "Stage17P2LMockContract.matchesChild(" in p2l_parent and
        "Stage17P2LMockPhase.BINDER_DIED" in p2l_parent and
        "Stage17P2LMockPhase.REPORT_TIMEOUT" in p2l_parent)
require("P2-L child process bypasses heavy AppContainer only for exact QA process",
        "if (isLightweightWorkerProcess()) return" in p2l_boot and
        "stage17P2LShouldSkipHeavyStartup(packageName, processName)" in p2l_boot and
        'applicationId + MODEL_VALIDATOR_PROCESS_SUFFIX' in p2l_boot and
        "Stage17P2LMockContract.allowPackage(applicationId)" in p2l_boot and
        "Stage17P2LMockContract.expectedProcess(applicationId)" in p2l_boot)
require("P2-L mock-only JVM test matrix includes failure and cancellation",
        "class Stage17P2LMockProtocolTest" in p2l_test and
        "callbackBeforeSendReturnIsNotReorderedOrInferred" in p2l_test and
        "submissionNotReturnedCannotManufactureTheMissingReturn" in p2l_test and
        "absentTerminalAndUnacknowledgedCancelAreDistinct" in p2l_test and
        "childProcessAttestationMustMatchExactProcessAndPid" in p2l_test and
        "serviceDeathOrFailedBindPoisonsAttempt" in p2l_test and
        "invalidReleaseCannotBePresentedAsConfirmed" in p2l_test and
        "import com.google.ai.edge.litertlm" not in p2l_test)
require("P2-L QA UI has no GPU/native launch action",
        "P2-L · 独立进程模拟诊断" in qa and
        "p2lMock.start(Stage17P2LMockScenario.NORMAL)" in qa and
        "p2lMock.start(Stage17P2LMockScenario.CALLBACK_BEFORE_RETURN)" in qa)
try:
    ns = "{http://schemas.android.com/apk/res/android}"
    for qa_manifest in (
        "app/src/qa/AndroidManifest.xml",
        "app/src/releaseQa/AndroidManifest.xml",
    ):
        tree = ET.fromstring(source(qa_manifest))
        services = [
            svc for svc in tree.iter("service")
            if svc.get(ns + "name") == ".Stage17P2LMockProbeService"
        ]
        require(f"P2-L only one QA private child service in {qa_manifest}",
                len(services) == 1 and
                services[0].get(ns + "exported") == "false" and
                services[0].get(ns + "process") == ":stage17_mock_probe" and
                services[0].get(ns + "isolatedProcess") is None)
    main_manifest = ET.fromstring(source("app/src/main/AndroidManifest.xml"))
    require("P2-L child service absent from production main manifest",
            not any(svc.get(ns + "name") == ".Stage17P2LMockProbeService"
                    for svc in main_manifest.iter("service")))
except ET.ParseError as exc:
    errors.append(f"P2-L QA manifest XML invalid: {exc}")

require("P2-D QA quarantine must be compile-time enabled",
        "const val GPU_QA_SUSPENDED: Boolean = true" in quarantine)
require("QA quarantine must deny GPU and AUTO, retaining only explicit CPU",
        "policy == LocalLlmBackendPolicy.CPU" in quarantine)
request_start = qa.find("    fun startRequest(")
request_end = qa.find("    fun start(", request_start)
dispatch = qa[request_start:request_end] if request_start >= 0 and request_end > request_start else ""
require("QA P2-D pre-native guard must precede breadcrumb arm and runtime.generate",
        ordered(dispatch,
                "Stage17R17CP2DAnrClosure.blockedReason(config.backendPolicy)",
                "r5CrashBreadcrumbs.arm(",
                "runtime.generate("))
for fn in ("startP2(", "startP2C(", "startP2C2("):
    block_at = qa.find("    fun " + fn)
    block_next = qa.find("\n    fun ", block_at + 1)
    block = qa[block_at:block_next if block_next >= 0 else len(qa)]
    require(f"QA {fn} must guard before any KEEP_SCREEN_ON",
            block_at >= 0 and
            ordered(block, "Stage17R17CP2DAnrClosure.GPU_QA_SUSPENDED",
                    "FLAG_KEEP_SCREEN_ON"))
require("production AUTO+schema => CPU boundary preserved",
        ordered(app, "config.backendPolicy == LocalLlmBackendPolicy.AUTO",
                "config.responseJsonSchema != null",
                "config.copy(backendPolicy = LocalLlmBackendPolicy.CPU)"))
require("local LiteRT-LM dependency pinned to 0.18.0 for forensic comparison",
        'val liteRtLmVersion = "0.18.0"' in gradle)
require("JNI adapter work is dispatched off the main thread",
        "withContext(Dispatchers.IO)" in native)
require("native generation uses async callback terminal",
        ordered(native, "sendMessageAsync(", "terminal.await()"))
require("cancel must signal native and await terminal before release",
        ordered(native, "liveConversation.cancelProcess()", "terminal.await()",
                "conversation?.let", "activeConversation.close()", "activeEngine.close()"))
require("ambiguous native release must remain quarantined",
        "QuarantinedNativeSession" in native and
        "LocalLlmRuntimePhase.RELEASE_UNCONFIRMED" in native)
require("single owner mutex must still guard one heavy inference",
        ordered(coordinator, "private val ownerMutex = Mutex()", "ownerMutex.tryLock()",
                "attempt("))
require("sticky poisoning after uncertain release preserved",
        "poisoned.set(true)" in coordinator and
        "LocalLlmRuntimeState.POISONED" in coordinator)
require("CPU fallback must not occur from generation-stage failures",
        "if (phase == LocalLlmFailurePhase.GENERATING) return false" in backend)

try:
    manifest = ET.fromstring(
        (ROOT / "engine/local-llm/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
    )
    namespace = "{http://schemas.android.com/apk/res/android}"
    libraries = {
        (node.get(namespace + "name"), node.get(namespace + "required"))
        for node in manifest.iter("uses-native-library")
    }
    for library in ("libOpenCL.so", "libvndksupport.so"):
        require(f"optional Android native library declaration missing: {library}",
                (library, "false") in libraries)
except (OSError, ET.ParseError) as exc:
    errors.append(f"Android native-library manifest parse failed: {exc}")

if errors:
    print("P2-E static contract FAILED:", file=sys.stderr)
    for error in errors:
        print(f" - {error}", file=sys.stderr)
    sys.exit(1)

print("P2-E static source contracts PASS (no device/native execution).")
print("AUDIT WARNING: Dispatchers.IO does not guarantee one OS thread across suspensions.")
print("AUDIT WARNING: static matching cannot prove GPU/HWUI synchronization root cause.")
print("AUDIT WARNING: release resources and JNI affinity remain real-device UNVERIFIED.")
