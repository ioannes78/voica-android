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
        val merged =
            RevisionParagraphDraft(
                text = joinForReading(first.text, second.text),
                sourceAnchorRefs = (first.sourceAnchorRefs + second.sourceAnchorRefs).distinct(),
                anchorStartSampleIndex = first.anchorStartSampleIndex ?: second.anchorStartSampleIndex,
                anchorEndSampleIndexExclusive =
                    second.anchorEndSampleIndexExclusive ?: first.anchorEndSampleIndexExclusive,
                speakerId = first.speakerId.takeIf { it != null && it == second.speakerId },
                timingQuality = RevisionTimingQuality.ANCHORED,
                isUserModified = true,
            )
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

    fun arrangeForReading(
        paragraphs: List<RevisionParagraphDraft>,
        maxParagraphChars: Int = 220,
    ): List<RevisionParagraphDraft> {
        require(maxParagraphChars >= 40)
        if (paragraphs.size < 2) return paragraphs
        var result = paragraphs
        var index = 0
        while (index < result.lastIndex) {
            val current = result[index]
            val next = result[index + 1]
            val sameSpeaker = current.speakerId == next.speakerId
            val combinedLength =
                current.text.trim().length + next.text.trim().length
            val sentenceContinues =
                current.text.trimEnd().lastOrNull() !in TERMINAL_PUNCTUATION
            if (sameSpeaker && sentenceContinues && combinedLength <= maxParagraphChars) {
                result = mergeWithNext(result, index)
            } else {
                index += 1
            }
        }
        return result
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

    private val TERMINAL_PUNCTUATION =
        setOf('。', '！', '？', '.', '!', '?')
    private val NO_LEADING_SPACE_PUNCTUATION =
        setOf('，', '。', '！', '？', '；', '：', '、', ',', '.', '!', '?', ';', ':', ')', ']', '}')
    private val OPENING_PUNCTUATION =
        setOf('（', '【', '《', '“', '‘', '(', '[', '{')
}
