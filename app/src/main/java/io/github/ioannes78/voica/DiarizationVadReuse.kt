package io.github.ioannes78.voica

import io.github.ioannes78.voica.audio.CanonicalPcmProfile
import io.github.ioannes78.voica.audio.PcmSource
import io.github.ioannes78.voica.database.TranscriptSegmentEntity
import io.github.ioannes78.voica.database.TranscriptionEntity
import io.github.ioannes78.voica.database.TranscriptionRepository
import io.github.ioannes78.voica.database.TranscriptionStateValue
import io.github.ioannes78.voica.model.ActiveModel
import io.github.ioannes78.voica.model.ModelDescriptor
import io.github.ioannes78.voica.sherpa.SherpaRuntime
import io.github.ioannes78.voica.transcript.ProgressListener
import io.github.ioannes78.voica.transcript.SpeechSegment
import io.github.ioannes78.voica.transcript.TranscriptionPhase
import io.github.ioannes78.voica.transcript.TranscriptionProgress
import io.github.ioannes78.voica.transcript.VadEngine
import io.github.ioannes78.voica.transcript.VadEngineFactory
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import org.json.JSONObject

internal data class DiarizationVadReuseContext(
    val recordingId: String,
    val canonicalSha256: String,
    val canonicalProfileId: String,
    val totalSampleCount: Long,
    val vadModel: ActiveModel,
    val vadSettings: LocalVadSettings,
    val effectiveThreads: Int,
)

/**
 * Load the most recent completed transcription and reuse its persisted VAD boundaries only when
 * the complete canonical/model/runtime/config lineage still matches this diarization request.
 * Any uncertainty returns null and the caller must run Silero VAD normally.
 */
internal suspend fun loadReusableVadSegmentsOrNull(
    transcriptionRepository: TranscriptionRepository,
    context: DiarizationVadReuseContext,
): List<SpeechSegment>? {
    val transcription =
        transcriptionRepository.observeLatestCompleted(context.recordingId).first()
            ?: return null
    val persistedSegments = transcriptionRepository.loadSegments(transcription.id)
    return reusableVadSegmentsOrNull(
        transcription = transcription,
        persistedSegments = persistedSegments,
        context = context,
    )
}

/**
 * Reuses persisted transcription VAD boundaries only when their complete input lineage still
 * matches the diarization request. Any uncertainty returns null and the caller must run Silero
 * VAD normally.
 *
 * Transcription pipeline v2 persists one transcript segment for every VAD speech segment. The
 * pipeline version gate is deliberate: a future transcription pipeline must opt in explicitly
 * before its segment boundaries can be treated as VAD output.
 */
