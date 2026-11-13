package io.github.ioannes78.voica

import io.github.ioannes78.voica.localllm.LocalLlmBackend
import io.github.ioannes78.voica.localllm.LocalLlmBackendPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage17R17CP2C2MediumQaTest {
    private val originalUnits = (1..150).map { index ->
        when (index) {
            25 -> "图灵测试用的是 GPT 四点五，高达八十七趴的人把人工智能当作真人。"
            30 -> "标普五百公司财报里提到 AI 的比例在二零一五年几乎是零。"
            45 -> "医疗设备 FDA 批准的数量从一个增加到两百二十三个。"
            57 -> "研究说新药研发时间可以缩短三到八成。"
            75 -> "一座大型数据中心改造只用了一百二十二天。"
            else -> "第" + index + "段围绕人工智能、企业增长、算力需求和具体行业应用进行说明，不添加额外统计数字。"
        }
    }
    private val source = Stage17R17CDirectQa.Source(
        recordingId = "r17c-c2-test",
        displayName = "中等长度对照.wav",
        durationMs = 510_000,
        transcriptionId = "transcription-123",
        revisionId = null,
        inputDigest = "a".repeat(64),
        transcriptText = originalUnits.joinToString("\n\n"),
        transcriptSha256 = "b".repeat(64),
        unitCount = originalUnits.size,
        evidenceRows = originalUnits.mapIndexed { index, text ->
            "U" + (index + 1).toString().padStart(5, '0') + " | S" +
                (index + 1).toString().padStart(5, '0') + " | " + text
        },
    )

    @Test fun selectsCompleteContiguousOriginalSourceParagraphs() {
        val frozen = Stage17R17CP2C2MediumQa.freeze(source)
        assertTrue(frozen.estimate in 1_500..2_000)
        assertTrue(frozen.selectedParagraphs in 1 until originalUnits.size)
        assertEquals(
            originalUnits.take(frozen.selectedParagraphs).joinToString("\n\n"),
            frozen.selectedText,
        )
        assertEquals(frozen.selectedParagraphs, frozen.evidence.size)
        assertTrue(frozen.prompt.contains(frozen.selectedText))
        assertTrue(frozen.prompt.contains("<PARTIAL_TRANSCRIPT>"))
        assertTrue(frozen.estimate + 1_024 + 192 + 256 <= 4_000)
        assertEquals(64, frozen.selectedSha256.length)
        assertEquals(64, frozen.promptSha256.length)
    }

    @Test fun sameFrozenPromptAndSamplingOnlyBackendDiffers() {
        val frozen = Stage17R17CP2C2MediumQa.freeze(source)
        val cpu = Stage17R17CP2C2MediumQa.request(frozen, Stage17R17CP2C2MediumQa.Case.CPU)
        val gpu = Stage17R17CP2C2MediumQa.request(frozen, Stage17R17CP2C2MediumQa.Case.GPU)
        assertEquals(cpu.frozen.promptSha256, gpu.frozen.promptSha256)
        assertEquals(cpu.frozen.selectedText, gpu.frozen.selectedText)
        assertEquals(cpu.config.copy(backendPolicy = LocalLlmBackendPolicy.GPU), gpu.config)
        assertEquals(4_000, cpu.config.maxNumTokens)
        assertEquals(1_024, gpu.config.maxOutputTokens)
        assertNull(gpu.config.responseJsonSchema)
        assertFalse(gpu.config.speculativeDecoding)
    }

    @Test fun rejectsShortSourceRatherThanPaddingOrInventingFacts() {
        val small = source.copy(transcriptText = "五分钟的短录音，只有一条。")
        val error = runCatching { Stage17R17CP2C2MediumQa.freeze(small) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test fun reportsKnownNumericalCorruptionWithoutClaimingTruth() {
        val frozen = Stage17R17CP2C2MediumQa.freeze(source)
        val audit = Stage17R17CP2C2MediumQa.audit(
            frozen,
            "标普50500的图灵测试有877%的人认可，另有日本語レポート和mọi。",
        )
        assertTrue(audit.flagged.contains("87->877%"))
        assertTrue(audit.flagged.contains("500->50500"))
        assertTrue(audit.languageSignals.isNotEmpty())
        assertTrue(audit.concise().contains("MANUAL_REVIEW_REQUIRED"))
    }

    @Test fun backendIdentityAndReleaseRemainRequired() {
        val cpu = Stage17R17CP2C2MediumQa.Case.CPU
        val gpu = Stage17R17CP2C2MediumQa.Case.GPU
        assertTrue(Stage17R17CP2C2MediumQa.runtimePass(cpu, LocalLlmBackend.CPU, false, true, 4))
        assertTrue(Stage17R17CP2C2MediumQa.runtimePass(gpu, LocalLlmBackend.GPU, false, true, 4))
        assertFalse(Stage17R17CP2C2MediumQa.runtimePass(gpu, LocalLlmBackend.CPU, false, true, 4))
        assertFalse(Stage17R17CP2C2MediumQa.runtimePass(gpu, LocalLlmBackend.GPU, true, true, 4))
        assertFalse(Stage17R17CP2C2MediumQa.runtimePass(gpu, LocalLlmBackend.GPU, false, true, null))
        assertFalse(Stage17R17CP2C2MediumQa.runtimePass(gpu, LocalLlmBackend.GPU, false, false, 4))
    }

    @Test fun exportRetainsFrozenSourceIdentityRawTextAndEvidence() {
        val frozen = Stage17R17CP2C2MediumQa.freeze(source)
        val output = "测试结果：877% 仍需要核查"
        val run = Stage17R17CP2C2MediumQa.Run(
            testCase = Stage17R17CP2C2MediumQa.Case.GPU,
            rawOutput = output,
            runtimeDiagnostic = "resolved=GPU\npeakPssKb=123456",
            audit = Stage17R17CP2C2MediumQa.audit(frozen, output),
            promptSha256 = frozen.promptSha256,
        )
        val report = Stage17R17CP2C2MediumQa.report(frozen, listOf(run), false)
        assertTrue(report.contains("sourceDigest=" + source.inputDigest))
        assertTrue(report.contains("wholeTranscriptSHA256=" + source.transcriptSha256))
        assertTrue(report.contains("selectedSHA256=" + frozen.selectedSha256))
        assertTrue(report.contains("qaPromptSHA=" + frozen.promptSha256))
        assertTrue(report.contains(frozen.selectedText))
        assertTrue(report.contains(frozen.evidence.first()))
        assertTrue(report.contains(output))
        assertTrue(report.contains("peakPssKb=123456"))
        assertTrue(report.contains("NOT full transcript"))
    }
}
