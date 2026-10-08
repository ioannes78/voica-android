package io.github.ioannes78.voica

import io.github.ioannes78.voica.audio.CanonicalPcmProfile
import io.github.ioannes78.voica.audio.PcmSource
import io.github.ioannes78.voica.database.DiarizationDao
import io.github.ioannes78.voica.database.DiarizationStateValue
import io.github.ioannes78.voica.database.TranscriptSegmentEntity
import io.github.ioannes78.voica.database.TranscriptionEntity
import io.github.ioannes78.voica.database.TranscriptionRepository
import io.github.ioannes78.voica.database.TranscriptionStateValue
import io.github.ioannes78.voica.model.ActiveModel
import io.github.ioannes78.voica.model.ModelDescriptor
import io.github.ioannes78.voica.sherpa.SherpaRuntime
import io.github.ioannes78.voica.transcript.DiarizationEngine
import io.github.ioannes78.voica.transcript.ProgressListener
import io.github.ioannes78.voica.transcript.SpeakerEmbeddingEngine
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
 * Stage 13C C1 provider decorator.
 *
 * It sits below the C0 profiling provider, so a reuse hit still flows through the ordinary
 * profiling VadEngine wrapper. As a result the benchmark keeps truthful VAD segment/speech
 * workload counters while VAD_ENGINE measures the near-zero cached path rather than silently
 * disappearing from the report.
 */
internal class VadReusingStage9DiarizationEngineProvider(
    private val delegate: Stage9DiarizationEngineProvider,
    private val diarizationDao: DiarizationDao,
    private val transcriptionRepository: TranscriptionRepository,
) : Stage9DiarizationEngineProvider {
    override fun vadFactory(
        model: ActiveModel,
        numThreads: Int,
        vadSettings: LocalVadSettings,
    ): VadEngineFactory {
        val fallbackFactory = delegate.vadFactory(model, numThreads, vadSettings)
        return VadEngineFactory {
            val candidate =
                resolveReusableVad(
                    model = model,
                    numThreads = numThreads,
                    vadSettings = vadSettings,
                )
            if (candidate == null) {
                fallbackFactory.open()
            } else {
                ReusedOrFallbackVadEngine(
                    model = model.descriptor,
                    expectedTotalSampleCount = candidate.totalSampleCount,
                    reusedSegments = candidate.segments,
                    fallbackFactory = fallbackFactory,
                )
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
    ): DiarizationEngine =
        delegate.diarizationEngine(
            segmentation = segmentation,
            embedding = embedding,
            numThreads = numThreads,
        )

    override fun embeddingEngine(
        model: ActiveModel,
        numThreads: Int,
    ): SpeakerEmbeddingEngine =
        delegate.embeddingEngine(
            model = model,
            numThreads = numThreads,
        )

    private suspend fun resolveReusableVad(
        model: ActiveModel,
        numThreads: Int,
        vadSettings: LocalVadSettings,
    ): ReusableVadCandidate? {
        val activeRuns = diarizationDao.loadActiveRuns(DiarizationStateValue.ACTIVE)
        val run = activeRuns.singleOrNull() ?: return null
        if (
            run.state != DiarizationStateValue.PREPARING &&
            run.state != DiarizationStateValue.VAD_ANALYZING
        ) {
            return null
        }
        if (run.runtimeId != SherpaRuntime.RUNTIME_ID) return null
        if (run.runtimeVersion != SherpaRuntime.RUNTIME_VERSION) return null
        if (run.vadModelId != model.descriptor.modelId) return null
        if (run.vadModelVersion != model.descriptor.version) return null
        if (run.vadModelRevision != model.descriptor.revision) return null

        val context =
            DiarizationVadReuseContext(
                recordingId = run.recordingId,
                canonicalSha256 = run.sourceCanonicalSha256,
                canonicalProfileId = run.canonicalProfileId,
                totalSampleCount = run.totalSampleCount,
                vadModel = model,
                vadSettings = vadSettings,
                effectiveThreads = numThreads,
            )
        val segments =
            loadReusableVadSegmentsOrNull(
                transcriptionRepository = transcriptionRepository,
                context = context,
            ) ?: return null
        return ReusableVadCandidate(
            totalSampleCount = run.totalSampleCount,
            segments = segments,
        )
    }
}

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
        val effectiveModel = modelArray.optJSONObject(index) ?: continue
        if (effectiveModel.optString("id") != descriptor.modelId) continue
        matchedModel =
            effectiveModel.optString("version") == descriptor.version &&
                effectiveModel.optLong("revision", Long.MIN_VALUE) == descriptor.revision &&
                effectiveModel.optString("manifestDigest")
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

private data class ReusableVadCandidate(
    val totalSampleCount: Long,
    val segments: List<SpeechSegment>,
)

/**
 * A source mismatch is treated as a safe miss and lazily opens the unchanged native Silero
 * factory. On a valid hit no native VAD model/session is opened.
 */
private class ReusedOrFallbackVadEngine(
    override val model: ModelDescriptor,
    private val expectedTotalSampleCount: Long,
    private val reusedSegments: List<SpeechSegment>,
    private val fallbackFactory: VadEngineFactory,
) : VadEngine {
    private var fallback: VadEngine? = null
    private var closed = false

    override suspend fun analyze(
        source: PcmSource,
        progressListener: ProgressListener?,
    ): List<SpeechSegment> {
        check(!closed) { "VAD engine is closed" }
        val sourceMatches =
            source.sampleRateHz == CanonicalPcmProfile.SAMPLE_RATE_HZ &&
                source.channelCount == CanonicalPcmProfile.CHANNEL_COUNT &&
                source.totalSampleCount == expectedTotalSampleCount
        if (sourceMatches) {
            progressListener?.onProgress(
                TranscriptionProgress(
                    phase = TranscriptionPhase.VAD,
                    processedUnits = source.totalSampleCount,
                    totalUnits = source.totalSampleCount,
                ),
            )
            return reusedSegments
        }

        val engine = fallback ?: fallbackFactory.open().also { fallback = it }
        return engine.analyze(source, progressListener)
    }

    override fun close() {
        if (closed) return
        closed = true
        fallback?.close()
        fallback = null
    }
}

private fun sameFloat(
    actual: Double,
    expected: Float,
): Boolean =
    actual.isFinite() && abs(actual - expected.toDouble()) <= FLOAT_TOLERANCE

private const val REUSABLE_TRANSCRIPTION_PIPELINE_VERSION = 2
private const val REUSABLE_CONFIG_SCHEMA_VERSION = 2
private const val VAD_WINDOW_SIZE_SAMPLES = 512
private const val FLOAT_TOLERANCE = 1e-6
