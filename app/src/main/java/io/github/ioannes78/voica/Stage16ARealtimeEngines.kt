package io.github.ioannes78.voica

import android.content.res.AssetManager
import io.github.ioannes78.voica.model.ActiveModel
import io.github.ioannes78.voica.model.ModelKind
import io.github.ioannes78.voica.model.ModelLease
import io.github.ioannes78.voica.model.ModelUseRegistry
import io.github.ioannes78.voica.sherpa.PunctuationSettings
import io.github.ioannes78.voica.sherpa.SherpaCtTransformerPunctuationEngine
import io.github.ioannes78.voica.sherpa.SherpaStreamingSileroVadEngine
import io.github.ioannes78.voica.sherpa.SherpaVadModelLocation
import io.github.ioannes78.voica.sherpa.SileroVadSettings
import io.github.ioannes78.voica.sherpa.StreamingZipformerSettings
import io.github.ioannes78.voica.sherpa.createSherpaStreamingAsrEngine
import io.github.ioannes78.voica.transcript.PunctuationEngine
import io.github.ioannes78.voica.transcript.StreamingAsrEngine
import io.github.ioannes78.voica.transcript.StreamingVadEngine
import java.io.Closeable
import java.io.File

private fun missingRealtimeModelsUserMessage(modelIds: List<String>): String =
    when {
        modelIds.size == 1 && modelIds.single() == Stage13ARealtimeModelIds.SMALL_BILINGUAL ->
            "实时转写模型未安装，请先下载 Small Bilingual Zipformer。"
        modelIds.size == 1 && modelIds.single() == Stage13ARealtimeModelIds.CHINESE_LARGE_CTC ->
            "实时转写模型未安装，请先下载 Chinese Large Zipformer CTC INT8。"
        else -> "实时转写所需模型未安装，请先在本地语音识别中完成模型安装。"
    }

class MissingRealtimeTranscriptionModelsException(
    val modelIds: List<String>,
) : IllegalStateException(
    missingRealtimeModelsUserMessage(modelIds),
)

data class Stage16ARealtimeResolvedModels(
    val asr: ActiveModel,
    val vad: ActiveModel,
    val punctuation: ActiveModel?,
    val performance: ResolvedSpeechPerformance,
    val realtimeSettings: ResolvedRealtimeAsrSettings,
    val vadSettings: LocalVadSettings,
) {
    init {
        require(asr.descriptor.modelId in Stage13ARealtimeModelIds.ALL) {
            "Stage 16A ASR must be in the frozen product matrix"
        }
        require(asr.descriptor.kind == ModelKind.ASR_STREAMING)
        require(asr.descriptor.capabilities.supportsStreaming)
        require(asr.descriptor.capabilities.supportsPartial)
        require(vad.descriptor.modelId == Stage8ModelIds.VAD)
        require(vad.descriptor.kind == ModelKind.VAD)
        require(punctuation == null || punctuation.descriptor.kind == ModelKind.PUNCTUATION)
    }

    val all: List<ActiveModel>
        get() = listOfNotNull(asr, vad, punctuation).distinctBy { it.descriptor.modelId }
}

/**
 * Holds model-use leases for the complete logical realtime run. Model updates/removal therefore
 * cannot replace an engine underneath an active Stage 16A session. Call [close] exactly once when
 * the product runtime has closed every native engine that uses these models.
 */
class Stage16ARealtimeModelLeaseBundle internal constructor(
    val models: Stage16ARealtimeResolvedModels,
    private val leases: List<ModelLease>,
) : Closeable {
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        leases.asReversed().forEach { lease -> runCatching { lease.close() } }
    }
}

/**
 * Resolves the frozen Stage 16A product model set without fallback routing.
 *
 * The selected Small/CTC ASR and Silero VAD are mandatory. External punctuation is optional by
 * policy: a missing punctuation model must not make otherwise-usable realtime ASR fail to start.
 */
