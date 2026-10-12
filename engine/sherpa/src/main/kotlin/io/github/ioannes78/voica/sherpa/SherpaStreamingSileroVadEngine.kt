package io.github.ioannes78.voica.sherpa

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import io.github.ioannes78.voica.audio.CanonicalPcmProfile
import io.github.ioannes78.voica.model.ModelDescriptor
import io.github.ioannes78.voica.model.ModelKind
import io.github.ioannes78.voica.transcript.StreamingVadEngine
import io.github.ioannes78.voica.transcript.StreamingVadSession
import io.github.ioannes78.voica.transcript.StreamingVadSpeechSegment
import io.github.ioannes78.voica.transcript.StreamingVadUpdate
import java.io.Closeable

class SherpaStreamingSileroVadEngine internal constructor(
    override val model: ModelDescriptor,
    private val modelLocation: SherpaVadModelLocation,
    private val settings: SileroVadSettings,
    private val sessionFactory: NativeStreamingVadSessionFactory,
) : StreamingVadEngine {
    private var closed = false

    constructor(
        model: ModelDescriptor,
        modelLocation: SherpaVadModelLocation,
        settings: SileroVadSettings = SileroVadSettings(),
    ) : this(
        model = model,
        modelLocation = modelLocation,
        settings = settings,
        sessionFactory = SherpaNativeStreamingVadSessionFactory,
    )

    init {
        require(model.kind == ModelKind.VAD) {
            "Streaming Silero VAD requires a VAD model descriptor"
        }
    }

    override suspend fun openSession(): StreamingVadSession {
        check(!closed) { "Streaming VAD engine is closed" }
        return SherpaStreamingVadSession(
            native = sessionFactory.create(modelLocation, settings),
        )
    }

    override fun close() {
        closed = true
    }
}

internal class SherpaStreamingVadSession(
    private val native: NativeStreamingVadSession,
) : StreamingVadSession {
    private var acceptedSampleCount = 0L
    private var inputFinished = false
    private var closed = false

    override suspend fun acceptSamples(
        samples: ShortArray,
        offset: Int,
        count: Int,
    ): StreamingVadUpdate {
        check(!closed) { "Streaming VAD session is closed" }
        check(!inputFinished) { "Streaming VAD input is already finished" }
        require(offset >= 0)
        require(count >= 0)
        require(offset + count <= samples.size)
        if (count == 0) return currentUpdate()

        val normalized = FloatArray(count) { index -> samples[offset + index] / 32768.0F }
        native.acceptWaveform(normalized)
        acceptedSampleCount = Math.addExact(acceptedSampleCount, count.toLong())
        return currentUpdate()
    }

    override suspend fun finishInput(): StreamingVadUpdate {
        check(!closed) { "Streaming VAD session is closed" }
        if (!inputFinished) {
            inputFinished = true
            native.flush()
        }
        return currentUpdate()
    }

    override suspend fun reset() {
        check(!closed) { "Streaming VAD session is closed" }
        native.reset()
        acceptedSampleCount = 0L
        inputFinished = false
    }

    override fun close() {
        if (closed) return
        closed = true
        native.close()
    }

    private fun currentUpdate(): StreamingVadUpdate {
        val segments = mutableListOf<StreamingVadSpeechSegment>()
        while (!native.empty()) {
            val segment = native.front()
            require(segment.startSampleIndex >= 0L)
            val end = Math.addExact(segment.startSampleIndex, segment.sampleCount.toLong())
            require(end <= acceptedSampleCount) {
                "VAD emitted segment beyond accepted realtime PCM"
            }
            if (end > segment.startSampleIndex) {
                segments +=
                    StreamingVadSpeechSegment(
                        startSampleOffset = segment.startSampleIndex,
                        endSampleOffsetExclusive = end,
                    )
            }
            native.pop()
        }
        return StreamingVadUpdate(
            speechDetected = native.isSpeechDetected(),
            completedSegments = segments,
        )
    }
}

internal interface NativeStreamingVadSession : Closeable {
    fun acceptWaveform(samples: FloatArray)

    fun empty(): Boolean

    fun front(): NativeVadSegment

    fun pop()

    fun isSpeechDetected(): Boolean

    fun reset()

    fun flush()
}

internal fun interface NativeStreamingVadSessionFactory {
    fun create(
        location: SherpaVadModelLocation,
        settings: SileroVadSettings,
    ): NativeStreamingVadSession
}

private object SherpaNativeStreamingVadSessionFactory : NativeStreamingVadSessionFactory {
    override fun create(
        location: SherpaVadModelLocation,
        settings: SileroVadSettings,
    ): NativeStreamingVadSession {
        val modelPath =
            when (location) {
                is SherpaVadModelLocation.Asset -> location.assetPath
                is SherpaVadModelLocation.File -> location.absolutePath
            }
        val config =
            VadModelConfig(
                sileroVadModelConfig =
                    SileroVadModelConfig(
                        model = modelPath,
                        threshold = settings.threshold,
                        minSilenceDuration = settings.minSilenceDurationSeconds,
                        minSpeechDuration = settings.minSpeechDurationSeconds,
                        windowSize = settings.windowSizeSamples,
                        maxSpeechDuration = settings.maxSpeechDurationSeconds,
                    ),
                sampleRate = CanonicalPcmProfile.SAMPLE_RATE_HZ,
                numThreads = settings.numThreads,
                provider = SherpaRuntime.PROVIDER_CPU,
            )
        val delegate =
            when (location) {
                is SherpaVadModelLocation.Asset -> Vad(location.assetManager, config)
                is SherpaVadModelLocation.File -> Vad(null, config)
            }
        return SherpaNativeStreamingVadSession(delegate)
    }
}

private class SherpaNativeStreamingVadSession(
    private val delegate: Vad,
) : NativeStreamingVadSession {
    override fun acceptWaveform(samples: FloatArray) = delegate.acceptWaveform(samples)

    override fun empty(): Boolean = delegate.empty()

    override fun front(): NativeVadSegment {
        val segment = delegate.front()
        return NativeVadSegment(
            startSampleIndex = segment.start.toLong(),
            sampleCount = segment.samples.size,
        )
    }

    override fun pop() = delegate.pop()

    override fun isSpeechDetected(): Boolean = delegate.isSpeechDetected()

    override fun reset() = delegate.reset()

    override fun flush() = delegate.flush()

    override fun close() = delegate.release()
}
