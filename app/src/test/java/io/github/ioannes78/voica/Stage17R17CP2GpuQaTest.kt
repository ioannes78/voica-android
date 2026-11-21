package io.github.ioannes78.voica

import io.github.ioannes78.voica.localllm.LocalLlmBackend
import io.github.ioannes78.voica.localllm.LocalLlmBackendPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage17R17CP2GpuQaTest {
    private val source = Stage17R17CDirectQa.Source(
        recordingId = "test-1",
        displayName = "EHBZKFZH6GIZG.m4a",
        durationMs = 507_611,
        transcriptionId = "test-transcription",
        revisionId = null,
        inputDigest = "a".repeat(64),
        transcriptText = "新药研发缩短三到八成；这是风险讨论。\n\n之后讨论工业智能。",
        transcriptSha256 = "b".repeat(64),
        unitCount = 2,
        evidenceRows = listOf("U00001 | S00001 | 三到八成"),
    )

    private fun safeReason(
        testCase: Stage17R17CP2GpuQa.Case = Stage17R17CP2GpuQa.Case.SHORT_4K,
        ack: Boolean = true,
        shortPass: Boolean = false,
        failed: Boolean = false,
        foreground: Boolean = true,
        resumed: Boolean = true,
        interactive: Boolean = true,
        keyguard: Boolean = false,
        locked: Boolean = false,
        idle: Boolean = true,
    ) = Stage17R17CP2GpuQa.preflightReason(
        testCase = testCase,
        acknowledgedRisk = ack,
        shortProbePassed = shortPass,
        previousFailureInSession = failed,
        foreground = foreground,
        activityResumed = resumed,
        interactive = interactive,
        keyguardShowing = keyguard,
        deviceLocked = locked,
        runtimeIdle = idle,
    )

    @Test fun shortProbeIsPureGpuAndBoundedWithoutNativeSchema() {
        val probe = Stage17R17CP2GpuQa.shortProbe()
        assertEquals(Stage17R17CP2GpuQa.Case.SHORT_4K, probe.testCase)
        assertEquals(LocalLlmBackendPolicy.GPU, probe.config.backendPolicy)
        assertEquals(4_000, probe.config.maxNumTokens)
        assertEquals(256, probe.config.maxOutputTokens)
        assertNull(probe.config.responseJsonSchema)
        assertFalse(probe.config.speculativeDecoding)
        assertTrue(probe.prompt.contains(Stage17R17CP2GpuQa.SHORT_MARKER))
        assertEquals(64, probe.promptSha256.length)
        assertTrue(probe.estimatedPromptTokens + 256 + 192 + 256 <= 4000)
    }

    @Test fun fullGpuUsesExactCpuPromptSamplingAndContextExceptBackend() {
        val cpu = Stage17R17CDirectQa.request(
            source, Stage17R17CDirectQa.Tier.C8K_2048,
        )
        val gpu = Stage17R17CP2GpuQa.fullTranscriptProbe(source)
        assertEquals(cpu.prompt, gpu.prompt)
        assertEquals(cpu.promptSha256, gpu.promptSha256)
        assertEquals(cpu.estimatedPromptTokens, gpu.estimatedPromptTokens)
        assertEquals(cpu.config.copy(backendPolicy = LocalLlmBackendPolicy.GPU), gpu.config)
        assertEquals("a".repeat(64), gpu.sourceDigest)
        assertEquals("b".repeat(64), gpu.transcriptSha256)
        assertEquals(8000, gpu.config.maxNumTokens)
        assertEquals(2048, gpu.config.maxOutputTokens)
        assertNull(gpu.config.responseJsonSchema)
    }

    @Test fun allHighRiskAndLifecycleBlocksAreEnforced() {
        assertNull(safeReason())
        assertTrue(safeReason(ack = false)!!.contains("风险"))
        assertTrue(safeReason(failed = true)!!.contains("失败"))
        assertTrue(safeReason(foreground = false)!!.contains("前台"))
        assertTrue(safeReason(resumed = false)!!.contains("前台"))
        assertTrue(safeReason(interactive = false)!!.contains("前台"))
        assertTrue(safeReason(keyguard = true)!!.contains("前台"))
        assertTrue(safeReason(locked = true)!!.contains("前台"))
        assertTrue(safeReason(idle = false)!!.contains("IDLE"))
        assertTrue(
            safeReason(
                testCase = Stage17R17CP2GpuQa.Case.REAL_8K_2048,
                shortPass = false,
            )!!.contains("短探针"),
        )
        assertNull(
            safeReason(
                testCase = Stage17R17CP2GpuQa.Case.REAL_8K_2048,
                shortPass = true,
            ),
        )
    }

    @Test fun onlyRealGpuWithMarkerAndClosedRuntimeCanPassShortGate() {
        assertTrue(Stage17R17CP2GpuQa.shortProbePass(
            "R17C_GPU_OK", LocalLlmBackend.GPU, false, 999_000L, true,
        ))
        assertFalse(Stage17R17CP2GpuQa.shortProbePass(
            "R17C_GPU_OK", LocalLlmBackend.CPU, false, 999_000L, true,
        ))
        assertFalse(Stage17R17CP2GpuQa.shortProbePass(
            "R17C_GPU_OK", LocalLlmBackend.GPU, true, 999_000L, true,
        ))
        assertFalse(Stage17R17CP2GpuQa.shortProbePass(
            "R17C_GPU_OK", LocalLlmBackend.GPU, false, null, true,
        ))
        assertFalse(Stage17R17CP2GpuQa.shortProbePass(
            "R17C_GPU_OK", LocalLlmBackend.GPU, false, 999_000L, false,
        ))
        assertFalse(Stage17R17CP2GpuQa.shortProbePass(
            "总结完成", LocalLlmBackend.GPU, false, 999_000L, true,
        ))
    }

    @Test fun longGpuQaHasMemoryPreflight() {
        assertFalse(Stage17R17CP2GpuQa.longMemoryReady(0L, false))
        assertFalse(Stage17R17CP2GpuQa.longMemoryReady(
            Stage17R17CP2GpuQa.LONG_MIN_FREE_BYTES - 1L, false,
        ))
        assertFalse(Stage17R17CP2GpuQa.longMemoryReady(
            Stage17R17CP2GpuQa.LONG_MIN_FREE_BYTES * 2L, true,
        ))
        assertTrue(Stage17R17CP2GpuQa.longMemoryReady(
            Stage17R17CP2GpuQa.LONG_MIN_FREE_BYTES, false,
        ))
    }

    @Test fun diagnosticReportIsSourceBoundAndFlagsKnownNumberProblem() {
        val report = Stage17R17CP2GpuQa.report(
            source = source,
            runs = listOf(Stage17R17CP2GpuQa.Run(
                testCase = Stage17R17CP2GpuQa.Case.REAL_8K_2048,
                rawOutput = "新药研究缩短3%到8%。",
                runtimeDiagnostic = "resolved=GPU\npeakPssKb=12345",
                promptSha256 = "c".repeat(64),
            )),
            shortProbePassed = true,
            sessionBlocked = false,
        )
        assertTrue(report.contains("inputDigest=" + source.inputDigest))
        assertTrue(report.contains(source.transcriptText))
        assertTrue(report.contains("peakPssKb=12345"))
        assertTrue(report.contains("FLAG_CRITICAL"))
        assertTrue(report.contains("R17C-P2 GPU"))
        assertTrue(report.contains("RUNTIME_DOES_NOT_EXIST").not())
    }
}
