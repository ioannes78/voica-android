package io.github.ioannes78.voica.localllm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Stage17LocalLlmProbeTest {
    @Test
    fun runtimeVersionMatchesGalleryForR5() {
        assertEquals("0.18.0", BuildConfig.LITERT_LM_VERSION)
        assertEquals("0.18.0", Stage17LocalLlmProbe.RUNTIME_VERSION)
    }

    @Test
    fun p0CandidateMatchesVerifiedGalleryBaseMetadata() {
        assertEquals("litert-community/gemma-4-E2B-it-litert-lm", Stage17P0Candidate.MODEL_ID)
        assertEquals("gemma-4-E2B-it.litertlm", Stage17P0Candidate.MODEL_FILE)
        assertEquals(2_588_147_712L, Stage17P0Candidate.GALLERY_BASE_SIZE_BYTES)
        assertEquals(
            "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
            Stage17P0Candidate.GALLERY_BASE_SHA256,
        )
        assertEquals(8, Stage17P0Candidate.MIN_DEVICE_MEMORY_GB)
        assertEquals(32_000, Stage17P0Candidate.MAX_CONTEXT_TOKENS)
    }

    @Test
    fun r5SmokeConfigKeepsGalleryDefaults() {
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
