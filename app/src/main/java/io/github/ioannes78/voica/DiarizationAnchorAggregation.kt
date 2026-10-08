package io.github.ioannes78.voica

import io.github.ioannes78.voica.model.ModelDescriptor
import io.github.ioannes78.voica.transcript.DiarizationConfig
import io.github.ioannes78.voica.transcript.DiarizationSpeakerTurn
import io.github.ioannes78.voica.transcript.SpeakerAnchorEmbedding
import io.github.ioannes78.voica.transcript.SpeakerEmbeddingEngine
import java.util.LinkedHashMap
import java.util.WeakHashMap

/**
 * Stage 13C C2 exact-range embedding reuse.
 *
 * DiarizationCoordinator creates one SpeakerEmbeddingEngine per diarization run and closes it when
 * that run finishes. The weak registry therefore scopes every cache to exactly one run/source
 * lineage and never reuses entries across recordings or later runs. Inside that scope the key pins
 * the absolute canonical sample range, complete embedding-model lineage, sample rate and anchor
 * preprocessing version.
 *
 * If this cache is ever made persistent or shared across runs, canonical SHA-256 must become an
 * explicit persisted key component before cross-run reuse is allowed.
 */
private val anchorEmbeddingCacheRegistry =
    WeakHashMap<SpeakerEmbeddingEngine, BoundedAnchorEmbeddingCache>()
private val anchorEmbeddingCacheRegistryLock = Any()

internal const val STAGE13C_ANCHOR_CACHE_MAX_ENTRIES = 128
private const val STAGE13C_ANCHOR_CACHE_MAX_BYTES = 512L * 1024L
private const val STAGE13C_ANCHOR_PREPROCESS_VERSION = 1

internal suspend fun buildSpeakerAnchorEmbeddings(
    windowStartSampleIndex: Long,
    windowSamples: ShortArray,
    turns: List<DiarizationSpeakerTurn>,
    embeddingEngine: SpeakerEmbeddingEngine,
    config: DiarizationConfig,
): List<SpeakerAnchorEmbedding> {
    val windowEndSampleIndex =
        Math.addExact(windowStartSampleIndex, windowSamples.size.toLong())

    return turns
        .filter { !it.overlap }
        .groupBy { it.speakerIndex }
        .entries
        .sortedBy { it.key }
        .mapNotNull { entry ->
            val ranges =
                entry.value
                    .mapNotNull { turn ->
                        val start =
                            maxOf(turn.startSampleIndex, windowStartSampleIndex)
                        val end =
                            minOf(turn.endSampleIndexExclusive, windowEndSampleIndex)
                        if (end > start) {
                            SpeakerAnchorRange(
                                speakerIndex = entry.key,
                                startSampleIndex = start,
                                endSampleIndexExclusive = end,
                            )
                        } else {
                            null
                        }
                    }
                    .filter { it.sampleCount >= config.stitchingMinimumAnchorSamples }
                    .sortedByDescending { it.sampleCount }
                    .take(config.stitchingMaxAnchorsPerSpeaker)

            if (ranges.isEmpty()) {
                return@mapNotNull null
            }

            var totalWeight = 0L
            var weightedEmbedding: DoubleArray? = null
            ranges.forEach { range ->
                val normalized =
                    embedSpeakerAnchorRange(
                        windowStartSampleIndex = windowStartSampleIndex,
                        windowSamples = windowSamples,
                        range = range,
                        embeddingEngine = embeddingEngine,
                        sampleRateHz = config.sampleRateHz,
                    )
                val accumulator =
                    weightedEmbedding ?: DoubleArray(normalized.size).also {
                        weightedEmbedding = it
                    }
                require(accumulator.size == normalized.size) {
                    "speaker embedding dimension changed while aggregating anchors"
                }
                normalized.indices.forEach { index ->
                    accumulator[index] +=
                        normalized[index].toDouble() * range.sampleCount.toDouble()
                }
                totalWeight = Math.addExact(totalWeight, range.sampleCount)
            }

            val combined =
                checkNotNull(weightedEmbedding) {
                    "speaker anchor aggregation produced no embedding"
                }
            SpeakerAnchorEmbedding(
                localSpeakerIndex = entry.key,
                embedding =
                    normalizeSpeakerAnchorEmbedding(
                        FloatArray(combined.size) { index ->
                            combined[index].toFloat()
                        },
                    ),
                anchorSampleCount = totalWeight,
            )
        }
}

