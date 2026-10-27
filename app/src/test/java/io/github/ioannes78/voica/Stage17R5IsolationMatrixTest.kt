package io.github.ioannes78.voica

import io.github.ioannes78.voica.ai.OfflineSummaryPromptContract
import io.github.ioannes78.voica.ai.RenderedOfflineSummaryPrompt
import io.github.ioannes78.voica.localllm.LocalLlmBackendPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage17R5IsolationMatrixTest {
    @Test
    fun b1UsesFormalPromptExplicitGpuAndNoSchema() {
        val source = source()

        val request =
            buildStage17R5IsolationRequest(
                Stage17R5IsolationCase.B1_FORMAL_PROMPT_GPU_NO_SCHEMA,
                source,
            )

        assertEquals(source.formalPrompt.text, request.prompt)
        assertEquals(LocalLlmBackendPolicy.GPU, request.config.backendPolicy)
        assertEquals(4_000, request.config.maxNumTokens)
        assertEquals(1_024, request.config.maxOutputTokens)
        assertEquals(64, request.config.topK)
        assertEquals(0.95, request.config.topP, 0.0)
        assertEquals(1.0, request.config.temperature, 0.0)
        assertFalse(request.config.speculativeDecoding)
        assertNull(request.config.responseJsonSchema)
        assertFalse(request.testCase.requiresStructuredValidation)
    }

    @Test
    fun b2UsesSmallPromptExplicitGpuAndFormalRequestSchema() {
        val source = source()

        val request =
            buildStage17R5IsolationRequest(
                Stage17R5IsolationCase.B2_SMALL_PROMPT_GPU_FORMAL_SCHEMA,
                source,
            )

        assertNotEquals(source.formalPrompt.text, request.prompt)
        assertTrue(request.prompt.length < source.formalPrompt.text.length)
        assertEquals(LocalLlmBackendPolicy.GPU, request.config.backendPolicy)
        val schema = request.config.responseJsonSchema
        assertNotNull(schema)
        assertTrue(checkNotNull(schema).contains("S00001"))
        assertEquals(source.formalPrompt.allowedEvidenceRefs, request.allowedEvidenceRefs)
        assertTrue(request.testCase.requiresStructuredValidation)
        assertTrue(request.estimatedPromptTokens > 0)
    }

    @Test
    fun b3UsesFormalPromptExplicitGpuAndFormalRequestSchema() {
        val source = source()

        val request =
            buildStage17R5IsolationRequest(
                Stage17R5IsolationCase.B3_FORMAL_PROMPT_GPU_FORMAL_SCHEMA,
                source,
            )

        assertEquals(source.formalPrompt.text, request.prompt)
        assertEquals(LocalLlmBackendPolicy.GPU, request.config.backendPolicy)
        assertNotNull(request.config.responseJsonSchema)
        assertTrue(checkNotNull(request.config.responseJsonSchema).contains("S00001"))
        assertTrue(request.testCase.requiresStructuredValidation)
    }

    private fun source(): Stage17R5IsolationSource =
        Stage17R5IsolationSource(
            recordingId = "recording-1",
            displayName = "Golden fixture",
            durationMs = 117_000L,
            formalPrompt =
                RenderedOfflineSummaryPrompt(
                    promptVersion = OfflineSummaryPromptContract.PROMPT_VERSION,
                    resultSchemaVersion = OfflineSummaryPromptContract.RESULT_SCHEMA_VERSION,
                    text =
                        "FORMAL_STAGE_17_2_PROMPT\n" +
                            "这是用于测试的正式转写上下文，长度故意大于 B2 小 prompt。\n" +
                            "[S00001] 示例证据。",
                    allowedEvidenceRefs = linkedSetOf("S00001"),
                    sourceUnitCount = 1,
                ),
        )
}
