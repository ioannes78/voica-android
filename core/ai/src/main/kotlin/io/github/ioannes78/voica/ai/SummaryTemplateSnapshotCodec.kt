package io.github.ioannes78.voica.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

object SummaryTemplateSnapshotCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(template: SummaryTemplateSpec): String =
        buildJsonObject {
            put("schemaVersion", 1)
            template.id?.let { put("id", it) }
            put("name", template.name)
            put(
                "sections",
                buildJsonArray {
                    template.sections.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.name)) }
                },
            )
            template.focus?.let { put("focus", it) }
            template.userInstruction?.let { put("userInstruction", it) }
        }.toString()

    fun decode(raw: String): SummaryTemplateSpec {
        val root = json.parseToJsonElement(raw).jsonObject
        val version = root["schemaVersion"]?.jsonPrimitive?.content?.toIntOrNull()
        require(version == 1) { "unsupported template snapshot" }
        val sections =
            root["sections"]?.jsonArray
                ?.map { element ->
                    val value = element.jsonPrimitive.content
                    AiSummarySectionType.entries.firstOrNull { it.name == value }
                        ?: error("unknown summary section type")
                }
                .orEmpty()
        return SummaryTemplateSpec(
            id = root["id"]?.jsonPrimitive?.contentOrNull,
            name =
                root["name"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?: error("template snapshot name missing"),
            sections = sections,
            focus = root["focus"]?.jsonPrimitive?.contentOrNull,
            userInstruction = root["userInstruction"]?.jsonPrimitive?.contentOrNull,
        )
    }
}
