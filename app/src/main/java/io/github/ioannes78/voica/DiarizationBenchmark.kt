package io.github.ioannes78.voica

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import io.github.ioannes78.voica.audio.PcmSource
import io.github.ioannes78.voica.audio.PcmSourceResolver
import io.github.ioannes78.voica.model.ActiveModel
import io.github.ioannes78.voica.model.ModelDescriptor
import io.github.ioannes78.voica.model.ModelManager
import io.github.ioannes78.voica.model.ModelUseRegistry
import io.github.ioannes78.voica.sherpa.SherpaRuntime
import io.github.ioannes78.voica.transcript.DiarizationChunkResult
import io.github.ioannes78.voica.transcript.DiarizationConfig
import io.github.ioannes78.voica.transcript.DiarizationEngine
import io.github.ioannes78.voica.transcript.DiarizationPhase
import io.github.ioannes78.voica.transcript.DiarizationProgressListener
import io.github.ioannes78.voica.transcript.DiarizationWindow
import io.github.ioannes78.voica.transcript.ProgressListener
import io.github.ioannes78.voica.transcript.SpeakerEmbeddingEngine
import io.github.ioannes78.voica.transcript.SpeechSegment
import io.github.ioannes78.voica.transcript.VadEngine
import io.github.ioannes78.voica.transcript.VadEngineFactory
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stage 13C C0 profiling entry point.
 *
 * This runner does not execute a second diarization pass. Instead it decorates the production
 * Stage 9 engine provider and observes the coordinator state machine, so the captured timings
 * describe the real pipeline used by the app. The native sherpa diarization call remains an
 * intentionally opaque aggregate (Pyannote segmentation + native embedding + clustering).
 */
@Suppress("UNUSED_PARAMETER")
class DiarizationBenchmarkRunner(
    application: Application,
    pcmSourceResolver: PcmSourceResolver,
    modelManager: ModelManager,
    modelUseRegistry: ModelUseRegistry,
    private val engineProvider: Stage9DiarizationEngineProvider,
    localSpeechSettings: () -> LocalSpeechSettings,
) {
    private val profiler = DiarizationProfiler(application)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val attached = AtomicBoolean(false)
    private var stateJob: Job? = null
    private var alignmentJob: Job? = null

    val latestReport: StateFlow<DiarizationBenchmarkReport?> = profiler.latestReport

    fun profilingEngineProvider(): Stage9DiarizationEngineProvider =
        ProfilingStage9DiarizationEngineProvider(
            delegate = engineProvider,
            profiler = profiler,
        )

    fun attach(coordinator: DiarizationCoordinator) {
        if (!attached.compareAndSet(false, true)) return
        stateJob =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.state.collect(profiler::onDiarizationState)
            }
        alignmentJob =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.alignmentState.collect(profiler::onAlignmentState)
            }
    }

    fun latestReportJson(): String? = latestReport.value?.toJson()

    fun latestReportFile(): File? = profiler.latestReportFile()
}

data class DiarizationBenchmarkPhaseMetric(
    val name: String,
    val elapsedMs: Double,
    val invocationCount: Int,
)

data class DiarizationBenchmarkWorkload(
    val audioDurationSamples: Long?,
    val speechSampleCount: Long,
    val vadSegmentCount: Int,
    val windowCount: Int,
    val totalWindowSamples: Long,
    val uniqueWindowSamples: Long,
    val overlapProcessedSamples: Long,
    val nativeLocalSpeakerCountSum: Int,
    val nativeTurnCount: Int,
    val nativeOverlapTurnCount: Int,
    val anchorEmbeddingCallCount: Int,
    val anchorEmbeddingTotalSamples: Long,
    val finalSpeakerCount: Int?,
    val finalTurnCount: Int?,
    val alignmentSpanCount: Int?,
)

data class DiarizationBenchmarkResources(
    val startPssKb: Long?,
    val sampledPeakPssKb: Long?,
    val endPssKb: Long?,
    val startJavaHeapBytes: Long,
    val sampledPeakJavaHeapBytes: Long,
    val endJavaHeapBytes: Long,
    val processCpuMs: Long,
    val cpuToWallRatio: Double?,
    val initialThermalStatus: Int?,
    val maxThermalStatus: Int?,
    val finalThermalStatus: Int?,
)

data class DiarizationBenchmarkEnvironment(
    val manufacturer: String,
    val model: String,
    val hardware: String,
    val socManufacturer: String?,
    val socModel: String?,
    val sdkInt: Int,
    val supportedAbis: List<String>,
    val totalRamBytes: Long,
    val logicalProcessors: Int,
    val appVersionCode: Long,
    val appVersionName: String,
    val gitSha: String,
    val runtimeId: String,
    val runtimeVersion: String,
)

data class DiarizationBenchmarkModel(
    val role: String,
    val modelId: String,
    val version: String,
    val revision: Long,
    val manifestDigest: String,
)

