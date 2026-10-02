package io.github.ioannes78.voica.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class SummaryStructuredOutputException(
    message: String,
) : IllegalArgumentException(message)

object SummaryResultCodec {
    private val json =
        Json {
            ignoreUnknownKeys = false
            isLenient = false
        }

    fun decode(
        raw: String,
        allowedEvidenceRefs: Set<String>,
    ): AiSummaryResult {
        val normalized = normalizeJsonObject(raw)
        val root =
            runCatching { json.parseToJsonElement(normalized).jsonObject }
                .getOrElse {
                    throw SummaryStructuredOutputException("summary output is not a JSON object")
                }

        val schemaVersion = root.required("schemaVersion").jsonPrimitive.int
        if (schemaVersion != SummaryPromptFactory.RESULT_SCHEMA_VERSION) {
            throw SummaryStructuredOutputException("unsupported summary schemaVersion")
        }

        val contentType =
            enumValue<AiContentType>(root.requiredString("contentType"), "contentType")
        val confidence =
            root["classificationConfidence"]
                ?.jsonPrimitive
                ?.doubleOrNull
                ?.also {
                    if (it !in 0.0..1.0) {
                        throw SummaryStructuredOutputException(
                            "classificationConfidence out of range",
                        )
                    }
                }
        val title = root.requiredString("title").trim()
        val overview = root.requiredString("overview").trim()
        val sectionIds = mutableSetOf<String>()
        val itemIds = mutableSetOf<String>()

        val sections =
            root.required("sections").jsonArray.map { sectionElement ->
                val section = sectionElement.jsonObject
                val id = section.requiredString("id").trim()
                if (id.isBlank() || !sectionIds.add(id)) {
                    throw SummaryStructuredOutputException("duplicate or blank section id")
                }
                val type =
                    enumValue<AiSummarySectionType>(
                        section.requiredString("type"),
                        "section type",
                    )
                val label = section.requiredString("label").trim()
                if (label.isBlank()) {
                    throw SummaryStructuredOutputException("blank section label")
                }
                val items =
                    section.required("items").jsonArray.map { itemElement ->
                        val item = itemElement.jsonObject
                        val itemId = item.requiredString("id").trim()
                        if (itemId.isBlank() || !itemIds.add(itemId)) {
                            throw SummaryStructuredOutputException("duplicate or blank item id")
                        }
                        val text = item.requiredString("text").trim()
                        if (text.isBlank()) {
                            throw SummaryStructuredOutputException("blank summary item")
                        }
                        val evidenceRefs =
                            item.required("evidenceRefs").jsonArray
                                .map { it.jsonPrimitive.content }
                                .distinct()
                        val invalidRefs = evidenceRefs.filterNot(allowedEvidenceRefs::contains)
                        if (invalidRefs.isNotEmpty()) {
                            throw SummaryStructuredOutputException(
                                "unknown evidence refs: " + invalidRefs.joinToString(),
                            )
                        }
                        val epistemicStatus =
                            enumValue<AiEpistemicStatus>(
                                item.requiredString("epistemicStatus"),
                                "epistemicStatus",
                            )
                        if (
                            epistemicStatus == AiEpistemicStatus.TRANSCRIPT_STATED &&
                            evidenceRefs.isEmpty()
                        ) {
                            throw SummaryStructuredOutputException(
                                "TRANSCRIPT_STATED item requires evidence",
                            )
                        }
                        val attributes =
                            item.required("attributes").jsonObject.mapValues { (_, value) ->
                                value.jsonPrimitive.content
                            }
                        AiSummaryItem(
                            id = itemId,
                            text = text,
                            evidenceRefs = evidenceRefs,
                            epistemicStatus = epistemicStatus,
                            attributes = attributes,
                        )
                    }
                AiSummarySection(
                    id = id,
                    type = type,
                    label = label,
                    items = items,
                )
            }

        return AiSummaryResult(
            schemaVersion = schemaVersion,
            contentType = contentType,
            classificationConfidence = confidence,
            title = title,
            overview = overview,
            sections = sections,
        )
    }

    internal fun normalizeJsonObject(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("```")) return trimmed

        val firstLineEnd = trimmed.indexOf('\n')
        if (firstLineEnd < 0) return trimmed
        val opening = trimmed.substring(0, firstLineEnd).trim()
        if (
            opening != "```" &&
            !opening.equals("```json", ignoreCase = true)
        ) {
            return trimmed
        }

        val remainder = trimmed.substring(firstLineEnd + 1).trim()
        if (!remainder.endsWith("```")) return trimmed
        return remainder.removeSuffix("```").trim()
    }

    fun encode(result: AiSummaryResult): String =
        buildJsonObject {
            put("schemaVersion", result.schemaVersion)
            put("contentType", result.contentType.name)
            result.classificationConfidence?.let {
                put("classificationConfidence", it)
            } ?: put("classificationConfidence", JsonNull)
            put("title", result.title)
            put("overview", result.overview)
            put(
                "sections",
                buildJsonArray {
                    result.sections.forEach { section ->
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
                                                    put(
                                                        "evidenceRefs",
                                                        buildJsonArray {
                                                            item.evidenceRefs.forEach {
                                                                add(JsonPrimitive(it))
                                                            }
                                                        },
                                                    )
                                                    put(
                                                        "epistemicStatus",
                                                        item.epistemicStatus.name,
                                                    )
                                                    put(
                                                        "attributes",
                                                        buildJsonObject {
                                                            item.attributes.forEach { (key, value) ->
                                                                put(key, value)
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

    fun evidenceRefs(result: AiSummaryResult): Set<String> =
        result.sections
            .asSequence()
            .flatMap { it.items.asSequence() }
            .flatMap { it.evidenceRefs.asSequence() }
            .toCollection(LinkedHashSet())

    private fun JsonObject.required(key: String) =
        this[key] ?: throw SummaryStructuredOutputException("missing field: $key")

    private fun JsonObject.requiredString(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull
            ?: throw SummaryStructuredOutputException("missing string field: $key")

    private inline fun <reified T : Enum<T>> enumValue(
        value: String,
        field: String,
    ): T =
        enumValues<T>().firstOrNull { it.name == value }
            ?: throw SummaryStructuredOutputException("invalid $field: $value")
}
