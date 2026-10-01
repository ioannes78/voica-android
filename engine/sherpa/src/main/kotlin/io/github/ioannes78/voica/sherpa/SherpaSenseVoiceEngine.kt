package io.github.ioannes78.voica.sherpa

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import io.github.ioannes78.voica.audio.CanonicalPcmProfile
import io.github.ioannes78.voica.model.ModelDescriptor
import io.github.ioannes78.voica.model.ModelKind
import io.github.ioannes78.voica.transcript.AsrCapabilities
import io.github.ioannes78.voica.transcript.AsrHypothesis
import io.github.ioannes78.voica.transcript.PunctuationCapability
import io.github.ioannes78.voica.transcript.SecondPassAsrEngine
import java.io.File

object SenseVoiceLayout {
    const val MODEL = "model.int8.onnx"
    const val TOKENS = "tokens.txt"
}

data class SenseVoiceSettings(
    val numThreads: Int = SherpaRuntime.DEFAULT_NUM_THREADS,
    val language: String = "",
    val useInverseTextNormalization: Boolean = true,
    val debug: Boolean = false,
) {
    init {
        require(numThreads > 0)
    }
}

class SherpaSenseVoiceEngine internal constructor(
    override val model: ModelDescriptor,
    private val native: NativeSecondPassRecognizer,
) : SecondPassAsrEngine {
    private var closed = false

    constructor(
        model: ModelDescriptor,
        modelDirectory: File,
        settings: SenseVoiceSettings = SenseVoiceSettings(),
    ) : this(
        model = model,
        native =
            SherpaNativeSenseVoiceRecognizer(
                files = SenseVoiceFiles.fromDirectory(modelDirectory),
                settings = settings,
            ),
    )

    init {
        require(model.kind == ModelKind.ASR_SECOND_PASS) {
            "SenseVoice requires an ASR_SECOND_PASS model descriptor"
        }
    }

    override val capabilities =
        AsrCapabilities(
            supportsStreaming = false,
            supportsPartial = false,
            supportsTokenTiming = true,
            supportsLanguageDetection = true,
            supportsConfidence = false,
            supportsInverseTextNormalization = true,
            punctuationCapability = PunctuationCapability.PARTIAL,
            supportsSecondPass = true,
        )

    override suspend fun transcribe(
        samples: ShortArray,
        sampleRateHz: Int,
    ): AsrHypothesis {
        check(!closed) { "SenseVoice engine is closed" }
        require(sampleRateHz == CanonicalPcmProfile.SAMPLE_RATE_HZ) {
            "SenseVoice Stage 8 adapter requires 16 kHz canonical PCM"
        }
        require(samples.isNotEmpty()) { "SenseVoice speech segment is empty" }

        val normalized =
            FloatArray(samples.size) { index ->
                samples[index] / 32768.0F
            }
        val result = native.transcribe(normalized, sampleRateHz)

        return AsrHypothesis(
            text = result.text,
            tokens =
                validatedTimedTokens(
                    tokens = result.tokens,
                    timestampsSeconds = result.timestampsSeconds,
                    acceptedSampleCount = samples.size.toLong(),
                ),
            detectedLanguage = result.language.takeIf { it.isNotBlank() },
            confidence = null,
            punctuationCapability = PunctuationCapability.PARTIAL,
            isFinal = true,
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        native.close()
    }
}

data class SenseVoiceFiles internal constructor(
    val model: File,
    val tokens: File,
) {
    companion object {
        fun fromDirectory(directory: File): SenseVoiceFiles {
            require(directory.isDirectory) { "SenseVoice model directory is missing" }
            val canonicalRoot = directory.canonicalFile

            fun required(relativePath: String): File =
                File(canonicalRoot, relativePath).canonicalFile.also { file ->
                    require(file.isFile) { "Missing SenseVoice model file: $relativePath" }
                    require(file.path.startsWith(canonicalRoot.path + File.separator)) {
                        "SenseVoice model file escapes model directory"
                    }
                }

            return SenseVoiceFiles(
                model = required(SenseVoiceLayout.MODEL),
                tokens = required(SenseVoiceLayout.TOKENS),
            )
        }
    }
}

internal data class NativeSenseVoiceResult(
    val text: String,
    val tokens: List<String>,
    val timestampsSeconds: List<Float>,
    val language: String,
)

internal interface NativeSecondPassRecognizer : AutoCloseable {
    fun transcribe(
        samples: FloatArray,
        sampleRateHz: Int,
    ): NativeSenseVoiceResult
}

private class SherpaNativeSenseVoiceRecognizer(
    files: SenseVoiceFiles,
    settings: SenseVoiceSettings,
) : NativeSecondPassRecognizer {
    private val delegate =
        OfflineRecognizer(
            assetManager = null,
            config =
                OfflineRecognizerConfig(
                    featConfig =
                        FeatureConfig(
                            sampleRate = CanonicalPcmProfile.SAMPLE_RATE_HZ,
                            featureDim = 80,
                            dither = 0.0F,
                        ),
                    modelConfig =
                        OfflineModelConfig(
                            senseVoice =
                                OfflineSenseVoiceModelConfig(
                                    model = files.model.absolutePath,
                                    language = settings.language,
                                    useInverseTextNormalization =
                                        settings.useInverseTextNormalization,
                                ),
                            numThreads = settings.numThreads,
                            debug = settings.debug,
                            provider = SherpaRuntime.PROVIDER_CPU,
                            tokens = files.tokens.absolutePath,
                        ),
                ),
        )
    private var closed = false

    override fun transcribe(
        samples: FloatArray,
        sampleRateHz: Int,
    ): NativeSenseVoiceResult {
        check(!closed)
        val stream = delegate.createStream()
        try {
            stream.acceptWaveform(samples, sampleRateHz)
            delegate.decode(stream)
            val result = delegate.getResult(stream)
            return NativeSenseVoiceResult(
                text = result.text,
                tokens = result.tokens.toList(),
                timestampsSeconds = result.timestamps.toList(),
                language = result.lang,
            )
        } finally {
            stream.release()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        delegate.release()
    }
}
