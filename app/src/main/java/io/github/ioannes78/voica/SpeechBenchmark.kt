package io.github.ioannes78.voica

import android.app.Application
import android.os.Debug
import android.os.SystemClock
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
import io.github.ioannes78.voica.transcript.ExternalVadEngineProvider
import io.github.ioannes78.voica.transcript.VadBenchmarkObservation
import io.github.ioannes78.voica.transcript.VadBenchmarkProfile
import io.github.ioannes78.voica.transcript.VadEngine
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

/**
 * Stage 16B QA-only VAD Gate A runner.
 *
 * Stage 13A's benchmark product UI remains retired. MainActivity only exposes this runner when
 * [VoicaModelChannel.isDebuggable] is true, which includes explicit Debug/QA candidate builds but
 * not formal release. Normal transcription continues to use the production Silero path.
 *
 * Gate A primary model-quality comparison uses a common 30 s maximum speech duration for Silero,
 * FSMN and TEN. TEN's upstream 5 s default is retained as a labelled secondary reference row so
 * forced-split policy is not confused with VAD-model quality.
 */
class SpeechBenchmarkRunner(
    private val application: Application,
    private val pcmSourceResolver: PcmSourceResolver,
    private val modelManager: ModelManager,
    private val modelUseRegistry: ModelUseRegistry,
    @Suppress("UNUSED_PARAMETER")
    engineProvider: Stage8TranscriptionEngineProvider,
    private val localSpeechSettings: () -> LocalSpeechSettings,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private var currentJob: Job? = null
    private val mutableState = MutableStateFlow<SpeechBenchmarkState>(SpeechBenchmarkState.Idle)

    val state: StateFlow<SpeechBenchmarkState> = mutableState.asStateFlow()

    fun startVadGateA(recordingId: String): Boolean {
        require(recordingId.isNotBlank())
        check(VoicaModelChannel.isDebuggable(application)) {
            "Stage 16B VAD benchmark is restricted to Debug/QA builds"
        }
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

    fun cancel() {
        synchronized(lock) {
            currentJob?.cancel(CancellationException("user cancelled VAD Gate A benchmark"))
        }
    }

    fun reset() {
        synchronized(lock) {
            if (currentJob?.isActive == true) return
            mutableState.value = SpeechBenchmarkState.Idle
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

    private suspend fun runProfile(
        recordingId: String,
        profile: VadBenchmarkProfile,
        model: ActiveModel,
        numThreads: Int,
    ): VadBenchmarkObservation =
        supervisorScope {
            val lease =
                modelUseRegistry.acquire(
                    modelId = model.descriptor.modelId,
                    version = model.descriptor.version,
                    revision = model.descriptor.revision,
                )
            try {
                val source =
                    pcmSourceResolver.resolvePcmSource(recordingId)
                        ?: error("canonical PCM source is unavailable")
                source.use { pcm ->
                    val peakPssKb = AtomicLong(Debug.getPss().toLong())
                    val sampler =
                        launch(Dispatchers.Default) {
                            while (isActive) {
                                updatePeak(peakPssKb, Debug.getPss().toLong())
                                delay(PSS_SAMPLE_INTERVAL_MS)
                            }
                        }
                    try {
                        openVadEngine(profile, model, numThreads).use { engine ->
                            val startedNs = SystemClock.elapsedRealtimeNanos()
                            val segments = engine.analyze(pcm)
                            val elapsedMs =
                                ((SystemClock.elapsedRealtimeNanos() - startedNs) / 1_000_000L)
                                    .coerceAtLeast(0L)
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

    private fun updatePeak(
        target: AtomicLong,
        candidate: Long,
    ) {
        while (true) {
            val current = target.get()
            if (candidate <= current || target.compareAndSet(current, candidate)) return
        }
    }

    private data class GateAModelResolution(
        val models: Map<String, ActiveModel>?,
        val missingModelIds: List<String>,
    )

    companion object {
        const val STAGE16B_FSMN_VAD_CANDIDATE_ID = "fsmn-vad-int8"
        const val STAGE16B_TEN_VAD_CANDIDATE_ID = "ten-vad-fp32"
        private const val PSS_SAMPLE_INTERVAL_MS = 50L
    }
}
