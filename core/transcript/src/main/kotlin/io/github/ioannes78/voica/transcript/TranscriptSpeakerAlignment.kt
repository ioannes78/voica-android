package io.github.ioannes78.voica.transcript

enum class SpeakerAssignmentQuality {
    ASSIGNED,
    ASSIGNED_WITH_OVERLAP,
    OVERLAP_AMBIGUOUS,
    UNRESOLVED,
}

data class SpeakerAlignedTextSpan(
    val spanIndex: Int,
    val sourceSegmentIndex: Int,
    val speakerIndex: Int?,
    val startSampleIndex: Long,
    val endSampleIndexExclusive: Long,
    val tokenSource: TokenSource?,
    val tokenStartIndex: Int?,
    val tokenEndIndexExclusive: Int?,
    val finalTextStartOffset: Int,
    val finalTextEndOffsetExclusive: Int,
    val assignmentQuality: SpeakerAssignmentQuality,
    val overlap: Boolean,
    val ambiguous: Boolean,
) {
    init {
        require(spanIndex >= 0)
        require(sourceSegmentIndex >= 0)
        require(speakerIndex == null || speakerIndex >= 0)
        require(startSampleIndex >= 0L)
        require(endSampleIndexExclusive > startSampleIndex)
        require(tokenStartIndex == null || tokenStartIndex >= 0)
        require(
            tokenEndIndexExclusive == null ||
                (tokenStartIndex != null && tokenEndIndexExclusive > tokenStartIndex),
        )
        require(finalTextStartOffset >= 0)
        require(finalTextEndOffsetExclusive >= finalTextStartOffset)
        require(!ambiguous || speakerIndex == null)
    }
}

data class TranscriptSpeakerAlignmentResult(
    val spans: List<SpeakerAlignedTextSpan>,
)

