package io.github.ioannes78.voica.sherpa

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineFireRedAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineFunAsrNanoModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import io.github.ioannes78.voica.audio.CanonicalPcmProfile
import io.github.ioannes78.voica.model.ModelDescriptor
import io.github.ioannes78.voica.model.ModelKind
import io.github.ioannes78.voica.model.ModelPunctuationMode
import io.github.ioannes78.voica.model.TimestampCapability
import io.github.ioannes78.voica.transcript.AsrCapabilities
import io.github.ioannes78.voica.transcript.AsrHypothesis
import io.github.ioannes78.voica.transcript.PunctuationCapability
import io.github.ioannes78.voica.transcript.RelativeTimedToken
import io.github.ioannes78.voica.transcript.SecondPassAsrEngine
import java.io.File

object SherpaOfflineAsrModelType {
    const val FIRE_RED_ASR2_CTC = "fire-red-asr2-ctc"
    const val FUNASR_NANO = "funasr-nano"
    const val QWEN3_ASR = "qwen3-asr"
}

internal fun runtimeSupportsNativeTokenTiming(model: ModelDescriptor): Boolean =
    model.capabilities.supportsTokenTiming ||
        model.runtimeModelType == SherpaOfflineAsrModelType.FIRE_RED_ASR2_CTC ||
        model.runtimeModelType == SherpaOfflineAsrModelType.FUNASR_NANO

/**
 * sherpa-onnx FunASR Nano emits one timestamp per generated BPE id. Its streaming UTF-8 decoder
 * may emit an empty token while bytes for one Unicode character are still pending. Compact those
 * empty pieces without inventing timing: the completed token inherits the first native timestamp
 * in that UTF-8 sequence, then the normal canonical-sample validator enforces monotonic bounds.
 */
internal fun validatedFunAsrNativeTimedTokens(
    tokens: List<String>,
    timestampsSeconds: List<Float>,
    acceptedSampleCount: Long,
): List<RelativeTimedToken> {
    if (tokens.isEmpty() || tokens.size != timestampsSeconds.size) return emptyList()

    val compactTokens = ArrayList<String>(tokens.size)
    val compactTimestamps = ArrayList<Float>(timestampsSeconds.size)
    var pendingTimestamp: Float? = null

    for (index in tokens.indices) {
        val timestamp = timestampsSeconds[index]
        if (!timestamp.isFinite() || timestamp < 0F) return emptyList()
        val token = tokens[index]
        if (token.isEmpty()) {
            if (pendingTimestamp == null) pendingTimestamp = timestamp
            continue
        }
        compactTokens += token
        compactTimestamps += pendingTimestamp ?: timestamp
        pendingTimestamp = null
    }

    return validatedTimedTokens(
        tokens = compactTokens,
        timestampsSeconds = compactTimestamps,
        acceptedSampleCount = acceptedSampleCount,
    )
}

data class LargeOfflineAsrSettings(
    val numThreads: Int = SherpaRuntime.DEFAULT_NUM_THREADS,
    val funAsrSystemPrompt: String = "You are a helpful assistant.",
    val funAsrUserPrompt: String = "语音转写：",
    val funAsrMaxNewTokens: Int = 512,
    val funAsrTemperature: Float = 1.0e-6F,
    val funAsrTopP: Float = 0.8F,
    val funAsrSeed: Int = 42,
    val funAsrLanguage: String = "",
    val funAsrUseInverseTextNormalization: Boolean = true,
    val funAsrHotwords: String = "",
    val qwenMaxTotalLen: Int = 512,
    val qwenMaxNewTokens: Int = 128,
    val qwenTemperature: Float = 1.0e-6F,
    val qwenTopP: Float = 0.8F,
    val qwenSeed: Int = 42,
    val qwenHotwords: String = "",
    val debug: Boolean = false,
) {
    init {
        require(numThreads > 0)
        require(funAsrSystemPrompt.isNotBlank())
        require(funAsrUserPrompt.isNotBlank())
        require(funAsrMaxNewTokens > 0)
        require(funAsrTemperature.isFinite() && funAsrTemperature >= 0F)
        require(funAsrTopP.isFinite() && funAsrTopP > 0F && funAsrTopP <= 1F)
        require(qwenMaxTotalLen > 0)
        require(qwenMaxNewTokens > 0)
        require(qwenTemperature.isFinite() && qwenTemperature >= 0F)
        require(qwenTopP.isFinite() && qwenTopP > 0F && qwenTopP <= 1F)
    }
}

