package io.github.ioannes78.voica

import android.content.Context
import io.github.ioannes78.voica.audio.CanonicalRealtimeEventSource
import io.github.ioannes78.voica.audio.StreamingAudioBuffer
import io.github.ioannes78.voica.sherpa.SherpaRuntime
import io.github.ioannes78.voica.transcript.PunctuationEngine
import io.github.ioannes78.voica.transcript.RealtimeDurableOutputFailure
import io.github.ioannes78.voica.transcript.RealtimeDurableOutputSink
import io.github.ioannes78.voica.transcript.RealtimeHypothesisStabilizer
import io.github.ioannes78.voica.transcript.RealtimeLocalAudioResult
import io.github.ioannes78.voica.transcript.RealtimeLocalAudioSideSink
import io.github.ioannes78.voica.transcript.RealtimeLocalAudioTargetFactory
import io.github.ioannes78.voica.transcript.RealtimePunctuationProcessor
import io.github.ioannes78.voica.transcript.RealtimeStablePunctuationMode
import io.github.ioannes78.voica.transcript.RealtimeStreamingAsrConfig
import io.github.ioannes78.voica.transcript.RealtimeStreamingAsrProcessor
import io.github.ioannes78.voica.transcript.RealtimeTranscriptCoordinator
import io.github.ioannes78.voica.transcript.RealtimeTranscriptionEventProcessor
import io.github.ioannes78.voica.transcript.RealtimeTranscriptionOutput
import io.github.ioannes78.voica.transcript.RealtimeTranscriptionOutputSink
import io.github.ioannes78.voica.transcript.RealtimeTranscriptionPipeline
import io.github.ioannes78.voica.transcript.RealtimeTranscriptionPipelineEventProcessor
import io.github.ioannes78.voica.transcript.RealtimeTranscriptionSessionRunner
import io.github.ioannes78.voica.transcript.RealtimeTranscriptionSessionRunnerState
import io.github.ioannes78.voica.transcript.StreamingAsrEngine
import io.github.ioannes78.voica.transcript.StreamingAudioSideSink
import io.github.ioannes78.voica.transcript.StreamingVadEngine
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class Stage16ARealtimePipelineConfig(
    /** Two seconds of canonical 16 kHz PCM absorbs screen-off scheduling jitter without hiding loss. */
    val maxBufferedPcmFrames: Int = 100,
    val maxBufferedPcmSamples: Int = 32_000,
    /** Provisional bring-up candidate; final product value is frozen only after P7 device benchmark. */
    val targetChunkSamples: Int = 1_280,
    val vadPrerollSamples: Int = 3_200,
    val localAudioQueueCapacity: Int = 128,
    val durableOutputQueueCapacity: Int = 64,
) {
    init {
        require(maxBufferedPcmFrames > 0)
        require(maxBufferedPcmSamples > 0)
        require(targetChunkSamples in CHUNK_SAMPLE_CANDIDATES) {
            "targetChunkSamples must remain inside the approved Stage 16A benchmark matrix"
        }
        require(vadPrerollSamples >= targetChunkSamples)
        require(localAudioQueueCapacity > 0)
        require(durableOutputQueueCapacity > 0)
    }

    val targetChunkDurationMs: Int
        get() = targetChunkSamples * 1_000 / 16_000

    companion object {
        /** 40 / 80 / 100 / 160 / 200 ms at canonical 16 kHz. */
        val CHUNK_SAMPLE_CANDIDATES: Set<Int> = setOf(640, 1_280, 1_600, 2_560, 3_200)
    }
}

internal fun stage16ARealtimeRunAudioRoot(
    localAudioRoot: File,
    namespace: String,
): File {
    require(namespace.isNotBlank()) { "Stage 16A local-audio namespace must not be blank" }
    require(namespace.all { char -> char.isLetterOrDigit() || char == '-' || char == '_' || char == '.' }) {
        "Stage 16A local-audio namespace contains unsafe path characters"
    }
    return File(localAudioRoot, "run-$namespace")
}

