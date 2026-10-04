package io.github.ioannes78.voica.ui.ai

import io.github.ioannes78.voica.TextContentDocument
import io.github.ioannes78.voica.ai.AiSummaryRevisionDocument
import io.github.ioannes78.voica.ai.AiSummaryRevisionProvenance

object AiSummaryContentExportFormatter {
    fun build(
        recordingName: String,
        document: AiSummaryRevisionDocument,
        providerName: String?,
        modelName: String?,
    ): TextContentDocument {
        val title =
            document.title.takeIf { it.isNotBlank() }
                ?: recordingName.takeIf { it.isNotBlank() }
                ?: "Voica AI 总结"

        val plain =
            buildString {
                appendLine(title)
                providerLine(providerName, modelName)?.let {
                    appendLine(it)
                }
                if (document.overview.isNotBlank()) {
                    appendLine()
                    appendLine(document.overview.trim())
                }
                document.sections.forEach { section ->
                    if (section.items.isEmpty()) return@forEach
                    appendLine()
                    appendLine(section.label)
                    section.items.forEach { item ->
                        append("• ")
                        append(item.text.trim())
                        when (item.provenance) {
                            AiSummaryRevisionProvenance.USER_EDITED ->
                                append(" [人工修改]")
                            AiSummaryRevisionProvenance.USER_ADDED ->
                                append(" [人工新增]")
                            AiSummaryRevisionProvenance.AI_ORIGINAL -> Unit
                        }
                        appendLine()
                    }
                }
            }.trim()

        val markdown =
            buildString {
                append("# ")
                appendLine(escapeMarkdown(title))
                providerLine(providerName, modelName)?.let {
                    appendLine()
                    append("_")
                    append(escapeMarkdown(it))
                    appendLine("_")
                }
                if (document.overview.isNotBlank()) {
                    appendLine()
                    appendLine(document.overview.trim())
                }
                document.sections.forEach { section ->
                    if (section.items.isEmpty()) return@forEach
                    appendLine()
                    append("## ")
                    appendLine(escapeMarkdown(section.label))
                    appendLine()
                    section.items.forEach { item ->
                        append("- ")
                        append(item.text.trim())
                        when (item.provenance) {
                            AiSummaryRevisionProvenance.USER_EDITED ->
                                append(" [人工修改]")
                            AiSummaryRevisionProvenance.USER_ADDED ->
                                append(" [人工新增]")
                            AiSummaryRevisionProvenance.AI_ORIGINAL -> Unit
                        }
                        appendLine()
                    }
                }
            }.trim()

        return TextContentDocument(
            baseName = title,
            plainText = plain,
            markdownText = markdown,
        )
    }

    private fun providerLine(
        providerName: String?,
        modelName: String?,
    ): String? {
        val provider = providerName?.takeIf { it.isNotBlank() }
        val model = modelName?.takeIf { it.isNotBlank() }
        return when {
            provider != null && model != null -> "AI：$provider · $model"
            provider != null -> "AI：$provider"
            model != null -> "模型：$model"
            else -> null
        }
    }

    private fun escapeMarkdown(value: String): String =
        value.replace("\\", "\\\\").replace("*", "\\*").replace("_", "\\_")
}
