package io.github.ioannes78.voica.sherpa

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import io.github.ioannes78.voica.audio.CanonicalPcmProfile
import io.github.ioannes78.voica.model.ModelDescriptor
import io.github.ioannes78.voica.model.ModelKind
import io.github.ioannes78.voica.transcript.AsrCapabilities
import io.github.ioannes78.voica.transcript.AsrHypothesis
import io.github.ioannes78.voica.transcript.PunctuationCapability
import io.github.ioannes78.voica.transcript.RelativeTimedToken
import io.github.ioannes78.voica.transcript.StreamingAsrEngine
import io.github.ioannes78.voica.transcript.StreamingAsrSession
import java.io.Closeable
import java.io.File
import kotlin.math.roundToLong

object SmallBilingualZipformerLayout {
    const val ENCODER = "encoder.int8.onnx"
    const val DECODER = "decoder.onnx"
    const val JOINER = "joiner.int8.onnx"
    const val TOKENS = "tokens.txt"

    val REQUIRED_FILES =
        listOf(
            ENCODER,
            DECODER,
            JOINER,
            TOKENS,
        )
}

data class ZipformerModelFiles internal constructor(
    val encoder: File,
    val decoder: File,
    val joiner: File,
    val tokens: File,
) {
    companion object {
        fun fromDirectory(directory: File): ZipformerModelFiles {
            require(directory.isDirectory) { "Zipformer model directory is missing" }

            fun required(relativePath: String): File =
                File(directory, relativePath).canonicalFile.also { file ->
                    require(file.isFile) { "Missing Zipformer model file: $relativePath" }
                    require(
                        file.path.startsWith(directory.canonicalFile.path + File.separator),
                    ) {
                        "Zipformer model file escapes model directory"
                    }
                }

            return ZipformerModelFiles(
                encoder = required(SmallBilingualZipformerLayout.ENCODER),
                decoder = required(SmallBilingualZipformerLayout.DECODER),
                joiner = required(SmallBilingualZipformerLayout.JOINER),
                tokens = required(SmallBilingualZipformerLayout.TOKENS),
            )
        }
    }
}

data class StreamingZipformerSettings(
    val numThreads: Int = SherpaRuntime.DEFAULT_NUM_THREADS,
    val decodingMethod: String = "greedy_search",
    val maxActivePaths: Int = 4,
    val debug: Boolean = false,
    val nativeModelType: String = "zipformer",
) {
    init {
        require(numThreads > 0)
        require(decodingMethod.isNotBlank())
        require(maxActivePaths > 0)
    }
}

object SherpaStreamingAsrModelType {
    const val ZIPFORMER_TRANSDUCER = "zipformer-transducer"
    const val ZIPFORMER2_TRANSDUCER = "zipformer2-transducer"
    const val ZIPFORMER2_CTC = "zipformer2-ctc"
}