class Stage16ARealtimePipelineBundle internal constructor(
    val models: Stage16ARealtimeModelLeaseBundle,
    val runner: RealtimeTranscriptionSessionRunner,
    val localAudioSink: RealtimeLocalAudioSideSink,
    val durableOutputSink: RealtimeDurableOutputSink,
    val processor: RealtimeStreamingAsrProcessor,
    private val buffer: StreamingAudioBuffer,
    private val performanceProbe: Stage16ARealtimePerformanceProbe,
    private val outputLatencyProbe: Stage16ARealtimeOutputLatencyProbe,
    private val systemMetricsProbe: Stage16ARealtimeSystemMetricsProbe?,
    private val performanceConfig: Stage16ARealtimePipelineConfig,
    private val audioActivitySink: Stage16ARealtimeAudioActivitySink?,
    private val asrEngine: StreamingAsrEngine,
    private val vadEngine: StreamingVadEngine,
    private val punctuationEngine: PunctuationEngine?,
    private val executionLease: Stage16ARealtimeExecutionLease?,
) {
    private val released = AtomicBoolean(false)

    init {
        Stage16ARealtimePerformanceDiagnostics.attach(this) {
            performanceSnapshot().toDiagnosticsSnapshot(
                outputLatency = outputLatencyProbe.snapshot(),
                systemMetrics = systemMetricsProbe?.snapshot(),
                localAudioSink = localAudioSink.snapshot(),
                startup = processor.startupSnapshot(),
            )
        }
    }

    internal fun performanceSnapshot(): Stage16ARealtimePipelinePerformanceSnapshot =
        Stage16ARealtimePipelinePerformanceSnapshot(
            modelId = models.models.asr.descriptor.modelId,
            modelLabel = models.models.asr.descriptor.displayName,
            effectiveThreads = models.models.performance.effectiveThreads,
            targetChunkSamples = performanceConfig.targetChunkSamples,
            targetChunkDurationMs = performanceConfig.targetChunkDurationMs,
            native = performanceProbe.snapshot(),
            buffer = buffer.snapshot(),
            runner = runner.snapshot(),
        )

    suspend fun release() {
        if (!released.compareAndSet(false, true)) return
        check(runner.snapshot().state != RealtimeTranscriptionSessionRunnerState.RUNNING) {
            "Stage 16A pipeline cannot release while realtime runner is active"
        }
        val finalDiagnostics = runCatching {
            performanceSnapshot().toDiagnosticsSnapshot(
                outputLatency = outputLatencyProbe.snapshot(),
                systemMetrics = systemMetricsProbe?.snapshot(),
                localAudioSink = localAudioSink.snapshot(),
                startup = processor.startupSnapshot(),
            )
        }.getOrNull()

        try {
            audioActivitySink?.close()
            localAudioSink.close()
            durableOutputSink.close()
            audioActivitySink?.join()
            localAudioSink.join()
            durableOutputSink.join()
        } finally {
            try {
                processor.close()
                runCatching { punctuationEngine?.close() }
                runCatching { vadEngine.close() }
                runCatching { asrEngine.close() }
                models.close()
            } finally {
                try {
                    Stage16ARealtimePerformanceDiagnostics.retainAndDetach(this, finalDiagnostics)
                } finally {
                    executionLease?.release()
                }
            }
        }
    }
}

