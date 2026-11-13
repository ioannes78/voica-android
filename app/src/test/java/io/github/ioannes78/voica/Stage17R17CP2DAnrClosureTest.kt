package io.github.ioannes78.voica

import io.github.ioannes78.voica.localllm.LocalLlmBackendPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage17R17CP2DAnrClosureTest {
    @Test fun qaGpuAndAutoCannotReachNativeRuntimeAfterConfirmedAnr() {
        assertTrue(Stage17R17CP2DAnrClosure.GPU_QA_SUSPENDED)
        assertTrue(Stage17R17CP2DAnrClosure.allowQaInference(LocalLlmBackendPolicy.CPU))
        assertFalse(Stage17R17CP2DAnrClosure.allowQaInference(LocalLlmBackendPolicy.GPU))
        assertFalse(Stage17R17CP2DAnrClosure.allowQaInference(LocalLlmBackendPolicy.AUTO))
        assertEquals(null, Stage17R17CP2DAnrClosure.blockedReason(LocalLlmBackendPolicy.CPU))
        assertTrue(Stage17R17CP2DAnrClosure.blockedReason(LocalLlmBackendPolicy.GPU)!!.contains("P2-D"))
        assertTrue(Stage17R17CP2DAnrClosure.blockedReason(LocalLlmBackendPolicy.AUTO)!!.contains("AUTO"))
    }

    @Test fun previousProcessGpuAnrClassifiesWithoutAssertingDriverRootCause() {
        val evidence = Stage17R17CP2DAnrClosure.knownExitEvidence(
            previousProcess = true,
            requestedBackend = "GPU", resolvedBackend = "GPU",
            runtimePhase = "GENERATING", exitReasonLabel = "ANR", emissionCount = 0L,
        )
        assertTrue(evidence.contains("CONFIRMED_ANDROID_ANR_DURING_GPU_GENERATING"))
        assertTrue(evidence.contains("emissions=0"))
        assertTrue(evidence.contains("causal_driver_root_unknown"))
        assertEquals(
            "CURRENT_PROCESS_NOT_EXIT_EVIDENCE",
            Stage17R17CP2DAnrClosure.knownExitEvidence(
                previousProcess = false,
                requestedBackend = "GPU", resolvedBackend = "GPU",
                runtimePhase = "GENERATING", exitReasonLabel = "ANR", emissionCount = 0L,
            ),
        )
    }

    @Test fun classifierKeepsOnlyBoundedContentFreeWaitCategories() {
        val exampleTrace = """
            "main" prio=5 tid=1 Native
              native: #00 /apex/libc.so (pthread_cond_wait+2)
              native: #01 /system/libhwui.so (SkCanvas+12)
            "RenderThread" prio=5 tid=44 Native
              native: #00 /system/libsync.so (sync_wait+4)
            "Executor" prio=5 tid=41 Native
              native: #00 /vendor/lib64/libGLESv2.so (adreno_wait+6)
              native: #01 /data/app/liblitertlm.so (work+5)
            Cmd line: com.secret.example
            user transcript: password-123
        """.trimIndent()
        val labels = classifyStage17R5AnrWaitSignals(exampleTrace)
        assertTrue(labels.contains("PTHREAD_COND_WAIT_PRESENT"))
        assertTrue(labels.contains("GRAPHICS_FENCE_WAIT_CANDIDATE"))
        assertTrue(labels.contains("ANDROID_HWUI_PRESENT"))
        assertTrue(labels.contains("GPU_DRIVER_GRAPHICS_SYMBOL_PRESENT"))
        assertTrue(labels.contains("ML_RUNTIME_SYMBOL_PRESENT"))
        assertEquals(labels.size, labels.distinct().size)
        val joined = labels.joinToString()
        assertFalse(joined.contains("secret"))
        assertFalse(joined.contains("password"))
        assertTrue(classifyStage17R5AnrWaitSignals("no matched stack").isEmpty())
    }
}
