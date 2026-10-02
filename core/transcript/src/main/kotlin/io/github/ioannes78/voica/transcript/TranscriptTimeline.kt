package io.github.ioannes78.voica.transcript

data class TimelineTranscriptSegmentInput(
    val id: String,
    val segment: TranscriptSegment,
) {
    init {
        require(id.isNotBlank())
    }
}

data class TimelineSpeakerSpanInput(
    val id: String,
    val spanIndex: Int,
    val sourceSegmentId: String,
    val speakerId: String?,
    val speakerOrdinal: Int?,
    val speakerDisplayName: String?,
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
        require(id.isNotBlank())
        require(spanIndex >= 0)
        require(sourceSegmentId.isNotBlank())
        require(startSampleIndex >= 0L)
        require(endSampleIndexExclusive > startSampleIndex)
        require(finalTextStartOffset >= 0)
        require(finalTextEndOffsetExclusive >= finalTextStartOffset)
        require((tokenStartIndex == null) == (tokenEndIndexExclusive == null))
    }
}

enum class TimelineRowKind {
    SEGMENT,
    SPEAKER_SPAN,
}

data class TimedTextCue(
    val id: String,
    val tokenSource: TokenSource,
    val tokenIndex: Int,
    val startSampleIndex: Long,
    val endSampleIndexExclusive: Long,
    val textStartOffset: Int,
    val textEndOffsetExclusive: Int,
    val projectionQuality: TextProjectionQuality,
) {
    init {
        require(id.isNotBlank())
        require(tokenIndex >= 0)
        require(startSampleIndex >= 0L)
        require(endSampleIndexExclusive > startSampleIndex)
        require(textStartOffset >= 0)
        require(textEndOffsetExclusive >= textStartOffset)
    }
}

data class TranscriptTimelineRow(
    val id: String,
    val kind: TimelineRowKind,
    val sourceSegmentId: String,
    val speakerSpanId: String?,
    val startSampleIndex: Long,
    val endSampleIndexExclusive: Long,
    val text: String,
    val speakerId: String?,
    val speakerOrdinal: Int?,
    val speakerDisplayName: String?,
    val assignmentQuality: SpeakerAssignmentQuality?,
    val overlap: Boolean,
    val ambiguous: Boolean,
    val cues: List<TimedTextCue>,
) {
    init {
        require(id.isNotBlank())
        require(sourceSegmentId.isNotBlank())
        require(startSampleIndex >= 0L)
        require(endSampleIndexExclusive > startSampleIndex)
    }
}

data class TranscriptTimeline(
    val recordingId: String,
    val transcriptionId: String,
    val alignmentId: String?,
    val sourceCanonicalAssetId: String,
    val sourceCanonicalSha256: String,
    val canonicalProfileId: String,
    val totalSampleCount: Long,
    val rows: List<TranscriptTimelineRow>,
) {
    init {
        require(recordingId.isNotBlank())
        require(transcriptionId.isNotBlank())
        require(sourceCanonicalAssetId.isNotBlank())
        require(sourceCanonicalSha256.isNotBlank())
        require(canonicalProfileId.isNotBlank())
        require(totalSampleCount >= 0L)
    }
}

data class TimelinePosition(
    val activeRowId: String?,
    val activeCueId: String?,
    val speakerId: String?,
    val inTranscriptGap: Boolean,
)

fun buildTranscriptTimeline(
    recordingId: String,
    transcriptionId: String,
    alignmentId: String?,
    sourceCanonicalAssetId: String,
    sourceCanonicalSha256: String,
    canonicalProfileId: String,
    totalSampleCount: Long,
    segments: List<TimelineTranscriptSegmentInput>,
    speakerSpans: List<TimelineSpeakerSpanInput> = emptyList(),
): TranscriptTimeline {
    val segmentById = segments.associateBy { it.id }
    val rows =
        if (speakerSpans.isNotEmpty()) {
            speakerSpans
                .sortedBy { it.spanIndex }
                .mapNotNull { span ->
                    val source = segmentById[span.sourceSegmentId] ?: return@mapNotNull null
                    buildSpeakerRow(span, source.segment)
                }
        } else {
            segments
                .sortedBy { it.segment.segmentIndex }
                .filter { it.segment.finalText.isNotEmpty() }
                .map(::buildSegmentRow)
        }

    rows.forEach { row ->
        require(row.endSampleIndexExclusive <= totalSampleCount)
    }

    return TranscriptTimeline(
        recordingId = recordingId,
        transcriptionId = transcriptionId,
        alignmentId = alignmentId,
        sourceCanonicalAssetId = sourceCanonicalAssetId,
        sourceCanonicalSha256 = sourceCanonicalSha256,
        canonicalProfileId = canonicalProfileId,
        totalSampleCount = totalSampleCount,
        rows =
            rows.sortedWith(
                compareBy<TranscriptTimelineRow> { it.startSampleIndex }
                    .thenBy { it.endSampleIndexExclusive }
                    .thenBy { it.id },
            ),
    )
}

