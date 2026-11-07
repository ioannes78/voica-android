package io.github.ioannes78.voica

import io.github.ioannes78.voica.localllm.LocalLlmBackend
import io.github.ioannes78.voica.localllm.LocalLlmBackendPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage17R17CP2CNumericQaTest {
    private val qa = Stage17R17CP2CNumericQa

    @Test fun onlyBackendDiffersBetweenCpuAndGpuRequest() {
        val cpu = qa.request(Stage17R17CP2CNumericQa.Case.CPU)
        val gpu = qa.request(Stage17R17CP2CNumericQa.Case.GPU)
        assertEquals(cpu.prompt, gpu.prompt)
        assertEquals(cpu.promptSha256, gpu.promptSha256)
        assertEquals(cpu.estimatedPromptTokens, gpu.estimatedPromptTokens)
        assertEquals(cpu.config.copy(backendPolicy = LocalLlmBackendPolicy.GPU), gpu.config)
        assertEquals(4_000, gpu.config.maxNumTokens)
        assertEquals(512, gpu.config.maxOutputTokens)
        assertNull(gpu.config.responseJsonSchema)
        assertFalse(gpu.config.speculativeDecoding)
        assertEquals(64, gpu.promptSha256.length)
        assertTrue(cpu.estimatedPromptTokens + 512 + 192 + 256 <= 4_000)
    }

    @Test fun knownNumericCorruptionsAreFoundAndDoNotReportPass() {
        val bad = "[1] 877% [2] 标普50500 20015年 20205年 50% " +
            "[3] FDA 1995年 2023年 2223个 " +
            "[4] 15个月 100万美元 700万美元 [5] 3%到8%"
        val result = qa.assess(bad)
        assertTrue(result.requiresReview)
        assertEquals(8, result.knownErrors.size)
        assertTrue(result.missing.isNotEmpty())
    }

    @Test fun completeReferenceStillNeedsManualSemanticReview() {
        val accurate = "[1] GPT-4.5测试87%。\n" +
            "[2] 标普500，从2015至2025年初接近50%。\n" +
            "[3] FDA数量从1995年1个到2023年223个。\n" +
            "[4] 15个月从1000万美元至7000万美元。\n" +
            "[5] 时间缩短三到八成。"
        val result = qa.assess(accurate)
        assertEquals(5, result.matched)
        assertEquals(5, result.total)
        assertTrue(result.knownErrors.isEmpty())
        assertFalse(result.requiresReview)
        assertTrue(result.summary().contains("MANUAL_REVIEW_STILL_REQUIRED"))
    }

    @Test fun missingNumbersAndMixedLanguageAreReviewNotAutomaticTruth() {
        val result = qa.assess("[1] 87%\n[2] billions\n[3] レポート\n[4] mọi")
        assertEquals(1, result.matched)
        assertTrue(result.requiresReview)
        assertEquals(3, result.languageSignals.size)
        assertTrue(result.missing.size >= 4)
    }

    @Test fun actualBackendAndReleaseAreRequiredBeforeAcceptingRun() {
        val cpu = Stage17R17CP2CNumericQa.Case.CPU
        val gpu = Stage17R17CP2CNumericQa.Case.GPU
        assertTrue(qa.runtimePass(cpu, LocalLlmBackend.CPU, false, true, 123L))
        assertTrue(qa.runtimePass(gpu, LocalLlmBackend.GPU, false, true, 234L))
        assertFalse(qa.runtimePass(gpu, LocalLlmBackend.CPU, false, true, 234L))
        assertFalse(qa.runtimePass(gpu, LocalLlmBackend.GPU, true, true, 234L))
        assertFalse(qa.runtimePass(gpu, LocalLlmBackend.GPU, false, false, 234L))
        assertFalse(qa.runtimePass(gpu, LocalLlmBackend.GPU, false, true, null))
    }

    @Test fun rawOutputsAndPromptShaAreRetainedInExport() {
        val result = qa.assess("877%")
        val text = qa.report(
            listOf(Stage17R17CP2CNumericQa.Run(
                Stage17R17CP2CNumericQa.Case.GPU, "877%",
                "resolved=GPU\npeakPssKb=1000", result,
            )),
            blocked = false, shortProbePassed = true,
        )
        assertTrue(text.contains("877%"))
        assertTrue(text.contains("87->877%"))
        assertTrue(text.contains("peakPssKb=1000"))
        assertTrue(text.contains("promptSHA=" + qa.promptSha256))
        assertTrue(text.contains("FROZEN INPUT"))
    }
}
