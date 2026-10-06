package io.github.ioannes78.voica.transcript

enum class RevisionTimingQuality {
    EXACT,
    ANCHORED,
    APPROXIMATE,
}

data class RevisionParagraphDraft(
    val text: String,
    val sourceAnchorRefs: List<String>,
    val anchorStartSampleIndex: Long?,
    val anchorEndSampleIndexExclusive: Long?,
    val speakerId: String?,
    val timingQuality: RevisionTimingQuality,
    val isUserModified: Boolean,
)

object TranscriptRevisionEditor {
    fun mergeWithNext(
        paragraphs: List<RevisionParagraphDraft>,
        index: Int,
    ): List<RevisionParagraphDraft> {
        require(index in 0 until paragraphs.lastIndex) { "no next paragraph to merge" }
        val first = paragraphs[index]
        val second = paragraphs[index + 1]
        val merged = mergeParagraphs(first, second)
        return buildList(paragraphs.size - 1) {
            addAll(paragraphs.subList(0, index))
            add(merged)
            addAll(paragraphs.subList(index + 2, paragraphs.size))
        }
    }

    fun mergeWithPrevious(
        paragraphs: List<RevisionParagraphDraft>,
        index: Int,
    ): List<RevisionParagraphDraft> {
        require(index in 1 until paragraphs.size) { "no previous paragraph to merge" }
        return mergeWithNext(paragraphs, index - 1)
    }

    fun splitAt(
        paragraphs: List<RevisionParagraphDraft>,
        index: Int,
        textOffset: Int,
        exactSplitSampleIndex: Long? = null,
    ): List<RevisionParagraphDraft> {
        require(index in paragraphs.indices)
        val source = paragraphs[index]
        require(textOffset in 1 until source.text.length) { "split point must be inside paragraph" }

        val leftText = source.text.substring(0, textOffset).trim()
        val rightText = source.text.substring(textOffset).trim()
        require(leftText.isNotEmpty() && rightText.isNotEmpty()) {
            "split point cannot produce an empty paragraph"
        }

        val exactSplit =
            exactSplitSampleIndex?.takeIf { sample ->
                source.timingQuality == RevisionTimingQuality.EXACT &&
                    source.anchorStartSampleIndex != null &&
                    source.anchorEndSampleIndexExclusive != null &&
                    sample > source.anchorStartSampleIndex &&
                    sample < source.anchorEndSampleIndexExclusive
            }

        val left =
            source.copy(
                text = leftText,
                anchorEndSampleIndexExclusive =
                    exactSplit ?: source.anchorEndSampleIndexExclusive,
                timingQuality =
                    if (exactSplit != null) {
                        RevisionTimingQuality.EXACT
                    } else {
                        RevisionTimingQuality.APPROXIMATE
                    },
                isUserModified = true,
            )
        val right =
            source.copy(
                text = rightText,
                anchorStartSampleIndex =
                    exactSplit ?: source.anchorStartSampleIndex,
                timingQuality =
                    if (exactSplit != null) {
                        RevisionTimingQuality.EXACT
                    } else {
                        RevisionTimingQuality.APPROXIMATE
                    },
                isUserModified = true,
            )

        return buildList(paragraphs.size + 1) {
            addAll(paragraphs.subList(0, index))
            add(left)
            add(right)
            addAll(paragraphs.subList(index + 1, paragraphs.size))
        }
    }