data class DiarizationBenchmarkConfig(
    val effectiveThreads: Int?,
    val vadThreshold: Float?,
    val vadMinSilenceDurationSeconds: Float?,
    val vadMinSpeechDurationSeconds: Float?,
    val vadMaxSpeechDurationSeconds: Float?,
    val chunkSizeSamples: Long?,
    val chunkOverlapSamples: Long?,
    val vadContextPaddingSamples: Long?,
    val expectedSpeakerCount: Int?,
    val minimumGlobalSpeakerCount: Int?,
    val maximumGlobalSpeakerCount: Int?,
    val clusteringThreshold: Float?,
    val stitchingCosineThreshold: Float?,
    val stitchingMinimumOverlapSamples: Long?,
    val stitchingMinimumAnchorSamples: Long?,
    val stitchingMaxAnchorsPerSpeaker: Int?,
)

data class DiarizationBenchmarkReport(
    val schemaVersion: Int,
    val benchmarkId: String,
    val createdAtEpochMs: Long,
    val recordingId: String,
    val runId: String?,
    val outcome: String,
    val totalElapsedMs: Double,
    val realTimeFactor: Double?,
    val speechRealTimeFactor: Double?,
    val phases: List<DiarizationBenchmarkPhaseMetric>,
    val workload: DiarizationBenchmarkWorkload,
    val resources: DiarizationBenchmarkResources,
    val environment: DiarizationBenchmarkEnvironment,
    val models: List<DiarizationBenchmarkModel>,
    val config: DiarizationBenchmarkConfig,
    val notes: List<String>,
) {
    fun toJson(): String {
        val root = JSONObject()
        root.put("schemaVersion", schemaVersion)
        root.put("benchmarkId", benchmarkId)
        root.put("createdAtEpochMs", createdAtEpochMs)
        root.put("recordingId", recordingId)
        root.putNullable("runId", runId)
        root.put("outcome", outcome)
        root.put("totalElapsedMs", totalElapsedMs)
        root.putNullable("realTimeFactor", realTimeFactor)
        root.putNullable("speechRealTimeFactor", speechRealTimeFactor)
        root.put(
            "phases",
            JSONArray().apply {
                phases.forEach { metric ->
                    put(
                        JSONObject()
                            .put("name", metric.name)
                            .put("elapsedMs", metric.elapsedMs)
                            .put("invocationCount", metric.invocationCount),
                    )
                }
            },
        )
        root.put(
            "workload",
            JSONObject()
                .putNullable("audioDurationSamples", workload.audioDurationSamples)
                .put("speechSampleCount", workload.speechSampleCount)
                .put("vadSegmentCount", workload.vadSegmentCount)
                .put("windowCount", workload.windowCount)
                .put("totalWindowSamples", workload.totalWindowSamples)
                .put("uniqueWindowSamples", workload.uniqueWindowSamples)
                .put("overlapProcessedSamples", workload.overlapProcessedSamples)
                .put("nativeLocalSpeakerCountSum", workload.nativeLocalSpeakerCountSum)
                .put("nativeTurnCount", workload.nativeTurnCount)
                .put("nativeOverlapTurnCount", workload.nativeOverlapTurnCount)
                .put("anchorEmbeddingCallCount", workload.anchorEmbeddingCallCount)
                .put("anchorEmbeddingTotalSamples", workload.anchorEmbeddingTotalSamples)
                .putNullable("finalSpeakerCount", workload.finalSpeakerCount)
                .putNullable("finalTurnCount", workload.finalTurnCount)
                .putNullable("alignmentSpanCount", workload.alignmentSpanCount),
        )
        root.put(
            "resources",
            JSONObject()
                .putNullable("startPssKb", resources.startPssKb)
                .putNullable("sampledPeakPssKb", resources.sampledPeakPssKb)
                .putNullable("endPssKb", resources.endPssKb)
                .put("startJavaHeapBytes", resources.startJavaHeapBytes)
                .put("sampledPeakJavaHeapBytes", resources.sampledPeakJavaHeapBytes)
                .put("endJavaHeapBytes", resources.endJavaHeapBytes)
                .put("processCpuMs", resources.processCpuMs)
                .putNullable("cpuToWallRatio", resources.cpuToWallRatio)
                .putNullable("initialThermalStatus", resources.initialThermalStatus)
                .putNullable("maxThermalStatus", resources.maxThermalStatus)
                .putNullable("finalThermalStatus", resources.finalThermalStatus),
        )
        root.put(
            "environment",
            JSONObject()
                .put("manufacturer", environment.manufacturer)
                .put("model", environment.model)
                .put("hardware", environment.hardware)
                .putNullable("socManufacturer", environment.socManufacturer)
                .putNullable("socModel", environment.socModel)
                .put("sdkInt", environment.sdkInt)
                .put("supportedAbis", JSONArray(environment.supportedAbis))
                .put("totalRamBytes", environment.totalRamBytes)
                .put("logicalProcessors", environment.logicalProcessors)
                .put("appVersionCode", environment.appVersionCode)
                .put("appVersionName", environment.appVersionName)
                .put("gitSha", environment.gitSha)
                .put("runtimeId", environment.runtimeId)
                .put("runtimeVersion", environment.runtimeVersion),
        )
        root.put(
            "models",
            JSONArray().apply {
                models.forEach { model ->
                    put(
                        JSONObject()
                            .put("role", model.role)
                            .put("modelId", model.modelId)
                            .put("version", model.version)
                            .put("revision", model.revision)
                            .put("manifestDigest", model.manifestDigest),
                    )
                }
            },
        )
        root.put(
            "config",
            JSONObject()
                .putNullable("effectiveThreads", config.effectiveThreads)
                .putNullable("vadThreshold", config.vadThreshold)
                .putNullable("vadMinSilenceDurationSeconds", config.vadMinSilenceDurationSeconds)
                .putNullable("vadMinSpeechDurationSeconds", config.vadMinSpeechDurationSeconds)
                .putNullable("vadMaxSpeechDurationSeconds", config.vadMaxSpeechDurationSeconds)
                .putNullable("chunkSizeSamples", config.chunkSizeSamples)
                .putNullable("chunkOverlapSamples", config.chunkOverlapSamples)
                .putNullable("vadContextPaddingSamples", config.vadContextPaddingSamples)
                .putNullable("expectedSpeakerCount", config.expectedSpeakerCount)
                .putNullable("minimumGlobalSpeakerCount", config.minimumGlobalSpeakerCount)
                .putNullable("maximumGlobalSpeakerCount", config.maximumGlobalSpeakerCount)
                .putNullable("clusteringThreshold", config.clusteringThreshold)
                .putNullable("stitchingCosineThreshold", config.stitchingCosineThreshold)
                .putNullable("stitchingMinimumOverlapSamples", config.stitchingMinimumOverlapSamples)
                .putNullable("stitchingMinimumAnchorSamples", config.stitchingMinimumAnchorSamples)
                .putNullable("stitchingMaxAnchorsPerSpeaker", config.stitchingMaxAnchorsPerSpeaker),
        )
        root.put("notes", JSONArray(notes))
        return root.toString(2)
    }
}

