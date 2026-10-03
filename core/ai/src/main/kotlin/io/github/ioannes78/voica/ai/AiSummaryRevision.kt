package io.github.ioannes78.voica.ai

import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

enum class AiSummaryRevisionProvenance {
    AI_ORIGINAL,
    USER_EDITED,
    USER_ADDED,
}

data class AiSummaryRevisionItem(
    val id: String,
    val text: String,
    val provenance: AiSummaryRevisionProvenance,
    val sourceItemId: String?,
    val sourceEvidenceRefs: List<String>,
)

data class AiSummaryRevisionSection(
    val id: String,
    val type: AiSummarySectionType,
    val label: String,
    val items: List<AiSummaryRevisionItem>,
)

data class AiSummaryRevisionDocument(
    val schemaVersion: Int = 1,
    val title: String,
    val overview: String,
    val sections: List<AiSummaryRevisionSection>,
)

object AiSummaryRevisionCodec {
    private val json = Json { ignoreUnknownKeys = false }

    fun fromOriginal(result: AiSummaryResult): AiSummaryRevisionDocument =
        AiSummaryRevisionDocument(
            title = result.title,
            overview = result.overview,
            sections =
                result.sections.map { section ->
                    AiSummaryRevisionSection(
                        id = section.id,
                        type = section.type,
                        label = section.label,
                        items =
                            section.items.map { item ->
                                AiSummaryRevisionItem(
                                    id = item.id,
                                    text = item.text,
                                    provenance = AiSummaryRevisionProvenance.AI_ORIGINAL,
                                    sourceItemId = item.id,
                                    sourceEvidenceRefs = item.evidenceRefs,
                                )
                            },
                    )
                },
        )