fun alignTranscriptSegmentsToSpeakers(
    segments: List<TranscriptSegment>,
    speakerTurns: List<GlobalSpeakerTurn>,
): TranscriptSpeakerAlignmentResult {
    require(segments.map { it.segmentIndex }.distinct().size == segments.size) {
        "transcript segment indices must be unique"
    }

    val orderedTurns =
        speakerTurns.sortedWith(
            compareBy<GlobalSpeakerTurn> { it.startSampleIndex }
                .thenBy { it.endSampleIndexExclusive }
                .thenBy { it.globalSpeakerIndex },
        )

    val output = mutableListOf<SpeakerAlignedTextSpan>()
    var nextSpanIndex = 0

    segments.sortedBy { it.segmentIndex }.forEach { segment ->
        if (segment.finalText.isEmpty()) return@forEach

        val selected = selectPreferredTokens(segment.tokens)
        val hasTimedToken = selected.any { it.token.startSampleIndex != null }

        if (selected.isEmpty() || !hasTimedToken) {
            val fallback = assignWholeSegment(segment, orderedTurns)
            output +=
                SpeakerAlignedTextSpan(
                    spanIndex = nextSpanIndex++,
                    sourceSegmentIndex = segment.segmentIndex,
                    speakerIndex = fallback.speakerIndex,
                    startSampleIndex = segment.startSampleIndex,
                    endSampleIndexExclusive = segment.endSampleIndexExclusive,
                    tokenSource = selected.firstOrNull()?.token?.source,
                    tokenStartIndex = selected.firstOrNull()?.originalIndex,
                    tokenEndIndexExclusive = selected.lastOrNull()?.originalIndex?.plus(1),
                    finalTextStartOffset = 0,
                    finalTextEndOffsetExclusive = segment.finalText.length,
                    assignmentQuality = fallback.quality,
                    overlap = fallback.overlap,
                    ambiguous = fallback.ambiguous,
                )
            return@forEach
        }

        val textBoundaries =
            projectTokensToFinalText(
                finalText = segment.finalText,
                tokens = selected.map { it.token.text },
            ).boundaries

        val pieces =
            selected.mapIndexedNotNull { position, indexed ->
                val textStart = textBoundaries[position]
                val textEnd = textBoundaries[position + 1]
                if (textEnd <= textStart) return@mapIndexedNotNull null

                val range =
                    deriveTokenRange(
                        position = position,
                        selected = selected,
                        segment = segment,
                    )
                val assignment =
                    range?.let { (start, end) ->
                        assignTimedRange(
                            startSampleIndex = start,
                            endSampleIndexExclusive = end,
                            speakerTurns = orderedTurns,
                        )
                    } ?: SpeakerAssignment.unresolved()

                TokenPiece(
                    originalTokenIndex = indexed.originalIndex,
                    tokenSource = indexed.token.source,
                    startSampleIndex = range?.first,
                    endSampleIndexExclusive = range?.second,
                    finalTextStartOffset = textStart,
                    finalTextEndOffsetExclusive = textEnd,
                    assignment = assignment,
                )
            }

        if (pieces.isEmpty()) {
            val fallback = assignWholeSegment(segment, orderedTurns)
            output +=
                SpeakerAlignedTextSpan(
                    spanIndex = nextSpanIndex++,
                    sourceSegmentIndex = segment.segmentIndex,
                    speakerIndex = fallback.speakerIndex,
                    startSampleIndex = segment.startSampleIndex,
                    endSampleIndexExclusive = segment.endSampleIndexExclusive,
                    tokenSource = selected.firstOrNull()?.token?.source,
                    tokenStartIndex = selected.firstOrNull()?.originalIndex,
                    tokenEndIndexExclusive = selected.lastOrNull()?.originalIndex?.plus(1),
                    finalTextStartOffset = 0,
                    finalTextEndOffsetExclusive = segment.finalText.length,
                    assignmentQuality = fallback.quality,
                    overlap = fallback.overlap,
                    ambiguous = fallback.ambiguous,
                )
            return@forEach
        }

        val groups = mutableListOf<MutableList<TokenPiece>>()
        pieces.forEach { piece ->
            val current = groups.lastOrNull()
            if (current == null || !sameAssignment(current.last(), piece)) {
                groups += mutableListOf(piece)
            } else {
                current += piece
            }
        }

        groups.forEach { group ->
            val first = group.first()
            val last = group.last()
            val timedStarts = group.mapNotNull { it.startSampleIndex }
            val timedEnds = group.mapNotNull { it.endSampleIndexExclusive }
            var sampleStart = timedStarts.minOrNull() ?: segment.startSampleIndex
            var sampleEnd = timedEnds.maxOrNull() ?: segment.endSampleIndexExclusive
            if (sampleEnd <= sampleStart) {
                sampleStart = segment.startSampleIndex
                sampleEnd = segment.endSampleIndexExclusive
            }

            output +=
                SpeakerAlignedTextSpan(
                    spanIndex = nextSpanIndex++,
                    sourceSegmentIndex = segment.segmentIndex,
                    speakerIndex = first.assignment.speakerIndex,
                    startSampleIndex = sampleStart,
                    endSampleIndexExclusive = sampleEnd,
                    tokenSource = first.tokenSource,
                    tokenStartIndex = first.originalTokenIndex,
                    tokenEndIndexExclusive = last.originalTokenIndex + 1,
                    finalTextStartOffset = first.finalTextStartOffset,
                    finalTextEndOffsetExclusive = last.finalTextEndOffsetExclusive,
                    assignmentQuality = first.assignment.quality,
                    overlap = first.assignment.overlap,
                    ambiguous = first.assignment.ambiguous,
                )
        }
    }

    return TranscriptSpeakerAlignmentResult(output)
}

fun reconstructAlignedFinalText(
    segment: TranscriptSegment,
    spans: List<SpeakerAlignedTextSpan>,
): String =
    spans
        .filter { it.sourceSegmentIndex == segment.segmentIndex }
        .sortedBy { it.finalTextStartOffset }
        .joinToString(separator = "") { span ->
            segment.finalText.substring(
                span.finalTextStartOffset,
                span.finalTextEndOffsetExclusive,
            )
        }

private data class IndexedTranscriptToken(
    val originalIndex: Int,
    val token: TranscriptToken,
)

private data class TokenPiece(
    val originalTokenIndex: Int,
    val tokenSource: TokenSource,
    val startSampleIndex: Long?,
    val endSampleIndexExclusive: Long?,
    val finalTextStartOffset: Int,
    val finalTextEndOffsetExclusive: Int,
    val assignment: SpeakerAssignment,
)