internal fun reusableVadSegmentsOrNull(
    transcription: TranscriptionEntity,
    persistedSegments: List<TranscriptSegmentEntity>,
    context: DiarizationVadReuseContext,
): List<SpeechSegment>? {
    if (transcription.state != TranscriptionStateValue.COMPLETED) return null
    if (transcription.pipelineVersion != REUSABLE_TRANSCRIPTION_PIPELINE_VERSION) return null
    if (transcription.recordingId != context.recordingId) return null
    if (!transcription.sourceCanonicalSha256.equals(context.canonicalSha256, ignoreCase = true)) {
        return null
    }
    if (transcription.canonicalProfileId != context.canonicalProfileId) return null
    if (transcription.totalSampleCount != context.totalSampleCount) return null
    if (transcription.runtimeId != SherpaRuntime.RUNTIME_ID) return null
    if (transcription.runtimeVersion != SherpaRuntime.RUNTIME_VERSION) return null

    val descriptor = context.vadModel.descriptor
    if (transcription.vadModelId != descriptor.modelId) return null
    if (transcription.vadModelVersion != descriptor.version) return null
    if (transcription.vadModelRevision != descriptor.revision) return null
    if (transcription.configSnapshotSchemaVersion != REUSABLE_CONFIG_SCHEMA_VERSION) return null

    val effective =
        runCatching { JSONObject(transcription.effectiveConfigSnapshot) }
            .getOrNull()
            ?: return null
    if (effective.optInt("schemaVersion", -1) != REUSABLE_CONFIG_SCHEMA_VERSION) return null
    if (effective.optInt("effectiveThreads", -1) != context.effectiveThreads) return null
    if (effective.optInt("vadWindowSizeSamples", -1) != VAD_WINDOW_SIZE_SAMPLES) return null

    val runtime = effective.optJSONObject("runtime") ?: return null
    if (runtime.optString("id") != SherpaRuntime.RUNTIME_ID) return null
    if (runtime.optString("version") != SherpaRuntime.RUNTIME_VERSION) return null
    if (runtime.optString("provider") != SherpaRuntime.PROVIDER_CPU) return null

    val vad = effective.optJSONObject("vad") ?: return null
    if (!sameFloat(vad.optDouble("threshold", Double.NaN), context.vadSettings.threshold)) {
        return null
    }
    if (
        !sameFloat(
            vad.optDouble("minSilenceDurationSeconds", Double.NaN),
            context.vadSettings.minSilenceDurationSeconds,
        )
    ) {
        return null
    }
    if (
        !sameFloat(
            vad.optDouble("minSpeechDurationSeconds", Double.NaN),
            context.vadSettings.minSpeechDurationSeconds,
        )
    ) {
        return null
    }
    if (
        !sameFloat(
            vad.optDouble("maxSpeechDurationSeconds", Double.NaN),
            context.vadSettings.maxSpeechDurationSeconds,
        )
    ) {
        return null
    }

    val modelArray = effective.optJSONArray("models") ?: return null
    var matchedModel = false
    for (index in 0 until modelArray.length()) {
        val model = modelArray.optJSONObject(index) ?: continue
        if (model.optString("id") != descriptor.modelId) continue
        matchedModel =
            model.optString("version") == descriptor.version &&
                model.optLong("revision", Long.MIN_VALUE) == descriptor.revision &&
                model.optString("manifestDigest")
                    .equals(context.vadModel.manifestDigest, ignoreCase = true)
        break
    }
    if (!matchedModel) return null

    val ordered = persistedSegments.sortedBy { it.segmentIndex }
    if (ordered.map { it.segmentIndex } != ordered.indices.toList()) return null

    var previousEnd = 0L
    val speechSegments = ArrayList<SpeechSegment>(ordered.size)
    for (segment in ordered) {
        val start = segment.startSampleIndex
        val end = segment.endSampleIndexExclusive
        if (start < previousEnd || end <= start || end > context.totalSampleCount) return null
        speechSegments +=
            SpeechSegment(
                startSampleIndex = start,
                endSampleIndexExclusive = end,
            )
        previousEnd = end
    }
    return speechSegments
}

/**
 * One-shot handoff from the lineage gate in [DiarizationCoordinator] to the Stage 9 VAD engine.
 *
 * The profiler wraps the Stage 9 provider outside this engine. Returning cached segments from
 * analyze() therefore preserves the normal VAD phase, segment/speech workload counters and timing
 * while avoiding Silero model initialization and inference. The handoff is cleared after the
 * current VAD call and can never become a persistent cross-recording cache.
 */
internal object DiarizationVadReuseRegistry {
    private val lock = Any()
    private var pending: Pending? = null

    fun install(
        context: DiarizationVadReuseContext,
        segments: List<SpeechSegment>,
    ) {
        synchronized(lock) {
            pending = Pending(context = context, segments = segments.toList())
        }
    }

    fun clear() {
        synchronized(lock) {
            pending = null
        }
    }

