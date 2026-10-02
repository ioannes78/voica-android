package io.github.ioannes78.voica.ai

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryCheckpointReuseTest {
    @Test
    fun completedMapAndReduceCheckpointsAvoidRepeatedProviderCalls() =
        runTest {
            val store = MemoryCheckpointStore()
            val input = longInput()
            val profile =
                ProviderProfile(
                    providerProfileId = "p1",
                    presetId = ProviderPresetIds.CUSTOM_OPENAI_COMPATIBLE,
                    displayName = "Test",
                    baseUrl = "https://example.test/v1",
                    credentialRef = "cred",
                    defaultModel = "model",
                    timeoutMs = 30_000,
                    manualContextWindowTokens = 8_000,
                    capabilityOverrides =
                        ProviderCapabilities(
                            contextWindowTokens = 8_000,
                            maxOutputTokens = 1_024,
                            supportsJsonObject = true,
                        ),
                    enabled = true,
                )
            val request =
                AiSummaryEngineRequest(
                    input = input,
                    profile = profile,
                    mode = AiSummaryMode.SMART,
                    template = SummaryTemplateCatalog.smart(),
                    checkpointStore = store,
                )

            val firstProvider = EvidenceEchoProvider()
            val first =
                AiSummaryEngine(
                    config =
                        AiSummaryEngineConfig(
                            outputReserveTokens = 1_024,
                            safetyMarginTokens = 512,
                            maxOutputTokens = 1_024,
                            maxRepairAttempts = 0,
                        ),
                    sleeper = {},
                ).generate(firstProvider, request)

            assertTrue(first.mapChunkCount > 1)
            assertTrue(firstProvider.callCount > 1)
            assertTrue(store.records.isNotEmpty())

            val secondProvider = EvidenceEchoProvider()
            val second =
                AiSummaryEngine(
                    config =
                        AiSummaryEngineConfig(
                            outputReserveTokens = 1_024,
                            safetyMarginTokens = 512,
                            maxOutputTokens = 1_024,
                            maxRepairAttempts = 0,
                        ),
                    sleeper = {},
                ).generate(secondProvider, request)

            assertEquals(0, secondProvider.callCount)
            assertEquals(first.structuredPayloadJson, second.structuredPayloadJson)
        }

    private fun longInput(): StructuredTranscriptInput {
        val units =
            (0 until 40).map { index ->
                val ref = "S" + (index + 1).toString().padStart(5, '0')
                StructuredTranscriptUnit(
                    evidence =
                        EvidenceSourceRef(
                            ref = ref,
                            sourceKind = TranscriptEvidenceSourceKind.TRANSCRIPT_SEGMENT,
                            sourceId = "segment-" + index,
                            speakerId = null,
                            speakerDisplayName = null,
                            assignmentQuality = null,
                            overlap = false,
                            ambiguous = false,
                            startSampleIndex = index * 16_000L,
                            endSampleIndexExclusive = (index + 1) * 16_000L,
                        ),
                    text = "这是第" + index + "段。" + "长文本".repeat(180),
                    detectedLanguage = "zh",
                )
            }
        return StructuredTranscriptInput(
            recordingId = "rec",
            transcriptionId = "tx",
            transcriptionMode = "FAST",
            canonicalAssetId = "asset",
            canonicalSha256 = "a".repeat(64),
            canonicalProfileId = "profile",
            totalSampleCount = 40L * 16_000L,
            languageConfig = "auto",
            alignmentId = null,
            units = units,
        )
    }

    private class MemoryCheckpointStore : SummaryCheckpointStore {
        val records = mutableMapOf<Triple<Int, Int, String>, SummaryCheckpointRecord>()

        override suspend fun load(
            level: Int,
            chunkIndex: Int,
            inputDigest: String,
        ): String? =
            records[Triple(level, chunkIndex, inputDigest)]?.structuredResultJson

        override suspend fun save(record: SummaryCheckpointRecord) {
            records[Triple(record.level, record.chunkIndex, record.inputDigest)] = record
        }
    }

    private class EvidenceEchoProvider : TextLlmProvider {
        var callCount = 0

        override suspend fun capabilities(profile: ProviderProfile): ProviderCapabilities =
            profile.capabilityOverrides ?: ProviderCapabilities()

        override suspend fun discoverModels(profile: ProviderProfile): Result<List<ProviderModel>> =
            Result.success(emptyList())

        override suspend fun testConnection(profile: ProviderProfile): ConnectionTestResult =
            ConnectionTestResult(true, capabilities = capabilities(profile))

        override suspend fun generate(
            profile: ProviderProfile,
            request: LlmGenerationRequest,
        ): Result<LlmGenerationResponse> {
            callCount++
            val refs =
                Regex("""S\d{5,}""")
                    .findAll(request.transcriptPayload)
                    .map { it.value }
                    .distinct()
                    .toList()
            val ref = refs.firstOrNull()
            val evidence = if (ref == null) "[]" else "[\"" + ref + "\"]"
            val epistemic =
                if (ref == null) "AI_SYNTHESIS" else "TRANSCRIPT_STATED"
            val json =
                "{" +
                    "\"schemaVersion\":1," +
                    "\"contentType\":\"GENERAL\"," +
                    "\"classificationConfidence\":0.7," +
                    "\"title\":\"总结\"," +
                    "\"overview\":\"测试总结\"," +
                    "\"sections\":[{" +
                    "\"id\":\"s1\"," +
                    "\"type\":\"SUMMARY\"," +
                    "\"label\":\"摘要\"," +
                    "\"items\":[{" +
                    "\"id\":\"i1\"," +
                    "\"text\":\"归纳内容\"," +
                    "\"evidenceRefs\":" + evidence + "," +
                    "\"epistemicStatus\":\"" + epistemic + "\"," +
                    "\"attributes\":{}" +
                    "}]}]}"
            return Result.success(
                LlmGenerationResponse(
                    requestId = request.requestId,
                    content = json,
                    usage = null,
                ),
            )
        }

        override fun cancel(requestId: String) = Unit
    }
}