class SherpaStreamingZipformerEngine internal constructor(
    override val model: ModelDescriptor,
    private val modelFiles: ZipformerModelFiles,
    settings: StreamingZipformerSettings,
    recognizerFactory: NativeStreamingRecognizerFactory,
) : StreamingAsrEngine {
    private val nativeRecognizer =
        recognizerFactory.create(
            modelFiles = modelFiles,
            settings = settings,
        )
    private var closed = false

    constructor(
        model: ModelDescriptor,
        modelDirectory: File,
        settings: StreamingZipformerSettings = StreamingZipformerSettings(),
    ) : this(
        model = model,
        modelFiles = ZipformerModelFiles.fromDirectory(modelDirectory),
        settings = settings,
        recognizerFactory = SherpaNativeStreamingRecognizerFactory,
    )

    init {
        require(model.kind == ModelKind.ASR_STREAMING) {
            "Streaming Zipformer requires an ASR_STREAMING model descriptor"
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
        )

    override suspend fun openSession(): StreamingAsrSession {
        check(!closed) { "ASR engine is closed" }
        return SherpaStreamingZipformerSession(
            native = nativeRecognizer.openSession(),
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        nativeRecognizer.close()
    }
}

private class SherpaStreamingZipformerSession(
    private val native: NativeStreamingAsrSession,
) : StreamingAsrSession {
    private var acceptedSampleCount = 0L
    private var closed = false
    private var inputFinished = false

    override suspend fun acceptSamples(
        samples: ShortArray,
        offset: Int,
        count: Int,
    ) {
        check(!closed) { "ASR session is closed" }
        check(!inputFinished) { "ASR input is already finished" }
        require(offset >= 0)
        require(count >= 0)
        require(offset + count <= samples.size)
        if (count == 0) return

        val normalized =
            FloatArray(count) { index ->
                samples[offset + index] / 32768.0F
            }
        native.acceptWaveform(normalized)
        acceptedSampleCount = Math.addExact(acceptedSampleCount, count.toLong())
    }

    override suspend fun decode(): AsrHypothesis {
        check(!closed) { "ASR session is closed" }
        return native.decodeReady().toHypothesis(
            acceptedSampleCount = acceptedSampleCount,
            isFinal = false,
        )
    }

    override suspend fun finishInput(): AsrHypothesis {
        check(!closed) { "ASR session is closed" }
        if (!inputFinished) {
            inputFinished = true
            return native.finishInput().toHypothesis(
                acceptedSampleCount = acceptedSampleCount,
                isFinal = true,
            )
        }
        return native.decodeReady().toHypothesis(
            acceptedSampleCount = acceptedSampleCount,
            isFinal = true,
        )
    }

    override suspend fun reset() {
        check(!closed) { "ASR session is closed" }
        native.reset()
        acceptedSampleCount = 0L
        inputFinished = false
    }

    override fun close() {
        if (closed) return
        closed = true
        native.close()
    }
}

internal data class NativeStreamingAsrResult(
    val text: String,
    val tokens: List<String>,
    val timestampsSeconds: List<Float>,
)

internal interface NativeStreamingAsrSession : Closeable {
    fun acceptWaveform(samples: FloatArray)

    fun decodeReady(): NativeStreamingAsrResult

    fun finishInput(): NativeStreamingAsrResult

    fun reset()
}

internal interface NativeStreamingRecognizer : Closeable {
    fun openSession(): NativeStreamingAsrSession
}

internal fun interface NativeStreamingRecognizerFactory {
    fun create(
        modelFiles: ZipformerModelFiles,
        settings: StreamingZipformerSettings,
    ): NativeStreamingRecognizer
}

private object SherpaNativeStreamingRecognizerFactory : NativeStreamingRecognizerFactory {
    override fun create(
        modelFiles: ZipformerModelFiles,
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
                        transducer =
                            OnlineTransducerModelConfig(
                                encoder = modelFiles.encoder.absolutePath,
                                decoder = modelFiles.decoder.absolutePath,
                                joiner = modelFiles.joiner.absolutePath,
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

internal class SherpaNativeStreamingRecognizer(
    private val delegate: OnlineRecognizer,
) : NativeStreamingRecognizer {
    private var closed = false

    override fun openSession(): NativeStreamingAsrSession {
        check(!closed)
        return SherpaNativeStreamingAsrSession(
            recognizer = delegate,
            stream = delegate.createStream(),
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        delegate.release()
    }
}

private class SherpaNativeStreamingAsrSession(
    private val recognizer: OnlineRecognizer,
    private val stream: OnlineStream,
) : NativeStreamingAsrSession {
    private var closed = false

    override fun acceptWaveform(samples: FloatArray) {
        check(!closed)
        stream.acceptWaveform(
            samples = samples,
            sampleRate = CanonicalPcmProfile.SAMPLE_RATE_HZ,
        )
    }

    override fun decodeReady(): NativeStreamingAsrResult {
        check(!closed)
        while (recognizer.isReady(stream)) {
            recognizer.decode(stream)
        }
        return recognizer.getResult(stream).toNativeResult()
    }

    override fun finishInput(): NativeStreamingAsrResult {
        check(!closed)
        stream.inputFinished()
        while (recognizer.isReady(stream)) {
            recognizer.decode(stream)
        }
        return recognizer.getResult(stream).toNativeResult()
    }

    override fun reset() {
        check(!closed)
        recognizer.reset(stream)
    }

    override fun close() {
        if (closed) return
        closed = true
        stream.release()
    }
}

private fun OnlineRecognizerResult.toNativeResult() =
    NativeStreamingAsrResult(
        text = text,
        tokens = tokens.toList(),
        timestampsSeconds = timestamps.toList(),
    )

private fun NativeStreamingAsrResult.toHypothesis(
    acceptedSampleCount: Long,
    isFinal: Boolean,
): AsrHypothesis =
    AsrHypothesis(
        text = text,
        tokens =
            validatedTimedTokens(
                tokens = tokens,
                timestampsSeconds = timestampsSeconds,
                acceptedSampleCount = acceptedSampleCount,
            ),
        detectedLanguage = null,
        confidence = null,
        punctuationCapability = PunctuationCapability.NONE,
        isFinal = isFinal,
    )

internal fun validatedTimedTokens(
    tokens: List<String>,
    timestampsSeconds: List<Float>,
    acceptedSampleCount: Long,
): List<RelativeTimedToken> {
    if (tokens.isEmpty()) return emptyList()
    if (tokens.size != timestampsSeconds.size) return emptyList()
    if (acceptedSampleCount < 0L) return emptyList()

    val mapped = ArrayList<RelativeTimedToken>(tokens.size)
    var previousOffset = -1L

    for (index in tokens.indices) {
        val token = tokens[index]
        val seconds = timestampsSeconds[index]
        if (token.isEmpty() || !seconds.isFinite() || seconds < 0.0F) {
            return emptyList()
        }

        val offset =
            (seconds.toDouble() * CanonicalPcmProfile.SAMPLE_RATE_HZ.toDouble())
                .roundToLong()
        if (offset < previousOffset || offset > acceptedSampleCount) {
            return emptyList()
        }

        mapped +=
            RelativeTimedToken(
                text = token,
                startSampleOffset = offset,
                endSampleOffsetExclusive = null,
            )
        previousOffset = offset
    }

    return mapped
}
