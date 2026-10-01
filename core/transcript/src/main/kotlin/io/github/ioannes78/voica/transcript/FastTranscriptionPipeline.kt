package io.github.ioannes78.voica.transcript

import io.github.ioannes78.voica.audio.PcmSource
import io.github.ioannes78.voica.audio.PcmSourceResolver
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.max
import kotlin.math.min

fun interface VadEngineFactory {
    suspend fun open(): VadEngine
}

fun interface StreamingAsrEngineFactory {
    suspend fun open(): StreamingAsrEngine
}

fun interface PunctuationEngineFactory {
    suspend fun open(): PunctuationEngine
}

data class FastTranscriptionResult(
    val totalSampleCount: Long,
    val speechSegments: List<SpeechSegment>,
    val transcriptSegments: List<TranscriptSegment>,
)

class FastTranscriptionPipeline(
    private val pcmSourceResolver: PcmSourceResolver,
    private val vadEngineFactory: VadEngineFactory,
    private val asrEngineFactory: StreamingAsrEngineFactory,
    private val punctuationEngineFactory: PunctuationEngineFactory,
    private val readChunkSamples: Int = DEFAULT_READ_CHUNK_SAMPLES,
) {
    init {
        require(readChunkSamples > 0)
    }

    suspend fun transcribe(
        recordingId: String,
        progressListener: ProgressListener? = null,
    ): FastTranscriptionResult {
        require(recordingId.isNotBlank())

        progressListener?.onProgress(
            TranscriptionProgress(phase = TranscriptionPhase.PREPARING),
        )

        val vadSource =
            pcmSourceResolver.resolvePcmSource(recordingId)
                ?: error("canonical PCM source is unavailable for recording $recordingId")
        val totalSampleCount = vadSource.totalSampleCount

        val speechSegments =
            try {
                val vad = vadEngineFactory.open()
                try {
                    vad.analyze(vadSource, progressListener)
                } finally {
                    vad.close()
                }
            } finally {
                vadSource.close()
            }

        validateSpeechSegments(speechSegments, totalSampleCount)

        if (speechSegments.isEmpty()) {
            return FastTranscriptionResult(
                totalSampleCount = totalSampleCount,
                speechSegments = emptyList(),
                transcriptSegments = emptyList(),
            )
        }

        val speechSampleCount =
            speechSegments.fold(0L) { total, segment ->
                Math.addExact(total, segment.sampleCount)
            }

        val firstPass =
            runFirstPass(
                recordingId = recordingId,
                expectedTotalSampleCount = totalSampleCount,
                speechSegments = speechSegments,
                speechSampleCount = speechSampleCount,
                progressListener = progressListener,
            )

        val finalSegments =
            runPunctuation(
                firstPass = firstPass,
                progressListener = progressListener,
            )

        return FastTranscriptionResult(
            totalSampleCount = totalSampleCount,
            speechSegments = speechSegments,
            transcriptSegments = finalSegments,
        )
    }

    private suspend fun runFirstPass(
        recordingId: String,
        expectedTotalSampleCount: Long,
        speechSegments: List<SpeechSegment>,
        speechSampleCount: Long,
        progressListener: ProgressListener?,
    ): List<FirstPassSegment> {
        val source =
            pcmSourceResolver.resolvePcmSource(recordingId)
                ?: error("canonical PCM source disappeared before first-pass ASR")
        require(source.totalSampleCount == expectedTotalSampleCount) {
            "canonical PCM sample count changed between VAD and ASR"
        }

        val engine = asrEngineFactory.open()
        require(engine.capabilities.supportsStreaming) {
            "fast transcription requires a streaming ASR engine"
        }

        try {
            val output = ArrayList<FirstPassSegment>(speechSegments.size)
            val buffer = ShortArray(readChunkSamples)
            var sourceCursor = 0L
            var segmentIndex = 0
            var session: StreamingAsrSession? = null
            var processedSpeechSamples = 0L

            progressListener?.onProgress(
                TranscriptionProgress(
                    phase = TranscriptionPhase.FIRST_PASS,
                    processedUnits = 0L,
                    totalUnits = speechSampleCount,
                ),
            )

            while (true) {
                currentCoroutineContext().ensureActive()
                val read = source.read(buffer) ?: break
                require(read.startSampleIndex == sourceCursor) {
                    "PCM source is not sequential during ASR"
                }
                require(read.sampleCount in 1..buffer.size)
                val readEnd = Math.addExact(read.startSampleIndex, read.sampleCount.toLong())
                require(readEnd <= expectedTotalSampleCount)

                while (
                    segmentIndex < speechSegments.size &&
                    speechSegments[segmentIndex].startSampleIndex < readEnd
                ) {
                    currentCoroutineContext().ensureActive()
                    val segment = speechSegments[segmentIndex]
                    require(segment.endSampleIndexExclusive > read.startSampleIndex) {
                        "speech segment was skipped by PCM reader"
                    }

                    val overlapStart = max(segment.startSampleIndex, read.startSampleIndex)
                    val overlapEnd = min(segment.endSampleIndexExclusive, readEnd)

                    if (overlapEnd > overlapStart) {
                        if (session == null) {
                            session = engine.openSession()
                        }

                        val targetOffset =
                            Math.toIntExact(overlapStart - read.startSampleIndex)
                        val count = Math.toIntExact(overlapEnd - overlapStart)
                        session.acceptSamples(
                            samples = buffer,
                            offset = targetOffset,
                            count = count,
                        )
                        session.decode()

                        processedSpeechSamples =
                            Math.addExact(processedSpeechSamples, count.toLong())
                        progressListener?.onProgress(
                            TranscriptionProgress(
                                phase = TranscriptionPhase.FIRST_PASS,
                                processedUnits = processedSpeechSamples,
                                totalUnits = speechSampleCount,
                            ),
                        )
                    }

                    if (segment.endSampleIndexExclusive <= readEnd) {
                        val completedSession =
                            session
                                ?: error("speech segment completed without PCM samples")
                        val final =
                            try {
                                completedSession.finishInput()
                            } finally {
                                completedSession.close()
                            }
                        session = null

                        require(final.isFinal) {
                            "streaming ASR did not return a final hypothesis"
                        }

                        output +=
                            FirstPassSegment(
                                segmentIndex = segmentIndex,
                                speechSegment = segment,
                                hypothesis = final,
                                absoluteTokens =
                                    safeMapRelativeTokens(
                                        tokens = final.tokens,
                                        segment = segment,
                                    ),
                            )
                        segmentIndex += 1
                    } else {
                        break
                    }
                }

                sourceCursor = readEnd
            }

            session?.close()
            require(sourceCursor == expectedTotalSampleCount) {
                "PCM source ended before its declared sample count"
            }
            require(segmentIndex == speechSegments.size) {
                "PCM source ended before all speech segments were transcribed"
            }
            require(processedSpeechSamples == speechSampleCount) {
                "ASR processed speech sample count mismatch"
            }
            return output
        } finally {
            engine.close()
            source.close()
        }
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

    private data class FirstPassSegment(
        val segmentIndex: Int,
        val speechSegment: SpeechSegment,
        val hypothesis: AsrHypothesis,
        val absoluteTokens: List<TranscriptToken>,
    )

    private companion object {
        const val DEFAULT_READ_CHUNK_SAMPLES = 4_096
    }
}

private fun validateSpeechSegments(
    segments: List<SpeechSegment>,
    totalSampleCount: Long,
) {
    require(totalSampleCount >= 0L)
    var previousEnd = 0L
    segments.forEach { segment ->
        require(segment.startSampleIndex >= previousEnd) {
            "speech segments must be ordered and non-overlapping"
        }
        require(segment.endSampleIndexExclusive <= totalSampleCount) {
            "speech segment exceeds canonical PCM bounds"
        }
        previousEnd = segment.endSampleIndexExclusive
    }
}

private fun safeMapRelativeTokens(
    tokens: List<RelativeTimedToken>,
    segment: SpeechSegment,
): List<TranscriptToken> =
    try {
        mapRelativeTokensToAbsolute(tokens, segment)
    } catch (_: IllegalArgumentException) {
        emptyList()
    } catch (_: ArithmeticException) {
        emptyList()
    }
