package io.github.ioannes78.voica.ai

object SummaryDisplayFormatter {
    fun format(result: AiSummaryResult): String =
        buildString {
            if (result.title.isNotBlank()) {
                append(result.title.trim())
                append("\n\n")
            }
            if (result.overview.isNotBlank()) {
                append(result.overview.trim())
            }
            result.sections.forEach { section ->
                if (section.items.isEmpty()) return@forEach
                if (isNotEmpty()) append("\n\n")
                append(section.label.trim())
                append("\n")
                section.items.forEach { item ->
                    append("• ")
                    append(item.text.trim())
                    if (item.evidenceRefs.isNotEmpty()) {
                        append("  [")
                        append(item.evidenceRefs.joinToString(", "))
                        append("]")
                    }
                    append("\n")
                }
                while (endsWith("\n")) {
                    deleteCharAt(length - 1)
                }
            }
        }.trim()
}