class Stage16ARealtimePipelineBundleFactory(
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val engineProvider: Stage16ARealtimeEngineProvider,
    private val context: Context? = null,
    private val config: Stage16ARealtimePipelineConfig = Stage16ARealtimePipelineConfig(),
) {
    fun create(
        source: CanonicalRealtimeEventSource,
        models: Stage16ARealtimeModelLeaseBundle,
        localAudioRoot: File,
        persistDurableOutput: suspend (RealtimeTranscriptionOutput) -> Unit,
        onDurableFailure: suspend (RealtimeDurableOutputFailure) -> Unit = {},
        onLocalAudioResult: suspend (RealtimeLocalAudioResult) -> Unit,
        liveOutputSinks: List<RealtimeTranscriptionOutputSink> = emptyList(),
    ): Stage16ARealtimePipelineBundle {
        require(localAudioRoot.exists() || localAudioRoot.mkdirs()) {
            "unable to create Stage 16A realtime local-audio root"
        }
        require(localAudioRoot.isDirectory)
        require(models.models.asr.descriptor.runtimeId == SherpaRuntime.RUNTIME_ID)
        val localAudioRunRoot =
            stage16ARealtimeRunAudioRoot(
                localAudioRoot = localAudioRoot,
                namespace = UUID.randomUUID().toString(),
            )

        Stage16AQaArtificialAsrSlowdown.beginRun()
        val performanceProbe = Stage16ARealtimePerformanceProbe()
        val systemMetricsProbe = context?.let(Stage16ARealtimeSystemMetricsProbe::android)
        val executionLease = context?.let(Stage16ARealtimeExecutionLease::android)
        var asrEngine: StreamingAsrEngine? = null
        var vadEngine: StreamingVadEngine? = null
        var punctuationEngine: PunctuationEngine? = null
        var processor: RealtimeStreamingAsrProcessor? = null
        var localAudioSink: RealtimeLocalAudioSideSink? = null
        var durableOutputSink: RealtimeDurableOutputSink? = null
        var audioActivitySink: Stage16ARealtimeAudioActivitySink? = null
        var executionLeaseAcquired = false

        try {
            asrEngine = performanceProbe.wrapAsr(engineProvider.openAsr(models.models))
            vadEngine = performanceProbe.wrapVad(engineProvider.openVad(models.models))
            punctuationEngine =
                engineProvider.openPunctuation(models.models)?.let(performanceProbe::wrapPunctuation)
            processor =
                RealtimeStreamingAsrProcessor(
                    asrEngine = asrEngine,
                    vadEngine = vadEngine,
                    config =
                        RealtimeStreamingAsrConfig(
                            targetChunkSamples = config.targetChunkSamples,
                            vadPrerollSamples = config.vadPrerollSamples,
                        ),
                )
            localAudioSink =
                RealtimeLocalAudioSideSink(
                    scope = scope,
                    targetFactory =
                        RealtimeLocalAudioTargetFactory { sessionId ->
                            File(localAudioRunRoot, "canonical-session-$sessionId.wav")
                        },
                    resultHandler = onLocalAudioResult,
                    queueCapacity = config.localAudioQueueCapacity,
                )
            durableOutputSink =
                RealtimeDurableOutputSink(
                    scope = scope,
                    persist = persistDurableOutput,
                    onFailure = onDurableFailure,
                    queueCapacity = config.durableOutputQueueCapacity,
                )

            val audioActivityConsumers =
                liveOutputSinks.filterIsInstance<Stage16ARealtimeAudioActivityConsumer>()
            if (audioActivityConsumers.isNotEmpty()) {
                audioActivitySink =
                    Stage16ARealtimeAudioActivitySink(
                        scope = scope,
                        consumers = audioActivityConsumers,
                    )
            }

            val coordinatorSideSinks = mutableListOf<StreamingAudioSideSink>(localAudioSink)
            audioActivitySink?.let(coordinatorSideSinks::add)
            val buffer =
                StreamingAudioBuffer(
                    maxQueuedPcmFrames = config.maxBufferedPcmFrames,
                    maxQueuedPcmSamples = config.maxBufferedPcmSamples,
                )
            val coordinator =
                RealtimeTranscriptCoordinator(
                    source = source,
                    outputBuffer = buffer,
                    sideSinks = coordinatorSideSinks,
                )
            val transcriptionPipeline =
                RealtimeTranscriptionPipeline(
                    processor = processor,
                    stabilizer = RealtimeHypothesisStabilizer(),
                    punctuationProcessor =
                        RealtimePunctuationProcessor(
                            punctuationEngine = punctuationEngine,
                            stableMode = RealtimeStablePunctuationMode.FINAL_ONLY,
                        ),
                )
            val outputLatencyProbe =
                Stage16ARealtimeOutputLatencyProbe(
                    RealtimeTranscriptionPipelineEventProcessor(transcriptionPipeline),
                )
            val runnerProcessor: RealtimeTranscriptionEventProcessor =
                if (Stage16AQaArtificialAsrSlowdown.isEnabled) {
                    Stage16AQaArtificialSlowdownEventProcessor(outputLatencyProbe)
                } else {
                    outputLatencyProbe
                }
            val runner =
                RealtimeTranscriptionSessionRunner(
                    coordinator = coordinator,
                    processor = runnerProcessor,
                    outputSinks = listOf(durableOutputSink) + liveOutputSinks,
                )

            executionLeaseAcquired = executionLease?.acquire() == true
            return Stage16ARealtimePipelineBundle(
                models = models,
                runner = runner,
                localAudioSink = localAudioSink,
                durableOutputSink = durableOutputSink,
                processor = processor,
                buffer = buffer,
                performanceProbe = performanceProbe,
                outputLatencyProbe = outputLatencyProbe,
                systemMetricsProbe = systemMetricsProbe,
                performanceConfig = config,
                audioActivitySink = audioActivitySink,
                asrEngine = asrEngine,
                vadEngine = vadEngine,
                punctuationEngine = punctuationEngine,
                executionLease = executionLease,
            )
        } catch (failure: Throwable) {
            audioActivitySink?.close()
            localAudioSink?.close()
            durableOutputSink?.close()
            processor?.close()
            runCatching { punctuationEngine?.close() }
            runCatching { vadEngine?.close() }
            runCatching { asrEngine?.close() }
            if (executionLeaseAcquired) executionLease?.release()
            models.close()
            throw failure
        }
    }
}
