package io.github.ioannes78.voica.ui.transcript

import io.github.ioannes78.voica.TextContentDocument
import java.util.Locale

object TranscriptContentExportFormatter {
    fun build(
        recordingName: String,
        versionName: String?,
        paragraphs: List<TranscriptReadingParagraph>,
        includeSpeaker: Boolean,
        includeTime: Boolean,
    ): TextContentDocument {
        val title =
            buildString {
                append(recordingName.ifBlank { "Voica 转写" })
                versionName?.takeIf { it.isNotBlank() }?.let {
                    append(" · ")
                    append(it)
                }
            }
        val plain =
            buildString {
                appendLine(title)
                appendLine()
                paragraphs.forEachIndexed { index, paragraph ->
                    val prefix =
                        buildList {
                            if (includeTime) {
                                paragraph.anchorStartSampleIndex?.let {
                                    add(formatSampleTime(it))
                                }
                            }
                            if (includeSpeaker) {
                                paragraph.speakerDisplayName
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let(::add)
                            }
                        }.joinToString(" · ")
                    if (prefix.isNotBlank()) {
                        appendLine(prefix)
                    }
                    append(paragraph.text.trim())
                    if (index != paragraphs.lastIndex) {
                        appendLine()
                        appendLine()
                    }
                }
            }.trim()

        val markdown =
            buildString {
                append("# ")
                appendLine(escapeMarkdown(title))
                appendLine()
                paragraphs.forEach { paragraph ->
                    val meta =
                        buildList {
                            if (includeTime) {
                                paragraph.anchorStartSampleIndex?.let {
                                    add(formatSampleTime(it))
                                }
                            }
                            if (includeSpeaker) {
                                paragraph.speakerDisplayName
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let(::add)
                            }
                        }.joinToString(" · ")
                    if (meta.isNotBlank()) {
                        append("**")
                        append(escapeMarkdown(meta))
                        appendLine("**")
                        appendLine()
                    }
                    appendLine(paragraph.text.trim())
                    appendLine()
                }
            }.trim()

        return TextContentDocument(
            baseName = title,
            plainText = plain,
            markdownText = markdown,
        )
    }

    private fun formatSampleTime(sampleIndex: Long): String {
        val totalSeconds = sampleIndex.coerceAtLeast(0L) / 16_000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    private fun escapeMarkdown(value: String): String =
        value.replace("\\", "\\\\").replace("*", "\\*").replace("_", "\\_")
}