    fun encode(document: AiSummaryRevisionDocument): String =
        buildJsonObject {
            put("schemaVersion", document.schemaVersion)
            put("title", document.title)
            put("overview", document.overview)
            put(
                "sections",
                buildJsonArray {
                    document.sections.forEach { section ->
                        add(
                            buildJsonObject {
                                put("id", section.id)
                                put("type", section.type.name)
                                put("label", section.label)
                                put(
                                    "items",
                                    buildJsonArray {
                                        section.items.forEach { item ->
                                            add(
                                                buildJsonObject {
                                                    put("id", item.id)
                                                    put("text", item.text)
                                                    put("provenance", item.provenance.name)
                                                    if (item.sourceItemId != null) {
                                                        put("sourceItemId", item.sourceItemId)
                                                    } else {
                                                        put("sourceItemId", JsonNull)
                                                    }
                                                    put(
                                                        "sourceEvidenceRefs",
                                                        buildJsonArray {
                                                            item.sourceEvidenceRefs.forEach { ref ->
                                                                add(kotlinx.serialization.json.JsonPrimitive(ref))
                                                            }
                                                        },
                                                    )
                                                },
                                            )
                                        }
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }.toString()

    fun decode(raw: String): AiSummaryRevisionDocument {
        val root = json.parseToJsonElement(raw).jsonObject
        val schemaVersion = root.required("schemaVersion").jsonPrimitive.int
        require(schemaVersion == 1) { "unsupported AI summary revision schema: $schemaVersion" }
        val title = root.requiredString("title")
        val overview = root.requiredString("overview")
        val sectionIds = mutableSetOf<String>()
        val itemIds = mutableSetOf<String>()
        val sections =
            root.required("sections").jsonArray.map { element ->
                val value = element.jsonObject
                val id = value.requiredString("id")
                require(id.isNotBlank() && sectionIds.add(id))
                val type = AiSummarySectionType.valueOf(value.requiredString("type"))
                val label = value.requiredString("label")
                require(label.isNotBlank())
                val items =
                    value.required("items").jsonArray.map { itemElement ->
                        val item = itemElement.jsonObject
                        val itemId = item.requiredString("id")
                        require(itemId.isNotBlank() && itemIds.add(itemId))
                        val text = item.requiredString("text")
                        require(text.isNotBlank())
                        val provenance =
                            AiSummaryRevisionProvenance.valueOf(
                                item.requiredString("provenance"),
                            )
                        val sourceItemId =
                            item["sourceItemId"]
                                ?.takeUnless { it is JsonNull }
                                ?.jsonPrimitive
                                ?.contentOrNull
                        val sourceEvidenceRefs =
                            item.required("sourceEvidenceRefs").jsonArray
                                .map { it.jsonPrimitive.content }
                                .distinct()
                        if (provenance == AiSummaryRevisionProvenance.USER_ADDED) {
                            require(sourceItemId == null)
                            require(sourceEvidenceRefs.isEmpty())
                        }
                        AiSummaryRevisionItem(
                            id = itemId,
                            text = text,
                            provenance = provenance,
                            sourceItemId = sourceItemId,
                            sourceEvidenceRefs = sourceEvidenceRefs,
                        )
                    }
                AiSummaryRevisionSection(
                    id = id,
                    type = type,
                    label = label,
                    items = items,
                )
            }
        return AiSummaryRevisionDocument(
            schemaVersion = schemaVersion,
            title = title,
            overview = overview,
            sections = sections,
        )
    }

    private fun kotlinx.serialization.json.JsonObject.required(key: String) =
        this[key] ?: error("missing field: $key")

    private fun kotlinx.serialization.json.JsonObject.requiredString(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull ?: error("missing string field: $key")
}

object AiSummaryRevisionEditor {
    fun editTitle(
        source: AiSummaryRevisionDocument,
        value: String,
    ): AiSummaryRevisionDocument =
        source.copy(title = value.trim())

    fun editOverview(
        source: AiSummaryRevisionDocument,
        value: String,
    ): AiSummaryRevisionDocument =
        source.copy(overview = value.trim())

    fun editSectionLabel(
        source: AiSummaryRevisionDocument,
        sectionId: String,
        label: String,
    ): AiSummaryRevisionDocument =
        source.copy(
            sections =
                source.sections.map { section ->
                    if (section.id == sectionId) {
                        section.copy(label = label.trim().ifBlank { section.label })
                    } else {
                        section
                    }
                },
        )

    fun editItem(
        source: AiSummaryRevisionDocument,
        itemId: String,
        text: String,
    ): AiSummaryRevisionDocument =
        source.copy(
            sections =
                source.sections.map { section ->
                    section.copy(
                        items =
                            section.items.map { item ->
                                if (item.id != itemId) {
                                    item
                                } else {
                                    val normalized = text.trim()
                                    require(normalized.isNotEmpty())
                                    if (normalized == item.text) {
                                        item
                                    } else {
                                        item.copy(
                                            text = normalized,
                                            provenance =
                                                if (item.provenance == AiSummaryRevisionProvenance.USER_ADDED) {
                                                    AiSummaryRevisionProvenance.USER_ADDED
                                                } else {
                                                    AiSummaryRevisionProvenance.USER_EDITED
                                                },
                                        )
                                    }
                                }
                            },
                    )
                },
        )

    fun addItem(
        source: AiSummaryRevisionDocument,
        sectionId: String,
        text: String,
        idFactory: () -> String = { UUID.randomUUID().toString() },
    ): AiSummaryRevisionDocument {
        val normalized = text.trim()
        require(normalized.isNotEmpty())
        return source.copy(
            sections =
                source.sections.map { section ->
                    if (section.id != sectionId) {
                        section
                    } else {
                        section.copy(
                            items =
                                section.items +
                                    AiSummaryRevisionItem(
                                        id = idFactory(),
                                        text = normalized,
                                        provenance = AiSummaryRevisionProvenance.USER_ADDED,
                                        sourceItemId = null,
                                        sourceEvidenceRefs = emptyList(),
                                    ),
                        )
                    }
                },
        )
    }

    fun deleteItem(
        source: AiSummaryRevisionDocument,
        itemId: String,
    ): AiSummaryRevisionDocument =
        source.copy(
            sections =
                source.sections.map { section ->
                    section.copy(items = section.items.filterNot { it.id == itemId })
                },
        )

    fun addSection(
        source: AiSummaryRevisionDocument,
        label: String,
        type: AiSummarySectionType = AiSummarySectionType.CUSTOM,
        idFactory: () -> String = { UUID.randomUUID().toString() },
    ): AiSummaryRevisionDocument {
        val normalized = label.trim()
        require(normalized.isNotEmpty())
        return source.copy(
            sections =
                source.sections +
                    AiSummaryRevisionSection(
                        id = idFactory(),
                        type = type,
                        label = normalized,
                        items = emptyList(),
                    ),
        )
    }

    fun deleteSection(
        source: AiSummaryRevisionDocument,
        sectionId: String,
    ): AiSummaryRevisionDocument =
        source.copy(sections = source.sections.filterNot { it.id == sectionId })

    fun moveSection(
        source: AiSummaryRevisionDocument,
        sectionId: String,
        delta: Int,
    ): AiSummaryRevisionDocument {
        val from = source.sections.indexOfFirst { it.id == sectionId }
        if (from < 0) return source
        val to = (from + delta).coerceIn(source.sections.indices)
        if (from == to) return source
        val mutable = source.sections.toMutableList()
        val item = mutable.removeAt(from)
        mutable.add(to, item)
        return source.copy(sections = mutable)
    }
}