internal data class DiarizationProfileWindowRange(
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

internal fun uniqueCoveredSamples(ranges: List<DiarizationProfileWindowRange>): Long {
    if (ranges.isEmpty()) return 0L
    val ordered = ranges.sortedWith(compareBy({ it.startSampleIndex }, { it.endSampleIndexExclusive }))
    var currentStart = ordered.first().startSampleIndex
    var currentEnd = ordered.first().endSampleIndexExclusive
    var total = 0L
    ordered.drop(1).forEach { range ->
        if (range.startSampleIndex <= currentEnd) {
            currentEnd = maxOf(currentEnd, range.endSampleIndexExclusive)
        } else {
            total = Math.addExact(total, currentEnd - currentStart)
            currentStart = range.startSampleIndex
            currentEnd = range.endSampleIndexExclusive
        }
    }
    return Math.addExact(total, currentEnd - currentStart)
}

internal fun realTimeFactor(
    elapsedMs: Double,
    sampleCount: Long?,
    sampleRateHz: Int = DiarizationConfig.CANONICAL_SAMPLE_RATE_HZ,
): Double? {
    if (sampleCount == null || sampleCount <= 0L || elapsedMs < 0.0) return null
    val audioSeconds = sampleCount.toDouble() / sampleRateHz.toDouble()
    return elapsedMs / 1_000.0 / audioSeconds
}

private class ProfilingStage9DiarizationEngineProvider(
    private val delegate: Stage9DiarizationEngineProvider,
    private val profiler: DiarizationProfiler,
) : Stage9DiarizationEngineProvider {
    override fun vadFactory(
        model: ActiveModel,
        numThreads: Int,
        vadSettings: LocalVadSettings,
    ): VadEngineFactory {
        profiler.recordVadConfiguration(model, numThreads, vadSettings)
        val delegateFactory = delegate.vadFactory(model, numThreads, vadSettings)
        return VadEngineFactory {
            val engine = delegateFactory.open()
            object : VadEngine {
                override val model: ModelDescriptor = engine.model

                override suspend fun analyze(
                    source: PcmSource,
                    progressListener: ProgressListener?,
                ): List<SpeechSegment> {
                    val started = SystemClock.elapsedRealtimeNanos()
                    return try {
                        engine.analyze(source, progressListener).also { segments ->
                            profiler.recordVadResult(
                                segments = segments,
                                elapsedNs = SystemClock.elapsedRealtimeNanos() - started,
                            )
                        }
                    } catch (error: Throwable) {
                        profiler.recordPhase(
                            PHASE_VAD_ENGINE,
                            SystemClock.elapsedRealtimeNanos() - started,
                        )
                        throw error
                    }
                }

                override fun close() = engine.close()
            }
        }
    }

    override suspend fun validateBundle(
        segmentation: ActiveModel,
        embedding: ActiveModel,
    ) {
        profiler.recordModel(MODEL_ROLE_SEGMENTATION, segmentation)
        profiler.recordModel(MODEL_ROLE_EMBEDDING, embedding)
        val started = SystemClock.elapsedRealtimeNanos()
        try {
            delegate.validateBundle(segmentation, embedding)
        } finally {
            profiler.recordPhase(
                PHASE_BUNDLE_VALIDATION,
                SystemClock.elapsedRealtimeNanos() - started,
            )
        }
    }

    override fun diarizationEngine(
        segmentation: ActiveModel,
        embedding: ActiveModel,
        numThreads: Int,
    ): DiarizationEngine {
        profiler.recordDiarizationConfiguration(segmentation, embedding, numThreads)
        val engine = delegate.diarizationEngine(segmentation, embedding, numThreads)
        return object : DiarizationEngine {
            override val segmentationModel: ModelDescriptor = engine.segmentationModel
            override val embeddingModel: ModelDescriptor = engine.embeddingModel

            override suspend fun diarize(
                window: DiarizationWindow,
                config: DiarizationConfig,
                progressListener: DiarizationProgressListener?,
            ): DiarizationChunkResult {
                val started = SystemClock.elapsedRealtimeNanos()
                return try {
                    engine.diarize(window, config, progressListener).also { result ->
                        profiler.recordNativeDiarization(
                            window = window,
                            config = config,
                            result = result,
                            elapsedNs = SystemClock.elapsedRealtimeNanos() - started,
                        )
                    }
                } catch (error: Throwable) {
                    profiler.recordPhase(
                        PHASE_NATIVE_DIARIZATION,
                        SystemClock.elapsedRealtimeNanos() - started,
                    )
                    throw error
                }
            }

            override fun close() = engine.close()
        }
    }

    override fun embeddingEngine(
        model: ActiveModel,
        numThreads: Int,
    ): SpeakerEmbeddingEngine {
        profiler.recordEmbeddingConfiguration(model, numThreads)
        val engine = delegate.embeddingEngine(model, numThreads)
        return object : SpeakerEmbeddingEngine {
            override val model: ModelDescriptor = engine.model

            override suspend fun embed(
                samples: ShortArray,
                sampleRateHz: Int,
            ): FloatArray {
                val started = SystemClock.elapsedRealtimeNanos()
                return try {
                    engine.embed(samples, sampleRateHz).also {
                        profiler.recordAnchorEmbedding(
                            sampleCount = samples.size.toLong(),
                            elapsedNs = SystemClock.elapsedRealtimeNanos() - started,
                        )
                    }
                } catch (error: Throwable) {
                    profiler.recordAnchorEmbedding(
                        sampleCount = samples.size.toLong(),
                        elapsedNs = SystemClock.elapsedRealtimeNanos() - started,
                    )
                    throw error
                }
            }

            override fun close() = engine.close()
        }
    }
}

private class DiarizationProfiler(
    private val application: Application,
) {
    private val lock = Any()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableLatestReport = MutableStateFlow<DiarizationBenchmarkReport?>(null)
    val latestReport: StateFlow<DiarizationBenchmarkReport?> = mutableLatestReport.asStateFlow()

    private var active: MutableDiarizationProfileSession? = null
    private val completedByRunId = LinkedHashMap<String, MutableDiarizationProfileSession>()

    fun onDiarizationState(state: DiarizationRunState) {
        var report: DiarizationBenchmarkReport? = null
        synchronized(lock) {
            when (state) {
                DiarizationRunState.Idle -> Unit
                is DiarizationRunState.Running -> {
                    val session =
                        active?.takeIf { it.recordingId == state.recordingId }
                            ?: MutableDiarizationProfileSession(
                                application = application,
                                recordingId = state.recordingId,
                            ).also { active = it }
                    if (state.runId != null) session.runId = state.runId
                    if (state.progress.phase == DiarizationPhase.VAD) {
                        state.progress.totalSamples?.let { session.audioDurationSamples = it }
                    }
                    session.observeCoordinatorPhase(state.progress.phase)
                }
                is DiarizationRunState.Completed -> {
                    val session = active?.takeIf { it.recordingId == state.recordingId } ?: return
                    session.runId = state.runId
                    session.finalSpeakerCount = state.speakerCount
                    session.finalTurnCount = state.turns.size
                    session.finishCoordinator("COMPLETED")
                    active = null
                    completedByRunId[state.runId] = session
                    trimCompletedSessions()
                    report = session.report()
                }
                is DiarizationRunState.Failed -> {
                    val session = active?.takeIf { it.recordingId == state.recordingId } ?: return
                    session.finishCoordinator("FAILED")
                    active = null
                    session.runId?.let { completedByRunId[it] = session }
                    trimCompletedSessions()
                    report = session.report()
                }
                is DiarizationRunState.Cancelled -> {
                    val session = active?.takeIf { it.recordingId == state.recordingId } ?: return
                    session.finishCoordinator("CANCELLED")
                    active = null
                    session.runId?.let { completedByRunId[it] = session }
                    trimCompletedSessions()
                    report = session.report()
                }
            }
        }
        report?.let(::publish)
    }

    fun onAlignmentState(state: SpeakerAlignmentRunState) {
        var report: DiarizationBenchmarkReport? = null
        synchronized(lock) {
            when (state) {
                SpeakerAlignmentRunState.Idle -> Unit
                is SpeakerAlignmentRunState.Running -> {
                    completedByRunId[state.diarizationRunId]?.startAlignment()
                }
                is SpeakerAlignmentRunState.Completed -> {
                    completedByRunId[state.diarizationRunId]?.let { session ->
                        session.finishAlignment(
                            outcome = "COMPLETED",
                            spanCount = state.spanCount,
                        )
                        report = session.report()
                    }
                }
                is SpeakerAlignmentRunState.Failed -> {
                    completedByRunId[state.diarizationRunId]?.let { session ->
                        session.finishAlignment("FAILED", null)
                        report = session.report()
                    }
                }
                is SpeakerAlignmentRunState.Cancelled -> {
                    completedByRunId[state.diarizationRunId]?.let { session ->
                        session.finishAlignment("CANCELLED", null)
                        report = session.report()
                    }
                }
            }
        }
        report?.let(::publish)
    }

    fun recordVadConfiguration(
        model: ActiveModel,
        numThreads: Int,
        settings: LocalVadSettings,
    ) = synchronized(lock) {
        active?.apply {
            recordModel(MODEL_ROLE_VAD, model)
            effectiveThreads = numThreads
            vadThreshold = settings.threshold
            vadMinSilenceDurationSeconds = settings.minSilenceDurationSeconds
            vadMinSpeechDurationSeconds = settings.minSpeechDurationSeconds
            vadMaxSpeechDurationSeconds = settings.maxSpeechDurationSeconds
        }
    }

    fun recordDiarizationConfiguration(
        segmentation: ActiveModel,
        embedding: ActiveModel,
        numThreads: Int,
    ) = synchronized(lock) {
        active?.apply {
            recordModel(MODEL_ROLE_SEGMENTATION, segmentation)
            recordModel(MODEL_ROLE_EMBEDDING, embedding)
            effectiveThreads = numThreads
        }
    }

    fun recordEmbeddingConfiguration(
        model: ActiveModel,
        numThreads: Int,
    ) = synchronized(lock) {
        active?.apply {
            recordModel(MODEL_ROLE_EMBEDDING, model)
            effectiveThreads = numThreads
        }
    }

    fun recordModel(
        role: String,
        model: ActiveModel,
    ) = synchronized(lock) {
        active?.recordModel(role, model)
    }

    fun recordVadResult(
        segments: List<SpeechSegment>,
        elapsedNs: Long,
    ) = synchronized(lock) {
        active?.apply {
            recordPhase(PHASE_VAD_ENGINE, elapsedNs)
            vadSegmentCount = segments.size
            speechSampleCount =
                segments.fold(0L) { total, segment ->
                    Math.addExact(total, segment.sampleCount)
                }
        }
    }

    fun recordNativeDiarization(
        window: DiarizationWindow,
        config: DiarizationConfig,
        result: DiarizationChunkResult,
        elapsedNs: Long,
    ) = synchronized(lock) {
        active?.apply {
            recordPhase(PHASE_NATIVE_DIARIZATION, elapsedNs)
            recordConfig(config)
            windowRanges +=
                DiarizationProfileWindowRange(
                    startSampleIndex = window.startSampleIndex,
                    endSampleIndexExclusive = window.endSampleIndexExclusive,
                )
            nativeLocalSpeakerCountSum += result.speakerCount
            nativeTurnCount += result.turns.size
            nativeOverlapTurnCount += result.turns.count { it.overlap }
        }
    }

    fun recordAnchorEmbedding(
        sampleCount: Long,
        elapsedNs: Long,
    ) = synchronized(lock) {
        active?.apply {
            recordPhase(PHASE_ANCHOR_EMBEDDING, elapsedNs)
            anchorEmbeddingCallCount += 1
            anchorEmbeddingTotalSamples = Math.addExact(anchorEmbeddingTotalSamples, sampleCount)
        }
    }

    fun recordPhase(
        name: String,
        elapsedNs: Long,
    ) = synchronized(lock) {
        active?.recordPhase(name, elapsedNs)
    }

    fun latestReportFile(): File? {
        val file = File(reportDirectory(), LATEST_REPORT_FILE)
        return file.takeIf { it.isFile }
    }

    private fun publish(report: DiarizationBenchmarkReport) {
        mutableLatestReport.value = report
        ioScope.launch {
            runCatching {
                val directory = reportDirectory().apply { mkdirs() }
                val perRun = File(directory, report.benchmarkId + ".json")
                val json = report.toJson()
                perRun.writeText(json)
                File(directory, LATEST_REPORT_FILE).writeText(json)
                directory.listFiles { file ->
                    file.extension == "json" && file.name != LATEST_REPORT_FILE
                }
                    ?.sortedByDescending(File::lastModified)
                    ?.drop(MAX_RETAINED_REPORTS)
                    ?.forEach(File::delete)
                Log.i(TAG, "Stage 13C diarization profile saved: ${perRun.absolutePath}")
            }.onFailure { error ->
                Log.w(TAG, "Failed to persist Stage 13C diarization profile", error)
            }
        }
    }

    private fun reportDirectory(): File =
        File(application.noBackupFilesDir, "diagnostics/diarization")

    private fun trimCompletedSessions() {
        while (completedByRunId.size > MAX_RETAINED_SESSIONS) {
            val firstKey = completedByRunId.keys.firstOrNull() ?: return
            completedByRunId.remove(firstKey)
        }
    }

    private companion object {
        const val TAG = "DiarizationProfile"
        const val LATEST_REPORT_FILE = "latest.json"
        const val MAX_RETAINED_REPORTS = 20
        const val MAX_RETAINED_SESSIONS = 8
    }
}

private class MutableDiarizationProfileSession(
    application: Application,
    val recordingId: String,
) {
    val benchmarkId: String = UUID.randomUUID().toString()
    val createdAtEpochMs: Long = System.currentTimeMillis()
    val startedNs: Long = SystemClock.elapsedRealtimeNanos()
    private val startCpuMs: Long = Process.getElapsedCpuTime()
    private val environment = captureEnvironment(application)
    private val startResources = sampleResources(application)
    private var peakPssKb: Long? = startResources.pssKb
    private var peakJavaHeapBytes: Long = startResources.javaHeapBytes
    private var maxThermalStatus: Int? = startResources.thermalStatus

    var runId: String? = null
    var audioDurationSamples: Long? = null
    var speechSampleCount: Long = 0L
    var vadSegmentCount: Int = 0
    val windowRanges = mutableListOf<DiarizationProfileWindowRange>()
    var nativeLocalSpeakerCountSum: Int = 0
    var nativeTurnCount: Int = 0
    var nativeOverlapTurnCount: Int = 0
    var anchorEmbeddingCallCount: Int = 0
    var anchorEmbeddingTotalSamples: Long = 0L
    var finalSpeakerCount: Int? = null
    var finalTurnCount: Int? = null
    var alignmentSpanCount: Int? = null

    var effectiveThreads: Int? = null
    var vadThreshold: Float? = null
    var vadMinSilenceDurationSeconds: Float? = null
    var vadMinSpeechDurationSeconds: Float? = null
    var vadMaxSpeechDurationSeconds: Float? = null
    var chunkSizeSamples: Long? = null
    var chunkOverlapSamples: Long? = null
    var vadContextPaddingSamples: Long? = null
    var expectedSpeakerCount: Int? = null
    var minimumGlobalSpeakerCount: Int? = null
    var maximumGlobalSpeakerCount: Int? = null
    var clusteringThreshold: Float? = null
    var stitchingCosineThreshold: Float? = null
    var stitchingMinimumOverlapSamples: Long? = null
    var stitchingMinimumAnchorSamples: Long? = null
    var stitchingMaxAnchorsPerSpeaker: Int? = null

    private val phaseNs = linkedMapOf<String, Long>()
    private val phaseCount = linkedMapOf<String, Int>()
    private val models = linkedMapOf<String, DiarizationBenchmarkModel>()
    private var currentStatePhase: DiarizationPhase? = null
    private var currentStatePhaseStartedNs: Long? = null
    private var endedNs: Long? = null
    private var endCpuMs: Long? = null
    private var endResources: RuntimeResourceSample? = null
    private var outcome: String = "RUNNING"
    private var alignmentStartedNs: Long? = null

    fun observeCoordinatorPhase(phase: DiarizationPhase) {
        val now = SystemClock.elapsedRealtimeNanos()
        if (phase == currentStatePhase) return
        closeCurrentStatePhase(now)
        currentStatePhase = phase
        currentStatePhaseStartedNs = now
        sampleResourcePoint()
    }

    fun recordPhase(
        name: String,
        elapsedNs: Long,
    ) {
        if (elapsedNs < 0L) return
        phaseNs[name] = Math.addExact(phaseNs[name] ?: 0L, elapsedNs)
        phaseCount[name] = (phaseCount[name] ?: 0) + 1
    }

    fun recordModel(
        role: String,
        model: ActiveModel,
    ) {
        models[role] =
            DiarizationBenchmarkModel(
                role = role,
                modelId = model.descriptor.modelId,
                version = model.descriptor.version,
                revision = model.descriptor.revision,
                manifestDigest = model.manifestDigest,
            )
    }

    fun recordConfig(config: DiarizationConfig) {
        chunkSizeSamples = config.chunkSizeSamples
        chunkOverlapSamples = config.chunkOverlapSamples
        vadContextPaddingSamples = config.vadContextPaddingSamples
        expectedSpeakerCount = config.expectedSpeakerCount
        minimumGlobalSpeakerCount = config.minimumGlobalSpeakerCount
        maximumGlobalSpeakerCount = config.maximumGlobalSpeakerCount
        clusteringThreshold = config.clusteringThreshold
        stitchingCosineThreshold = config.stitchingCosineThreshold
        stitchingMinimumOverlapSamples = config.stitchingMinimumOverlapSamples
        stitchingMinimumAnchorSamples = config.stitchingMinimumAnchorSamples
        stitchingMaxAnchorsPerSpeaker = config.stitchingMaxAnchorsPerSpeaker
    }

    fun finishCoordinator(outcome: String) {
        val now = SystemClock.elapsedRealtimeNanos()
        closeCurrentStatePhase(now)
        this.outcome = outcome
        endedNs = now
        endCpuMs = Process.getElapsedCpuTime()
        endResources = sampleResources(application = environment.applicationRef)
        endResources?.let(::accumulateResourceSample)
    }

    fun startAlignment() {
        if (alignmentStartedNs == null) {
            alignmentStartedNs = SystemClock.elapsedRealtimeNanos()
        }
    }

    fun finishAlignment(
        outcome: String,
        spanCount: Int?,
    ) {
        val started = alignmentStartedNs ?: return
        val elapsed = SystemClock.elapsedRealtimeNanos() - started
        recordPhase(PHASE_ALIGNMENT_TOTAL, elapsed)
        alignmentStartedNs = null
        if (outcome == "COMPLETED") {
            alignmentSpanCount = spanCount
        }
        sampleResourcePoint()
    }

    fun report(): DiarizationBenchmarkReport {
        val finishedNs = endedNs ?: SystemClock.elapsedRealtimeNanos()
        val totalElapsedMs = (finishedNs - startedNs).toDouble() / 1_000_000.0
        val totalWindowSamples =
            windowRanges.fold(0L) { total, range -> Math.addExact(total, range.sampleCount) }
        val uniqueWindowSamples = uniqueCoveredSamples(windowRanges)
        val overlapSamples = (totalWindowSamples - uniqueWindowSamples).coerceAtLeast(0L)
        val finalResources = endResources ?: sampleResources(environment.applicationRef)
        accumulateResourceSample(finalResources)
        val cpuMs = ((endCpuMs ?: Process.getElapsedCpuTime()) - startCpuMs).coerceAtLeast(0L)
        return DiarizationBenchmarkReport(
            schemaVersion = REPORT_SCHEMA_VERSION,
            benchmarkId = benchmarkId,
            createdAtEpochMs = createdAtEpochMs,
            recordingId = recordingId,
            runId = runId,
            outcome = outcome,
            totalElapsedMs = totalElapsedMs,
            realTimeFactor = realTimeFactor(totalElapsedMs, audioDurationSamples),
            speechRealTimeFactor = realTimeFactor(totalElapsedMs, speechSampleCount.takeIf { it > 0L }),
            phases =
                phaseNs.map { (name, elapsedNs) ->
                    DiarizationBenchmarkPhaseMetric(
                        name = name,
                        elapsedMs = elapsedNs.toDouble() / 1_000_000.0,
                        invocationCount = phaseCount[name] ?: 0,
                    )
                },
            workload =
                DiarizationBenchmarkWorkload(
                    audioDurationSamples = audioDurationSamples,
                    speechSampleCount = speechSampleCount,
                    vadSegmentCount = vadSegmentCount,
                    windowCount = windowRanges.size,
                    totalWindowSamples = totalWindowSamples,
                    uniqueWindowSamples = uniqueWindowSamples,
                    overlapProcessedSamples = overlapSamples,
                    nativeLocalSpeakerCountSum = nativeLocalSpeakerCountSum,
                    nativeTurnCount = nativeTurnCount,
                    nativeOverlapTurnCount = nativeOverlapTurnCount,
                    anchorEmbeddingCallCount = anchorEmbeddingCallCount,
                    anchorEmbeddingTotalSamples = anchorEmbeddingTotalSamples,
                    finalSpeakerCount = finalSpeakerCount,
                    finalTurnCount = finalTurnCount,
                    alignmentSpanCount = alignmentSpanCount,
                ),
            resources =
                DiarizationBenchmarkResources(
                    startPssKb = startResources.pssKb,
                    sampledPeakPssKb = peakPssKb,
                    endPssKb = finalResources.pssKb,
                    startJavaHeapBytes = startResources.javaHeapBytes,
                    sampledPeakJavaHeapBytes = peakJavaHeapBytes,
                    endJavaHeapBytes = finalResources.javaHeapBytes,
                    processCpuMs = cpuMs,
                    cpuToWallRatio = cpuMs.toDouble().takeIf { totalElapsedMs > 0.0 }?.div(totalElapsedMs),
                    initialThermalStatus = startResources.thermalStatus,
                    maxThermalStatus = maxThermalStatus,
                    finalThermalStatus = finalResources.thermalStatus,
                ),
            environment = environment.publicCopy(),
            models = models.values.toList(),
            config =
                DiarizationBenchmarkConfig(
                    effectiveThreads = effectiveThreads,
                    vadThreshold = vadThreshold,
                    vadMinSilenceDurationSeconds = vadMinSilenceDurationSeconds,
                    vadMinSpeechDurationSeconds = vadMinSpeechDurationSeconds,
                    vadMaxSpeechDurationSeconds = vadMaxSpeechDurationSeconds,
                    chunkSizeSamples = chunkSizeSamples,
                    chunkOverlapSamples = chunkOverlapSamples,
                    vadContextPaddingSamples = vadContextPaddingSamples,
                    expectedSpeakerCount = expectedSpeakerCount,
                    minimumGlobalSpeakerCount = minimumGlobalSpeakerCount,
                    maximumGlobalSpeakerCount = maximumGlobalSpeakerCount,
                    clusteringThreshold = clusteringThreshold,
                    stitchingCosineThreshold = stitchingCosineThreshold,
                    stitchingMinimumOverlapSamples = stitchingMinimumOverlapSamples,
                    stitchingMinimumAnchorSamples = stitchingMinimumAnchorSamples,
                    stitchingMaxAnchorsPerSpeaker = stitchingMaxAnchorsPerSpeaker,
                ),
            notes =
                listOf(
                    "NATIVE_DIARIZATION is an opaque sherpa aggregate: Pyannote segmentation + native speaker embedding + clustering; no synthetic internal split is reported.",
                    "PSS is sampled at coordinator phase boundaries and terminal events; sampledPeakPssKb is not an absolute process peak.",
                    "STATE_* phase metrics are state-observed intervals and are diagnostic only; detailed engine metrics are used for optimization attribution.",
                ),
        )
    }

    private fun closeCurrentStatePhase(nowNs: Long) {
        val phase = currentStatePhase ?: return
        val started = currentStatePhaseStartedNs ?: return
        recordPhase("STATE_${phase.name}", nowNs - started)
        currentStatePhase = null
        currentStatePhaseStartedNs = null
    }

    private fun sampleResourcePoint() {
        accumulateResourceSample(sampleResources(environment.applicationRef))
    }

    private fun accumulateResourceSample(sample: RuntimeResourceSample) {
        if (sample.pssKb != null) {
            peakPssKb = maxOf(peakPssKb ?: sample.pssKb, sample.pssKb)
        }
        peakJavaHeapBytes = maxOf(peakJavaHeapBytes, sample.javaHeapBytes)
        if (sample.thermalStatus != null) {
            maxThermalStatus = maxOf(maxThermalStatus ?: sample.thermalStatus, sample.thermalStatus)
        }
    }

    private companion object {
        const val REPORT_SCHEMA_VERSION = 1
    }
}

private data class RuntimeResourceSample(
    val pssKb: Long?,
    val javaHeapBytes: Long,
    val thermalStatus: Int?,
)

private data class CapturedEnvironment(
    val applicationRef: Application,
    val manufacturer: String,
    val model: String,
    val hardware: String,
    val socManufacturer: String?,
    val socModel: String?,
    val sdkInt: Int,
    val supportedAbis: List<String>,
    val totalRamBytes: Long,
    val logicalProcessors: Int,
    val appVersionCode: Long,
    val appVersionName: String,
    val gitSha: String,
) {
    fun publicCopy() =
        DiarizationBenchmarkEnvironment(
            manufacturer = manufacturer,
            model = model,
            hardware = hardware,
            socManufacturer = socManufacturer,
            socModel = socModel,
            sdkInt = sdkInt,
            supportedAbis = supportedAbis,
            totalRamBytes = totalRamBytes,
            logicalProcessors = logicalProcessors,
            appVersionCode = appVersionCode,
            appVersionName = appVersionName,
            gitSha = gitSha,
            runtimeId = SherpaRuntime.RUNTIME_ID,
            runtimeVersion = SherpaRuntime.RUNTIME_VERSION,
        )
}

private fun captureEnvironment(application: Application): CapturedEnvironment {
    val activityManager = application.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
    val packageInfo = application.packageManager.getPackageInfo(application.packageName, 0)
    val versionCode =
        if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
    return CapturedEnvironment(
        applicationRef = application,
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        hardware = Build.HARDWARE,
        socManufacturer = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else null,
        socModel = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else null,
        sdkInt = Build.VERSION.SDK_INT,
        supportedAbis = Build.SUPPORTED_ABIS.toList(),
        totalRamBytes = memoryInfo.totalMem,
        logicalProcessors = Runtime.getRuntime().availableProcessors(),
        appVersionCode = versionCode,
        appVersionName = packageInfo.versionName.orEmpty(),
        gitSha = BuildConfig.GIT_SHA,
    )
}

private fun sampleResources(application: Application): RuntimeResourceSample {
    val runtime = Runtime.getRuntime()
    val javaHeapBytes = runtime.totalMemory() - runtime.freeMemory()
    val thermal =
        if (Build.VERSION.SDK_INT >= 29) {
            (application.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus
        } else {
            null
        }
    return RuntimeResourceSample(
        pssKb = runCatching { Debug.getPss().toLong() }.getOrNull(),
        javaHeapBytes = javaHeapBytes,
        thermalStatus = thermal,
    )
}

private fun JSONObject.putNullable(
    key: String,
    value: Any?,
): JSONObject = put(key, value ?: JSONObject.NULL)

private const val MODEL_ROLE_VAD = "VAD"
private const val MODEL_ROLE_SEGMENTATION = "DIARIZATION_SEGMENTATION"
private const val MODEL_ROLE_EMBEDDING = "SPEAKER_EMBEDDING"
private const val PHASE_BUNDLE_VALIDATION = "BUNDLE_VALIDATION"
private const val PHASE_VAD_ENGINE = "VAD_ENGINE"
private const val PHASE_NATIVE_DIARIZATION = "NATIVE_DIARIZATION"
private const val PHASE_ANCHOR_EMBEDDING = "ANCHOR_EMBEDDING"
private const val PHASE_ALIGNMENT_TOTAL = "ALIGNMENT_TOTAL"
