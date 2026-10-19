package io.github.ioannes78.voica

import android.app.Application
import android.os.Debug
import android.os.SystemClock
import io.github.ioannes78.voica.audio.PcmSource
import io.github.ioannes78.voica.audio.PcmSourceResolver
import io.github.ioannes78.voica.model.ActiveModel
import io.github.ioannes78.voica.model.ModelLease
import io.github.ioannes78.voica.model.ModelManager
import io.github.ioannes78.voica.model.ModelUseRegistry
import io.github.ioannes78.voica.sherpa.SHERPA_TEN_VAD_CANDIDATE_MODEL_ID
import io.github.ioannes78.voica.sherpa.SherpaSileroVadEngine
import io.github.ioannes78.voica.sherpa.SherpaTenVadEngine
import io.github.ioannes78.voica.sherpa.SherpaVadModelLocation
import io.github.ioannes78.voica.sherpa.SileroVadSettings
import io.github.ioannes78.voica.sherpa.TenVadSettings
import io.github.ioannes78.voica.transcript.AsrBenchmarkScoring
import io.github.ioannes78.voica.transcript.ExternalVadEngineProvider
import io.github.ioannes78.voica.transcript.HighQualityTranscriptionPipeline
import io.github.ioannes78.voica.transcript.ProgressListener
import io.github.ioannes78.voica.transcript.SecondPassAsrEngine
import io.github.ioannes78.voica.transcript.SecondPassAsrEngineFactory
import io.github.ioannes78.voica.transcript.SpeechSegment
import io.github.ioannes78.voica.transcript.VadBenchmarkObservation
import io.github.ioannes78.voica.transcript.VadBenchmarkProfile
import io.github.ioannes78.voica.transcript.VadEngine
import io.github.ioannes78.voica.transcript.VadEngineFactory
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

sealed interface SpeechBenchmarkState {
    data object Idle : SpeechBenchmarkState

    data class Running(
        val recordingId: String,
        val currentProfile: VadBenchmarkProfile,
        val completedProfiles: Int,
        val totalProfiles: Int,
    ) : SpeechBenchmarkState

    data class Completed(
        val recordingId: String,
        val observations: List<VadBenchmarkObservation>,
    ) : SpeechBenchmarkState

    data class Failed(
        val recordingId: String,
        val message: String,
        val missingModelIds: List<String> = emptyList(),
    ) : SpeechBenchmarkState

    data class Cancelled(
        val recordingId: String,
    ) : SpeechBenchmarkState
}

data class SenseVoiceVadBenchmarkObservation(
    val profile: VadBenchmarkProfile,
    val speechSegmentCount: Int,
    val speechDurationMillis: Long,
    val transcriptSegmentCount: Int,
    val blankTranscriptSegmentCount: Int,
    val normalizedCharacterCount: Int,
    val punctuationCount: Int,
    val vadWallTimeMillis: Long,
    val senseVoiceWallTimeMillis: Long,
    val totalWallTimeMillis: Long,
    val peakPssKb: Long?,
    val punctuationFallbackError: String?,
    val text: String,
)

sealed interface SenseVoiceBenchmarkState {
    data object Idle : SenseVoiceBenchmarkState

    data class Running(
        val recordingId: String,
        val currentProfile: VadBenchmarkProfile,
        val completedProfiles: Int,
        val totalProfiles: Int,
    ) : SenseVoiceBenchmarkState

    data class Completed(
        val recordingId: String,
        val observations: List<SenseVoiceVadBenchmarkObservation>,
    ) : SenseVoiceBenchmarkState

    data class Failed(
        val recordingId: String,
        val message: String,
        val missingModelIds: List<String> = emptyList(),
    ) : SenseVoiceBenchmarkState

    data class Cancelled(
        val recordingId: String,
    ) : SenseVoiceBenchmarkState
}

/**
 * Stage 16B QA-only VAD Gate A runner.
 *
 * Gate A A1 compares Silero/FSMN/TEN on the same canonical PCM. Gate A A2 then runs the same
 * SenseVoice path behind each 30 s VAD profile so downstream text quality can decide whether
 * differences in detected speech are useful recall or harmful false-positive/silence capture.
 * Normal product transcription continues to use the production VAD path.
 */
