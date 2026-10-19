package io.github.ioannes78.voica.fsmn

import io.github.ioannes78.voica.audio.CanonicalPcmProfile
import io.github.ioannes78.voica.audio.PcmSource
import io.github.ioannes78.voica.model.ModelDescriptor
import io.github.ioannes78.voica.model.ModelKind
import io.github.ioannes78.voica.transcript.ProgressListener
import io.github.ioannes78.voica.transcript.SpeechSegment
import io.github.ioannes78.voica.transcript.TranscriptionPhase
import io.github.ioannes78.voica.transcript.TranscriptionProgress
import io.github.ioannes78.voica.transcript.VadEngine
import java.io.Closeable

/** Candidate-only Stage 16B FSMN-VAD model location. */
data class FsmnVadModelLocation(
    val modelPath: String,
    val cmvnPath: String,
) {
    init {
        require(modelPath.isNotBlank())
        require(cmvnPath.isNotBlank())
    }
}

data class FsmnVadSettings(
    val numThreads: Int = DEFAULT_NUM_THREADS,
    val speechNoiseThreshold: Float = 0.6F,
    val maxSpeechDurationSeconds: Float = 60.0F,
) {
    init {
        require(numThreads > 0)
        require(speechNoiseThreshold > -1.0F && speechNoiseThreshold < 1.0F)
        require(maxSpeechDurationSeconds > 0.0F)
    }

    internal val maxSpeechDurationMs: Int
        get() = (maxSpeechDurationSeconds * 1_000.0F).toInt()

    companion object {
        const val DEFAULT_NUM_THREADS = 2
    }
}

class FsmnVadEngine internal constructor(
    override val model: ModelDescriptor,
    private val modelLocation: FsmnVadModelLocation,
    private val settings: FsmnVadSettings,
    private val sessionFactory: NativeFsmnVadSessionFactory,
    private val pcmChunkSamples: Int,
) : VadEngine {
    private var closed = false

    constructor(
        model: ModelDescriptor,
        modelLocation: FsmnVadModelLocation,
        settings: FsmnVadSettings = FsmnVadSettings(),
        pcmChunkSamples: Int = DEFAULT_PCM_CHUNK_SAMPLES,
    ) : this(
        model = model,
        modelLocation = modelLocation,
        settings = settings,
        sessionFactory = JniFsmnVadSessionFactory,
        pcmChunkSamples = pcmChunkSamples,
    )

    init {
        require(model.kind == ModelKind.VAD) {
            "FSMN VAD engine requires a VAD model descriptor"
        }
        require(pcmChunkSamples > 0)
    }

    override suspend fun analyze(
        source: PcmSource,
        progressListener: ProgressListener?,
    ): List<SpeechSegment> {
        check(!closed) { "VAD engine is closed" }
        require(source.sampleRateHz == CanonicalPcmProfile.SAMPLE_RATE_HZ) {
            "FSMN VAD requires 16 kHz canonical PCM"
        }
        require(source.channelCount == CanonicalPcmProfile.CHANNEL_COUNT) {
            "FSMN VAD requires mono canonical PCM"
        }
        require(source.totalSampleCount >= 0L)

        if (source.totalSampleCount == 0L) {
            progressListener?.onProgress(
                TranscriptionProgress(
                    phase = TranscriptionPhase.VAD,
                    processedUnits = 0L,
                    totalUnits = 0L,
                ),
            )
            return emptyList()
        }

        val output = mutableListOf<SpeechSegment>()
        val buffer = ShortArray(pcmChunkSamples)
        var processed = 0L
        var lastEnd = 0L

        sessionFactory.create(modelLocation, settings).use { session ->
            while (true) {
                val read = source.read(buffer) ?: break
                require(read.startSampleIndex == processed) {
                    "PCM source is not sequential: expected $processed, got ${read.startSampleIndex}"
                }
                require(read.sampleCount in 1..buffer.size)
                val nextProcessed = Math.addExact(processed, read.sampleCount.toLong())
                require(nextProcessed <= source.totalSampleCount) {
                    "PCM source exceeded declared total sample count"
                }

                lastEnd =
                    appendValidatedSegments(
                        sourceTotalSamples = source.totalSampleCount,
                        currentProcessedSamples = nextProcessed,
                        previousEnd = lastEnd,
                        nativeSegments = session.accept(buffer, 0, read.sampleCount),
                        target = output,
                    )

                processed = nextProcessed
                progressListener?.onProgress(
                    TranscriptionProgress(
                        phase = TranscriptionPhase.VAD,
                        processedUnits = processed,
                        totalUnits = source.totalSampleCount,
                    ),
                )
            }

            require(processed == source.totalSampleCount) {
                "PCM source ended at $processed but declared ${source.totalSampleCount}"
            }

            appendValidatedSegments(
                sourceTotalSamples = source.totalSampleCount,
                currentProcessedSamples = processed,
                previousEnd = lastEnd,
                nativeSegments = session.finish(),
                target = output,
            )
        }

        return output
    }

    override fun close() {
        closed = true
    }

    private fun appendValidatedSegments(
        sourceTotalSamples: Long,
        currentProcessedSamples: Long,
        previousEnd: Long,
        nativeSegments: List<NativeFsmnVadSegment>,
        target: MutableList<SpeechSegment>,
    ): Long {
        var lastEnd = previousEnd
        nativeSegments.forEach { native ->
            require(native.startSampleIndex >= 0L)
            require(native.endSampleIndexExclusive > native.startSampleIndex)
            require(native.startSampleIndex >= lastEnd) {
                "FSMN VAD segments must be monotonic and non-overlapping"
            }
            require(native.endSampleIndexExclusive <= currentProcessedSamples) {
                "FSMN VAD emitted a segment beyond processed PCM"
            }
            val end = native.endSampleIndexExclusive.coerceAtMost(sourceTotalSamples)
            if (native.startSampleIndex < sourceTotalSamples && end > native.startSampleIndex) {
                target += SpeechSegment(native.startSampleIndex, end)
                lastEnd = end
            }
        }
        return lastEnd
    }

    private companion object {
        const val DEFAULT_PCM_CHUNK_SAMPLES = 4_096
    }
}

