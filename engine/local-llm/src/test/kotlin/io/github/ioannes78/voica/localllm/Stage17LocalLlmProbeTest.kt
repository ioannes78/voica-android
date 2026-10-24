package io.github.ioannes78.voica.localllm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Stage17LocalLlmProbeTest {
    @Test
    fun runtimeVersionIsPinnedForP0() {
        assertEquals("0.18.1", BuildConfig.LITERT_LM_VERSION)
    }

    @Test
    fun p0CandidateMatchesVerifiedGalleryMetadata() {
        assertEquals("litert-community/gemma-4-E2B-it-litert-lm", Stage17P0Candidate.MODEL_ID)
        assertEquals("gemma-4-E2B-it.litertlm", Stage17P0Candidate.MODEL_FILE)
        assertEquals(2_588_147_712L, Stage17P0Candidate.GALLERY_BASE_SIZE_BYTES)
        assertEquals(8, Stage17P0Candidate.MIN_DEVICE_MEMORY_GB)
        assertEquals(32_000, Stage17P0Candidate.MAX_CONTEXT_TOKENS)
    }

    @Test
    fun probeRequestRejectsBlankModelPath() {
        assertFailsWith<IllegalArgumentException> {
            Stage17ProbeRequest(
                modelPath = " ",
                cacheDir = "/data/local/tmp",
                backend = ProbeBackend.CPU,
            )
        }
    }

    @Test
    fun probeRequestRejectsBlankCacheDir() {
        assertFailsWith<IllegalArgumentException> {
            Stage17ProbeRequest(
                modelPath = "/data/local/tmp/model.litertlm",
                cacheDir = "",
                backend = ProbeBackend.GPU,
            )
        }
    }

    @Test
    fun probePromptIsFixedChineseSmokePrompt() {
        assertEquals("请用一句中文回答：你现在运行在本地设备上。", Stage17LocalLlmProbe.PROBE_PROMPT)
    }
}
