package io.github.ioannes78.voica

import android.app.Application
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import io.github.ioannes78.voica.audio.PcmSource
import io.github.ioannes78.voica.model.ActiveModel
import io.github.ioannes78.voica.model.ModelDescriptor
import io.github.ioannes78.voica.transcript.DiarizationChunkResult
import io.github.ioannes78.voica.transcript.DiarizationConfig
import io.github.ioannes78.voica.transcript.DiarizationEngine
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stage 13C C4 measurement-only wrapper.
 *
 * It observes the exact VAD segments and windows already consumed by the production diarization
 * chain, then exports overlap-boundary speech/silence diagnostics after native diarization closes.
 * It never changes the VAD result, window plan, native diarization input, stitching or alignment.
 */
class Stage13CBoundaryProfilingProvider(
    private val application: Application,
    private val delegate: Stage9DiarizationEngineProvider,
) : Stage9DiarizationEngineProvider {
    private val capture = Stage13CBoundaryCapture(application)

    override fun vadFactory(
        model: ActiveModel,
        numThreads: Int,
        vadSettings: LocalVadSettings,
    ): VadEngineFactory {
        val delegateFactory = delegate.vadFactory(model, numThreads, vadSettings)
        return VadEngineFactory {
            val engine = delegateFactory.open()
            object : VadEngine {
                override val model: ModelDescriptor = engine.model

                override suspend fun analyze(
                    source: PcmSource,
                    progressListener: ProgressListener?,
                ): List<SpeechSegment> =
                    engine.analyze(source, progressListener).also(capture::recordVad)

                override fun close() = engine.close()
            }
        }
    }

    override suspend fun validateBundle(
        segmentation: ActiveModel,
        embedding: ActiveModel,
    ) = delegate.validateBundle(segmentation, embedding)

    override fun diarizationEngine(
        segmentation: ActiveModel,
        embedding: ActiveModel,
        numThreads: Int,
    ): DiarizationEngine {
        val engine = delegate.diarizationEngine(segmentation, embedding, numThreads)
        val session = capture.bindEngine()
        return object : DiarizationEngine {
            override val segmentationModel: ModelDescriptor = engine.segmentationModel
            override val embeddingModel: ModelDescriptor = engine.embeddingModel

            override suspend fun diarize(
                window: DiarizationWindow,
                config: DiarizationConfig,
                progressListener: DiarizationProgressListener?,
            ): DiarizationChunkResult =
                engine.diarize(window, config, progressListener).also {
                    session?.recordWindow(window)
                }

            override fun close() {
                try {
                    engine.close()
                } finally {
                    session?.finish(capture)
                }
            }
        }
    }

    override fun embeddingEngine(
        model: ActiveModel,
        numThreads: Int,
    ): SpeakerEmbeddingEngine = delegate.embeddingEngine(model, numThreads)
}