private data class SpeakerAssignment(
    val speakerIndex: Int?,
    val quality: SpeakerAssignmentQuality,
    val overlap: Boolean,
    val ambiguous: Boolean,
) {
    companion object {
        fun assigned(speakerIndex: Int) =
            SpeakerAssignment(
                speakerIndex = speakerIndex,
                quality = SpeakerAssignmentQuality.ASSIGNED,
                overlap = false,
                ambiguous = false,
            )

        fun ambiguous() =
            SpeakerAssignment(
                speakerIndex = null,
                quality = SpeakerAssignmentQuality.OVERLAP_AMBIGUOUS,
                overlap = true,
                ambiguous = true,
            )

        fun unresolved() =
            SpeakerAssignment(
                speakerIndex = null,
                quality = SpeakerAssignmentQuality.UNRESOLVED,
                overlap = false,
                ambiguous = false,
            )
    }
}

private fun selectPreferredTokens(
    tokens: List<TranscriptToken>,
): List<IndexedTranscriptToken> {
    val secondPass =
        tokens
            .filter { it.source == TokenSource.SECOND_PASS }
            .mapIndexed(::IndexedTranscriptToken)
    val firstPass =
        tokens
            .filter { it.source == TokenSource.FIRST_PASS }
            .mapIndexed(::IndexedTranscriptToken)

    return when {
        secondPass.any { it.token.startSampleIndex != null } -> secondPass
        firstPass.any { it.token.startSampleIndex != null } -> firstPass
        secondPass.isNotEmpty() -> secondPass
        else -> firstPass
    }
}

private fun deriveTokenRange(
    position: Int,
    selected: List<IndexedTranscriptToken>,
    segment: TranscriptSegment,
): Pair<Long, Long>? {
    val token = selected[position].token
    val rawStart = token.startSampleIndex ?: return null
    val start =
        rawStart.coerceIn(
            segment.startSampleIndex,
            segment.endSampleIndexExclusive,
        )

    val inferredEnd =
        token.endSampleIndexExclusive
            ?: selected
                .drop(position + 1)
                .firstNotNullOfOrNull { it.token.startSampleIndex }
            ?: segment.endSampleIndexExclusive

    val end =
        inferredEnd.coerceIn(
            start,
            segment.endSampleIndexExclusive,
        )
    return start to end
}

private fun assignTimedRange(
    startSampleIndex: Long,
    endSampleIndexExclusive: Long,
    speakerTurns: List<GlobalSpeakerTurn>,
): SpeakerAssignment {
    val speakers =
        if (endSampleIndexExclusive > startSampleIndex) {
            speakerTurns
                .asSequence()
                .filter { turn ->
                    startSampleIndex < turn.endSampleIndexExclusive &&
                        turn.startSampleIndex < endSampleIndexExclusive
                }
                .map { it.globalSpeakerIndex }
                .toSet()
        } else {
            speakerTurns
                .asSequence()
                .filter { turn ->
                    startSampleIndex >= turn.startSampleIndex &&
                        startSampleIndex < turn.endSampleIndexExclusive
                }
                .map { it.globalSpeakerIndex }
                .toSet()
        }

    return when (speakers.size) {
        0 -> SpeakerAssignment.unresolved()
        1 -> SpeakerAssignment.assigned(speakers.single())
        else -> SpeakerAssignment.ambiguous()
    }
}

private fun assignWholeSegment(
    segment: TranscriptSegment,
    speakerTurns: List<GlobalSpeakerTurn>,
): SpeakerAssignment {
    val overlapping =
        speakerTurns.filter { turn ->
            segment.startSampleIndex < turn.endSampleIndexExclusive &&
                turn.startSampleIndex < segment.endSampleIndexExclusive
        }
    val distinctSpeakers = overlapping.map { it.globalSpeakerIndex }.toSet()
    if (distinctSpeakers.size != 1) return SpeakerAssignment.unresolved()

    val covering =
        overlapping.filter { turn ->
            turn.startSampleIndex <= segment.startSampleIndex &&
                turn.endSampleIndexExclusive >= segment.endSampleIndexExclusive
        }
    if (covering.size != 1) return SpeakerAssignment.unresolved()
    return SpeakerAssignment.assigned(covering.single().globalSpeakerIndex)
}

private fun sameAssignment(
    left: TokenPiece,
    right: TokenPiece,
): Boolean =
    left.assignment == right.assignment &&
        left.tokenSource == right.tokenSource &&
        left.finalTextEndOffsetExclusive == right.finalTextStartOffset