private data class LargeOfflineAsrFiles(
    val model: File? = null,
    val tokens: File? = null,
    val encoderAdaptor: File? = null,
    val llm: File? = null,
    val embedding: File? = null,
    val convFrontend: File? = null,
    val encoder: File? = null,
    val decoder: File? = null,
    val tokenizer: File? = null,
) {
    companion object {
        fun fireRed(directory: File): LargeOfflineAsrFiles {
            val root = canonicalRoot(directory)
            return LargeOfflineAsrFiles(
                model = requiredFile(root, "model.int8.onnx"),
                tokens = requiredFile(root, "tokens.txt"),
            )
        }

        fun funAsrNano(directory: File): LargeOfflineAsrFiles {
            val root = canonicalRoot(directory)
            val tokenizer = requiredDirectory(root, "Qwen3-0.6B")
            check(tokenizer.walkTopDown().any { it.isFile }) {
                "FunASR-Nano tokenizer directory is empty"
            }
            return LargeOfflineAsrFiles(
                encoderAdaptor = requiredFile(root, "encoder_adaptor.int8.onnx"),
                llm = requiredFile(root, "llm.int8.onnx"),
                embedding = requiredFile(root, "embedding.int8.onnx"),
                tokenizer = tokenizer,
            )
        }

        fun qwen3(directory: File): LargeOfflineAsrFiles {
            val root = canonicalRoot(directory)
            val tokenizer = requiredDirectory(root, "tokenizer")
            check(tokenizer.walkTopDown().any { it.isFile }) {
                "Qwen3-ASR tokenizer directory is empty"
            }
            return LargeOfflineAsrFiles(
                convFrontend = requiredFile(root, "conv_frontend.onnx"),
                encoder = requiredFile(root, "encoder.int8.onnx"),
                decoder = requiredFile(root, "decoder.int8.onnx"),
                tokenizer = tokenizer,
            )
        }

        private fun canonicalRoot(directory: File): File =
            directory.canonicalFile.also {
                require(it.isDirectory) { "offline ASR model directory is missing" }
            }

        private fun requiredFile(
            root: File,
            relativePath: String,
        ): File =
            File(root, relativePath).canonicalFile.also { file ->
                require(file.path.startsWith(root.path + File.separator)) {
                    "offline ASR model file escapes model directory"
                }
                require(file.isFile) {
                    "Missing offline ASR model file: $relativePath"
                }
            }

        private fun requiredDirectory(
            root: File,
            relativePath: String,
        ): File =
            File(root, relativePath).canonicalFile.also { directory ->
                require(directory.path.startsWith(root.path + File.separator)) {
                    "offline ASR model directory escapes model root"
                }
                require(directory.isDirectory) {
                    "Missing offline ASR model directory: $relativePath"
                }
            }
    }
}

private data class NativeLargeOfflineResult(
    val text: String,
    val tokens: List<String>,
    val timestampsSeconds: List<Float>,
    val language: String,
)

private interface NativeLargeOfflineRecognizer : AutoCloseable {
    fun transcribe(
        samples: FloatArray,
        sampleRateHz: Int,
    ): NativeLargeOfflineResult
}

