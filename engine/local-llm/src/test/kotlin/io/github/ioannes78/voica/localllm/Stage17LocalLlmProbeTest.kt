package io.github.ioannes78.voica.localllm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Stage17LocalLlmProbeTest {
    @Test
    fun runtimeVersionMatchesGalleryForR6() {
        assertEquals("0.18.0", BuildConfig.LITERT_LM_VERSION)
        assertEquals("0.18.0", Stage17LocalLlmProbe.RUNTIME_VERSION)
    }

    @Test
    fun p0CandidateMatchesVerifiedPreMtpGalleryArtifact() {
        assertEquals("litert-community/gemma-4-E2B-it-litert-lm", Stage17P0Candidate.MODEL_ID)
        assertEquals("gemma-4-E2B-it.litertlm", Stage17P0Candidate.MODEL_FILE)
        assertEquals(
            "7fa1d78473894f7e736a21d920c3aa80f950c0db",
            Stage17P0Candidate.GALLERY_BASE_REVISION,
        )
        assertEquals(
            "6e5c4f1e395deb959c494953478fa5cec4b8008f",
            Stage17P0Candidate.GALLERY_NEWER_MTP_REVISION,
        )
        assertEquals(2_583_085_056L, Stage17P0Candidate.GALLERY_BASE_SIZE_BYTES)
        assertEquals(
            "ab7838cdfc8f77e54d8ca45eadceb20452d9f01e4bfade03e5dce27911b27e42",
            Stage17P0Candidate.GALLERY_BASE_SHA256,
        )
        assertEquals(8, Stage17P0Candidate.MIN_DEVICE_MEMORY_GB)
        assertEquals(32_000, Stage17P0Candidate.MAX_CONTEXT_TOKENS)
    }

    @Test
    fun r6SmokeConfigKeepsR5RuntimeVariablesFixed() {
        assertEquals(4_000, Stage17LocalLlmProbe.PROBE_MAX_CONTEXT_TOKENS)
        assertEquals(64, Stage17LocalLlmProbe.PROBE_TOP_K)
        assertEquals(0.95, Stage17LocalLlmProbe.PROBE_TOP_P)
        assertEquals(1.0, Stage17LocalLlmProbe.PROBE_TEMPERATURE)
        assertEquals(
            Stage17LocalLlmProbe.PROBE_MAX_CONTEXT_TOKENS,
            Stage17ProbeRequest(
                modelPath = "/data/local/tmp/model.litertlm",
                backend = ProbeBackend.CPU,
            ).maxNumTokens,
        )
    }

    @Test
    fun probeRequestRejectsBlankModelPath() {
        assertFailsWith<IllegalArgumentException> {
            Stage17ProbeRequest(
                modelPath = " ",
                backend = ProbeBackend.CPU,
            )
        }
    }

    @Test
    fun probeRequestRejectsInvalidContextLimit() {
        assertFailsWith<IllegalArgumentException> {
            Stage17ProbeRequest(
                modelPath = "/data/local/tmp/model.litertlm",
                backend = ProbeBackend.CPU,
                maxNumTokens = 0,
            )
        }
    }

    @Test
    fun probePromptIsFixedShortChineseSmokePrompt() {
        assertEquals("请用一句简短中文回答：你现在运行在本地设备上。", Stage17LocalLlmProbe.PROBE_PROMPT)
    }
}
