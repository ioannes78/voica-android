package io.github.ioannes78.voica.transcript

import io.github.ioannes78.voica.audio.PcmSource
import io.github.ioannes78.voica.model.AsrExecutionMode
import io.github.ioannes78.voica.model.ModelDescriptor
import io.github.ioannes78.voica.model.TimestampCapability
import java.io.Closeable
import kotlin.math.roundToLong

enum class PunctuationCapability {
    RELIABLE,
    PARTIAL,
    NONE,
}

enum class TranscriptionMode {
    FAST,
    HIGH_QUALITY,
}

enum class AsrHypothesisStability {
    PARTIAL,
    STABLE,
    FINAL,
}

enum class TranscriptionState {
    PREPARING,
    VAD_ANALYZING,
    FIRST_PASS_TRANSCRIBING,
    SECOND_PASS_TRANSCRIBING,
    PUNCTUATING,
    PERSISTING,
    COMPLETED,
    CANCELLED,
    INTERRUPTED,
    FAILED_RECOVERABLE,
    FAILED_PERMANENT,
}

enum class TranscriptionPhase {
    PREPARING,
    VAD,
    FIRST_PASS,
    SECOND_PASS,
    PUNCTUATION,
    PERSISTING,
}

data class SpeechSegment(
    val startSampleIndex: Long,
    val endSampleIndexExclusive: Long,
) {
    init {
        require(startSampleIndex >= 0L)
        require(endSampleIndexExclusive > startSampleIndex)
    }

    val sampleCount: Long
        get() = endSampleIndexExclusive - startSampleIndex
}

data class RelativeTimedToken(
    val text: String,
    val startSampleOffset: Long?,
    val endSampleOffsetExclusive: Long? = null,
) {
    init {
        require(text.isNotEmpty())
        require(startSampleOffset == null || startSampleOffset >= 0L)
        require(
            endSampleOffsetExclusive == null ||
                (startSampleOffset != null && endSampleOffsetExclusive >= startSampleOffset),
        )
    }
}

data class AsrCapabilities(
    val supportsStreaming: Boolean,
    val supportsPartial: Boolean,
    val supportsTokenTiming: Boolean,
    val supportsLanguageDetection: Boolean,
    val supportsConfidence: Boolean,
    val supportsInverseTextNormalization: Boolean,
    val punctuationCapability: PunctuationCapability,
    val supportsSecondPass: Boolean,
    val executionMode: AsrExecutionMode =
        when {
            supportsStreaming -> AsrExecutionMode.TRUE_STREAMING
            supportsSecondPass -> AsrExecutionMode.SECOND_PASS
            else -> AsrExecutionMode.OFFLINE
        },
    val timestampCapability: TimestampCapability =
        if (supportsTokenTiming) TimestampCapability.TOKEN else TimestampCapability.NONE,
    val supportsLanguageForcing: Boolean = false,
    val supportsHotwords: Boolean = false,
)

data class AsrHypothesis(
    val text: String,
    val tokens: List<RelativeTimedToken> = emptyList(),
    val detectedLanguage: String? = null,
    val confidence: Float? = null,
    val punctuationCapability: PunctuationCapability,
    val isFinal: Boolean,
    val stability: AsrHypothesisStability =
        if (isFinal) AsrHypothesisStability.FINAL else AsrHypothesisStability.PARTIAL,
) {
    init {
        require((stability == AsrHypothesisStability.FINAL) == isFinal) {
            "FINAL stability must match isFinal=true; PARTIAL/STABLE require isFinal=false"
        }
    }
}

data class TranscriptToken(
    val text: String,
    val startSampleIndex: Long?,
    val endSampleIndexExclusive: Long?,
    val source: TokenSource,
)

enum class TokenSource {
    FIRST_PASS,
    SECOND_PASS,
}