    /**
     * Builds reading paragraphs without rewriting recognition text.
     *
     * ASR segments are timing/source anchors, not guaranteed reading paragraphs. This organizer:
     * - joins adjacent short sentences from the same speaker into readable natural paragraphs;
     * - still repairs an ASR boundary that cuts through one sentence;
     * - never automatically crosses a speaker boundary;
     * - splits an excessively long source paragraph only at textual boundaries when possible;
     * - never invents token timestamps. A split without a verified token boundary keeps the source
     *   anchor range and is explicitly marked [RevisionTimingQuality.APPROXIMATE].
     */
    fun arrangeForReading(
        paragraphs: List<RevisionParagraphDraft>,
        targetParagraphChars: Int = 220,
        maxParagraphChars: Int = 320,
    ): List<RevisionParagraphDraft> {
        require(targetParagraphChars >= 40)
        require(maxParagraphChars >= targetParagraphChars)
        if (paragraphs.isEmpty()) return paragraphs

        val expanded =
            paragraphs.flatMap { paragraph ->
                splitLongParagraph(paragraph, maxParagraphChars)
            }
        if (expanded.isEmpty()) return paragraphs

        val arranged = mutableListOf<RevisionParagraphDraft>()
        var current: RevisionParagraphDraft? = null

        fun flush() {
            current?.let(arranged::add)
            current = null
        }

        for (next in expanded) {
            val active = current
            if (active == null) {
                current = next
                continue
            }

            val sameSpeaker = active.speakerId == next.speakerId
            val combinedLength =
                active.text.trim().length + next.text.trim().length
            val activeSentenceContinues =
                active.text.trimEnd().lastOrNull() !in PARAGRAPH_BOUNDARY_PUNCTUATION
            val shouldMerge =
                sameSpeaker &&
                    combinedLength <= maxParagraphChars &&
                    (activeSentenceContinues || active.text.trim().length < targetParagraphChars)

            if (shouldMerge) {
                current = mergeParagraphs(active, next)
            } else {
                flush()
                current = next
            }
        }
        flush()

        return if (sameParagraphs(paragraphs, arranged)) paragraphs else arranged
    }

    fun editText(
        paragraphs: List<RevisionParagraphDraft>,
        index: Int,
        text: String,
    ): List<RevisionParagraphDraft> {
        require(index in paragraphs.indices)
        val normalized = text.trim()
        require(normalized.isNotEmpty())
        return paragraphs.toMutableList().also { result ->
            val source = result[index]
            result[index] =
                source.copy(
                    text = normalized,
                    timingQuality =
                        if (normalized == source.text) {
                            source.timingQuality
                        } else {
                            when (source.timingQuality) {
                                RevisionTimingQuality.EXACT,
                                RevisionTimingQuality.ANCHORED,
                                -> RevisionTimingQuality.ANCHORED

                                RevisionTimingQuality.APPROXIMATE ->
                                    RevisionTimingQuality.APPROXIMATE
                            }
                        },
                    isUserModified = source.isUserModified || normalized != source.text,
                )
        }
    }

    private fun mergeParagraphs(
        first: RevisionParagraphDraft,
        second: RevisionParagraphDraft,
    ): RevisionParagraphDraft =
        RevisionParagraphDraft(
            text = joinForReading(first.text, second.text),
            sourceAnchorRefs = (first.sourceAnchorRefs + second.sourceAnchorRefs).distinct(),
            anchorStartSampleIndex = first.anchorStartSampleIndex ?: second.anchorStartSampleIndex,
            anchorEndSampleIndexExclusive =
                second.anchorEndSampleIndexExclusive ?: first.anchorEndSampleIndexExclusive,
            speakerId = first.speakerId.takeIf { it == second.speakerId },
            timingQuality =
                if (
                    first.timingQuality == RevisionTimingQuality.APPROXIMATE ||
                    second.timingQuality == RevisionTimingQuality.APPROXIMATE
                ) {
                    RevisionTimingQuality.APPROXIMATE
                } else {
                    RevisionTimingQuality.ANCHORED
                },
            isUserModified = true,
        )

