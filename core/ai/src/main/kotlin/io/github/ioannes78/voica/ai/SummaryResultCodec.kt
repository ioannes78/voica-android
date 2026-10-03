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

enum class SummaryStructuredOutputErrorCode {
    INVALID_JSON,
    TRUNCATED_JSON,
    MISSING_FIELD,
    WRONG_FIELD_TYPE,
    INVALID_ENUM,
    INVALID_VALUE,
    INVALID_EVIDENCE_REF,
    MISSING_EVIDENCE,
    DUPLICATE_ID,
    EMPTY_FINAL_CONTENT,
}

class SummaryStructuredOutputException(
    val code: SummaryStructuredOutputErrorCode,
    message: String,
) : IllegalArgumentException(message) {
    constructor(message: String) : this(SummaryStructuredOutputErrorCode.INVALID_VALUE, message)
}

object SummaryResultCodec {
    private val json =
        Json {
            ignoreUnknownKeys = false
            isLenient = false
        }

    fun decode(
        raw: String,
        allowedEvidenceRefs: Set<String>,
    ): AiSummaryResult =
        try {
            decodeValidated(
                normalized = normalizeJsonObject(raw),
                allowedEvidenceRefs = allowedEvidenceRefs,
            )
        } catch (error: SummaryStructuredOutputException) {
            throw error
        } catch (error: Throwable) {
            throw SummaryStructuredOutputException(
                SummaryStructuredOutputErrorCode.WRONG_FIELD_TYPE,
                error.message?.take(180) ?: "summary field has an invalid type",
            )
        }

    private fun decodeValidated(
        normalized: String,
        allowedEvidenceRefs: Set<String>,
    ): AiSummaryResult {
        if (normalized.isBlank()) {
            throw SummaryStructuredOutputException(
                SummaryStructuredOutputErrorCode.EMPTY_FINAL_CONTENT,
                "summary output is empty",
            )
        }
        val root =
            runCatching { json.parseToJsonElement(normalized).jsonObject }
                .getOrElse {
                    throw SummaryStructuredOutputException(
                        if (looksTruncatedJson(normalized)) {
                            SummaryStructuredOutputErrorCode.TRUNCATED_JSON
                        } else {
                            SummaryStructuredOutputErrorCode.INVALID_JSON
                        },
                        "summary output is not a complete JSON object",
                    )
                }

        val schemaVersion = root.required("schemaVersion").jsonPrimitive.int
        if (schemaVersion != SummaryPromptFactory.RESULT_SCHEMA_VERSION) {
            throw SummaryStructuredOutputException(
                SummaryStructuredOutputErrorCode.INVALID_VALUE,
                "unsupported summary schemaVersion: $schemaVersion",
            )
        }

        val contentType =
            enumValue<AiContentType>(root.requiredString("contentType"), "contentType")
        val confidence =
            root["classificationConfidence"]
                ?.takeUnless { it is JsonNull }
                ?.jsonPrimitive
                ?.doubleOrNull
                ?.also {
                    if (it !in 0.0..1.0) {
                        throw SummaryStructuredOutputException(
                            SummaryStructuredOutputErrorCode.INVALID_VALUE,
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
                    throw SummaryStructuredOutputException(
                        SummaryStructuredOutputErrorCode.DUPLICATE_ID,
                        "duplicate or blank section id",
                    )
                }
                val type =
                    enumValue<AiSummarySectionType>(
                        section.requiredString("type"),
                        "section type",
                    )
                val label = section.requiredString("label").trim()
                if (label.isBlank()) {
                    throw SummaryStructuredOutputException(
                        SummaryStructuredOutputErrorCode.INVALID_VALUE,
                        "blank section label",
                    )
                }
                val items =
                    section.required("items").jsonArray.map { itemElement ->
                        val item = itemElement.jsonObject
                        val itemId = item.requiredString("id").trim()
                        if (itemId.isBlank() || !itemIds.add(itemId)) {
                            throw SummaryStructuredOutputException(
                                SummaryStructuredOutputErrorCode.DUPLICATE_ID,
                                "duplicate or blank item id",
                            )
                        }
                        val text = item.requiredString("text").trim()
                        if (text.isBlank()) {
                            throw SummaryStructuredOutputException(
                                SummaryStructuredOutputErrorCode.INVALID_VALUE,
                                "blank summary item",
                            )
                        }
                        val evidenceRefs =
                            item.required("evidenceRefs").jsonArray
                                .map { it.jsonPrimitive.content }
                                .distinct()
                        val invalidRefs = evidenceRefs.filterNot(allowedEvidenceRefs::contains)
                        if (invalidRefs.isNotEmpty()) {
                            throw SummaryStructuredOutputException(
                                SummaryStructuredOutputErrorCode.INVALID_EVIDENCE_REF,
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
                                SummaryStructuredOutputErrorCode.MISSING_EVIDENCE,
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
        var trimmed = raw.trim().removePrefix("\uFEFF").trim()
        if (trimmed.startsWith("```")) {
            val firstLineEnd = trimmed.indexOf('\n')
            if (firstLineEnd >= 0) {
                val opening = trimmed.substring(0, firstLineEnd).trim()
                if (opening == "```" || opening.equals("```json", ignoreCase = true)) {
                    val remainder = trimmed.substring(firstLineEnd + 1).trim()
                    if (remainder.endsWith("```")) {
                        trimmed = remainder.removeSuffix("```").trim()
                    }
                }
            }
        }
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) return trimmed
        return extractSingleJsonObject(trimmed) ?: trimmed
    }

    private fun extractSingleJsonObject(value: String): String? {
        val start = value.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until value.length) {
            val ch = value[index]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return value.substring(start, index + 1)
                    }
                }
            }
        }
        return null
    }

    private fun looksTruncatedJson(value: String): Boolean {
        val trimmed = value.trim()
        if (!trimmed.startsWith("{")) return false
        return !trimmed.endsWith("}")
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
        this[key]
            ?: throw SummaryStructuredOutputException(
                SummaryStructuredOutputErrorCode.MISSING_FIELD,
                "missing field: $key",
            )

    private fun JsonObject.requiredString(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull
            ?: throw SummaryStructuredOutputException(
                SummaryStructuredOutputErrorCode.MISSING_FIELD,
                "missing string field: $key",
            )

    private inline fun <reified T : Enum<T>> enumValue(
        value: String,
        field: String,
    ): T =
        enumValues<T>().firstOrNull { it.name == value }
            ?: throw SummaryStructuredOutputException(
                SummaryStructuredOutputErrorCode.INVALID_ENUM,
                "invalid $field: $value",
            )
}
