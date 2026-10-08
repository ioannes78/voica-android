package io.github.ioannes78.voica

import io.github.ioannes78.voica.audio.CanonicalPcmProfile
import io.github.ioannes78.voica.database.TranscriptSegmentEntity
import io.github.ioannes78.voica.database.TranscriptionEntity
import io.github.ioannes78.voica.database.TranscriptionStateValue
import io.github.ioannes78.voica.model.ActiveModel
import io.github.ioannes78.voica.model.ModelCapabilities
import io.github.ioannes78.voica.model.ModelDescriptor
import io.github.ioannes78.voica.model.ModelFileDescriptor
import io.github.ioannes78.voica.model.ModelKind
import io.github.ioannes78.voica.model.ModelPackageFormat
import io.github.ioannes78.voica.model.ModelSourceType
import io.github.ioannes78.voica.model.RedistributionPolicy
import io.github.ioannes78.voica.sherpa.SherpaRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiarizationVadReuseTest {
    @Test
    fun compatibleLineageRestoresExactVadBoundaries() {
        val context = context()
        val segments = segments()

        val reused =
            reusableVadSegmentsOrNull(
                transcription = transcription(),
                persistedSegments = segments,
                context = context,
            )

        assertEquals(2, reused?.size)
        assertEquals(1_000L, reused?.get(0)?.startSampleIndex)
        assertEquals(12_000L, reused?.get(0)?.endSampleIndexExclusive)
        assertEquals(20_000L, reused?.get(1)?.startSampleIndex)
        assertEquals(30_000L, reused?.get(1)?.endSampleIndexExclusive)
    }

    @Test
    fun canonicalMismatchFallsBack() {
        val context = context().copy(canonicalSha256 = "e".repeat(64))

        assertNull(
            reusableVadSegmentsOrNull(
                transcription = transcription(),
                persistedSegments = segments(),
                context = context,
            ),
        )
    }

    @Test
    fun vadSettingsMismatchFallsBack() {
        val context =
            context().copy(
                vadSettings = context().vadSettings.copy(threshold = 0.6F),
            )

        assertNull(
            reusableVadSegmentsOrNull(
                transcription = transcription(),
                persistedSegments = segments(),
                context = context,
            ),
        )
    }

    @Test
    fun effectiveThreadMismatchFallsBack() {
        val context = context().copy(effectiveThreads = 4)

        assertNull(
            reusableVadSegmentsOrNull(
                transcription = transcription(),
                persistedSegments = segments(),
                context = context,
            ),
        )
    }

    @Test
    fun vadManifestMismatchFallsBack() {
        val original = context()
        val context =
            original.copy(
                vadModel = original.vadModel.copy(manifestDigest = "f".repeat(64)),
            )

        assertNull(
            reusableVadSegmentsOrNull(
                transcription = transcription(),
                persistedSegments = segments(),
                context = context,
            ),
        )
    }

    @Test
    fun malformedPersistedSegmentSequenceFallsBack() {
        val malformed =
            segments().mapIndexed { index, segment ->
                if (index == 1) segment.copy(segmentIndex = 2) else segment
            }

        assertNull(
            reusableVadSegmentsOrNull(
                transcription = transcription(),
                persistedSegments = malformed,
                context = context(),
            ),
        )
    }

    @Test
    fun overlappingPersistedSegmentsFallBack() {
        val overlapping =
            listOf(
                segment(index = 0, start = 1_000L, end = 20_000L),
                segment(index = 1, start = 19_000L, end = 30_000L),
            )

        assertNull(
            reusableVadSegmentsOrNull(
                transcription = transcription(),
                persistedSegments = overlapping,
                context = context(),
            ),
        )
    }

    private fun context(): DiarizationVadReuseContext =
        DiarizationVadReuseContext(
            recordingId = RECORDING_ID,
            canonicalSha256 = CANONICAL_SHA,
            canonicalProfileId = CanonicalPcmProfile.PROFILE_ID,
            totalSampleCount = TOTAL_SAMPLES,
            vadModel = activeVad(),
            vadSettings = LocalVadSettings(),
            effectiveThreads = 2,
        )

    private fun transcription(): TranscriptionEntity =
        TranscriptionEntity(
            id = TRANSCRIPTION_ID,
            recordingId = RECORDING_ID,
            mode = "HIGH_QUALITY",
            state = TranscriptionStateValue.COMPLETED,
            sourceCanonicalAssetId = "canonical-asset",
            sourceCanonicalSha256 = CANONICAL_SHA,
            canonicalProfileId = CanonicalPcmProfile.PROFILE_ID,
            totalSampleCount = TOTAL_SAMPLES,
            pipelineVersion = 2,
            runtimeId = SherpaRuntime.RUNTIME_ID,
            runtimeVersion = SherpaRuntime.RUNTIME_VERSION,
            vadModelId = Stage8ModelIds.VAD,
            vadModelVersion = VAD_VERSION,
            firstPassAsrModelId = "fixture-asr",
            firstPassAsrModelVersion = "v1",
            secondPassAsrModelId = null,
            secondPassAsrModelVersion = null,
            punctuationModelId = null,
            punctuationModelVersion = null,
            languageConfig = "auto",
            configSnapshot = EFFECTIVE_CONFIG,
            modelManifestDigest = "a".repeat(64),
            createdAtMs = 1L,
            startedAtMs = 1L,
            updatedAtMs = 2L,
            completedAtMs = 2L,
            errorCode = null,
            errorMessage = null,
            vadModelRevision = 1L,
            firstPassAsrModelRevision = 1L,
            secondPassAsrModelRevision = null,
            punctuationModelRevision = null,
            configSnapshotSchemaVersion = 2,
            requestedConfigSnapshot = EFFECTIVE_CONFIG,
            effectiveConfigSnapshot = EFFECTIVE_CONFIG,
        )

    private fun segments(): List<TranscriptSegmentEntity> =
        listOf(
            segment(index = 0, start = 1_000L, end = 12_000L),
            segment(index = 1, start = 20_000L, end = 30_000L),
        )

    private fun segment(
        index: Int,
        start: Long,
        end: Long,
    ) =
        TranscriptSegmentEntity(
            id = "segment-$index",
            transcriptionId = TRANSCRIPTION_ID,
            segmentIndex = index,
            startSampleIndex = start,
            endSampleIndexExclusive = end,
            firstPassRawText = "",
            secondPassRawText = "测试",
            finalText = "测试",
            detectedLanguage = "zh",
            confidence = null,
        )

    private fun activeVad() =
        ActiveModel(
            descriptor =
                ModelDescriptor(
                    modelId = Stage8ModelIds.VAD,
                    kind = ModelKind.VAD,
                    displayName = "Silero VAD fixture",
                    version = VAD_VERSION,
                    revision = 1L,
                    runtimeId = SherpaRuntime.RUNTIME_ID,
                    runtimeVersionMin = SherpaRuntime.RUNTIME_VERSION,
                    runtimeVersionMax = null,
                    languages = setOf("zh"),
                    capabilities = ModelCapabilities(),
                    sourceType = ModelSourceType.BUILTIN,
                    builtinAssetPath = "models/vad.onnx",
                    packageFormat = ModelPackageFormat.SINGLE_FILE,
                    downloadUrl = null,
                    downloadSizeBytes = null,
                    installedSizeBytes = 1L,
                    packageSha256 = null,
                    files =
                        listOf(
                            ModelFileDescriptor(
                                relativePath = "vad.onnx",
                                sizeBytes = 1L,
                                sha256 = "b".repeat(64),
                            ),
                        ),
                    abis = setOf("arm64-v8a"),
                    minSdk = 26,
                    appVersionMin = 1,
                    appVersionMax = null,
                    licenseId = "test",
                    licenseUrl = null,
                    sourceUrl = "https://example.invalid/vad",
                    homepage = null,
                    attribution = "fixture",
                    redistributionPolicy = RedistributionPolicy.NO_REDISTRIBUTION,
                    releaseChannel = "test",
                    autoUpdateEligible = false,
                ),
            manifestDigest = VAD_MANIFEST,
            installedDirectory = null,
        )

    private companion object {
        const val RECORDING_ID = "rec-stage13c-vad-reuse"
        const val TRANSCRIPTION_ID = "tx-stage13c-vad-reuse"
        const val TOTAL_SAMPLES = 32_000L
        const val VAD_VERSION = "2025-07-11"
        val CANONICAL_SHA = "c".repeat(64)
        val VAD_MANIFEST = "d".repeat(64)
        val EFFECTIVE_CONFIG =
            """
            {
              "schemaVersion":2,
              "effectiveThreads":2,
              "vadWindowSizeSamples":512,
              "vad":{
                "threshold":0.5,
                "minSilenceDurationSeconds":0.25,
                "minSpeechDurationSeconds":0.25,
                "maxSpeechDurationSeconds":30.0
              },
              "runtime":{
                "id":"${SherpaRuntime.RUNTIME_ID}",
                "version":"${SherpaRuntime.RUNTIME_VERSION}",
                "provider":"${SherpaRuntime.PROVIDER_CPU}"
              },
              "models":[
                {
                  "id":"${Stage8ModelIds.VAD}",
                  "version":"$VAD_VERSION",
                  "revision":1,
                  "manifestDigest":"$VAD_MANIFEST"
                }
              ]
            }
            """.trimIndent()
    }
}