class SpeechBenchmarkRunner(
    private val application: Application,
    private val pcmSourceResolver: PcmSourceResolver,
    private val modelManager: ModelManager,
    private val modelUseRegistry: ModelUseRegistry,
    private val engineProvider: Stage8TranscriptionEngineProvider,
    private val localSpeechSettings: () -> LocalSpeechSettings,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private var currentJob: Job? = null
    private val mutableState = MutableStateFlow<SpeechBenchmarkState>(SpeechBenchmarkState.Idle)
    private val mutableA2State =
        MutableStateFlow<SenseVoiceBenchmarkState>(SenseVoiceBenchmarkState.Idle)

    val state: StateFlow<SpeechBenchmarkState> = mutableState.asStateFlow()
    val a2State: StateFlow<SenseVoiceBenchmarkState> = mutableA2State.asStateFlow()

    fun startVadGateA(recordingId: String): Boolean {
        require(recordingId.isNotBlank())
        checkQaBuild()
        synchronized(lock) {
            if (currentJob?.isActive == true) return false
            val job = scope.launch { runVadGateA(recordingId) }
            currentJob = job
            job.invokeOnCompletion {
                synchronized(lock) {
                    if (currentJob === job) currentJob = null
                }
            }
            return true
        }
    }

    fun startSenseVoiceGateA2(recordingId: String): Boolean {
        require(recordingId.isNotBlank())
        checkQaBuild()
        synchronized(lock) {
            if (currentJob?.isActive == true) return false
            val job = scope.launch { runSenseVoiceGateA2(recordingId) }
            currentJob = job
            job.invokeOnCompletion {
                synchronized(lock) {
                    if (currentJob === job) currentJob = null
                }
            }
            return true
        }
    }

    fun cancel() {
        synchronized(lock) {
            currentJob?.cancel(CancellationException("user cancelled Stage 16B Gate A benchmark"))
        }
    }

    fun reset() {
        synchronized(lock) {
            if (currentJob?.isActive == true) return
            mutableState.value = SpeechBenchmarkState.Idle
        }
    }

    fun resetA2() {
        synchronized(lock) {
            if (currentJob?.isActive == true) return
            mutableA2State.value = SenseVoiceBenchmarkState.Idle
        }
    }

    private fun checkQaBuild() {
        check(VoicaModelChannel.isDebuggable(application)) {
            "Stage 16B VAD benchmark is restricted to Debug/QA builds"
        }
    }

    private suspend fun runVadGateA(recordingId: String) {
        val observations = mutableListOf<VadBenchmarkObservation>()
        try {
            val resolved = resolveGateAModels()
            val missing = resolved.missingModelIds
            if (missing.isNotEmpty()) {
                mutableState.value =
                    SpeechBenchmarkState.Failed(
                        recordingId = recordingId,
                        message = "Gate A 候选模型未全部启用",
                        missingModelIds = missing,
                    )
                return
            }

            val models = checkNotNull(resolved.models)
            val threads = localSpeechSettings().resolvePerformance().effectiveThreads
            val profiles = gateAProfiles()
            profiles.forEachIndexed { index, profile ->
                mutableState.value =
                    SpeechBenchmarkState.Running(
                        recordingId = recordingId,
                        currentProfile = profile,
                        completedProfiles = index,
                        totalProfiles = profiles.size,
                    )
                val model = models.getValue(profile.modelId)
                observations +=
                    runProfile(
                        recordingId = recordingId,
                        profile = profile,
                        model = model,
                        numThreads = threads,
                    )
            }

            mutableState.value =
                SpeechBenchmarkState.Completed(
                    recordingId = recordingId,
                    observations = observations.toList(),
                )
        } catch (cancelled: CancellationException) {
            mutableState.value = SpeechBenchmarkState.Cancelled(recordingId)
            throw cancelled
        } catch (error: Exception) {
            mutableState.value =
                SpeechBenchmarkState.Failed(
                    recordingId = recordingId,
                    message = error.message ?: error::class.java.simpleName,
                )
        }
    }

    private suspend fun runSenseVoiceGateA2(recordingId: String) {
        val observations = mutableListOf<SenseVoiceVadBenchmarkObservation>()
        try {
            val resolved = resolveGateA2Models()
            if (resolved.missingModelIds.isNotEmpty()) {
                mutableA2State.value =
                    SenseVoiceBenchmarkState.Failed(
                        recordingId = recordingId,
                        message = "A2 所需 VAD / SenseVoice 模型未全部启用",
                        missingModelIds = resolved.missingModelIds,
                    )
                return
            }

            val vadModels = checkNotNull(resolved.vadModels)
            val senseVoice = checkNotNull(resolved.senseVoice)
            val settings = localSpeechSettings()
            val threads = settings.resolvePerformance().effectiveThreads
            val profiles = gateAProfiles().filter(VadBenchmarkProfile::primaryGateAProfile)
            profiles.forEachIndexed { index, profile ->
                mutableA2State.value =
                    SenseVoiceBenchmarkState.Running(
                        recordingId = recordingId,
                        currentProfile = profile,
                        completedProfiles = index,
                        totalProfiles = profiles.size,
                    )
                observations +=
                    runSenseVoiceProfile(
                        recordingId = recordingId,
                        profile = profile,
                        vadModel = vadModels.getValue(profile.modelId),
                        senseVoice = senseVoice,
                        punctuation = resolved.punctuation,
                        settings = settings,
                        numThreads = threads,
                    )
            }

            mutableA2State.value =
                SenseVoiceBenchmarkState.Completed(
                    recordingId = recordingId,
                    observations = observations.toList(),
                )
        } catch (cancelled: CancellationException) {
            mutableA2State.value = SenseVoiceBenchmarkState.Cancelled(recordingId)
            throw cancelled
        } catch (error: Exception) {
            mutableA2State.value =
                SenseVoiceBenchmarkState.Failed(
                    recordingId = recordingId,
                    message = error.message ?: error::class.java.simpleName,
                )
        }
    }

    private suspend fun runProfile(
        recordingId: String,
        profile: VadBenchmarkProfile,
        model: ActiveModel,
        numThreads: Int,
    ): VadBenchmarkObservation =
        supervisorScope {
            val lease = acquire(model)
            try {
                val source =
                    pcmSourceResolver.resolvePcmSource(recordingId)
                        ?: error("canonical PCM source is unavailable")
                source.use { pcm ->
                    val peakPssKb = AtomicLong(Debug.getPss().toLong())
                    val sampler = launchPssSampler(peakPssKb)
                    try {
                        openVadEngine(profile, model, numThreads).use { engine ->
                            val startedNs = SystemClock.elapsedRealtimeNanos()
                            val segments = engine.analyze(pcm)
                            val elapsedMs = elapsedMillis(startedNs)
                            updatePeak(peakPssKb, Debug.getPss().toLong())
                            VadBenchmarkObservation(
                                profile = profile,
                                modelVersion = model.descriptor.version,
                                modelRevision = model.descriptor.revision,
                                sampleRateHz = pcm.sampleRateHz,
                                totalSampleCount = pcm.totalSampleCount,
                                wallTimeMillis = elapsedMs,
                                peakPssKb = peakPssKb.get(),
                                segments = segments,
                            )
                        }
                    } finally {
                        sampler.cancelAndJoin()
                    }
                }
            } finally {
                lease.close()
            }
        }

    private suspend fun runSenseVoiceProfile(
        recordingId: String,
        profile: VadBenchmarkProfile,
        vadModel: ActiveModel,
        senseVoice: ActiveModel,
        punctuation: ActiveModel?,
        settings: LocalSpeechSettings,
        numThreads: Int,
    ): SenseVoiceVadBenchmarkObservation =
        supervisorScope {
            val leases =
                listOfNotNull(vadModel, senseVoice, punctuation)
                    .distinctBy { it.descriptor.modelId }
                    .map(::acquire)
            try {
                val vadElapsedMs = AtomicLong(0L)
                val asrElapsedMs = AtomicLong(0L)
                val peakPssKb = AtomicLong(Debug.getPss().toLong())
                val sampler = launchPssSampler(peakPssKb)
                try {
                    val vadFactory =
                        VadEngineFactory {
                            MeasuringVadEngine(
                                delegate = openVadEngine(profile, vadModel, numThreads),
                                elapsedMillis = vadElapsedMs,
                            )
                        }
                    val baseSecondPassFactory =
                        engineProvider.secondPassFactory(
                            model = senseVoice,
                            numThreads = numThreads,
                            senseVoiceSettings = settings.senseVoice,
                            qwenSettings = settings.qwen,
                        )
                    val secondPassFactory =
                        SecondPassAsrEngineFactory {
                            MeasuringSecondPassAsrEngine(
                                delegate = baseSecondPassFactory.open(),
                                elapsedMillis = asrElapsedMs,
                            )
                        }
                    val punctuationFactory =
                        punctuation?.let { model ->
                            engineProvider.punctuationFactory(model, numThreads)
                        }

                    val startedNs = SystemClock.elapsedRealtimeNanos()
                    val result =
                        HighQualityTranscriptionPipeline(
                            pcmSourceResolver = pcmSourceResolver,
                            vadEngineFactory = vadFactory,
                            secondPassAsrEngineFactory = secondPassFactory,
                            punctuationEngineFactory = punctuationFactory,
                            timelineAlignmentAsrEngineFactory = null,
                        ).transcribe(recordingId)
                    val totalWallTimeMillis = elapsedMillis(startedNs)
                    updatePeak(peakPssKb, Debug.getPss().toLong())

                    val text =
                        result.transcriptSegments
                            .joinToString(separator = "\n") { it.finalText.trim() }
                            .trim()
                    val normalized = AsrBenchmarkScoring.normalizeForCharacterErrorRate(text)
                    val normalizedCharacterCount =
                        normalized.codePointCount(0, normalized.length)
                    val speechSamples = result.speechSegments.sumOf(SpeechSegment::sampleCount)

                    SenseVoiceVadBenchmarkObservation(
                        profile = profile,
                        speechSegmentCount = result.speechSegments.size,
                        speechDurationMillis = samplesToMillis(speechSamples),
                        transcriptSegmentCount = result.transcriptSegments.size,
                        blankTranscriptSegmentCount =
                            result.transcriptSegments.count { it.finalText.isBlank() },
                        normalizedCharacterCount = normalizedCharacterCount,
                        punctuationCount = countBenchmarkPunctuation(text),
                        vadWallTimeMillis = vadElapsedMs.get(),
                        senseVoiceWallTimeMillis = asrElapsedMs.get(),
                        totalWallTimeMillis = totalWallTimeMillis,
                        peakPssKb = peakPssKb.get(),
                        punctuationFallbackError = result.punctuationFallbackError,
                        text = text,
                    )
                } finally {
                    sampler.cancelAndJoin()
                }
            } finally {
                leases.asReversed().forEach(ModelLease::close)
            }
        }

    private fun openVadEngine(
        profile: VadBenchmarkProfile,
        model: ActiveModel,
        numThreads: Int,
    ): VadEngine =
        when (profile.modelId) {
            Stage8ModelIds.VAD ->
                SherpaSileroVadEngine(
                    model = model.descriptor,
                    modelLocation = sherpaVadLocation(model),
                    settings =
                        SileroVadSettings(
                            threshold = 0.5F,
                            minSilenceDurationSeconds = 0.25F,
                            minSpeechDurationSeconds = 0.25F,
                            maxSpeechDurationSeconds = profile.maxSpeechDurationSeconds,
                            numThreads = numThreads,
                        ),
                )

            STAGE16B_TEN_VAD_CANDIDATE_ID ->
                SherpaTenVadEngine(
                    model = model.descriptor,
                    modelLocation = sherpaVadLocation(model),
                    settings =
                        TenVadSettings(
                            threshold = 0.5F,
                            minSilenceDurationSeconds = 0.25F,
                            minSpeechDurationSeconds = 0.25F,
                            windowSizeSamples = 256,
                            maxSpeechDurationSeconds = profile.maxSpeechDurationSeconds,
                            numThreads = numThreads,
                        ),
                )

            STAGE16B_FSMN_VAD_CANDIDATE_ID ->
                fsmnProvider().open(
                    model = model.descriptor,
                    installedDirectory =
                        model.installedDirectory
                            ?: error("FSMN candidate must be an installed managed model"),
                    numThreads = numThreads,
                    maxSpeechDurationSeconds = profile.maxSpeechDurationSeconds,
                )

            else -> error("unsupported Gate A VAD model: ${profile.modelId}")
        }

    private fun sherpaVadLocation(model: ActiveModel): SherpaVadModelLocation {
        val installed = model.installedDirectory
        if (installed != null) {
            val modelFile =
                model.descriptor.files
                    .singleOrNull { it.relativePath.endsWith(".onnx", ignoreCase = true) }
                    ?.let { File(installed, it.relativePath).canonicalFile }
                    ?: error("VAD model must contain exactly one ONNX file")
            require(modelFile.isFile) { "VAD model file is missing: ${modelFile.name}" }
            return SherpaVadModelLocation.File(modelFile.absolutePath)
        }
        val assetPath =
            model.descriptor.builtinAssetPath
                ?: error("VAD model is neither installed nor backed by a builtin asset")
        return SherpaVadModelLocation.Asset(application.assets, assetPath)
    }

    private fun fsmnProvider(): ExternalVadEngineProvider {
        val className = "io.github.ioannes78.voica.fsmn.FsmnVadEngineProvider"
        val providerClass =
            try {
                Class.forName(className)
            } catch (error: ClassNotFoundException) {
                error(
                    "FSMN candidate runtime is unavailable in this build; use a Stage 16B QA APK",
                )
            }
        return providerClass.getDeclaredConstructor().newInstance() as? ExternalVadEngineProvider
            ?: error("FSMN candidate provider does not implement ExternalVadEngineProvider")
    }

    private suspend fun resolveGateAModels(): GateAModelResolution {
        val ids =
            listOf(
                Stage8ModelIds.VAD,
                STAGE16B_FSMN_VAD_CANDIDATE_ID,
                STAGE16B_TEN_VAD_CANDIDATE_ID,
            )
        val models = linkedMapOf<String, ActiveModel>()
        ids.forEach { id ->
            modelManager.activeModel(id)?.let { models[id] = it }
        }
        val missing = ids.filterNot(models::containsKey)
        return if (missing.isEmpty()) {
            GateAModelResolution(models = models, missingModelIds = emptyList())
        } else {
            GateAModelResolution(models = null, missingModelIds = missing)
        }
    }

    private suspend fun resolveGateA2Models(): GateA2ModelResolution {
        val vadResolution = resolveGateAModels()
        val missing = vadResolution.missingModelIds.toMutableList()
        val senseVoice = modelManager.activeModel(Stage8ModelIds.SECOND_PASS_ASR)
        if (senseVoice == null) missing += Stage8ModelIds.SECOND_PASS_ASR
        return if (missing.isEmpty()) {
            GateA2ModelResolution(
                vadModels = checkNotNull(vadResolution.models),
                senseVoice = checkNotNull(senseVoice),
                punctuation = modelManager.activeModel(Stage8ModelIds.PUNCTUATION),
                missingModelIds = emptyList(),
            )
        } else {
            GateA2ModelResolution(
                vadModels = null,
                senseVoice = null,
                punctuation = null,
                missingModelIds = missing.distinct(),
            )
        }
    }

    private fun gateAProfiles(): List<VadBenchmarkProfile> =
        listOf(
            VadBenchmarkProfile(
                id = "silero-30s",
                displayName = "Silero · 30s 控制组",
                modelId = Stage8ModelIds.VAD,
                maxSpeechDurationSeconds = 30.0F,
                primaryGateAProfile = true,
                notes = listOf("production baseline", "threshold=0.5", "minSilence=250ms"),
            ),
            VadBenchmarkProfile(
                id = "fsmn-30s",
                displayName = "FSMN · 30s 控制组",
                modelId = STAGE16B_FSMN_VAD_CANDIDATE_ID,
                maxSpeechDurationSeconds = 30.0F,
                primaryGateAProfile = true,
                notes = listOf("endpoint=800/150/100ms", "speechNoiseThreshold=0.6"),
            ),
            VadBenchmarkProfile(
                id = "ten-30s",
                displayName = "TEN · 30s 控制组",
                modelId = STAGE16B_TEN_VAD_CANDIDATE_ID,
                maxSpeechDurationSeconds = 30.0F,
                primaryGateAProfile = true,
                notes = listOf("threshold=0.5", "minSilence=250ms", "window=256"),
            ),
            VadBenchmarkProfile(
                id = "ten-5s-official",
                displayName = "TEN · 官方 5s 参考",
                modelId = SHERPA_TEN_VAD_CANDIDATE_MODEL_ID,
                maxSpeechDurationSeconds = 5.0F,
                primaryGateAProfile = false,
                notes = listOf("secondary reference; not primary model-quality row"),
            ),
        )

    private fun acquire(model: ActiveModel): ModelLease =
        modelUseRegistry.acquire(
            modelId = model.descriptor.modelId,
            version = model.descriptor.version,
            revision = model.descriptor.revision,
        )

    private fun CoroutineScope.launchPssSampler(target: AtomicLong): Job =
        launch(Dispatchers.Default) {
            while (isActive) {
                updatePeak(target, Debug.getPss().toLong())
                delay(PSS_SAMPLE_INTERVAL_MS)
            }
        }

    private fun updatePeak(
        target: AtomicLong,
        candidate: Long,
    ) {
        while (true) {
            val current = target.get()
            if (candidate <= current || target.compareAndSet(current, candidate)) return
        }
    }

    private fun elapsedMillis(startedNs: Long): Long =
        ((SystemClock.elapsedRealtimeNanos() - startedNs) / 1_000_000L).coerceAtLeast(0L)

    private fun samplesToMillis(samples: Long): Long =
        ((samples * 1000.0) / CANONICAL_SAMPLE_RATE_HZ.toDouble()).toLong()

    private data class GateAModelResolution(
        val models: Map<String, ActiveModel>?,
        val missingModelIds: List<String>,
    )

    private data class GateA2ModelResolution(
        val vadModels: Map<String, ActiveModel>?,
        val senseVoice: ActiveModel?,
        val punctuation: ActiveModel?,
        val missingModelIds: List<String>,
    )

    private class MeasuringVadEngine(
        private val delegate: VadEngine,
        private val elapsedMillis: AtomicLong,
    ) : VadEngine {
        override val model
            get() = delegate.model

        override suspend fun analyze(
            source: PcmSource,
            progressListener: ProgressListener?,
        ): List<SpeechSegment> {
            val startedNs = SystemClock.elapsedRealtimeNanos()
            return try {
                delegate.analyze(source, progressListener)
            } finally {
                elapsedMillis.addAndGet(
                    ((SystemClock.elapsedRealtimeNanos() - startedNs) / 1_000_000L)
                        .coerceAtLeast(0L),
                )
            }
        }

        override fun close() = delegate.close()
    }

    private class MeasuringSecondPassAsrEngine(
        private val delegate: SecondPassAsrEngine,
        private val elapsedMillis: AtomicLong,
    ) : SecondPassAsrEngine {
        override val model
            get() = delegate.model
        override val capabilities
            get() = delegate.capabilities

        override suspend fun transcribe(
            samples: ShortArray,
            sampleRateHz: Int,
        ) = try {
            val startedNs = SystemClock.elapsedRealtimeNanos()
            try {
                delegate.transcribe(samples, sampleRateHz)
            } finally {
                elapsedMillis.addAndGet(
                    ((SystemClock.elapsedRealtimeNanos() - startedNs) / 1_000_000L)
                        .coerceAtLeast(0L),
                )
            }
        } finally {
            // Deliberately empty: timing is accumulated in the inner finally.
        }

        override fun close() = delegate.close()
    }

    companion object {
        const val STAGE16B_FSMN_VAD_CANDIDATE_ID = "fsmn-vad-int8"
        const val STAGE16B_TEN_VAD_CANDIDATE_ID = "ten-vad-fp32"
        private const val PSS_SAMPLE_INTERVAL_MS = 50L
        private const val CANONICAL_SAMPLE_RATE_HZ = 16_000
    }
}

internal fun countBenchmarkPunctuation(text: String): Int {
    val punctuation = setOf('，', '。', '！', '？', '；', '：', '、', ',', '.', '!', '?', ';', ':')
    return text.count { it in punctuation }
}