data class TranscriptSegment(
    val segmentIndex: Int,
    val startSampleIndex: Long,
    val endSampleIndexExclusive: Long,
    val firstPassRawText: String,
    val secondPassRawText: String? = null,
    val finalText: String,
    val detectedLanguage: String? = null,
    val confidence: Float? = null,
    val tokens: List<TranscriptToken> = emptyList(),
) {
    init {
        require(segmentIndex >= 0)
        require(startSampleIndex >= 0L)
        require(endSampleIndexExclusive > startSampleIndex)
    }
}

data class TranscriptionProgress(
    val phase: TranscriptionPhase,
    val processedUnits: Long? = null,
    val totalUnits: Long? = null,
) {
    init {
        require(processedUnits == null || processedUnits >= 0L)
        require(totalUnits == null || totalUnits >= 0L)
        if (processedUnits != null && totalUnits != null) {
            require(processedUnits <= totalUnits)
        }
    }

    val fraction: Double?
        get() =
            if (processedUnits != null && totalUnits != null && totalUnits > 0L) {
                processedUnits.toDouble() / totalUnits.toDouble()
            } else {
                null
            }
}

fun interface ProgressListener {
    suspend fun onProgress(progress: TranscriptionProgress)
}

interface VadEngine : Closeable {
    val model: ModelDescriptor

    suspend fun analyze(
        source: PcmSource,
        progressListener: ProgressListener? = null,
    ): List<SpeechSegment>
}

interface StreamingAsrEngine : Closeable {
    val model: ModelDescriptor
    val capabilities: AsrCapabilities

    suspend fun openSession(): StreamingAsrSession
}

interface StreamingAsrSession : Closeable {
    suspend fun acceptSamples(
        samples: ShortArray,
        offset: Int = 0,
        count: Int = samples.size - offset,
    )

    suspend fun decode(): AsrHypothesis

    suspend fun finishInput(): AsrHypothesis

    suspend fun reset()
}

interface SecondPassAsrEngine : Closeable {
    val model: ModelDescriptor
    val capabilities: AsrCapabilities

    suspend fun transcribe(
        samples: ShortArray,
        sampleRateHz: Int,
    ): AsrHypothesis
}

interface PunctuationEngine : Closeable {
    val model: ModelDescriptor

    suspend fun addPunctuation(text: String): String
}

fun relativeSecondsToAbsoluteSampleIndex(
    segmentStartSampleIndex: Long,
    relativeSeconds: Double,
    sampleRateHz: Int,
    segmentEndSampleIndexExclusive: Long,
): Long {
    require(segmentStartSampleIndex >= 0L)
    require(relativeSeconds.isFinite() && relativeSeconds >= 0.0)
    require(sampleRateHz > 0)
    require(segmentEndSampleIndexExclusive > segmentStartSampleIndex)

    val offset = (relativeSeconds * sampleRateHz.toDouble()).roundToLong()
    val absolute = Math.addExact(segmentStartSampleIndex, offset)
    return absolute.coerceIn(
        segmentStartSampleIndex,
        segmentEndSampleIndexExclusive,
    )
}

fun mapRelativeTokensToAbsolute(
    tokens: List<RelativeTimedToken>,
    segment: SpeechSegment,
    source: TokenSource = TokenSource.FIRST_PASS,
): List<TranscriptToken> {
    var previousStart: Long? = null
    return tokens.map { token ->
        val start =
            token.startSampleOffset?.let { offset ->
                Math.addExact(segment.startSampleIndex, offset)
                    .coerceIn(segment.startSampleIndex, segment.endSampleIndexExclusive)
            }
        val end =
            token.endSampleOffsetExclusive?.let { offset ->
                Math.addExact(segment.startSampleIndex, offset)
                    .coerceIn(segment.startSampleIndex, segment.endSampleIndexExclusive)
            }

        require(start == null || previousStart == null || start >= previousStart!!) {
            "token timestamps must be monotonic"
        }
        require(start == null || end == null || end >= start) {
            "token end must not precede token start"
        }
        if (start != null) previousStart = start

        TranscriptToken(
            text = token.text,
            startSampleIndex = start,
            endSampleIndexExclusive = end,
            source = source,
        )
    }
}