    fun consumeIfCompatible(
        source: PcmSource,
        model: ActiveModel,
        numThreads: Int,
        vadSettings: LocalVadSettings,
    ): List<SpeechSegment>? =
        synchronized(lock) {
            val candidate = pending ?: return@synchronized null
            val context = candidate.context
            val descriptor = model.descriptor
            val expected = context.vadModel.descriptor
            val compatible =
                source.sampleRateHz == CanonicalPcmProfile.SAMPLE_RATE_HZ &&
                    source.channelCount == CanonicalPcmProfile.CHANNEL_COUNT &&
                    source.totalSampleCount == context.totalSampleCount &&
                    descriptor.modelId == expected.modelId &&
                    descriptor.version == expected.version &&
                    descriptor.revision == expected.revision &&
                    model.manifestDigest.equals(context.vadModel.manifestDigest, ignoreCase = true) &&
                    numThreads == context.effectiveThreads &&
                    sameVadSettings(vadSettings, context.vadSettings)
            pending = null
            if (compatible) candidate.segments else null
        }

    private data class Pending(
        val context: DiarizationVadReuseContext,
        val segments: List<SpeechSegment>,
    )
}

/**
 * Wrap the native Stage 9 VAD lazily. A valid one-shot reuse never opens the native Silero engine;
 * a cache miss creates and executes the unchanged delegate engine.
 */
internal fun reuseAwareVadFactory(
    delegateFactory: VadEngineFactory,
    model: ActiveModel,
    numThreads: Int,
    vadSettings: LocalVadSettings,
): VadEngineFactory =
    VadEngineFactory {
        ReuseAwareVadEngine(
            descriptor = model.descriptor,
            delegateFactory = delegateFactory,
            activeModel = model,
            numThreads = numThreads,
            vadSettings = vadSettings,
        )
    }

private class ReuseAwareVadEngine(
    override val model: ModelDescriptor,
    private val delegateFactory: VadEngineFactory,
    private val activeModel: ActiveModel,
    private val numThreads: Int,
    private val vadSettings: LocalVadSettings,
) : VadEngine {
    private var delegate: VadEngine? = null
    private var closed = false

    override suspend fun analyze(
        source: PcmSource,
        progressListener: ProgressListener?,
    ): List<SpeechSegment> {
        check(!closed) { "VAD engine is closed" }
        val reused =
            DiarizationVadReuseRegistry.consumeIfCompatible(
                source = source,
                model = activeModel,
                numThreads = numThreads,
                vadSettings = vadSettings,
            )
        if (reused != null) {
            progressListener?.onProgress(
                TranscriptionProgress(
                    phase = TranscriptionPhase.VAD,
                    processedUnits = source.totalSampleCount,
                    totalUnits = source.totalSampleCount,
                ),
            )
            return reused
        }

        val engine = delegate ?: delegateFactory.open().also { delegate = it }
        return engine.analyze(source, progressListener)
    }

    override fun close() {
        if (closed) return
        closed = true
        delegate?.close()
        delegate = null
    }
}

private fun sameVadSettings(
    first: LocalVadSettings,
    second: LocalVadSettings,
): Boolean =
    sameFloat(first.threshold.toDouble(), second.threshold) &&
        sameFloat(first.minSilenceDurationSeconds.toDouble(), second.minSilenceDurationSeconds) &&
        sameFloat(first.minSpeechDurationSeconds.toDouble(), second.minSpeechDurationSeconds) &&
        sameFloat(first.maxSpeechDurationSeconds.toDouble(), second.maxSpeechDurationSeconds)

private fun sameFloat(
    actual: Double,
    expected: Float,
): Boolean =
    actual.isFinite() && abs(actual - expected.toDouble()) <= FLOAT_TOLERANCE

private const val REUSABLE_TRANSCRIPTION_PIPELINE_VERSION = 2
private const val REUSABLE_CONFIG_SCHEMA_VERSION = 2
private const val VAD_WINDOW_SIZE_SAMPLES = 512
private const val FLOAT_TOLERANCE = 1e-6
