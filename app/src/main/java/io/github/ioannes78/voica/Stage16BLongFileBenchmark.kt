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
import io.github.ioannes78.voica.sherpa.SherpaSileroVadEngine
import io.github.ioannes78.voica.sherpa.SherpaVadModelLocation
import io.github.ioannes78.voica.sherpa.SileroVadSettings
import io.github.ioannes78.voica.transcript.AsrBenchmarkScoring
import io.github.ioannes78.voica.transcript.ExternalVadEngineProvider
import io.github.ioannes78.voica.transcript.HighQualityTranscriptionPipeline
import io.github.ioannes78.voica.transcript.ProgressListener
import io.github.ioannes78.voica.transcript.SecondPassAsrEngine
import io.github.ioannes78.voica.transcript.SecondPassAsrEngineFactory
import io.github.ioannes78.voica.transcript.SpeechSegment
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

/**
 * Stage 16B Gate A4 long-file / thermal-stability benchmark.
 *
 * Gate A has been narrowed to the production Silero baseline and the FSMN challenger. A4 keeps
 * the downstream SenseVoice path identical and records end-to-end runtime plus early-vs-late ASR
 * RTF as a practical thermal-throttling signal. TEN is intentionally excluded from A4.
 *
 * This runner is QA-only. It never persists transcripts and never changes the production VAD.
 */
data class LongFileVadBenchmarkObservation(
    val profile: VadBenchmarkProfile,
    val recordingDurationMillis: Long,
    val speechSegmentCount: Int,
    val speechDurationMillis: Long,
    val transcriptSegmentCount: Int,
    val blankTranscriptSegmentCount: Int,
    val normalizedCharacterCount: Int,
    val asrCallCount: Int,
    val vadWallTimeMillis: Long,
    val senseVoiceWallTimeMillis: Long,
    val totalWallTimeMillis: Long,
    val totalRtf: Double?,
    val senseVoiceRtf: Double?,
    val firstQuarterSenseVoiceRtf: Double?,
    val lastQuarterSenseVoiceRtf: Double?,
    val thermalSlowdownRatio: Double?,
    val startingPssKb: Long,
    val peakPssKb: Long,
    val effectiveThreads: Int,
    val senseVoiceLanguage: String,
    val senseVoiceItn: Boolean,
    val punctuationFallbackError: String?,
)

sealed interface LongFileVadBenchmarkState {
    data object Idle : LongFileVadBenchmarkState

    data class Running(
        val recordingId: String,
        val currentProfile: VadBenchmarkProfile,
        val completedProfiles: Int,
        val totalProfiles: Int,
    ) : LongFileVadBenchmarkState

    data class Completed(
        val recordingId: String,
        val observations: List<LongFileVadBenchmarkObservation>,
    ) : LongFileVadBenchmarkState

    data class Failed(
        val recordingId: String,
        val message: String,
        val missingModelIds: List<String> = emptyList(),
    ) : LongFileVadBenchmarkState

    data class Cancelled(
        val recordingId: String,
    ) : LongFileVadBenchmarkState
}

