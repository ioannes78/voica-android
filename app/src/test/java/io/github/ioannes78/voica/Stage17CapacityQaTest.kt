package io.github.ioannes78.voica

import io.github.ioannes78.voica.ai.OfflineSummaryResponseFormat
import io.github.ioannes78.voica.localllm.LocalLlmBackendPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage17CapacityQaTest {
    @Test
    fun contextMatrixUsesSameInputToIsolateKvCacheAndBoundsEveryPrompt() {
        val baseline = Stage17CapacityQa.contextProbe(4_000, false)
        val same = Stage17CapacityQa.contextProbe(8_000, false)
        val longer = Stage17CapacityQa.contextProbe(8_000, true)
        val longest = Stage17CapacityQa.contextProbe(16_000, true)
        assertEquals(baseline.prompt, same.prompt)
        assertEquals(baseline.expectedRecord, same.expectedRecord)
        assertEquals(4_000, baseline.config.maxNumTokens)
        assertEquals(8_000, same.config.maxNumTokens)
        assertTrue(longer.estimatedPromptTokens > same.estimatedPromptTokens)
        assertTrue(longest.estimatedPromptTokens > longer.estimatedPromptTokens)
        for (request in listOf(baseline, same, longer, longest)) {
            assertEquals(LocalLlmBackendPolicy.CPU, request.config.backendPolicy)
            assertEquals(1_024, request.config.maxOutputTokens)
            assertTrue(request.estimatedPromptTokens + 1_024 + 192 + 256 <= request.config.maxNumTokens)
            assertTrue(request.prompt.contains(request.expectedRecord!!))
            assertTrue(request.config.responseJsonSchema == null)
        }
    }

    @Test
    fun outputMatrixChangesOnlyQaSchemaAndOutputTokens() {
        val baseline = Stage17CapacityQa.outputProbe(false)
        val expanded = Stage17CapacityQa.outputProbe(true)
        val baseSchema = Json.parseToJsonElement(requireNotNull(baseline.config.responseJsonSchema)).jsonObject
        val extendedSchema = Json.parseToJsonElement(requireNotNull(expanded.config.responseJsonSchema)).jsonObject
        val baseMax = baseSchema["properties"]!!.jsonObject["items"]!!.jsonObject["maxItems"].toString().toInt()
        val expandedMax = extendedSchema["properties"]!!.jsonObject["items"]!!.jsonObject["maxItems"].toString().toInt()
        assertEquals(8, baseMax)
        assertEquals(16, expandedMax)
        assertEquals(1_024, baseline.config.maxOutputTokens)
        assertEquals(2_048, expanded.config.maxOutputTokens)
        assertEquals(4_000, baseline.config.maxNumTokens)
        assertEquals(8_000, expanded.config.maxNumTokens)
        assertEquals(baseline.factIds, expanded.factIds)
        assertEquals(16, baseline.factIds.size)
        assertTrue(baseline.estimatedPromptTokens + 1_024 + 192 + 256 <= 4_000)
        assertTrue(expanded.estimatedPromptTokens + 2_048 + 192 + 256 <= 8_000)
        assertEquals(4, OfflineSummaryResponseFormat.MAX_SECTIONS)
        assertEquals(2, OfflineSummaryResponseFormat.MAX_ITEMS_PER_SECTION)
        assertEquals(4_000, OfflineSummaryLocalLlmPreset.configFor(emptySet()).maxNumTokens)
        assertEquals(1_024, OfflineSummaryLocalLlmPreset.configFor(emptySet()).maxOutputTokens)
    }

    @Test
    fun outputCoverageReportsUniqueFactsAndRejectsMalformedJson() {
        val ids = Stage17CapacityQa.outputProbe(false).factIds
        val result = Stage17CapacityQa.evaluateOutput(
            """{"items":[{"factId":"F01","text":"研发投入"},{"factId":"F02","text":"用电"},{"factId":"F01","text":"重复"}]}""",
            ids,
        )
        assertTrue(result.contains("items=3"))
        assertTrue(result.contains("distinctFacts=2/16"))
        assertTrue(result.contains("duplicates=1"))
        assertTrue(Stage17CapacityQa.evaluateOutput("broken", ids).startsWith("capacityJson=FAIL"))
        assertFalse(Stage17CapacityQa.outputProbe(true).config.responseJsonSchema.isNullOrBlank())
    }
}
