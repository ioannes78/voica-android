package io.github.ioannes78.voica.transcript

import io.github.ioannes78.voica.audio.PcmSourceResolver
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.max
import kotlin.math.min

internal data class FirstPassSegment(
    val segmentIndex: Int,
    val speechSegment: SpeechSegment,
    val hypothesis: AsrHypothesis,
    val absoluteTokens: List<TranscriptToken>,
)

internal data class FirstPassComputation(
    val totalSampleCount: Long,
    val speechSampleCount: Long,
    val speechSegments: List<SpeechSegment>,
    val segments: List<FirstPassSegment>,
)

internal class FirstPassTranscriptionRunner(
    private val pcmSourceResolver: PcmSourceResolver,
    private val vadEngineFactory: VadEngineFactory,
    private val asrEngineFactory: StreamingAsrEngineFactory,
    private val readChunkSamples: Int,
) {
    init {
        require(readChunkSamples > 0)
    }

    suspend fun run(
        recordingId: String,
        progressListener: ProgressListener?,
    ): FirstPassComputation {
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
        val speechSampleCount =
            speechSegments.fold(0L) { total, segment ->
                Math.addExact(total, segment.sampleCount)
            }

        if (speechSegments.isEmpty()) {
            return FirstPassComputation(
                totalSampleCount = totalSampleCount,
                speechSampleCount = 0L,
                speechSegments = emptyList(),
                segments = emptyList(),
            )
        }

        val segments =
            transcribeSpeechSegments(
                recordingId = recordingId,
                expectedTotalSampleCount = totalSampleCount,
                speechSegments = speechSegments,
                speechSampleCount = speechSampleCount,
                progressListener = progressListener,
            )

        return FirstPassComputation(
            totalSampleCount = totalSampleCount,
            speechSampleCount = speechSampleCount,
            speechSegments = speechSegments,
            segments = segments,
        )
    }

    private suspend fun transcribeSpeechSegments(
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
            "first-pass transcription requires a streaming ASR engine"
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
                                        source = TokenSource.FIRST_PASS,
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
}

internal fun validateSpeechSegments(
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

internal fun safeMapRelativeTokens(
    tokens: List<RelativeTimedToken>,
    segment: SpeechSegment,
    source: TokenSource,
): List<TranscriptToken> =
    try {
        mapRelativeTokensToAbsolute(
            tokens = tokens,
            segment = segment,
            source = source,
        )
    } catch (_: IllegalArgumentException) {
        emptyList()
    } catch (_: ArithmeticException) {
        emptyList()
    }
