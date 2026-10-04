package io.github.ioannes78.voica.sherpa

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import io.github.ioannes78.voica.audio.CanonicalPcmProfile
import io.github.ioannes78.voica.model.ModelDescriptor
import io.github.ioannes78.voica.model.ModelKind
import io.github.ioannes78.voica.transcript.AsrCapabilities
import io.github.ioannes78.voica.transcript.PunctuationCapability
import io.github.ioannes78.voica.transcript.StreamingAsrEngine
import io.github.ioannes78.voica.transcript.StreamingAsrSession
import java.io.File

object Zipformer2CtcLayout {
    const val MODEL = "model.int8.onnx"
    const val TOKENS = "tokens.txt"

    val REQUIRED_FILES = listOf(MODEL, TOKENS)
}

data class Zipformer2CtcModelFiles internal constructor(
    val model: File,
    val tokens: File,
) {
    companion object {
        fun fromDirectory(directory: File): Zipformer2CtcModelFiles {
            require(directory.isDirectory) { "Zipformer2 CTC model directory is missing" }
            val root = directory.canonicalFile

            fun required(relativePath: String): File =
                File(root, relativePath).canonicalFile.also { file ->
                    require(file.path.startsWith(root.path + File.separator)) {
                        "Zipformer2 CTC model file escapes model directory"
                    }
                    require(file.isFile) {
                        "Missing Zipformer2 CTC model file: $relativePath"
                    }
                }

            return Zipformer2CtcModelFiles(
                model = required(Zipformer2CtcLayout.MODEL),
                tokens = required(Zipformer2CtcLayout.TOKENS),
            )
        }
    }
}

class SherpaStreamingZipformerCtcEngine internal constructor(
    override val model: ModelDescriptor,
    private val modelFiles: Zipformer2CtcModelFiles,
    settings: StreamingZipformerSettings,
    recognizerFactory: NativeStreamingCtcRecognizerFactory,
) : StreamingAsrEngine {
    private val delegate =
        recognizerFactory.create(
            modelFiles = modelFiles,
            settings = settings,
        )
    private var closed = false

    constructor(
        model: ModelDescriptor,
        modelDirectory: File,
        settings: StreamingZipformerSettings = StreamingZipformerSettings(nativeModelType = ""),
    ) : this(
        model = model,
        modelFiles = Zipformer2CtcModelFiles.fromDirectory(modelDirectory),
        settings = settings,
        recognizerFactory = SherpaNativeStreamingCtcRecognizerFactory,
    )

    init {
        require(model.kind == ModelKind.ASR_STREAMING) {
            "Streaming Zipformer2 CTC requires an ASR_STREAMING model descriptor"
        }
        require(model.capabilities.supportsStreaming)
        require(model.capabilities.supportsPartial)
        require(model.capabilities.supportsTokenTiming)
    }

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
            supportsHotwords = model.capabilities.supportsHotwords,
        )

    override suspend fun openSession(): StreamingAsrSession {
        check(!closed) { "ASR engine is closed" }
        return SherpaStreamingZipformerSession(delegate.openSession())
    }

    override fun close() {
        if (closed) return
        closed = true
        delegate.close()
    }
}

internal fun interface NativeStreamingCtcRecognizerFactory {
    fun create(
        modelFiles: Zipformer2CtcModelFiles,
        settings: StreamingZipformerSettings,
    ): NativeStreamingRecognizer
}

private object SherpaNativeStreamingCtcRecognizerFactory : NativeStreamingCtcRecognizerFactory {
    override fun create(
        modelFiles: Zipformer2CtcModelFiles,
        settings: StreamingZipformerSettings,
    ): NativeStreamingRecognizer {
        val config =
            OnlineRecognizerConfig(
                featConfig =
                    FeatureConfig(
                        sampleRate = CanonicalPcmProfile.SAMPLE_RATE_HZ,
                        featureDim = 80,
                        dither = 0.0F,
                    ),
                modelConfig =
                    OnlineModelConfig(
                        zipformer2Ctc =
                            OnlineZipformer2CtcModelConfig(
                                model = modelFiles.model.absolutePath,
                            ),
                        tokens = modelFiles.tokens.absolutePath,
                        numThreads = settings.numThreads,
                        debug = settings.debug,
                        provider = SherpaRuntime.PROVIDER_CPU,
                        modelType = settings.nativeModelType,
                    ),
                enableEndpoint = false,
                decodingMethod = settings.decodingMethod,
                maxActivePaths = settings.maxActivePaths,
            )
        return SherpaNativeStreamingRecognizer(
            delegate =
                OnlineRecognizer(
                    assetManager = null,
                    config = config,
                ),
        )
    }
}

fun createSherpaStreamingAsrEngine(
    model: ModelDescriptor,
    modelDirectory: File,
    settings: StreamingZipformerSettings = StreamingZipformerSettings(),
): StreamingAsrEngine =
    when (model.runtimeModelType) {
        SherpaStreamingAsrModelType.ZIPFORMER2_CTC ->
            SherpaStreamingZipformerCtcEngine(
                model = model,
                modelDirectory = modelDirectory,
                settings = settings.copy(nativeModelType = ""),
            )

        SherpaStreamingAsrModelType.ZIPFORMER2_TRANSDUCER ->
            SherpaStreamingZipformerEngine(
                model = model,
                modelDirectory = modelDirectory,
                settings = settings.copy(nativeModelType = ""),
            )

        null,
        SherpaStreamingAsrModelType.ZIPFORMER_TRANSDUCER,
        ->
            SherpaStreamingZipformerEngine(
                model = model,
                modelDirectory = modelDirectory,
                settings = settings.copy(
                    nativeModelType =
                        if (model.runtimeModelType == null) {
                            "zipformer"
                        } else {
                            settings.nativeModelType
                        },
                ),
            )

        else -> error(
            "unsupported sherpa streaming ASR runtimeModelType: " + model.runtimeModelType,
        )
    }
