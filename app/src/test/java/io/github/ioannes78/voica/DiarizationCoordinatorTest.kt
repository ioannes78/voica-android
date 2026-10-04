package io.github.ioannes78.voica

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.ioannes78.voica.audio.PcmReadResult
import io.github.ioannes78.voica.audio.PcmSource
import io.github.ioannes78.voica.audio.PcmSourceResolver
import io.github.ioannes78.voica.database.CanonicalTranscriptionLineage
import io.github.ioannes78.voica.database.DiarizationRepository
import io.github.ioannes78.voica.database.DiarizationStateValue
import io.github.ioannes78.voica.database.RecordingEntity
import io.github.ioannes78.voica.database.RecordingSourceType
import io.github.ioannes78.voica.database.TranscriptSegmentEntity
import io.github.ioannes78.voica.database.TranscriptTokenEntity
import io.github.ioannes78.voica.database.TranscriptTokenSourceValue
import io.github.ioannes78.voica.database.TranscriptionEntity
import io.github.ioannes78.voica.database.TranscriptionModeValue
import io.github.ioannes78.voica.database.TranscriptionRepository
import io.github.ioannes78.voica.database.TranscriptionStateValue
import io.github.ioannes78.voica.database.VoicaDatabase
import io.github.ioannes78.voica.model.ActiveModel
import io.github.ioannes78.voica.model.ModelAvailability
import io.github.ioannes78.voica.model.ModelCatalog
import io.github.ioannes78.voica.model.ModelCapabilities
import io.github.ioannes78.voica.model.ModelDescriptor
import io.github.ioannes78.voica.model.ModelFileDescriptor
import io.github.ioannes78.voica.model.ModelKind
import io.github.ioannes78.voica.model.ModelManager
import io.github.ioannes78.voica.model.ModelOperationStatus
import io.github.ioannes78.voica.model.ModelPackageFormat
import io.github.ioannes78.voica.model.ModelSourceType
import io.github.ioannes78.voica.model.ModelUseRegistry
import io.github.ioannes78.voica.model.RedistributionPolicy
import io.github.ioannes78.voica.model.SpeakerModelRole
import io.github.ioannes78.voica.transcript.DiarizationChunkResult
import io.github.ioannes78.voica.transcript.DiarizationConfig
import io.github.ioannes78.voica.transcript.DiarizationEngine
import io.github.ioannes78.voica.transcript.DiarizationProgressListener
import io.github.ioannes78.voica.transcript.DiarizationSpeakerTurn
import io.github.ioannes78.voica.transcript.DiarizationWindow
import io.github.ioannes78.voica.transcript.ProgressListener
import io.github.ioannes78.voica.transcript.SpeakerEmbeddingEngine
import io.github.ioannes78.voica.transcript.SpeechSegment
import io.github.ioannes78.voica.transcript.TranscriptionPhase
import io.github.ioannes78.voica.transcript.TranscriptionProgress
import io.github.ioannes78.voica.transcript.VadEngine
import io.github.ioannes78.voica.transcript.VadEngineFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiarizationCoordinatorTest {
    private lateinit var database: VoicaDatabase
    private lateinit var diarizationRepository: DiarizationRepository
    private lateinit var transcriptionRepository: TranscriptionRepository
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            database =
                Room.inMemoryDatabaseBuilder(
                    context,
                    VoicaDatabase::class.java,
                )
                    .allowMainThreadQueries()
                    .build()
            diarizationRepository = DiarizationRepository(database)
            transcriptionRepository = TranscriptionRepository(database)
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

            database.recordingDao().insertRecordingIgnore(
                RecordingEntity(
                    id = RECORDING_ID,
                    sourceType = RecordingSourceType.DEVICE_DOWNLOAD,
                    sourceRemoteIdentity = "remote-stage9",
                    sourceDeviceAddress = "AA:BB",
                    originalFilename = "stage9.wav",
                    displayName = "Stage 9",
                    recordedAtLocalIso = null,
                    deviceReportedDurationMs = 2_000,
                    downloadedAtMs = 1L,
                    createdAtMs = 1L,
                    updatedAtMs = 1L,
                ),
            )
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
    }

    @Test
    fun completedDiarizationUsesCampPlusPersistsAndAlignsTranscript() = runBlocking {
        val descriptors = requiredDescriptors()
        val active = descriptors.associate { it.modelId to active(it) }
        val registry = ModelUseRegistry()
        val coordinator =
            coordinator(
                modelManager = FakeModelManager(active),
                registry = registry,
                provider = FakeEngineProvider(),
            )

        assertTrue(
            coordinator.start(
                recordingId = RECORDING_ID,
                config =
                    DiarizationConfig(
                        vadContextPaddingSamples = 0L,
                        stitchingMinimumAnchorSamples = 4_000L,
                    ),
            ),
        )

        val completed =
            coordinator.state
                .filterIsInstance<DiarizationRunState.Completed>()
                .first()
        val run = checkNotNull(diarizationRepository.findRun(completed.runId))

        assertEquals(2, completed.speakerCount)
        assertEquals(2, completed.turns.size)
        assertEquals(DiarizationStateValue.COMPLETED, run.state)
        assertEquals(Stage13ASpeakerEmbeddingModelIds.CAMP_PLUS, run.embeddingModelId)
        assertEquals(2, diarizationRepository.loadSpeakers(completed.runId).size)

        descriptors.forEach { descriptor ->
            assertFalse(
                registry.isInUse(
                    descriptor.modelId,
                    descriptor.version,
                    descriptor.revision,
                ),
            )
        }

        seedCompletedTranscription()
        assertTrue(
            coordinator.alignTranscription(
                transcriptionId = TRANSCRIPTION_ID,
                diarizationRunId = completed.runId,
            ),
        )
        val aligned =
            coordinator.alignmentState
                .filterIsInstance<SpeakerAlignmentRunState.Completed>()
                .first()
        val spans = diarizationRepository.loadSpans(aligned.alignmentId)
        assertEquals(2, spans.size)
        assertEquals(TranscriptTokenSourceValue.FIRST_PASS, spans[0].tokenSource)
    }

    @Test
    fun missingSpeakerModelsReportsCampPlusAndSegmentation() = runBlocking {
        val vad = descriptor(Stage8ModelIds.VAD, ModelKind.VAD, null)
        val coordinator =
            coordinator(
                modelManager = FakeModelManager(mapOf(vad.modelId to active(vad))),
                registry = ModelUseRegistry(),
                provider = FakeEngineProvider(),
            )

        assertTrue(coordinator.start(RECORDING_ID))
        val failed =
            coordinator.state
                .filterIsInstance<DiarizationRunState.Failed>()
                .first()

        assertEquals(
            listOf(
                Stage13ASpeakerEmbeddingModelIds.CAMP_PLUS,
                Stage9ModelIds.SEGMENTATION,
            ).sorted(),
            failed.missingModelIds,
        )
        assertTrue(diarizationRepository.observeRuns(RECORDING_ID).first().isEmpty())
    }

    @Test
    fun noSpeechCompletesWithZeroSpeakers() = runBlocking {
        val descriptors = requiredDescriptors()
        val coordinator =
            coordinator(
                modelManager = FakeModelManager(descriptors.associate { it.modelId to active(it) }),
                registry = ModelUseRegistry(),
                provider = FakeEngineProvider(noSpeech = true),
            )

        assertTrue(coordinator.start(RECORDING_ID))
        val completed =
            coordinator.state
                .filterIsInstance<DiarizationRunState.Completed>()
                .first()

        assertEquals(0, completed.speakerCount)
        assertTrue(completed.turns.isEmpty())
    }

    @Test
    fun cancellationDuringVadMarksRunCancelledAndReleasesAllLeases() = runBlocking {
        val descriptors = requiredDescriptors()
        val registry = ModelUseRegistry()
        val coordinator =
            coordinator(
                modelManager = FakeModelManager(descriptors.associate { it.modelId to active(it) }),
                registry = registry,
                provider = FakeEngineProvider(blockVad = true),
            )

        assertTrue(coordinator.start(RECORDING_ID))
        val running =
            coordinator.state
                .filterIsInstance<DiarizationRunState.Running>()
                .first { it.runId != null }
        val runId = checkNotNull(running.runId)

        coordinator.cancel()
        coordinator.state
            .filterIsInstance<DiarizationRunState.Cancelled>()
            .first()

        assertEquals(
            DiarizationStateValue.CANCELLED,
            diarizationRepository.findRun(runId)!!.state,
        )
        descriptors.forEach { descriptor ->
            assertFalse(registry.isInUse(descriptor.modelId, descriptor.version, descriptor.revision))
        }
    }

    private fun coordinator(
        modelManager: ModelManager,
        registry: ModelUseRegistry,
        provider: Stage9DiarizationEngineProvider,
    ) =
        DiarizationCoordinator(
            scope = scope,
            pcmSourceResolver = PatternPcmSourceResolver(),
            diarizationRepository = diarizationRepository,
            transcriptionRepository = transcriptionRepository,
            loadCanonicalLineage = { recordingId, profileId ->
                CanonicalTranscriptionLineage(
                    recordingId = recordingId,
                    canonicalAssetId = "canonical-stage9",
                    canonicalSha256 = "a".repeat(64),
                    canonicalProfileId = profileId,
                    canonicalPipelineVersion = 1,
                )
            },
            modelManager = modelManager,
            modelUseRegistry = registry,
            engineProvider = provider,
            localSpeechSettings = { LocalSpeechSettings() },
        )

    private suspend fun seedCompletedTranscription() {
        val dao = database.transcriptionDao()
        dao.insertTranscription(
            TranscriptionEntity(
                id = TRANSCRIPTION_ID,
                recordingId = RECORDING_ID,
                mode = TranscriptionModeValue.FAST,
                state = TranscriptionStateValue.COMPLETED,
                sourceCanonicalAssetId = "canonical-stage9",
                sourceCanonicalSha256 = "a".repeat(64),
                canonicalProfileId = "CANONICAL_PCM16_16000_MONO_WAV_V1",
                totalSampleCount = TOTAL_SAMPLES.toLong(),
                pipelineVersion = 1,
                runtimeId = "sherpa-onnx",
                runtimeVersion = "1.13.8",
                vadModelId = Stage8ModelIds.VAD,
                vadModelVersion = "v1",
                firstPassAsrModelId = Stage8ModelIds.FIRST_PASS_ASR,
                firstPassAsrModelVersion = "v1",
                secondPassAsrModelId = null,
                secondPassAsrModelVersion = null,
                punctuationModelId = Stage8ModelIds.PUNCTUATION,
                punctuationModelVersion = "v1",
                languageConfig = "auto",
                configSnapshot = "{}",
                modelManifestDigest = DIGEST,
                createdAtMs = 100L,
                startedAtMs = 100L,
                updatedAtMs = 101L,
                completedAtMs = 101L,
                errorCode = null,
                errorMessage = null,
            ),
        )
        dao.insertSegment(
            TranscriptSegmentEntity(
                id = SEGMENT_ID,
                transcriptionId = TRANSCRIPTION_ID,
                segmentIndex = 0,
                startSampleIndex = 0L,
                endSampleIndexExclusive = TOTAL_SAMPLES.toLong(),
                firstPassRawText = "你好世界",
                secondPassRawText = null,
                finalText = "你好，世界。",
                detectedLanguage = "zh",
                confidence = 0.9F,
            ),
        )
        dao.insertTokens(
            listOf(
                TranscriptTokenEntity(
                    id = "token-0",
                    transcriptSegmentId = SEGMENT_ID,
                    tokenIndex = 0,
                    text = "你好",
                    startSampleIndex = 0L,
                    endSampleIndexExclusive = 16_000L,
                    source = TranscriptTokenSourceValue.FIRST_PASS,
                ),
                TranscriptTokenEntity(
                    id = "token-1",
                    transcriptSegmentId = SEGMENT_ID,
                    tokenIndex = 1,
                    text = "世界",
                    startSampleIndex = 16_000L,
                    endSampleIndexExclusive = 32_000L,
                    source = TranscriptTokenSourceValue.FIRST_PASS,
                ),
            ),
        )
    }

    private class PatternPcmSourceResolver : PcmSourceResolver {
        override suspend fun resolvePcmSource(recordingId: String): PcmSource =
            PatternPcmSource(TOTAL_SAMPLES)
    }

    private class PatternPcmSource(
        private val size: Int,
    ) : PcmSource {
        override val sampleRateHz = 16_000
        override val channelCount = 1
        override val totalSampleCount = size.toLong()
        private var cursor = 0

        override suspend fun read(
            target: ShortArray,
            targetOffset: Int,
            maxSamples: Int,
        ): PcmReadResult? {
            if (cursor >= size) return null
            val count = minOf(maxSamples, size - cursor)
            repeat(count) { offset ->
                target[targetOffset + offset] =
                    if (cursor + offset < size / 2) 1_000 else -1_000
            }
            val start = cursor.toLong()
            cursor += count
            return PcmReadResult(start, count)
        }

        override fun close() = Unit
    }

    private class FakeEngineProvider(
        private val noSpeech: Boolean = false,
        private val blockVad: Boolean = false,
    ) : Stage9DiarizationEngineProvider {
        override fun vadFactory(
            model: ActiveModel,
            numThreads: Int,
            vadSettings: LocalVadSettings,
        ) =
            VadEngineFactory {
                FakeVadEngine(
                    model = model.descriptor,
                    noSpeech = noSpeech,
                    block = blockVad,
                )
            }

        override suspend fun validateBundle(
            segmentation: ActiveModel,
            embedding: ActiveModel,
        ) {
            require(segmentation.descriptor.speakerRole == SpeakerModelRole.DIARIZATION_SEGMENTATION)
            require(embedding.descriptor.speakerRole == SpeakerModelRole.EMBEDDING)
            require(embedding.descriptor.modelId == Stage13ASpeakerEmbeddingModelIds.CAMP_PLUS)
        }

        override fun diarizationEngine(
            segmentation: ActiveModel,
            embedding: ActiveModel,
            numThreads: Int,
        ): DiarizationEngine =
            FakeDiarizationEngine(
                segmentationModel = segmentation.descriptor,
                embeddingModel = embedding.descriptor,
            )

        override fun embeddingEngine(
            model: ActiveModel,
            numThreads: Int,
        ): SpeakerEmbeddingEngine = FakeEmbeddingEngine(model.descriptor)
    }

    private class FakeVadEngine(
        override val model: ModelDescriptor,
        private val noSpeech: Boolean,
        private val block: Boolean,
    ) : VadEngine {
        override suspend fun analyze(
            source: PcmSource,
            progressListener: ProgressListener?,
        ): List<SpeechSegment> {
            if (block) awaitCancellation()
            if (noSpeech) return emptyList()
            val buffer = ShortArray(4096)
            var processed = 0L
            while (true) {
                val read = source.read(buffer) ?: break
                processed += read.sampleCount
                progressListener?.onProgress(
                    TranscriptionProgress(
                        phase = TranscriptionPhase.VAD,
                        processedUnits = processed,
                        totalUnits = source.totalSampleCount,
                    ),
                )
            }
            return listOf(SpeechSegment(0L, source.totalSampleCount))
        }

        override fun close() = Unit
    }

    private class FakeDiarizationEngine(
        override val segmentationModel: ModelDescriptor,
        override val embeddingModel: ModelDescriptor,
    ) : DiarizationEngine {
        override suspend fun diarize(
            window: DiarizationWindow,
            config: DiarizationConfig,
            progressListener: DiarizationProgressListener?,
        ): DiarizationChunkResult {
            val midpoint = window.startSampleIndex + window.samples.size.toLong() / 2L
            return DiarizationChunkResult(
                speakerCount = 2,
                turns =
                    listOf(
                        DiarizationSpeakerTurn(
                            speakerIndex = 0,
                            startSampleIndex = window.startSampleIndex,
                            endSampleIndexExclusive = midpoint,
                            confidence = 0.8F,
                        ),
                        DiarizationSpeakerTurn(
                            speakerIndex = 1,
                            startSampleIndex = midpoint,
                            endSampleIndexExclusive = window.endSampleIndexExclusive,
                            confidence = 0.7F,
                        ),
                    ),
            )
        }

        override fun close() = Unit
    }

    private class FakeEmbeddingEngine(
        override val model: ModelDescriptor,
    ) : SpeakerEmbeddingEngine {
        override suspend fun embed(samples: ShortArray, sampleRateHz: Int): FloatArray {
            val positive = samples.firstOrNull()?.let { it >= 0 } ?: true
            return if (positive) floatArrayOf(1F, 0F) else floatArrayOf(0F, 1F)
        }

        override fun close() = Unit
    }

    private class FakeModelManager(
        private val active: Map<String, ActiveModel>,
    ) : ModelManager {
        override val operations: StateFlow<Map<String, ModelOperationStatus>> = MutableStateFlow(emptyMap())

        override suspend fun catalog(): ModelCatalog =
            ModelCatalog(
                catalogVersion = 1,
                manifestVersion = 1,
                channel = "test",
                publishedAt = null,
                manifestDigest = DIGEST,
                models = active.values.map { it.descriptor },
            )

        override suspend fun availability(modelId: String): ModelAvailability? = null

        override suspend fun activeModel(modelId: String): ActiveModel? = active[modelId]

        override suspend fun checkForUpdates(force: Boolean): ModelCatalog = catalog()

        override suspend fun install(modelId: String, version: String, revision: Long) = error("not used")

        override suspend fun cancelInstall(modelId: String) = Unit

        override suspend fun confirmInstalledVersion(modelId: String, version: String, revision: Long) = error("not used")

        override suspend fun removeDownloadedVersion(modelId: String, version: String, revision: Long) = error("not used")

        override suspend fun rollback(modelId: String) = error("not used")
    }

    private companion object {
        const val RECORDING_ID = "rec-stage9"
        const val TRANSCRIPTION_ID = "tx-stage9"
        const val SEGMENT_ID = "seg-stage9"
        const val TOTAL_SAMPLES = 32_000
        val DIGEST = "d".repeat(64)

        fun requiredDescriptors(): List<ModelDescriptor> =
            listOf(
                descriptor(Stage8ModelIds.VAD, ModelKind.VAD, null),
                descriptor(
                    Stage9ModelIds.SEGMENTATION,
                    ModelKind.SPEAKER,
                    SpeakerModelRole.DIARIZATION_SEGMENTATION,
                ),
                descriptor(
                    Stage13ASpeakerEmbeddingModelIds.CAMP_PLUS,
                    ModelKind.SPEAKER,
                    SpeakerModelRole.EMBEDDING,
                ),
            )

        fun active(descriptor: ModelDescriptor) =
            ActiveModel(
                descriptor = descriptor,
                manifestDigest = DIGEST,
                installedDirectory = null,
            )

        fun descriptor(
            modelId: String,
            kind: ModelKind,
            role: SpeakerModelRole?,
        ) =
            ModelDescriptor(
                modelId = modelId,
                kind = kind,
                displayName = modelId,
                version = "v1",
                revision = 1,
                runtimeId = "sherpa-onnx",
                runtimeVersionMin = "1.13.8",
                runtimeVersionMax = null,
                languages = setOf("zh"),
                capabilities = ModelCapabilities(),
                sourceType = ModelSourceType.BUILTIN,
                builtinAssetPath = "models/fake.bin",
                packageFormat = ModelPackageFormat.SINGLE_FILE,
                downloadUrl = null,
                downloadMirrors = emptyList(),
                downloadSizeBytes = null,
                installedSizeBytes = 1L,
                packageSha256 = null,
                files =
                    listOf(
                        ModelFileDescriptor(
                            relativePath = "fake.bin",
                            sizeBytes = 1L,
                            sha256 = "b".repeat(64),
                        ),
                    ),
                abis = setOf("arm64-v8a"),
                minSdk = 26,
                appVersionMin = 24,
                appVersionMax = null,
                licenseId = "test",
                licenseUrl = null,
                sourceUrl = "https://example.invalid/source",
                homepage = null,
                attribution = "fixture",
                redistributionPolicy = RedistributionPolicy.NO_REDISTRIBUTION,
                releaseChannel = "test",
                autoUpdateEligible = false,
                deprecated = false,
                criticalUpdate = false,
                speakerRole = role,
            )
    }
}
