package io.github.ioannes78.voica.ai

import java.util.ArrayDeque
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryPipelineTest {
    @Test
    fun cloudPayloadUsesStableRefsWithoutInternalIds() {
        val input = transcriptInput()
        val payload = TranscriptPayloadFormatter.format(input)

        assertTrue(payload.contains("[S00001]"))
        assertTrue(payload.contains("张三"))
        assertFalse(payload.contains("recording-secret-id"))
        assertFalse(payload.contains("transcription-secret-id"))
        assertFalse(payload.contains("span-internal-id"))
    }

    @Test
    fun structuredOutputRejectsUnknownEvidenceAndTranscriptStatementWithoutEvidence() {
        val unknown =
            runCatching {
                SummaryResultCodec.decode(
                    validJson("S99999"),
                    allowedEvidenceRefs = setOf("S00001"),
                )
            }
        assertTrue(unknown.isFailure)

        val noEvidence =
            runCatching {
                SummaryResultCodec.decode(
                    validJson(ref = null),
                    allowedEvidenceRefs = setOf("S00001"),
                )
            }
        assertTrue(noEvidence.isFailure)
    }

    @Test
    fun oversizedUnitSplitsWithoutInventingChildTimestampsOrRefs() {
        val input = transcriptInput(longText = "甲".repeat(200))
        val unit = input.units.single()
        val chunks =
            SummaryChunkPlanner(CharacterEstimator()).plan(
                input = input,
                targetChunkTokens = 30,
            )

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.evidenceRefs == setOf("S00001") })
        assertTrue(
            chunks.flatMap { it.units }.all {
                it.evidence.startSampleIndex == unit.evidence.startSampleIndex &&
                    it.evidence.endSampleIndexExclusive == unit.evidence.endSampleIndexExclusive
            },
        )
    }

    @Test
    fun directGenerationReturnsValidatedStructuredResult() =
        runTest {
            val provider =
                QueueProvider(
                    capabilities =
                        ProviderCapabilities(
                            supportsJsonSchema = true,
                            contextWindowTokens = 100_000,
                            maxOutputTokens = 4_096,
                        ),
                    responses =
                        ArrayDeque(
                            listOf(
                                Result.success(
                                    LlmGenerationResponse(
                                        "r1",
                                        validJson("S00001"),
                                        LlmUsage(10, 5, 15),
                                    ),
                                ),
                            ),
                        ),
                )
            var nextId = 0
            val engine =
                AiSummaryEngine(
                    idFactory = { "req-" + (++nextId) },
                    sleeper = {},
                )
            val output =
                engine.generate(
                    provider,
                    AiSummaryEngineRequest(
                        input = transcriptInput(),
                        profile = profile(),
                        mode = AiSummaryMode.SMART,
                        template = SummaryTemplateCatalog.smart(),
                    ),
                )

            assertEquals(AiContentType.MEETING, output.result.contentType)
            assertEquals(1, output.providerCallCount)
            assertEquals(15L, output.usage?.totalTokens)
            assertEquals("S00001", output.result.sections.single().items.single().evidenceRefs.single())
        }

    @Test
    fun malformedJsonGetsOneBoundedRepair() =
        runTest {
            val provider =
                QueueProvider(
                    capabilities =
                        ProviderCapabilities(
                            supportsJsonSchema = true,
                            contextWindowTokens = 100_000,
                        ),
                    responses =
                        ArrayDeque(
                            listOf(
                                Result.success(LlmGenerationResponse("a", "not-json", null)),
                                Result.success(
                                    LlmGenerationResponse(
                                        "b",
                                        validJson("S00001"),
                                        null,
                                    ),
                                ),
                            ),
                        ),
                )
            var nextId = 0
            val engine =
                AiSummaryEngine(
                    idFactory = { "req-" + (++nextId) },
                    sleeper = {},
                )

            val output =
                engine.generate(
                    provider,
                    AiSummaryEngineRequest(
                        input = transcriptInput(),
                        profile = profile(),
                        mode = AiSummaryMode.SMART,
                        template = SummaryTemplateCatalog.smart(),
                    ),
                )

            assertEquals(2, output.providerCallCount)
            assertEquals(2, provider.requests.size)
            assertTrue(provider.requests[1].taskInstruction.contains("Repair"))
        }

    @Test
    fun retryPolicyOnlyRetriesDeclaredTransientFailures() {
        val policy = ProviderRetryPolicy(maxAttempts = 3, baseDelayMs = 100)
        assertTrue(
            policy.shouldRetry(
                ProviderFailure(
                    ProviderErrorCode.RATE_LIMITED,
                    "429",
                    retryable = true,
                ),
                attempt = 1,
            ),
        )
        assertFalse(
            policy.shouldRetry(
                ProviderFailure(
                    ProviderErrorCode.AUTHENTICATION_FAILED,
                    "401",
                    retryable = false,
                ),
                attempt = 1,
            ),
        )
        assertFalse(
            policy.shouldRetry(
                ProviderFailure(
                    ProviderErrorCode.CONTEXT_LIMIT_EXCEEDED,
                    "context",
                    retryable = false,
                ),
                attempt = 1,
            ),
        )
    }

    private fun transcriptInput(
        longText: String = "请确认项目方案并在周五前完成。",
    ): StructuredTranscriptInput =
        StructuredTranscriptInput(
            recordingId = "recording-secret-id",
            transcriptionId = "transcription-secret-id",
            transcriptionMode = "FAST",
            canonicalAssetId = "canonical-secret-id",
            canonicalSha256 = "a".repeat(64),
            canonicalProfileId = "canonical-profile",
            totalSampleCount = 160_000,
            languageConfig = "auto",
            alignmentId = "alignment-secret-id",
            units =
                listOf(
                    StructuredTranscriptUnit(
                        evidence =
                            EvidenceSourceRef(
                                ref = "S00001",
                                sourceKind = TranscriptEvidenceSourceKind.SPEAKER_SPAN,
                                sourceId = "span-internal-id",
                                speakerId = "speaker-internal-id",
                                speakerDisplayName = "张三",
                                assignmentQuality = "ASSIGNED",
                                overlap = false,
                                ambiguous = false,
                                startSampleIndex = 16_000,
                                endSampleIndexExclusive = 80_000,
                            ),
                        text = longText,
                        detectedLanguage = "zh",
                    ),
                ),
        )

    private fun profile(): ProviderProfile =
        ProviderProfile(
            providerProfileId = "p1",
            presetId = ProviderPresetIds.CUSTOM_OPENAI_COMPATIBLE,
            displayName = "Test",
            baseUrl = "https://example.test/v1",
            credentialRef = "cred",
            defaultModel = "model",
            timeoutMs = 30_000,
            manualContextWindowTokens = 100_000,
            capabilityOverrides = null,
            enabled = true,
        )

    private fun validJson(ref: String?): String {
        val evidenceJson = if (ref == null) "[]" else "[\"" + ref + "\"]"
        return (
            "{" +
                "\"schemaVersion\":1," +
                "\"contentType\":\"MEETING\"," +
                "\"classificationConfidence\":0.9," +
                "\"title\":\"项目讨论\"," +
                "\"overview\":\"讨论了项目安排。\"," +
                "\"sections\":[{" +
                "\"id\":\"s1\"," +
                "\"type\":\"DECISION\"," +
                "\"label\":\"决策\"," +
                "\"items\":[{" +
                "\"id\":\"i1\"," +
                "\"text\":\"确认项目方案。\"," +
                "\"evidenceRefs\":" + evidenceJson + "," +
                "\"epistemicStatus\":\"TRANSCRIPT_STATED\"," +
                "\"attributes\":{}" +
                "}]" +
                "}]" +
                "}"
        )
    }

    private class CharacterEstimator : TokenEstimator {
        override fun estimate(text: String): Int = text.length
    }

    private class QueueProvider(
        private val capabilities: ProviderCapabilities,
        private val responses: ArrayDeque<Result<LlmGenerationResponse>>,
    ) : TextLlmProvider {
        val requests = mutableListOf<LlmGenerationRequest>()

        override suspend fun capabilities(profile: ProviderProfile): ProviderCapabilities =
            capabilities

        override suspend fun discoverModels(profile: ProviderProfile): Result<List<ProviderModel>> =
            Result.success(emptyList())

        override suspend fun testConnection(profile: ProviderProfile): ConnectionTestResult =
            ConnectionTestResult(true, capabilities)

        override suspend fun generate(
            profile: ProviderProfile,
            request: LlmGenerationRequest,
        ): Result<LlmGenerationResponse> {
            requests += request
            return responses.removeFirst()
        }

        override fun cancel(requestId: String) = Unit
    }
}
