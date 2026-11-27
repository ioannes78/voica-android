package io.github.ioannes78.voica.ai

/** Stable plain-text projection used by persistence/search/export layers, independent of Compose. */
object OfflineSummaryDisplayFormatter {
    fun format(result: AiSummaryResult): String {
        val blocks = mutableListOf<String>()
        result.title.trim().takeIf { it.isNotEmpty() }?.let(blocks::add)
        result.overview.trim().takeIf { it.isNotEmpty() }?.let(blocks::add)
        result.sections.forEach { section ->
            val lines = mutableListOf<String>()
            section.label.trim().takeIf { it.isNotEmpty() }?.let(lines::add)
            section.items.forEach { item ->
                item.text.trim().takeIf { it.isNotEmpty() }?.let { text ->
                    lines += "- $text"
                }
            }
            if (lines.isNotEmpty()) {
                blocks += lines.joinToString(separator = "\n")
            }
        }
        return blocks.joinToString(separator = "\n\n")
    }
}