class Stage16ARealtimeModelResolver(
    private val activeModel: suspend (String) -> ActiveModel?,
    private val modelUseRegistry: ModelUseRegistry,
) {
    suspend fun acquire(settings: LocalSpeechSettings): Stage16ARealtimeModelLeaseBundle {
        val selectedAsrModelId = settings.selectedRealtimeModelId()
        require(selectedAsrModelId in Stage13ARealtimeModelIds.ALL) {
            "unsupported Stage 16A realtime model: $selectedAsrModelId"
        }

        val asr = activeModel(selectedAsrModelId)
        val vad = activeModel(Stage8ModelIds.VAD)
        val punctuation = activeModel(Stage8ModelIds.PUNCTUATION)
        val missing = buildList {
            if (asr == null) add(selectedAsrModelId)
            if (vad == null) add(Stage8ModelIds.VAD)
        }
        if (missing.isNotEmpty()) {
            throw MissingRealtimeTranscriptionModelsException(missing)
        }

        val requiredAsr = checkNotNull(asr)
        val requiredVad = checkNotNull(vad)
        require(requiredAsr.descriptor.modelId == selectedAsrModelId) {
            "model resolver returned a different realtime ASR model"
        }
        require(requiredAsr.descriptor.kind == ModelKind.ASR_STREAMING)
        require(requiredAsr.descriptor.capabilities.supportsStreaming)
        require(requiredAsr.descriptor.capabilities.supportsPartial)
        require(requiredVad.descriptor.modelId == Stage8ModelIds.VAD)
        require(requiredVad.descriptor.kind == ModelKind.VAD)
        punctuation?.let {
            require(it.descriptor.modelId == Stage8ModelIds.PUNCTUATION)
            require(it.descriptor.kind == ModelKind.PUNCTUATION)
        }

        val resolved =
            Stage16ARealtimeResolvedModels(
                asr = requiredAsr,
                vad = requiredVad,
                punctuation = punctuation,
                performance = settings.resolvePerformance(),
                realtimeSettings =
                    settings.realtime.resolveFor(
                        requiredAsr.descriptor.capabilities.supportedParameters,
                    ),
                vadSettings = settings.vad,
            )
        val leases = mutableListOf<ModelLease>()
        try {
            resolved.all.forEach { active ->
                leases +=
                    modelUseRegistry.acquire(
                        modelId = active.descriptor.modelId,
                        version = active.descriptor.version,
                        revision = active.descriptor.revision,
                    )
            }
            return Stage16ARealtimeModelLeaseBundle(resolved, leases)
        } catch (failure: Throwable) {
            leases.asReversed().forEach { lease -> runCatching { lease.close() } }
            throw failure
        }
    }
}

interface Stage16ARealtimeEngineProvider {
    fun openAsr(models: Stage16ARealtimeResolvedModels): StreamingAsrEngine

    fun openVad(models: Stage16ARealtimeResolvedModels): StreamingVadEngine

    fun openPunctuation(models: Stage16ARealtimeResolvedModels): PunctuationEngine?
}

/** Production sherpa-onnx engine provider for the frozen local Stage 16A model matrix. */
class SherpaStage16ARealtimeEngineProvider(
    private val assetManager: AssetManager,
) : Stage16ARealtimeEngineProvider {
    override fun openAsr(models: Stage16ARealtimeResolvedModels): StreamingAsrEngine {
        val directory =
            models.asr.installedDirectory
                ?: error("Stage 16A realtime ASR must be a managed installed model")
        return createSherpaStreamingAsrEngine(
            model = models.asr.descriptor,
            modelDirectory = directory,
            settings =
                StreamingZipformerSettings(
                    numThreads = models.performance.effectiveThreads,
                    decodingMethod = models.realtimeSettings.decodingMethod.runtimeValue,
                    maxActivePaths = models.realtimeSettings.maxActivePaths,
                ),
        )
    }

    override fun openVad(models: Stage16ARealtimeResolvedModels): StreamingVadEngine {
        val active = models.vad
        val location =
            active.installedDirectory?.let { directory ->
                val modelFile =
                    active.descriptor.files
                        .singleOrNull { it.relativePath.endsWith(".onnx") }
                        ?.let { File(directory, it.relativePath).canonicalFile }
                        ?: error("Stage 16A VAD must contain exactly one ONNX file")
                require(modelFile.isFile) { "Stage 16A VAD model file is missing" }
                SherpaVadModelLocation.File(modelFile.path)
            } ?: SherpaVadModelLocation.Asset(
                assetManager = assetManager,
                assetPath =
                    active.descriptor.builtinAssetPath
                        ?: error("builtin Stage 16A VAD is missing asset path"),
            )
        return SherpaStreamingSileroVadEngine(
            model = active.descriptor,
            modelLocation = location,
            settings =
                SileroVadSettings(
                    threshold = models.vadSettings.threshold,
                    minSilenceDurationSeconds = models.vadSettings.minSilenceDurationSeconds,
                    minSpeechDurationSeconds = models.vadSettings.minSpeechDurationSeconds,
                    maxSpeechDurationSeconds = models.vadSettings.maxSpeechDurationSeconds,
                    numThreads = models.performance.effectiveThreads,
                ),
        )
    }

    override fun openPunctuation(models: Stage16ARealtimeResolvedModels): PunctuationEngine? {
        val active = models.punctuation ?: return null
        val directory =
            active.installedDirectory
                ?: error("Stage 16A punctuation must be a managed installed model")
        return SherpaCtTransformerPunctuationEngine(
            model = active.descriptor,
            modelDirectory = directory,
            settings = PunctuationSettings(numThreads = models.performance.effectiveThreads),
        )
    }
}