class TranscriptTimelinePositionMapper(
    timeline: TranscriptTimeline,
) {
    private val totalSampleCount = timeline.totalSampleCount
    private val rows = timeline.rows
    private val prefixMaxEnd =
        LongArray(rows.size).also { prefix ->
            var maxEnd = 0L
            rows.forEachIndexed { index, row ->
                maxEnd = maxOf(maxEnd, row.endSampleIndexExclusive)
                prefix[index] = maxEnd
            }
        }
    private val exactCuesByRowId =
        rows.associate { row ->
            row.id to
                row.cues.filter { cue ->
                    cue.projectionQuality == TextProjectionQuality.EXACT
                }
        }

    private var lastPositionSampleIndex: Long? = null
    private var rowCandidateIndex: Int = -1
    private var cueRowId: String? = null
    private var cueCandidateIndex: Int = -1

    fun map(positionSampleIndex: Long): TimelinePosition {
        val previousPosition = lastPositionSampleIndex
        val forward =
            previousPosition != null &&
                positionSampleIndex >= previousPosition

        if (
            positionSampleIndex < 0L ||
            positionSampleIndex >= totalSampleCount ||
            rows.isEmpty()
        ) {
            lastPositionSampleIndex = positionSampleIndex
            if (!forward) {
                rowCandidateIndex = -1
                resetCueCursor()
            }
            return emptyPosition()
        }

        val row = findActiveRow(positionSampleIndex, forward)
        val cue =
            row?.let { activeRow ->
                findActiveCue(
                    row = activeRow,
                    positionSampleIndex = positionSampleIndex,
                    forward =
                        forward &&
                            cueRowId == activeRow.id,
                )
            }

        if (row == null) {
            resetCueCursor()
        }
        lastPositionSampleIndex = positionSampleIndex

        return if (row == null) {
            emptyPosition()
        } else {
            TimelinePosition(
                activeRowId = row.id,
                activeCueId = cue?.id,
                speakerId = row.speakerId,
                inTranscriptGap = false,
            )
        }
    }

    private fun emptyPosition() =
        TimelinePosition(
            activeRowId = null,
            activeCueId = null,
            speakerId = null,
            inTranscriptGap = true,
        )

    private fun findActiveRow(
        positionSampleIndex: Long,
        forward: Boolean,
    ): TranscriptTimelineRow? {
        val candidate =
            if (forward) {
                var index = rowCandidateIndex.coerceAtLeast(-1)
                while (
                    index + 1 < rows.size &&
                    rows[index + 1].startSampleIndex <= positionSampleIndex
                ) {
                    index += 1
                }
                index
            } else {
                upperBoundRowStart(positionSampleIndex)
            }

        rowCandidateIndex = candidate
        return findContainingRowFromCandidate(
            candidateIndex = candidate,
            positionSampleIndex = positionSampleIndex,
        )
    }

    private fun upperBoundRowStart(positionSampleIndex: Long): Int {
        var low = 0
        var high = rows.lastIndex
        var candidate = -1
        while (low <= high) {
            val mid = (low + high).ushr(1)
            if (rows[mid].startSampleIndex <= positionSampleIndex) {
                candidate = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return candidate
    }

    private fun findContainingRowFromCandidate(
        candidateIndex: Int,
        positionSampleIndex: Long,
    ): TranscriptTimelineRow? {
        var index = candidateIndex
        while (index >= 0 && prefixMaxEnd[index] > positionSampleIndex) {
            val row = rows[index]
            if (
                positionSampleIndex >= row.startSampleIndex &&
                positionSampleIndex < row.endSampleIndexExclusive
            ) {
                return row
            }
            index -= 1
        }
        return null
    }

    private fun findActiveCue(
        row: TranscriptTimelineRow,
        positionSampleIndex: Long,
        forward: Boolean,
    ): TimedTextCue? {
        val cues = exactCuesByRowId[row.id].orEmpty()
        if (cues.isEmpty()) {
            cueRowId = row.id
            cueCandidateIndex = -1
            return null
        }

        if (cueRowId != row.id) {
            cueRowId = row.id
            cueCandidateIndex = -1
        }

        val candidate =
            if (forward) {
                var index = cueCandidateIndex.coerceAtLeast(-1)
                while (
                    index + 1 < cues.size &&
                    cues[index + 1].startSampleIndex <= positionSampleIndex
                ) {
                    index += 1
                }
                index
            } else {
                upperBoundCueStart(cues, positionSampleIndex)
            }
        cueCandidateIndex = candidate

        var index = candidate
        while (index >= 0) {
            val cue = cues[index]
            if (
                positionSampleIndex >= cue.startSampleIndex &&
                positionSampleIndex < cue.endSampleIndexExclusive
            ) {
                return cue
            }
            if (cue.endSampleIndexExclusive <= positionSampleIndex) {
                break
            }
            index -= 1
        }
        return null
    }

    private fun upperBoundCueStart(
        cues: List<TimedTextCue>,
        positionSampleIndex: Long,
    ): Int {
        var low = 0
        var high = cues.lastIndex
        var candidate = -1
        while (low <= high) {
            val mid = (low + high).ushr(1)
            if (cues[mid].startSampleIndex <= positionSampleIndex) {
                candidate = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return candidate
    }

    private fun resetCueCursor() {
        cueRowId = null
        cueCandidateIndex = -1
    }
}

private data class IndexedTimelineToken(
    val tokenIndex: Int,
    val token: TranscriptToken,
)

private fun buildSegmentRow(
    source: TimelineTranscriptSegmentInput,
): TranscriptTimelineRow {
    val segment = source.segment
    val rowId = "segment:" + source.id
    val selected = selectTimelineTokens(segment.tokens, preferredSource = null)
    return TranscriptTimelineRow(
        id = rowId,
        kind = TimelineRowKind.SEGMENT,
        sourceSegmentId = source.id,
        speakerSpanId = null,
        startSampleIndex = segment.startSampleIndex,
        endSampleIndexExclusive = segment.endSampleIndexExclusive,
        text = segment.finalText,
        speakerId = null,
        speakerOrdinal = null,
        speakerDisplayName = null,
        assignmentQuality = null,
        overlap = false,
        ambiguous = false,
        cues =
            buildCues(
                rowId = rowId,
                segment = segment,
                selected = selected,
                rowStartSampleIndex = segment.startSampleIndex,
                rowEndSampleIndexExclusive = segment.endSampleIndexExclusive,
                rowTextStartOffset = 0,
                rowTextEndOffsetExclusive = segment.finalText.length,
                tokenStartIndex = null,
                tokenEndIndexExclusive = null,
            ),
    )
}

private fun buildSpeakerRow(
    span: TimelineSpeakerSpanInput,
    segment: TranscriptSegment,
): TranscriptTimelineRow {
    require(span.finalTextEndOffsetExclusive <= segment.finalText.length)
    require(span.startSampleIndex >= segment.startSampleIndex)
    require(span.endSampleIndexExclusive <= segment.endSampleIndexExclusive)

    val rowId = "span:" + span.id
    val selected = selectTimelineTokens(segment.tokens, preferredSource = span.tokenSource)
    return TranscriptTimelineRow(
        id = rowId,
        kind = TimelineRowKind.SPEAKER_SPAN,
        sourceSegmentId = span.sourceSegmentId,
        speakerSpanId = span.id,
        startSampleIndex = span.startSampleIndex,
        endSampleIndexExclusive = span.endSampleIndexExclusive,
        text =
            segment.finalText.substring(
                span.finalTextStartOffset,
                span.finalTextEndOffsetExclusive,
            ),
        speakerId = span.speakerId,
        speakerOrdinal = span.speakerOrdinal,
        speakerDisplayName = span.speakerDisplayName,
        assignmentQuality = span.assignmentQuality,
        overlap = span.overlap,
        ambiguous = span.ambiguous,
        cues =
            buildCues(
                rowId = rowId,
                segment = segment,
                selected = selected,
                rowStartSampleIndex = span.startSampleIndex,
                rowEndSampleIndexExclusive = span.endSampleIndexExclusive,
                rowTextStartOffset = span.finalTextStartOffset,
                rowTextEndOffsetExclusive = span.finalTextEndOffsetExclusive,
                tokenStartIndex = span.tokenStartIndex,
                tokenEndIndexExclusive = span.tokenEndIndexExclusive,
            ),
    )
}

private fun selectTimelineTokens(
    tokens: List<TranscriptToken>,
    preferredSource: TokenSource?,
): List<IndexedTimelineToken> {
    fun source(source: TokenSource): List<IndexedTimelineToken> =
        tokens
            .filter { it.source == source }
            .mapIndexed { index, token ->
                IndexedTimelineToken(
                    tokenIndex = index,
                    token = token,
                )
            }

    if (preferredSource != null) return source(preferredSource)

    val secondPass = source(TokenSource.SECOND_PASS)
    val firstPass = source(TokenSource.FIRST_PASS)
    return when {
        secondPass.any { it.token.startSampleIndex != null } -> secondPass
        firstPass.any { it.token.startSampleIndex != null } -> firstPass
        secondPass.isNotEmpty() -> secondPass
        else -> firstPass
    }
}

private fun buildCues(
    rowId: String,
    segment: TranscriptSegment,
    selected: List<IndexedTimelineToken>,
    rowStartSampleIndex: Long,
    rowEndSampleIndexExclusive: Long,
    rowTextStartOffset: Int,
    rowTextEndOffsetExclusive: Int,
    tokenStartIndex: Int?,
    tokenEndIndexExclusive: Int?,
): List<TimedTextCue> {
    if (selected.isEmpty() || segment.finalText.isEmpty()) return emptyList()

    val projection =
        projectTokensToFinalText(
            finalText = segment.finalText,
            tokens = selected.map { it.token.text },
        )
    val startIndex = tokenStartIndex ?: 0
    val endIndexExclusive = tokenEndIndexExclusive ?: selected.size

    return selected.mapIndexedNotNull { position, indexed ->
        if (indexed.tokenIndex !in startIndex until endIndexExclusive) {
            return@mapIndexedNotNull null
        }

        val rawStart = indexed.token.startSampleIndex ?: return@mapIndexedNotNull null
        val inferredEnd =
            indexed.token.endSampleIndexExclusive
                ?: selected
                    .drop(position + 1)
                    .firstNotNullOfOrNull { it.token.startSampleIndex }
                ?: segment.endSampleIndexExclusive

        val start =
            rawStart.coerceIn(
                rowStartSampleIndex,
                rowEndSampleIndexExclusive,
            )
        val end =
            inferredEnd.coerceIn(
                start,
                rowEndSampleIndexExclusive,
            )
        if (end <= start) return@mapIndexedNotNull null

        val globalTextStart =
            projection.boundaries[position]
                .coerceIn(rowTextStartOffset, rowTextEndOffsetExclusive)
        val globalTextEnd =
            projection.boundaries[position + 1]
                .coerceIn(globalTextStart, rowTextEndOffsetExclusive)
        if (globalTextEnd <= globalTextStart) return@mapIndexedNotNull null

        TimedTextCue(
            id =
                rowId + ":" +
                    indexed.token.source.name + ":" +
                    indexed.tokenIndex,
            tokenSource = indexed.token.source,
            tokenIndex = indexed.tokenIndex,
            startSampleIndex = start,
            endSampleIndexExclusive = end,
            textStartOffset = globalTextStart - rowTextStartOffset,
            textEndOffsetExclusive = globalTextEnd - rowTextStartOffset,
            projectionQuality = projection.quality,
        )
    }
}