private suspend fun embedSpeakerAnchorRange(
    windowStartSampleIndex: Long,
    windowSamples: ShortArray,
    range: SpeakerAnchorRange,
    embeddingEngine: SpeakerEmbeddingEngine,
    sampleRateHz: Int,
): FloatArray {
    val cache = runScopedAnchorEmbeddingCache(embeddingEngine)
    val key =
        AnchorEmbeddingCacheKey(
            identity = cache.identity,
            startSampleIndex = range.startSampleIndex,
            endSampleIndexExclusive = range.endSampleIndexExclusive,
            sampleRateHz = sampleRateHz,
            preprocessVersion = STAGE13C_ANCHOR_PREPROCESS_VERSION,
        )
    cache.get(key)?.let { return it }

    val startOffset =
        Math.toIntExact(range.startSampleIndex - windowStartSampleIndex)
    val endOffset =
        Math.toIntExact(range.endSampleIndexExclusive - windowStartSampleIndex)
    val normalized =
        normalizeSpeakerAnchorEmbedding(
            embeddingEngine.embed(
                samples = windowSamples.copyOfRange(startOffset, endOffset),
                sampleRateHz = sampleRateHz,
            ),
        )
    cache.put(key, normalized)
    return normalized
}

private fun runScopedAnchorEmbeddingCache(
    embeddingEngine: SpeakerEmbeddingEngine,
): BoundedAnchorEmbeddingCache =
    synchronized(anchorEmbeddingCacheRegistryLock) {
        anchorEmbeddingCacheRegistry.getOrPut(embeddingEngine) {
            BoundedAnchorEmbeddingCache(
                identity = embeddingEngine.model.toAnchorCacheIdentity(),
            )
        }
    }

private data class SpeakerAnchorRange(
    val speakerIndex: Int,
    val startSampleIndex: Long,
    val endSampleIndexExclusive: Long,
) {
    val sampleCount: Long
        get() = endSampleIndexExclusive - startSampleIndex
}

private data class AnchorEmbeddingCacheIdentity(
    val modelId: String,
    val modelVersion: String,
    val modelRevision: Long,
    val modelFileSha256: String,
)

private data class AnchorEmbeddingCacheKey(
    val identity: AnchorEmbeddingCacheIdentity,
    val startSampleIndex: Long,
    val endSampleIndexExclusive: Long,
    val sampleRateHz: Int,
    val preprocessVersion: Int,
)

private class BoundedAnchorEmbeddingCache(
    val identity: AnchorEmbeddingCacheIdentity,
) {
    private val entries =
        LinkedHashMap<AnchorEmbeddingCacheKey, FloatArray>(16, 0.75F, true)
    private var retainedBytes = 0L

    @Synchronized
    fun get(key: AnchorEmbeddingCacheKey): FloatArray? =
        entries[key]?.copyOf()

    @Synchronized
    fun put(
        key: AnchorEmbeddingCacheKey,
        embedding: FloatArray,
    ) {
        require(key.identity == identity)
        val stored = embedding.copyOf()
        val previous = entries.put(key, stored)
        if (previous != null) {
            retainedBytes -= previous.embeddingBytes()
        }
        retainedBytes += stored.embeddingBytes()
        trimToBounds()
    }

    private fun trimToBounds() {
        while (
            entries.size > STAGE13C_ANCHOR_CACHE_MAX_ENTRIES ||
            retainedBytes > STAGE13C_ANCHOR_CACHE_MAX_BYTES
        ) {
            val iterator = entries.entries.iterator()
            if (!iterator.hasNext()) return
            val eldest = iterator.next()
            retainedBytes -= eldest.value.embeddingBytes()
            iterator.remove()
        }
    }
}

private fun ModelDescriptor.toAnchorCacheIdentity(): AnchorEmbeddingCacheIdentity =
    AnchorEmbeddingCacheIdentity(
        modelId = modelId,
        modelVersion = version,
        modelRevision = revision,
        modelFileSha256 =
            files
                .sortedBy { it.relativePath }
                .joinToString("|") { file ->
                    file.relativePath + ":" + file.sha256.lowercase()
                },
    )

private fun FloatArray.embeddingBytes(): Long = size.toLong() * Float.SIZE_BYTES.toLong()

private fun normalizeSpeakerAnchorEmbedding(values: FloatArray): FloatArray {
    require(values.isNotEmpty())
    var sumSquares = 0.0
    values.forEach { value ->
        require(value.isFinite())
        sumSquares += value.toDouble() * value.toDouble()
    }
    require(sumSquares > 0.0) { "speaker embedding norm must be positive" }
    val norm = kotlin.math.sqrt(sumSquares).toFloat()
    return FloatArray(values.size) { index -> values[index] / norm }
}