private class SherpaNativeLargeOfflineRecognizer(
    config: OfflineRecognizerConfig,
) : NativeLargeOfflineRecognizer {
    private val delegate = OfflineRecognizer(assetManager = null, config = config)
    private var closed = false

    override fun transcribe(
        samples: FloatArray,
        sampleRateHz: Int,
    ): NativeLargeOfflineResult {
        check(!closed)
        val stream = delegate.createStream()
        try {
            stream.acceptWaveform(samples, sampleRateHz)
            delegate.decode(stream)
            val result = delegate.getResult(stream)
            return NativeLargeOfflineResult(
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

class SherpaLargeOfflineAsrEngine private constructor(
    override val model: ModelDescriptor,
    private val native: NativeLargeOfflineRecognizer,
) : SecondPassAsrEngine {
    private var closed = false
    private val runtimeSupportsTokenTiming = runtimeSupportsNativeTokenTiming(model)

    constructor(
        model: ModelDescriptor,
        modelDirectory: File,
        settings: LargeOfflineAsrSettings = LargeOfflineAsrSettings(),
    ) : this(
        model = model,
        native =
            SherpaNativeLargeOfflineRecognizer(
                config =
                    offlineRecognizerConfig(
                        descriptor = model,
                        directory = modelDirectory,
                        settings = settings,
                    ),
            ),
    )

    init {
        require(model.kind == ModelKind.ASR_LARGE) {
            "large offline ASR requires ASR_LARGE model descriptor"
        }
        require(!model.capabilities.supportsStreaming) {
            "ASR_LARGE must not be declared as streaming"
        }
    }

    override val capabilities =
        AsrCapabilities(
            supportsStreaming = false,
            supportsPartial = false,
            supportsTokenTiming = runtimeSupportsTokenTiming,
            supportsLanguageDetection = model.capabilities.supportsLanguageDetection,
            supportsConfidence = model.capabilities.supportsConfidence,
            supportsInverseTextNormalization =
                model.capabilities.supportsInverseTextNormalization,
            punctuationCapability =
                when (model.capabilities.punctuationMode) {
                    ModelPunctuationMode.NATIVE -> PunctuationCapability.RELIABLE
                    ModelPunctuationMode.EXTERNAL,
                    ModelPunctuationMode.NONE,
                    -> PunctuationCapability.NONE
                },
            supportsSecondPass = true,
            executionMode = model.capabilities.executionMode,
            timestampCapability =
                if (runtimeSupportsTokenTiming) {
                    TimestampCapability.TOKEN
                } else {
                    model.capabilities.timestampCapability
                },
            supportsLanguageForcing = model.capabilities.supportsLanguageForcing,
            supportsHotwords = model.capabilities.supportsHotwords,
        )

    override suspend fun transcribe(
        samples: ShortArray,
        sampleRateHz: Int,
    ): AsrHypothesis {
        check(!closed) { "large offline ASR engine is closed" }
        require(sampleRateHz == CanonicalPcmProfile.SAMPLE_RATE_HZ) {
            "large offline ASR requires canonical 16 kHz PCM"
        }
        require(samples.isNotEmpty()) { "offline ASR speech segment is empty" }

        val normalized =
            FloatArray(samples.size) { index ->
                samples[index] / 32768.0F
            }
        val result = native.transcribe(normalized, sampleRateHz)
        val timedTokens =
            if (!runtimeSupportsTokenTiming) {
                emptyList()
            } else if (model.runtimeModelType == SherpaOfflineAsrModelType.FUNASR_NANO) {
                validatedFunAsrNativeTimedTokens(
                    tokens = result.tokens,
                    timestampsSeconds = result.timestampsSeconds,
                    acceptedSampleCount = samples.size.toLong(),
                )
            } else {
                validatedTimedTokens(
                    tokens = result.tokens,
                    timestampsSeconds = result.timestampsSeconds,
                    acceptedSampleCount = samples.size.toLong(),
                )
            }

        return AsrHypothesis(
            text = result.text,
            tokens = timedTokens,
            detectedLanguage =
                result.language.takeIf {
                    model.capabilities.supportsLanguageDetection && it.isNotBlank()
                },
            confidence = null,
            punctuationCapability = capabilities.punctuationCapability,
            isFinal = true,
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        native.close()
    }
}

fun createSherpaLargeOfflineAsrEngine(
    model: ModelDescriptor,
    modelDirectory: File,
    settings: LargeOfflineAsrSettings = LargeOfflineAsrSettings(),
): SecondPassAsrEngine =
    SherpaLargeOfflineAsrEngine(
        model = model,
        modelDirectory = modelDirectory,
        settings = settings,
    )

private fun offlineRecognizerConfig(
    descriptor: ModelDescriptor,
    directory: File,
    settings: LargeOfflineAsrSettings,
): OfflineRecognizerConfig {
    val modelConfig =
        when (descriptor.runtimeModelType) {
            SherpaOfflineAsrModelType.FIRE_RED_ASR2_CTC -> {
                val files = LargeOfflineAsrFiles.fireRed(directory)
                OfflineModelConfig(
                    fireRedAsrCtc =
                        OfflineFireRedAsrCtcModelConfig(
                            model = checkNotNull(files.model).absolutePath,
                        ),
                    tokens = checkNotNull(files.tokens).absolutePath,
                    numThreads = settings.numThreads,
                    debug = settings.debug,
                    provider = SherpaRuntime.PROVIDER_CPU,
                )
            }

            SherpaOfflineAsrModelType.FUNASR_NANO -> {
                val files = LargeOfflineAsrFiles.funAsrNano(directory)
                OfflineModelConfig(
                    funasrNano =
                        OfflineFunAsrNanoModelConfig(
                            encoderAdaptor = checkNotNull(files.encoderAdaptor).absolutePath,
                            llm = checkNotNull(files.llm).absolutePath,
                            embedding = checkNotNull(files.embedding).absolutePath,
                            tokenizer = checkNotNull(files.tokenizer).absolutePath,
                            systemPrompt = settings.funAsrSystemPrompt,
                            userPrompt = settings.funAsrUserPrompt,
                            maxNewTokens = settings.funAsrMaxNewTokens,
                            temperature = settings.funAsrTemperature,
                            topP = settings.funAsrTopP,
                            seed = settings.funAsrSeed,
                            language = settings.funAsrLanguage,
                            itn = settings.funAsrUseInverseTextNormalization,
                            hotwords = settings.funAsrHotwords,
                        ),
                    tokens = "",
                    numThreads = settings.numThreads,
                    debug = settings.debug,
                    provider = SherpaRuntime.PROVIDER_CPU,
                )
            }

            SherpaOfflineAsrModelType.QWEN3_ASR -> {
                val files = LargeOfflineAsrFiles.qwen3(directory)
                OfflineModelConfig(
                    qwen3Asr =
                        OfflineQwen3AsrModelConfig(
                            convFrontend = checkNotNull(files.convFrontend).absolutePath,
                            encoder = checkNotNull(files.encoder).absolutePath,
                            decoder = checkNotNull(files.decoder).absolutePath,
                            tokenizer = checkNotNull(files.tokenizer).absolutePath,
                            maxTotalLen = settings.qwenMaxTotalLen,
                            maxNewTokens = settings.qwenMaxNewTokens,
                            temperature = settings.qwenTemperature,
                            topP = settings.qwenTopP,
                            seed = settings.qwenSeed,
                            hotwords = settings.qwenHotwords,
                        ),
                    tokens = "",
                    numThreads = settings.numThreads,
                    debug = settings.debug,
                    provider = SherpaRuntime.PROVIDER_CPU,
                )
            }

            else ->
                error(
                    "unsupported sherpa offline ASR runtimeModelType: " +
                        descriptor.runtimeModelType,
                )
        }

    return OfflineRecognizerConfig(
        featConfig =
            FeatureConfig(
                sampleRate = CanonicalPcmProfile.SAMPLE_RATE_HZ,
                featureDim = 80,
                dither = 0.0F,
            ),
        modelConfig = modelConfig,
    )
}