class Stage16BLongFileBenchmarkRunner(
    private val application: Application,
    private val pcmSourceResolver: PcmSourceResolver,
    private val modelManager: ModelManager,
    private val modelUseRegistry: ModelUseRegistry,
    private val localSpeechSettings: () -> LocalSpeechSettings,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private var currentJob: Job? = null
    private val engineProvider = SherpaStage8TranscriptionEngineProvider(application.assets)
    private val mutableState =
        MutableStateFlow<LongFileVadBenchmarkState>(LongFileVadBenchmarkState.Idle)

    val state: StateFlow<LongFileVadBenchmarkState> = mutableState.asStateFlow()

    fun start(recordingId: String): Boolean {
        require(recordingId.isNotBlank())
        checkQaBuild()
        synchronized(lock) {
            if (currentJob?.isActive == true) return false
            val job = scope.launch { runGateA4(recordingId) }
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
            currentJob?.cancel(CancellationException("user cancelled Stage 16B Gate A4 benchmark"))
        }
    }

    fun reset() {
        synchronized(lock) {
            if (currentJob?.isActive == true) return
            mutableState.value = LongFileVadBenchmarkState.Idle
        }
    }

    private fun checkQaBuild() {
        check(VoicaModelChannel.isDebuggable(application)) {
            "Stage 16B Gate A4 is restricted to Debug/QA builds"
        }
    }

    private suspend fun runGateA4(recordingId: String) {
        val observations = mutableListOf<LongFileVadBenchmarkObservation>()
        try {
            val resolved = resolveModels()
            if (resolved.missingModelIds.isNotEmpty()) {
                mutableState.value =
                    LongFileVadBenchmarkState.Failed(
                        recordingId = recordingId,
                        message = "A4 所需 Silero / FSMN / SenseVoice 模型未全部启用",
                        missingModelIds = resolved.missingModelIds,
                    )
                return
            }

            val vadModels = checkNotNull(resolved.vadModels)
            val senseVoice = checkNotNull(resolved.senseVoice)
            val settings = localSpeechSettings()
            val threads = settings.resolvePerformance().effectiveThreads
            val profiles = gateA4Profiles()

            profiles.forEachIndexed { index, profile ->
                mutableState.value =
                    LongFileVadBenchmarkState.Running(
                        recordingId = recordingId,
                        currentProfile = profile,
                        completedProfiles = index,
                        totalProfiles = profiles.size,
                    )
                observations +=
                    runProfile(
                        recordingId = recordingId,
                        profile = profile,
                        vadModel = vadModels.getValue(profile.modelId),
                        senseVoice = senseVoice,
                        punctuation = resolved.punctuation,
                        settings = settings,
                        numThreads = threads,
                    )
            }

            mutableState.value =
                LongFileVadBenchmarkState.Completed(
                    recordingId = recordingId,
                    observations = observations.toList(),
                )
        } catch (cancelled: CancellationException) {
            mutableState.value = LongFileVadBenchmarkState.Cancelled(recordingId)
            throw cancelled
        } catch (error: Exception) {
            mutableState.value =
                LongFileVadBenchmarkState.Failed(
                    recordingId = recordingId,
                    message = error.message ?: error::class.java.simpleName,
                )
        }
    }

    private suspend fun runProfile(
        recordingId: String,
        profile: VadBenchmarkProfile,
        vadModel: ActiveModel,
        senseVoice: ActiveModel,
        punctuation: ActiveModel?,
        settings: LocalSpeechSettings,
        numThreads: Int,
    ): LongFileVadBenchmarkObservation =
        supervisorScope {
            val leases =
                listOfNotNull(vadModel, senseVoice, punctuation)
                    .distinctBy { it.descriptor.modelId }
                    .map(::acquire)
            try {
                val durationSource =
                    pcmSourceResolver.resolvePcmSource(recordingId)
                        ?: error("canonical PCM source is unavailable")
                val recordingSamples = durationSource.totalSampleCount
                require(durationSource.sampleRateHz == CANONICAL_SAMPLE_RATE_HZ)
                require(durationSource.channelCount == 1)
                durationSource.close()

                val vadElapsedMs = AtomicLong(0L)
                val asrElapsedMs = AtomicLong(0L)
                val timingCollector = AsrTimingCollector()
                val startingPssKb = Debug.getPss().toLong()
                val peakPssKb = AtomicLong(startingPssKb)
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
                                timingCollector = timingCollector,
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

                    val speechSamples = result.speechSegments.sumOf(SpeechSegment::sampleCount)
                    val recordingDurationMillis = samplesToMillis(recordingSamples)
                    val speechDurationMillis = samplesToMillis(speechSamples)
                    val text =
                        result.transcriptSegments
                            .joinToString(separator = "\n") { it.finalText.trim() }
                            .trim()
                    val normalized = AsrBenchmarkScoring.normalizeForCharacterErrorRate(text)
                    val timings = timingCollector.snapshot()
                    val quarterSize = if (timings.isEmpty()) 0 else maxOf(1, timings.size / 4)
                    val firstQuarterRtf =
                        timings.take(quarterSize).takeIf { it.isNotEmpty() }?.let(::timingRtf)
                    val lastQuarterRtf =
                        timings.takeLast(quarterSize).takeIf { it.isNotEmpty() }?.let(::timingRtf)
                    val slowdownRatio =
                        if (firstQuarterRtf != null && firstQuarterRtf > 0.0 && lastQuarterRtf != null) {
                            lastQuarterRtf / firstQuarterRtf
                        } else {
                            null
                        }

                    LongFileVadBenchmarkObservation(
                        profile = profile,
                        recordingDurationMillis = recordingDurationMillis,
                        speechSegmentCount = result.speechSegments.size,
                        speechDurationMillis = speechDurationMillis,
                        transcriptSegmentCount = result.transcriptSegments.size,
                        blankTranscriptSegmentCount =
                            result.transcriptSegments.count { it.finalText.isBlank() },
                        normalizedCharacterCount =
                            normalized.codePointCount(0, normalized.length),
                        asrCallCount = timings.size,
                        vadWallTimeMillis = vadElapsedMs.get(),
                        senseVoiceWallTimeMillis = asrElapsedMs.get(),
                        totalWallTimeMillis = totalWallTimeMillis,
                        totalRtf = ratio(totalWallTimeMillis, recordingDurationMillis),
                        senseVoiceRtf = ratio(asrElapsedMs.get(), speechDurationMillis),
                        firstQuarterSenseVoiceRtf = firstQuarterRtf,
                        lastQuarterSenseVoiceRtf = lastQuarterRtf,
                        thermalSlowdownRatio = slowdownRatio,
                        startingPssKb = startingPssKb,
                        peakPssKb = peakPssKb.get(),
                        effectiveThreads = numThreads,
                        senseVoiceLanguage = settings.senseVoice.language.runtimeValue.ifBlank { "AUTO" },
                        senseVoiceItn = settings.senseVoice.useInverseTextNormalization,
                        punctuationFallbackError = result.punctuationFallbackError,
                    )
                } finally {
                    sampler.cancelAndJoin()
                }
            } finally {
                leases.asReversed().forEach(ModelLease::close)
            }
        }

    private suspend fun resolveModels(): ModelResolution {
        val silero = modelManager.activeModel(Stage8ModelIds.VAD)
        val fsmn = modelManager.activeModel(SpeechBenchmarkRunner.STAGE16B_FSMN_VAD_CANDIDATE_ID)
        val senseVoice = modelManager.activeModel(Stage8ModelIds.SECOND_PASS_ASR)
        val missing = mutableListOf<String>()
        if (silero == null) missing += Stage8ModelIds.VAD
        if (fsmn == null) missing += SpeechBenchmarkRunner.STAGE16B_FSMN_VAD_CANDIDATE_ID
        if (senseVoice == null) missing += Stage8ModelIds.SECOND_PASS_ASR
        return if (missing.isEmpty()) {
            ModelResolution(
                vadModels =
                    mapOf(
                        Stage8ModelIds.VAD to checkNotNull(silero),
                        SpeechBenchmarkRunner.STAGE16B_FSMN_VAD_CANDIDATE_ID to checkNotNull(fsmn),
                    ),
                senseVoice = checkNotNull(senseVoice),
                punctuation = modelManager.activeModel(Stage8ModelIds.PUNCTUATION),
                missingModelIds = emptyList(),
            )
        } else {
            ModelResolution(
                vadModels = null,
                senseVoice = null,
                punctuation = null,
                missingModelIds = missing,
            )
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
                            maxSpeechDurationSeconds = 30F,
                            numThreads = numThreads,
                        ),
                )

            SpeechBenchmarkRunner.STAGE16B_FSMN_VAD_CANDIDATE_ID ->
                fsmnProvider().open(
                    model = model.descriptor,
                    installedDirectory =
                        model.installedDirectory
                            ?: error("FSMN candidate must be an installed managed model"),
                    numThreads = numThreads,
                    maxSpeechDurationSeconds = 30F,
                )

            else -> error("unsupported Gate A4 VAD model: ${profile.modelId}")
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
                error("FSMN candidate runtime is unavailable in this build; use a Stage 16B QA APK")
            }
        return providerClass.getDeclaredConstructor().newInstance() as? ExternalVadEngineProvider
            ?: error("FSMN candidate provider does not implement ExternalVadEngineProvider")
    }

    private fun gateA4Profiles(): List<VadBenchmarkProfile> =
        listOf(
            VadBenchmarkProfile(
                id = "silero-30s-a4",
                displayName = "Silero · 30s 基线",
                modelId = Stage8ModelIds.VAD,
                maxSpeechDurationSeconds = 30F,
                primaryGateAProfile = true,
                notes = listOf("A4 long-file production baseline"),
            ),
            VadBenchmarkProfile(
                id = "fsmn-30s-a4",
                displayName = "FSMN · 30s 候选",
                modelId = SpeechBenchmarkRunner.STAGE16B_FSMN_VAD_CANDIDATE_ID,
                maxSpeechDurationSeconds = 30F,
                primaryGateAProfile = true,
                notes = listOf("A4 long-file shortlisted challenger"),
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

    private fun ratio(numeratorMillis: Long, denominatorMillis: Long): Double? =
        denominatorMillis.takeIf { it > 0L }?.let { numeratorMillis.toDouble() / it.toDouble() }

    private fun timingRtf(timings: List<AsrCallTiming>): Double {
        val elapsed = timings.sumOf(AsrCallTiming::elapsedMillis).toDouble()
        val audioMillis =
            timings.sumOf { timing ->
                timing.sampleCount.toDouble() * 1000.0 / CANONICAL_SAMPLE_RATE_HZ.toDouble()
            }
        return if (audioMillis > 0.0) elapsed / audioMillis else 0.0
    }

    private data class ModelResolution(
        val vadModels: Map<String, ActiveModel>?,
        val senseVoice: ActiveModel?,
        val punctuation: ActiveModel?,
        val missingModelIds: List<String>,
    )

    private data class AsrCallTiming(
        val sampleCount: Int,
        val elapsedMillis: Long,
    )

    private class AsrTimingCollector {
        private val lock = Any()
        private val values = mutableListOf<AsrCallTiming>()

        fun add(timing: AsrCallTiming) {
            synchronized(lock) { values += timing }
        }

        fun snapshot(): List<AsrCallTiming> = synchronized(lock) { values.toList() }
    }

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
        private val timingCollector: AsrTimingCollector,
    ) : SecondPassAsrEngine {
        override val model
            get() = delegate.model
        override val capabilities
            get() = delegate.capabilities

        override suspend fun transcribe(
            samples: ShortArray,
            sampleRateHz: Int,
        ) = run {
            val startedNs = SystemClock.elapsedRealtimeNanos()
            try {
                delegate.transcribe(samples, sampleRateHz)
            } finally {
                val elapsed =
                    ((SystemClock.elapsedRealtimeNanos() - startedNs) / 1_000_000L)
                        .coerceAtLeast(0L)
                elapsedMillis.addAndGet(elapsed)
                timingCollector.add(AsrCallTiming(samples.size, elapsed))
            }
        }

        override fun close() = delegate.close()
    }

    companion object {
        private const val PSS_SAMPLE_INTERVAL_MS = 100L
        private const val CANONICAL_SAMPLE_RATE_HZ = 16_000
    }
}
