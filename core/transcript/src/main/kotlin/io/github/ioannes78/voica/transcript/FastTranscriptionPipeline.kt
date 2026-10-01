package io.github.ioannes78.voica.transcript

import io.github.ioannes78.voica.audio.PcmSourceResolver
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

fun interface VadEngineFactory {
    suspend fun open(): VadEngine
}

fun interface StreamingAsrEngineFactory {
    suspend fun open(): StreamingAsrEngine
}

fun interface PunctuationEngineFactory {
    suspend fun open(): PunctuationEngine
}

fun interface SecondPassAsrEngineFactory {
    suspend fun open(): SecondPassAsrEngine
}

data class FastTranscriptionResult(
    val totalSampleCount: Long,
    val speechSegments: List<SpeechSegment>,
    val transcriptSegments: List<TranscriptSegment>,
)

class FastTranscriptionPipeline(
    pcmSourceResolver: PcmSourceResolver,
    vadEngineFactory: VadEngineFactory,
    asrEngineFactory: StreamingAsrEngineFactory,
    private val punctuationEngineFactory: PunctuationEngineFactory,
    readChunkSamples: Int = DEFAULT_READ_CHUNK_SAMPLES,
) {
    private val firstPassRunner =
        FirstPassTranscriptionRunner(
            pcmSourceResolver = pcmSourceResolver,
            vadEngineFactory = vadEngineFactory,
            asrEngineFactory = asrEngineFactory,
            readChunkSamples = readChunkSamples,
        )

    suspend fun transcribe(
        recordingId: String,
        progressListener: ProgressListener? = null,
    ): FastTranscriptionResult {
        val firstPass =
            firstPassRunner.run(
                recordingId = recordingId,
                progressListener = progressListener,
            )

        if (firstPass.segments.isEmpty()) {
            return FastTranscriptionResult(
                totalSampleCount = firstPass.totalSampleCount,
                speechSegments = firstPass.speechSegments,
                transcriptSegments = emptyList(),
            )
        }

        val finalSegments =
            runPunctuation(
                firstPass = firstPass.segments,
                progressListener = progressListener,
            )

        return FastTranscriptionResult(
            totalSampleCount = firstPass.totalSampleCount,
            speechSegments = firstPass.speechSegments,
            transcriptSegments = finalSegments,
        )
    }

    private suspend fun runPunctuation(
        firstPass: List<FirstPassSegment>,
        progressListener: ProgressListener?,
    ): List<TranscriptSegment> {
        val engine = punctuationEngineFactory.open()
        try {
            val total = firstPass.size.toLong()
            val output = ArrayList<TranscriptSegment>(firstPass.size)
            progressListener?.onProgress(
                TranscriptionProgress(
                    phase = TranscriptionPhase.PUNCTUATION,
                    processedUnits = 0L,
                    totalUnits = total,
                ),
            )

            firstPass.forEachIndexed { index, raw ->
                currentCoroutineContext().ensureActive()
                val finalText =
                    if (raw.hypothesis.text.isBlank()) {
                        raw.hypothesis.text
                    } else {
                        engine.addPunctuation(raw.hypothesis.text)
                    }

                output +=
                    TranscriptSegment(
                        segmentIndex = raw.segmentIndex,
                        startSampleIndex = raw.speechSegment.startSampleIndex,
                        endSampleIndexExclusive = raw.speechSegment.endSampleIndexExclusive,
                        firstPassRawText = raw.hypothesis.text,
                        finalText = finalText,
                        detectedLanguage = raw.hypothesis.detectedLanguage,
                        confidence = raw.hypothesis.confidence,
                        tokens = raw.absoluteTokens,
                    )

                progressListener?.onProgress(
                    TranscriptionProgress(
                        phase = TranscriptionPhase.PUNCTUATION,
                        processedUnits = (index + 1).toLong(),
                        totalUnits = total,
                    ),
                )
            }
            return output
        } finally {
            engine.close()
        }
    }

    private companion object {
        const val DEFAULT_READ_CHUNK_SAMPLES = 4_096
    }
}