internal data class Stage13CBoundaryWindowRange(
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

internal data class Stage13CBoundaryMetric(
    val boundaryIndex: Int,
    val leftWindowStartSampleIndex: Long,
    val leftWindowEndSampleIndexExclusive: Long,
    val rightWindowStartSampleIndex: Long,
    val rightWindowEndSampleIndexExclusive: Long,
    val overlapStartSampleIndex: Long,
    val overlapEndSampleIndexExclusive: Long,
    val overlapSamples: Long,
    val speechSamples: Long,
    val silenceSamples: Long,
    val speechRunCount: Int,
    val silenceGapCount: Int,
    val longestSilenceGapSamples: Long,
    val longestSilenceGapStartSampleIndex: Long?,
    val longestSilenceGapEndSampleIndexExclusive: Long?,
) {
    val speechRatio: Double
        get() = if (overlapSamples > 0L) speechSamples.toDouble() / overlapSamples.toDouble() else 0.0
}

internal fun analyzeStage13CBoundaries(
    windows: List<Stage13CBoundaryWindowRange>,
    speechSegments: List<SpeechSegment>,
): List<Stage13CBoundaryMetric> {
    if (windows.size < 2) return emptyList()
    val orderedWindows = windows.sortedBy { it.startSampleIndex }
    val orderedSpeech = speechSegments.sortedBy { it.startSampleIndex }
    val result = mutableListOf<Stage13CBoundaryMetric>()

    orderedWindows.zipWithNext().forEachIndexed { index, (left, right) ->
        val overlapStart = maxOf(left.startSampleIndex, right.startSampleIndex)
        val overlapEnd = minOf(left.endSampleIndexExclusive, right.endSampleIndexExclusive)
        if (overlapEnd <= overlapStart) return@forEachIndexed

        val speechRanges =
            mergeSampleRanges(
                orderedSpeech.mapNotNull { segment ->
                    val start = maxOf(segment.startSampleIndex, overlapStart)
                    val end = minOf(segment.endSampleIndexExclusive, overlapEnd)
                    if (end > start) SampleRange(start, end) else null
                },
            )
        val speechSamples =
            speechRanges.fold(0L) { total, range ->
                Math.addExact(total, range.sampleCount)
            }
        val overlapSamples = overlapEnd - overlapStart
        val silenceSamples = overlapSamples - speechSamples
        val silenceGaps = complementRanges(overlapStart, overlapEnd, speechRanges)
        val longestSilence = silenceGaps.maxByOrNull { it.sampleCount }

        result +=
            Stage13CBoundaryMetric(
                boundaryIndex = index,
                leftWindowStartSampleIndex = left.startSampleIndex,
                leftWindowEndSampleIndexExclusive = left.endSampleIndexExclusive,
                rightWindowStartSampleIndex = right.startSampleIndex,
                rightWindowEndSampleIndexExclusive = right.endSampleIndexExclusive,
                overlapStartSampleIndex = overlapStart,
                overlapEndSampleIndexExclusive = overlapEnd,
                overlapSamples = overlapSamples,
                speechSamples = speechSamples,
                silenceSamples = silenceSamples,
                speechRunCount = speechRanges.size,
                silenceGapCount = silenceGaps.size,
                longestSilenceGapSamples = longestSilence?.sampleCount ?: 0L,
                longestSilenceGapStartSampleIndex = longestSilence?.startSampleIndex,
                longestSilenceGapEndSampleIndexExclusive = longestSilence?.endSampleIndexExclusive,
            )
    }
    return result
}

private data class SampleRange(
    val startSampleIndex: Long,
    val endSampleIndexExclusive: Long,
) {
    val sampleCount: Long
        get() = endSampleIndexExclusive - startSampleIndex
}

private fun mergeSampleRanges(ranges: List<SampleRange>): List<SampleRange> {
    if (ranges.isEmpty()) return emptyList()
    val ordered = ranges.sortedBy { it.startSampleIndex }
    val merged = mutableListOf<SampleRange>()
    var current = ordered.first()
    ordered.drop(1).forEach { range ->
        if (range.startSampleIndex <= current.endSampleIndexExclusive) {
            current =
                SampleRange(
                    current.startSampleIndex,
                    maxOf(current.endSampleIndexExclusive, range.endSampleIndexExclusive),
                )
        } else {
            merged += current
            current = range
        }
    }
    merged += current
    return merged
}

private fun complementRanges(
    startSampleIndex: Long,
    endSampleIndexExclusive: Long,
    covered: List<SampleRange>,
): List<SampleRange> {
    val gaps = mutableListOf<SampleRange>()
    var cursor = startSampleIndex
    covered.forEach { range ->
        if (range.startSampleIndex > cursor) {
            gaps += SampleRange(cursor, range.startSampleIndex)
        }
        cursor = maxOf(cursor, range.endSampleIndexExclusive)
    }
    if (cursor < endSampleIndexExclusive) {
        gaps += SampleRange(cursor, endSampleIndexExclusive)
    }
    return gaps
}

private class Stage13CBoundaryCapture(
    private val application: Application,
) {
    private val lock = Any()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingSession: BoundaryCaptureSession? = null

    fun recordVad(segments: List<SpeechSegment>) {
        synchronized(lock) {
            pendingSession =
                BoundaryCaptureSession(
                    createdAtEpochMs = System.currentTimeMillis(),
                    speechSegments = segments.toList(),
                )
        }
    }

    fun bindEngine(): BoundaryCaptureSession? =
        synchronized(lock) {
            pendingSession.also { pendingSession = null }
        }

    fun export(snapshot: BoundaryCaptureSnapshot) {
        ioScope.launch {
            // Keep the boundary analysis/export outside the measured native path.
            delay(EXPORT_DELAY_MS)
            runCatching {
                val report = snapshot.toReport()
                val json = report.toJson()
                persistAppPrivate(report.fileName, json)
                exportDownloads(report.fileName, json)
                Log.i(TAG, "Stage 13C C4 boundary profile exported: ${report.fileName}")
            }.onFailure { error ->
                Log.w(TAG, "Failed to export Stage 13C C4 boundary profile", error)
            }
        }
    }

    private fun persistAppPrivate(
        fileName: String,
        json: String,
    ) {
        val directory =
            File(application.noBackupFilesDir, "diagnostics/diarization-boundary")
                .apply { mkdirs() }
        File(directory, fileName).writeText(json)
        File(directory, LATEST_REPORT_FILE).writeText(json)
        directory.listFiles { file ->
            file.extension == "json" && file.name != LATEST_REPORT_FILE
        }
            ?.sortedByDescending(File::lastModified)
            ?.drop(MAX_RETAINED_REPORTS)
            ?.forEach(File::delete)
    }

    private fun exportDownloads(
        fileName: String,
        json: String,
    ) {
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = application.contentResolver
            val values =
                ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/Voica/Diagnostics",
                    )
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
            val uri =
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("failed to create C4 boundary export")
            try {
                resolver.openOutputStream(uri, "w")
                    ?.bufferedWriter()
                    ?.use { it.write(json) }
                    ?: error("failed to open C4 boundary export")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (error: Throwable) {
                resolver.delete(uri, null, null)
                throw error
            }
        } else {
            val root =
                application.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: application.filesDir
            val directory = File(root, "Voica/Diagnostics").apply { mkdirs() }
            File(directory, fileName).writeText(json)
        }
    }

    private companion object {
        const val TAG = "Stage13CBoundary"
        const val EXPORT_DELAY_MS = 1_500L
        const val LATEST_REPORT_FILE = "latest-c4-boundary.json"
        const val MAX_RETAINED_REPORTS = 20
    }
}