internal data class NativeFsmnVadSegment(
    val startSampleIndex: Long,
    val endSampleIndexExclusive: Long,
)

internal interface NativeFsmnVadSession : Closeable {
    fun accept(
        samples: ShortArray,
        offset: Int,
        count: Int,
    ): List<NativeFsmnVadSegment>

    fun finish(): List<NativeFsmnVadSegment>
}

internal fun interface NativeFsmnVadSessionFactory {
    fun create(
        location: FsmnVadModelLocation,
        settings: FsmnVadSettings,
    ): NativeFsmnVadSession
}

private object JniFsmnVadSessionFactory : NativeFsmnVadSessionFactory {
    override fun create(
        location: FsmnVadModelLocation,
        settings: FsmnVadSettings,
    ): NativeFsmnVadSession =
        JniFsmnVadSession(
            handle =
                FsmnNativeBridge.nativeCreate(
                    modelPath = location.modelPath,
                    cmvnPath = location.cmvnPath,
                    numThreads = settings.numThreads,
                    speechNoiseThreshold = settings.speechNoiseThreshold,
                    maxSpeechDurationMs = settings.maxSpeechDurationMs,
                ),
        )
}

private class JniFsmnVadSession(
    private var handle: Long,
) : NativeFsmnVadSession {
    init {
        check(handle != 0L) { "FSMN native session creation returned a null handle" }
    }

    override fun accept(
        samples: ShortArray,
        offset: Int,
        count: Int,
    ): List<NativeFsmnVadSegment> {
        check(handle != 0L) { "FSMN native session is closed" }
        require(offset >= 0 && count >= 0 && offset + count <= samples.size)
        if (count == 0) return emptyList()
        return decodeSegments(FsmnNativeBridge.nativeAccept(handle, samples, offset, count))
    }

    override fun finish(): List<NativeFsmnVadSegment> {
        check(handle != 0L) { "FSMN native session is closed" }
        return decodeSegments(FsmnNativeBridge.nativeFinish(handle))
    }

    override fun close() {
        val current = handle
        if (current != 0L) {
            handle = 0L
            FsmnNativeBridge.nativeClose(current)
        }
    }

    private fun decodeSegments(values: LongArray): List<NativeFsmnVadSegment> {
        require(values.size % 2 == 0) { "FSMN native segment payload must contain start/end pairs" }
        return List(values.size / 2) { index ->
            NativeFsmnVadSegment(
                startSampleIndex = values[index * 2],
                endSampleIndexExclusive = values[index * 2 + 1],
            )
        }
    }
}

internal object FsmnNativeBridge {
    init {
        System.loadLibrary("voica_fsmn_candidate_jni")
    }

    external fun nativeCreate(
        modelPath: String,
        cmvnPath: String,
        numThreads: Int,
        speechNoiseThreshold: Float,
        maxSpeechDurationMs: Int,
    ): Long

    external fun nativeAccept(
        handle: Long,
        samples: ShortArray,
        offset: Int,
        count: Int,
    ): LongArray

    external fun nativeFinish(handle: Long): LongArray

    external fun nativeClose(handle: Long)
}
