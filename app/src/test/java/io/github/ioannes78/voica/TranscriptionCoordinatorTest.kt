package io.github.ioannes78.voica

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.ioannes78.voica.audio.PcmReadResult
import io.github.ioannes78.voica.audio.PcmSource
import io.github.ioannes78.voica.audio.PcmSourceResolver
import io.github.ioannes78.voica.database.CanonicalTranscriptionLineage
import io.github.ioannes78.voica.database.RecordingEntity
import io.github.ioannes78.voica.database.RecordingSourceType
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
import io.github.ioannes78.voica.transcript.AsrCapabilities
import io.github.ioannes78.voica.transcript.AsrHypothesis
import io.github.ioannes78.voica.transcript.PunctuationCapability
import io.github.ioannes78.voica.transcript.PunctuationEngine
import io.github.ioannes78.voica.transcript.PunctuationEngineFactory
import io.github.ioannes78.voica.transcript.ProgressListener
import io.github.ioannes78.voica.transcript.RelativeTimedToken
import io.github.ioannes78.voica.transcript.SecondPassAsrEngineFactory
import io.github.ioannes78.voica.transcript.SpeechSegment
import io.github.ioannes78.voica.transcript.StreamingAsrEngine
import io.github.ioannes78.voica.transcript.StreamingAsrEngineFactory
import io.github.ioannes78.voica.transcript.StreamingAsrSession
import io.github.ioannes78.voica.transcript.TranscriptionMode
import io.github.ioannes78.voica.transcript.TranscriptionPhase
import io.github.ioannes78.voica.transcript.TranscriptionProgress
import io.github.ioannes78.voica.transcript.VadEngine
import io.github.ioannes78.voica.transcript.VadEngineFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
class TranscriptionCoordinatorTest {
    private lateinit var database: VoicaDatabase
    private lateinit var repository: TranscriptionRepository
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
        repository = TranscriptionRepository(database)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        database.recordingDao().insertRecordingIgnore(
            RecordingEntity(
                id = RECORDING_ID,
                sourceType = RecordingSourceType.DEVICE_DOWNLOAD,
                sourceRemoteIdentity = "remote-test",
                sourceDeviceAddress = "AA:BB",
                originalFilename = "note.wav",
                displayName = "Note",
                recordedAtLocalIso = null,
                deviceReportedDurationMs = 50,
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
    fun missingRequiredModelsFailsBeforeCreatingTranscriptionRow() = runBlocking {
        val vad = active(descriptor(Stage8ModelIds.VAD, ModelKind.VAD))
        val modelManager = FakeModelManager(mapOf(Stage8ModelIds.VAD to vad))
        val coordinator =
            coordinator(
                modelManager = modelManager,
                registry = ModelUseRegistry(),
            )

        assertTrue(coordinator.start(RECORDING_ID, TranscriptionMode.FAST))

        val failed =
            coordinator.state
                .filterIsInstance<TranscriptionRunState.Failed>()
                .first()

        assertEquals(
            listOf(
                Stage8ModelIds.FIRST_PASS_ASR,
                Stage8ModelIds.PUNCTUATION,
            ).sorted(),
            failed.missingModelIds,
        )
        assertTrue(repository.observeVersions(RECORDING_ID).first().isEmpty())
    }

    @Test
    fun fastTranscriptionCompletesPersistsAndReleasesModelLeases() = runBlocking {
        val models =
            listOf(
                descriptor(Stage8ModelIds.VAD, ModelKind.VAD),
                descriptor(Stage8ModelIds.FIRST_PASS_ASR, ModelKind.ASR_STREAMING),
                descriptor(Stage8ModelIds.PUNCTUATION, ModelKind.PUNCTUATION),
            )
        val active = models.associate { it.modelId to active(it) }
        val registry = ModelUseRegistry()
        val coordinator =
            coordinator(
                modelManager = FakeModelManager(active),
                registry = registry,
            )

        assertTrue(coordinator.start(RECORDING_ID, TranscriptionMode.FAST))

        val completed =
            coordinator.state
                .filterIsInstance<TranscriptionRunState.Completed>()
                .first()

        assertEquals(RECORDING_ID, completed.recordingId)
        assertEquals("测试。", completed.segments.single().finalText)

        val persisted = repository.find(completed.transcriptionId)!!
        assertEquals(TranscriptionStateValue.COMPLETED, persisted.state)
        assertEquals(
            "测试。",
            repository.loadSegments(completed.transcriptionId).single().finalText,
        )

        models.forEach { model ->
            assertFalse(
                registry.isInUse(
                    modelId = model.modelId,
                    version = model.version,
                    revision = model.revision,
                ),
            )
        }
    }

    private fun coordinator(
        modelManager: ModelManager,
        registry: ModelUseRegistry,
    ) =
        TranscriptionCoordinator(
            scope = scope,
            pcmSourceResolver = FakePcmSourceResolver(),
            transcriptionRepository = repository,
            loadCanonicalLineage = { recordingId, profileId ->
                CanonicalTranscriptionLineage(
                    recordingId = recordingId,
                    canonicalAssetId = "canonical-1",
                    canonicalSha256 = "a".repeat(64),
                    canonicalProfileId = profileId,
                    canonicalPipelineVersion = 1,
                )
            },
            modelManager = modelManager,
            modelUseRegistry = registry,
            engineProvider = FakeEngineProvider,
        )

    private class FakePcmSourceResolver : PcmSourceResolver {
        override suspend fun resolvePcmSource(recordingId: String): PcmSource =
            FakePcmSource(ShortArray(TOTAL_SAMPLES))
    }

    private class FakePcmSource(
        private val samples: ShortArray,
    ) : PcmSource {
        override val sampleRateHz = 16_000
        override val channelCount = 1
        override val totalSampleCount = samples.size.toLong()
        private var cursor = 0

        override suspend fun read(
            target: ShortArray,
            targetOffset: Int,
            maxSamples: Int,
        ): PcmReadResult? {
            if (cursor >= samples.size) return null
            val count = minOf(maxSamples, samples.size - cursor)
            samples.copyInto(
                destination = target,
                destinationOffset = targetOffset,
                startIndex = cursor,
                endIndex = cursor + count,
            )
            val start = cursor.toLong()
            cursor += count
            return PcmReadResult(start, count)
        }

        override fun close() = Unit
    }

    private object FakeEngineProvider : Stage8TranscriptionEngineProvider {
        override fun vadFactory(model: ActiveModel) =
            VadEngineFactory { FakeVadEngine(model.descriptor) }

        override fun firstPassFactory(model: ActiveModel) =
            StreamingAsrEngineFactory { FakeStreamingEngine(model.descriptor) }

        override fun punctuationFactory(model: ActiveModel) =
            PunctuationEngineFactory { FakePunctuationEngine(model.descriptor) }

        override fun secondPassFactory(model: ActiveModel): SecondPassAsrEngineFactory =
            error("second pass is not used in FAST test")
    }

    private class FakeVadEngine(
        override val model: ModelDescriptor,
    ) : VadEngine {
        override suspend fun analyze(
            source: PcmSource,
            progressListener: ProgressListener?,
        ): List<SpeechSegment> {
            progressListener?.onProgress(
                TranscriptionProgress(
                    phase = TranscriptionPhase.VAD,
                    processedUnits = 0L,
                    totalUnits = source.totalSampleCount,
                ),
            )
            val buffer = ShortArray(256)
            var processed = 0L
            while (true) {
                val read = source.read(buffer) ?: break
                processed += read.sampleCount
            }
            progressListener?.onProgress(
                TranscriptionProgress(
                    phase = TranscriptionPhase.VAD,
                    processedUnits = processed,
                    totalUnits = source.totalSampleCount,
                ),
            )
            return listOf(SpeechSegment(0L, source.totalSampleCount))
        }

        override fun close() = Unit
    }

    private class FakeStreamingEngine(
        override val model: ModelDescriptor,
    ) : StreamingAsrEngine {
        override val capabilities =
            AsrCapabilities(
                supportsStreaming = true,
                supportsPartial = true,
                supportsTokenTiming = true,
                supportsLanguageDetection = false,
                supportsConfidence = false,
                supportsInverseTextNormalization = false,
                punctuationCapability = PunctuationCapability.NONE,
                supportsSecondPass = false,
            )

        override suspend fun openSession(): StreamingAsrSession =
            object : StreamingAsrSession {
                override suspend fun acceptSamples(
                    samples: ShortArray,
                    offset: Int,
                    count: Int,
                ) = Unit

                override suspend fun decode() =
                    AsrHypothesis(
                        text = "",
                        punctuationCapability = PunctuationCapability.NONE,
                        isFinal = false,
                    )

                override suspend fun finishInput() =
                    AsrHypothesis(
                        text = "测试",
                        tokens =
                            listOf(
                                RelativeTimedToken(
                                    text = "测试",
                                    startSampleOffset = 0L,
                                ),
                            ),
                        punctuationCapability = PunctuationCapability.NONE,
                        isFinal = true,
                    )

                override suspend fun reset() = Unit

                override fun close() = Unit
            }

        override fun close() = Unit
    }

    private class FakePunctuationEngine(
        override val model: ModelDescriptor,
    ) : PunctuationEngine {
        override suspend fun addPunctuation(text: String): String = text + "。"

        override fun close() = Unit
    }

    private class FakeModelManager(
        private val active: Map<String, ActiveModel>,
    ) : ModelManager {
        override val operations: StateFlow<Map<String, ModelOperationStatus>> =
            MutableStateFlow(emptyMap())

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

        override suspend fun install(modelId: String, version: String, revision: Long) =
            error("not used")

        override suspend fun cancelInstall(modelId: String) = Unit

        override suspend fun confirmInstalledVersion(
            modelId: String,
            version: String,
            revision: Long,
        ) = error("not used")

        override suspend fun removeDownloadedVersion(
            modelId: String,
            version: String,
            revision: Long,
        ) = error("not used")

        override suspend fun rollback(modelId: String) = error("not used")
    }

    companion object {
        private const val RECORDING_ID = "rec-1"
        private const val TOTAL_SAMPLES = 800
        private val DIGEST = "d".repeat(64)

        private fun active(descriptor: ModelDescriptor) =
            ActiveModel(
                descriptor = descriptor,
                manifestDigest = DIGEST,
                installedDirectory = null,
            )

        private fun descriptor(
            modelId: String,
            kind: ModelKind,
        ) =
            ModelDescriptor(
                modelId = modelId,
                kind = kind,
                displayName = modelId,
                version = "v1",
                revision = 1,
                runtimeId = "sherpa-onnx",
                runtimeVersionMin = null,
                runtimeVersionMax = null,
                languages = setOf("zh", "en"),
                capabilities =
                    ModelCapabilities(
                        supportsStreaming = kind == ModelKind.ASR_STREAMING,
                        supportsPartial = kind == ModelKind.ASR_STREAMING,
                        supportsTokenTiming = kind == ModelKind.ASR_STREAMING,
                    ),
                sourceType = ModelSourceType.BUILTIN,
                builtinAssetPath = "models/fake.bin",
                packageFormat = ModelPackageFormat.SINGLE_FILE,
                downloadUrl = null,
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
                appVersionMin = 20,
                appVersionMax = null,
                licenseId = "test",
                licenseUrl = null,
                sourceUrl = "https://example.invalid/source",
                homepage = null,
                attribution = "fixture",
                redistributionPolicy = RedistributionPolicy.NO_REDISTRIBUTION,
                releaseChannel = "test",
                autoUpdateEligible = false,
            )
    }
}