private class BoundaryCaptureSession(
    val createdAtEpochMs: Long,
    val speechSegments: List<SpeechSegment>,
) {
    private val finished = AtomicBoolean(false)
    private val windows = mutableListOf<Stage13CBoundaryWindowRange>()

    fun recordWindow(window: DiarizationWindow) {
        if (finished.get()) return
        windows +=
            Stage13CBoundaryWindowRange(
                startSampleIndex = window.startSampleIndex,
                endSampleIndexExclusive = window.endSampleIndexExclusive,
            )
    }

    fun finish(capture: Stage13CBoundaryCapture) {
        if (!finished.compareAndSet(false, true)) return
        capture.export(
            BoundaryCaptureSnapshot(
                sessionId = UUID.randomUUID().toString(),
                createdAtEpochMs = createdAtEpochMs,
                speechSegments = speechSegments,
                windows = windows.toList(),
            ),
        )
    }
}

private data class BoundaryCaptureSnapshot(
    val sessionId: String,
    val createdAtEpochMs: Long,
    val speechSegments: List<SpeechSegment>,
    val windows: List<Stage13CBoundaryWindowRange>,
) {
    fun toReport(): Stage13CBoundaryReport {
        val boundaries = analyzeStage13CBoundaries(windows, speechSegments)
        return Stage13CBoundaryReport(
            schemaVersion = 1,
            sessionId = sessionId,
            createdAtEpochMs = createdAtEpochMs,
            sampleRateHz = DiarizationConfig.CANONICAL_SAMPLE_RATE_HZ,
            appVersionCode = BuildConfig.VERSION_CODE.toLong(),
            appVersionName = BuildConfig.VERSION_NAME,
            gitSha = BuildConfig.GIT_SHA,
            windowCount = windows.size,
            vadSegmentCount = speechSegments.size,
            boundaries = boundaries,
        )
    }
}