    private fun splitLongParagraph(
        paragraph: RevisionParagraphDraft,
        maxParagraphChars: Int,
    ): List<RevisionParagraphDraft> {
        val text = paragraph.text.trim()
        if (text.length <= maxParagraphChars) {
            return if (text == paragraph.text) listOf(paragraph) else listOf(paragraph.copy(text = text))
        }

        val sentenceUnits = splitSentenceUnits(text)
        val chunks = mutableListOf<String>()
        var current = ""

        fun flushCurrent() {
            if (current.isNotBlank()) chunks += current.trim()
            current = ""
        }

        for (unit in sentenceUnits) {
            if (unit.length > maxParagraphChars) {
                flushCurrent()
                chunks += hardSplit(unit, maxParagraphChars)
                continue
            }
            if (current.isEmpty()) {
                current = unit
            } else if (current.length + unit.length <= maxParagraphChars) {
                current = joinForReading(current, unit)
            } else {
                flushCurrent()
                current = unit
            }
        }
        flushCurrent()

        if (chunks.size <= 1) return listOf(paragraph)
        return chunks.map { chunk ->
            paragraph.copy(
                text = chunk,
                timingQuality = RevisionTimingQuality.APPROXIMATE,
                isUserModified = true,
            )
        }
    }

    private fun splitSentenceUnits(text: String): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        text.forEachIndexed { index, char ->
            if (char in SENTENCE_SPLIT_PUNCTUATION) {
                val unit = text.substring(start, index + 1).trim()
                if (unit.isNotEmpty()) result += unit
                start = index + 1
            }
        }
        if (start < text.length) {
            val tail = text.substring(start).trim()
            if (tail.isNotEmpty()) result += tail
        }
        return result.ifEmpty { listOf(text) }
    }

    private fun hardSplit(
        text: String,
        maxParagraphChars: Int,
    ): List<String> {
        val result = mutableListOf<String>()
        var remaining = text.trim()
        while (remaining.length > maxParagraphChars) {
            val minimumPreferred = maxParagraphChars / 2
            val preferred =
                (maxParagraphChars downTo minimumPreferred)
                    .firstOrNull { index ->
                        remaining.getOrNull(index - 1) in SECONDARY_SPLIT_PUNCTUATION
                    }
                    ?: maxParagraphChars
            result += remaining.substring(0, preferred).trim()
            remaining = remaining.substring(preferred).trim()
        }
        if (remaining.isNotEmpty()) result += remaining
        return result
    }

    private fun sameParagraphs(
        left: List<RevisionParagraphDraft>,
        right: List<RevisionParagraphDraft>,
    ): Boolean =
        left.size == right.size && left.indices.all { index -> left[index] == right[index] }

    private fun joinForReading(
        left: String,
        right: String,
    ): String {
        val a = left.trimEnd()
        val b = right.trimStart()
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a

        val leftChar = a.last()
        val rightChar = b.first()
        val separator =
            if (
                leftChar.isWhitespace() ||
                rightChar.isWhitespace() ||
                isCjk(leftChar) ||
                isCjk(rightChar) ||
                rightChar in NO_LEADING_SPACE_PUNCTUATION ||
                leftChar in OPENING_PUNCTUATION
            ) {
                ""
            } else {
                " "
            }
        return a + separator + b
    }

    private fun isCjk(char: Char): Boolean {
        val code = char.code
        return code in 0x3400..0x4DBF ||
            code in 0x4E00..0x9FFF ||
            code in 0xF900..0xFAFF
    }

    private val PARAGRAPH_BOUNDARY_PUNCTUATION =
        setOf('。', '！', '？', '.', '!', '?', '；', ';')
    private val SENTENCE_SPLIT_PUNCTUATION =
        setOf('。', '！', '？', '.', '!', '?', '；', ';')
    private val SECONDARY_SPLIT_PUNCTUATION =
        setOf('，', ',', '、', '：', ':')
    private val NO_LEADING_SPACE_PUNCTUATION =
        setOf('，', '。', '！', '？', '；', '：', '、', ',', '.', '!', '?', ';', ':', ')', ']', '}')
    private val OPENING_PUNCTUATION =
        setOf('（', '【', '《', '“', '‘', '(', '[', '{')
}
