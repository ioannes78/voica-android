package io.github.ioannes78.voica.transcript

/**
 * Deterministically partitions VAD speech segments into bounded offline-ASR consumer chunks.
 *
 * The original VAD segments remain the product/timeline truth. These chunks are an internal
 * transport detail used only to keep every offline ASR request within its safety bound.
 */
internal data class OfflineAsrPartition(
    val originalSegments: List<SpeechSegment>,
    val chunks: List<SpeechSegment>,
    val parentSegmentIndexes: List<Int>,
) {
    init {
        require(chunks.size == parentSegmentIndexes.size)
    }
}

internal fun partitionSpeechSegmentsForOfflineAsr(
    speechSegments: List<SpeechSegment>,
    maxChunkSamples: Long,
): OfflineAsrPartition {
    require(maxChunkSamples in 1L..Int.MAX_VALUE.toLong())

    val chunks = ArrayList<SpeechSegment>()
    val parents = ArrayList<Int>()
    speechSegments.forEachIndexed { segmentIndex, segment ->
        var chunkStart = segment.startSampleIndex
        while (chunkStart < segment.endSampleIndexExclusive) {
            val remaining = segment.endSampleIndexExclusive - chunkStart
            val chunkLength = minOf(remaining, maxChunkSamples)
            val chunkEnd = Math.addExact(chunkStart, chunkLength)
            chunks += SpeechSegment(chunkStart, chunkEnd)
            parents += segmentIndex
            chunkStart = chunkEnd
        }
    }

    return OfflineAsrPartition(
        originalSegments = speechSegments,
        chunks = chunks,
        parentSegmentIndexes = parents,
    )
}

internal fun mergeOfflineAsrChunks(
    partition: OfflineAsrPartition,
    chunkResults: List<OfflineSegment>,
): List<OfflineSegment> {
    require(chunkResults.size == partition.chunks.size) {
        "offline ASR chunk result count mismatch"
    }
    if (partition.originalSegments.isEmpty()) return emptyList()

    chunkResults.forEachIndexed { chunkIndex, result ->
        require(result.segmentIndex == chunkIndex) {
            "offline ASR chunk results are not ordered"
        }
        require(result.speechSegment == partition.chunks[chunkIndex]) {
            "offline ASR chunk result range mismatch"
        }
    }

    val grouped = Array(partition.originalSegments.size) { mutableListOf<OfflineSegment>() }
    chunkResults.forEachIndexed { chunkIndex, result ->
        val parentIndex = partition.parentSegmentIndexes[chunkIndex]
        require(parentIndex in partition.originalSegments.indices)
        grouped[parentIndex] += result
    }

    return partition.originalSegments.mapIndexed { originalIndex, originalSegment ->
        val parts = grouped[originalIndex]
        require(parts.isNotEmpty()) { "offline ASR parent segment has no chunks" }
        validateChunkCoverage(originalSegment, parts)

        if (parts.size == 1) {
            return@mapIndexed parts.single().copy(
                segmentIndex = originalIndex,
                speechSegment = originalSegment,
            )
        }

        val mergedText = mergeChunkTexts(parts.map { it.hypothesis.text })
        val tokensComplete =
            parts.all { part ->
                part.hypothesis.text.isBlank() || part.absoluteTokens.isNotEmpty()
            }
        val absoluteTokens =
            if (tokensComplete) {
                parts.flatMap { it.absoluteTokens }
            } else {
                // A partially timed segment is more dangerous than an untimed one: returning an
                // empty list forces the existing full-segment timeline alignment fallback.
                emptyList()
            }
        val relativeTokens =
            absoluteTokens.map { token ->
                RelativeTimedToken(
                    text = token.text,
                    startSampleOffset =
                        token.startSampleIndex?.let { it - originalSegment.startSampleIndex },
                    endSampleOffsetExclusive =
                        token.endSampleIndexExclusive?.let {
                            it - originalSegment.startSampleIndex
                        },
                )
            }
        val languages = parts.mapNotNull { it.hypothesis.detectedLanguage }.distinct()
        val confidence =
            if (parts.all { it.hypothesis.confidence != null }) {
                val weighted =
                    parts.sumOf { part ->
                        part.hypothesis.confidence!!.toDouble() * part.speechSegment.sampleCount.toDouble()
                    }
                (weighted / originalSegment.sampleCount.toDouble()).toFloat()
            } else {
                null
            }

        OfflineSegment(
            segmentIndex = originalIndex,
            speechSegment = originalSegment,
            hypothesis =
                AsrHypothesis(
                    text = mergedText,
                    tokens = relativeTokens,
                    detectedLanguage = languages.singleOrNull(),
                    confidence = confidence,
                    punctuationCapability =
                        mergePunctuationCapability(parts.map { it.hypothesis.punctuationCapability }),
                    isFinal = true,
                ),
            absoluteTokens = absoluteTokens,
        )
    }
}

private fun validateChunkCoverage(
    original: SpeechSegment,
    parts: List<OfflineSegment>,
) {
    var expectedStart = original.startSampleIndex
    parts.forEach { part ->
        require(part.speechSegment.startSampleIndex == expectedStart) {
            "offline ASR chunks do not continuously cover the parent segment"
        }
        require(part.speechSegment.endSampleIndexExclusive <= original.endSampleIndexExclusive)
        expectedStart = part.speechSegment.endSampleIndexExclusive
    }
    require(expectedStart == original.endSampleIndexExclusive) {
        "offline ASR chunks do not fully cover the parent segment"
    }
}

private fun mergePunctuationCapability(
    capabilities: List<PunctuationCapability>,
): PunctuationCapability =
    when {
        PunctuationCapability.NONE in capabilities -> PunctuationCapability.NONE
        PunctuationCapability.PARTIAL in capabilities -> PunctuationCapability.PARTIAL
        else -> PunctuationCapability.RELIABLE
    }

internal fun mergeChunkTexts(parts: List<String>): String {
    val output = StringBuilder()
    parts.forEach { raw ->
        val text = raw.trim()
        if (text.isEmpty()) return@forEach
        if (output.isNotEmpty() && needsAsciiWordBoundary(output.last(), text.first())) {
            output.append(' ')
        }
        output.append(text)
    }
    return output.toString()
}

private fun needsAsciiWordBoundary(left: Char, right: Char): Boolean =
    left.isLetterOrDigit() && right.isLetterOrDigit() && left.code < 128 && right.code < 128