private data class Stage13CBoundaryReport(
    val schemaVersion: Int,
    val sessionId: String,
    val createdAtEpochMs: Long,
    val sampleRateHz: Int,
    val appVersionCode: Long,
    val appVersionName: String,
    val gitSha: String,
    val windowCount: Int,
    val vadSegmentCount: Int,
    val boundaries: List<Stage13CBoundaryMetric>,
) {
    val fileName: String
        get() =
            "diarization-boundary-$createdAtEpochMs-${sessionId.take(8)}.json"

    fun toJson(): String {
        val totalOverlapSamples = boundaries.sumOf { it.overlapSamples }
        val totalSpeechSamples = boundaries.sumOf { it.speechSamples }
        val totalSilenceSamples = boundaries.sumOf { it.silenceSamples }
        val root =
            JSONObject()
                .put("schemaVersion", schemaVersion)
                .put("sessionId", sessionId)
                .put("createdAtEpochMs", createdAtEpochMs)
                .put("sampleRateHz", sampleRateHz)
                .put(
                    "build",
                    JSONObject()
                        .put("appVersionCode", appVersionCode)
                        .put("appVersionName", appVersionName)
                        .put("gitSha", gitSha)
                        .put("sdkInt", Build.VERSION.SDK_INT),
                )
                .put("windowCount", windowCount)
                .put("vadSegmentCount", vadSegmentCount)
                .put(
                    "summary",
                    JSONObject()
                        .put("boundaryCount", boundaries.size)
                        .put("totalOverlapSamples", totalOverlapSamples)
                        .put("speechSamplesInOverlap", totalSpeechSamples)
                        .put("silenceSamplesInOverlap", totalSilenceSamples)
                        .put(
                            "speechRatioInOverlap",
                            if (totalOverlapSamples > 0L) {
                                totalSpeechSamples.toDouble() / totalOverlapSamples.toDouble()
                            } else {
                                0.0
                            },
                        )
                        .put(
                            "boundariesWithAnySilence",
                            boundaries.count { it.silenceSamples > 0L },
                        )
                        .put(
                            "boundariesWithNoSilence",
                            boundaries.count { it.silenceSamples == 0L },
                        )
                        .put(
                            "maxLongestSilenceGapSamples",
                            boundaries.maxOfOrNull { it.longestSilenceGapSamples } ?: 0L,
                        ),
                )
                .put(
                    "boundaries",
                    JSONArray().apply {
                        boundaries.forEach { metric ->
                            put(metric.toJson())
                        }
                    },
                )
                .put(
                    "notes",
                    JSONArray(
                        listOf(
                            "C4 is measurement-only: window planning and diarization inputs are unchanged.",
                            "Speech/silence is derived from the exact VAD segments already used by diarization.",
                            "Longest silence gaps are observations only; no cut threshold or adaptive-overlap policy is applied in this build.",
                        ),
                    ),
                )
        return root.toString(2)
    }
}

private fun Stage13CBoundaryMetric.toJson(): JSONObject =
    JSONObject()
        .put("boundaryIndex", boundaryIndex)
        .put("leftWindowStartSampleIndex", leftWindowStartSampleIndex)
        .put("leftWindowEndSampleIndexExclusive", leftWindowEndSampleIndexExclusive)
        .put("rightWindowStartSampleIndex", rightWindowStartSampleIndex)
        .put("rightWindowEndSampleIndexExclusive", rightWindowEndSampleIndexExclusive)
        .put("overlapStartSampleIndex", overlapStartSampleIndex)
        .put("overlapEndSampleIndexExclusive", overlapEndSampleIndexExclusive)
        .put("overlapSamples", overlapSamples)
        .put("speechSamples", speechSamples)
        .put("silenceSamples", silenceSamples)
        .put("speechRatio", speechRatio)
        .put("speechRunCount", speechRunCount)
        .put("silenceGapCount", silenceGapCount)
        .put("longestSilenceGapSamples", longestSilenceGapSamples)
        .putNullable("longestSilenceGapStartSampleIndex", longestSilenceGapStartSampleIndex)
        .putNullable(
            "longestSilenceGapEndSampleIndexExclusive",
            longestSilenceGapEndSampleIndexExclusive,
        )

private fun JSONObject.putNullable(
    name: String,
    value: Any?,
): JSONObject = put(name, value ?: JSONObject.NULL)
