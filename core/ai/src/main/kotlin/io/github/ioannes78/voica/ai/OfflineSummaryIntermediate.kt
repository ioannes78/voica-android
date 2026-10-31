package io.github.ioannes78.voica.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

enum class OfflineSummaryIntermediateErrorCode {
    INVALID_JSON,
    TRUNCATED_JSON,
    MISSING_FIELD,
    WRONG_FIELD_TYPE,
    INVALID_ENUM,
    INVALID_VALUE,
    INVALID_SYNOPSIS,
    INSUFFICIENT_SOURCE_COVERAGE,
    INVALID_SOURCE_REF,
    INVALID_EVIDENCE_REF,
    DUPLICATE_ID,
}

class OfflineSummaryIntermediateException(
    val code: OfflineSummaryIntermediateErrorCode,
    message: String,
) : IllegalArgumentException(message)

object OfflineSummaryIntermediateResponseFormat {
    private const val MAX_ITEM_ID_CHARS = 16

    fun jsonSchema(
        allowedSourceRefs: Set<String>,
        allowedEvidenceRefs: Set<String>,
    ): String {
        require(allowedSourceRefs.isNotEmpty())
        require(allowedSourceRefs.all { SOURCE_REF_PATTERN.matches(it) })
        require(allowedEvidenceRefs.all { EVIDENCE_REF_PATTERN.matches(it) })

        val itemTypes = enumJson(enumValues<OfflineSummaryIntermediateItemType>().map { it.name })
        val epistemicStatuses = enumJson(enumValues<AiEpistemicStatus>().map { it.name })
        val sourceItems =
            "\"minItems\":1,\"maxItems\":${OfflineSummaryLongContract.MAX_SOURCE_REFS_PER_ITEM}," +
                "\"items\":{\"type\":\"string\",\"enum\":${enumJson(allowedSourceRefs.toList())}}"
        val evidenceItems =
            if (allowedEvidenceRefs.isEmpty()) {
                "\"maxItems\":0,\"items\":{\"type\":\"string\"}"
            } else {
                "\"maxItems\":${OfflineSummaryLongContract.MAX_EVIDENCE_REFS_PER_ITEM}," +
                    "\"items\":{\"type\":\"string\",\"enum\":${enumJson(allowedEvidenceRefs.toList())}}"
            }

        return """
            {
              "x-guidance":{"whitespace_flexible":false},
              "type":"object",
              "additionalProperties":false,
              "properties":{
                "schemaVersion":{"type":"integer","const":${OfflineSummaryLongContract.INTERMEDIATE_SCHEMA_VERSION}},
                "synopsis":{"type":"string","minLength":1,"maxLength":${OfflineSummaryLongContract.MAX_INTERMEDIATE_SYNOPSIS_CHARS}},
                "items":{
                  "type":"array",
                  "minItems":1,
                  "maxItems":${OfflineSummaryLongContract.MAX_INTERMEDIATE_ITEMS},
                  "items":{
                    "type":"object",
                    "additionalProperties":false,
                    "properties":{
                      "id":{"type":"string","minLength":1,"maxLength":$MAX_ITEM_ID_CHARS},
                      "type":{"type":"string","enum":$itemTypes},
                      "text":{"type":"string","minLength":1,"maxLength":${OfflineSummaryLongContract.MAX_INTERMEDIATE_ITEM_TEXT_CHARS}},
                      "sourceRefs":{"type":"array",$sourceItems},
                      "evidenceRefs":{"type":"array",$evidenceItems},
                      "epistemicStatus":{"type":"string","enum":$epistemicStatuses}
                    },
                    "required":["id","type","text","sourceRefs","evidenceRefs","epistemicStatus"]
                  }
                }
              },
              "required":["schemaVersion","synopsis","items"]
            }
        """.trimIndent()
    }

    private fun enumJson(values: List<String>): String =
        values.joinToString(prefix = "[\"", separator = "\",\"", postfix = "\"]")

    private val SOURCE_REF_PATTERN = Regex("""U\d{5}(?::F\d{3,})?""")
    private val EVIDENCE_REF_PATTERN = Regex("""S\d{5,}""")
}

/**
 * Marks source refs that belong to a live MAP/REDUCE generation request.
 *
 * Distributed beginning/middle/end coverage is a generation-time quality gate. Durable Room
 * checkpoint reads intentionally use ordinary sets: they revalidate schema and lineage membership
 * without incorrectly treating refs compressed out of a child payload as model-visible input.
 */
internal class OfflineSummaryGenerationSourceRefs(values: Iterable<String>) : LinkedHashSet<String>() {
    init {
        values.forEach(::add)
    }
}

internal fun offlineSummaryGenerationSourceRefs(values: Iterable<String>): Set<String> =
    OfflineSummaryGenerationSourceRefs(values)

object OfflineSummaryIntermediateCodec {
    private const val MAX_ITEM_ID_CHARS = 16
    private const val MIN_NATURAL_SYNOPSIS_CHARS = 4
    private const val DISTRIBUTED_COVERAGE_MIN_SOURCE_REFS = 6
    private const val DISTRIBUTED_COVERAGE_BUCKETS = 3
    private val json = Json { ignoreUnknownKeys = false; isLenient = false }

    fun decode(
        raw: String,
        allowedSourceRefs: Set<String>,
        allowedEvidenceRefs: Set<String>,
    ): OfflineSummaryIntermediate =
        try {
            decodeValidated(
                normalized = SummaryResultCodec.normalizeJsonObject(raw),
                allowedSourceRefs = allowedSourceRefs,
                allowedEvidenceRefs = allowedEvidenceRefs,
            )
        } catch (known: OfflineSummaryIntermediateException) {
            throw known
        } catch (error: Throwable) {
            throw OfflineSummaryIntermediateException(
                OfflineSummaryIntermediateErrorCode.WRONG_FIELD_TYPE,
                error.message?.take(180) ?: "intermediate summary field has an invalid type",
            )
        }

    fun encode(value: OfflineSummaryIntermediate): String =
        buildJsonObject {
            put("schemaVersion", value.schemaVersion)
            put("synopsis", value.synopsis)
            put(
                "items",
                buildJsonArray {
                    value.items.forEach { item ->
                        add(
                            buildJsonObject {
                                put("id", item.id)
                                put("type", item.type.name)
                                put("text", item.text)
                                put(
                                    "sourceRefs",
                                    buildJsonArray {
                                        item.sourceRefs.forEach { add(JsonPrimitive(it)) }
                                    },
                                )
                                put(
                                    "evidenceRefs",
                                    buildJsonArray {
                                        item.evidenceRefs.forEach { add(JsonPrimitive(it)) }
                                    },
                                )
                                put("epistemicStatus", item.epistemicStatus.name)
                            },
                        )
                    }
                },
            )
        }.toString()

    private fun decodeValidated(
        normalized: String,
        allowedSourceRefs: Set<String>,
        allowedEvidenceRefs: Set<String>,
    ): OfflineSummaryIntermediate {
        if (normalized.isBlank()) {
            throw OfflineSummaryIntermediateException(
                OfflineSummaryIntermediateErrorCode.INVALID_VALUE,
                "intermediate summary is empty",
            )
        }
        val root =
            runCatching { json.parseToJsonElement(normalized).jsonObject }
                .getOrElse {
                    throw OfflineSummaryIntermediateException(
                        if (looksTruncatedJson(normalized)) {
                            OfflineSummaryIntermediateErrorCode.TRUNCATED_JSON
                        } else {
                            OfflineSummaryIntermediateErrorCode.INVALID_JSON
                        },
                        "intermediate summary is not a complete JSON object",
                    )
                }
        root.requireExactKeys(setOf("schemaVersion", "synopsis", "items"), "root")

        val schemaVersion = root.required("schemaVersion").jsonPrimitive.int
        if (schemaVersion != OfflineSummaryLongContract.INTERMEDIATE_SCHEMA_VERSION) {
            invalid("unsupported intermediate schemaVersion: $schemaVersion")
        }

        val synopsis = root.requiredString("synopsis").trim()
        requireTextBounds(
            value = synopsis,
            maxChars = OfflineSummaryLongContract.MAX_INTERMEDIATE_SYNOPSIS_CHARS,
            field = "synopsis",
        )
        if (synopsis.count { it.isLetterOrDigit() } < MIN_NATURAL_SYNOPSIS_CHARS) {
            throw OfflineSummaryIntermediateException(
                OfflineSummaryIntermediateErrorCode.INVALID_SYNOPSIS,
                "synopsis must contain natural-language content",
            )
        }

        val itemIds = mutableSetOf<String>()
        val itemElements = root.required("items").jsonArray
        if (itemElements.size !in 1..OfflineSummaryLongContract.MAX_INTERMEDIATE_ITEMS) {
            invalid("intermediate item count out of bounds")
        }
        val items =
            itemElements.map { element ->
                val item = element.jsonObject
                item.requireExactKeys(
                    setOf("id", "type", "text", "sourceRefs", "evidenceRefs", "epistemicStatus"),
                    "item",
                )
                val id = item.requiredString("id").trim()
                if (id.isBlank() || id.length > MAX_ITEM_ID_CHARS || !itemIds.add(id)) {
                    throw OfflineSummaryIntermediateException(
                        OfflineSummaryIntermediateErrorCode.DUPLICATE_ID,
                        "duplicate, blank or oversized intermediate item id",
                    )
                }
                val type = enumValue<OfflineSummaryIntermediateItemType>(item.requiredString("type"), "type")
                val text = item.requiredString("text").trim()
                requireTextBounds(
                    value = text,
                    maxChars = OfflineSummaryLongContract.MAX_INTERMEDIATE_ITEM_TEXT_CHARS,
                    field = "item.text",
                )
                val sourceRefs = item.required("sourceRefs").jsonArray.map { it.jsonPrimitive.content }
                if (
                    sourceRefs.isEmpty() ||
                    sourceRefs.size > OfflineSummaryLongContract.MAX_SOURCE_REFS_PER_ITEM ||
                    sourceRefs.size != sourceRefs.distinct().size
                ) {
                    invalid("sourceRefs must be non-empty, unique and bounded")
                }
                val invalidSourceRefs = sourceRefs.filterNot(allowedSourceRefs::contains)
                if (invalidSourceRefs.isNotEmpty()) {
                    throw OfflineSummaryIntermediateException(
                        OfflineSummaryIntermediateErrorCode.INVALID_SOURCE_REF,
                        "unknown source refs: ${invalidSourceRefs.joinToString()}",
                    )
                }
                val evidenceRefs = item.required("evidenceRefs").jsonArray.map { it.jsonPrimitive.content }
                if (
                    evidenceRefs.size > OfflineSummaryLongContract.MAX_EVIDENCE_REFS_PER_ITEM ||
                    evidenceRefs.size != evidenceRefs.distinct().size
                ) {
                    invalid("evidenceRefs must be unique and bounded")
                }
                val invalidEvidenceRefs = evidenceRefs.filterNot(allowedEvidenceRefs::contains)
                if (invalidEvidenceRefs.isNotEmpty()) {
                    throw OfflineSummaryIntermediateException(
                        OfflineSummaryIntermediateErrorCode.INVALID_EVIDENCE_REF,
                        "unknown evidence refs: ${invalidEvidenceRefs.joinToString()}",
                    )
                }
                val epistemicStatus =
                    enumValue<AiEpistemicStatus>(item.requiredString("epistemicStatus"), "epistemicStatus")

                OfflineSummaryIntermediateItem(
                    id = id,
                    type = type,
                    text = text,
                    sourceRefs = sourceRefs,
                    evidenceRefs = evidenceRefs,
                    epistemicStatus = epistemicStatus,
                )
            }

        if (allowedSourceRefs is OfflineSummaryGenerationSourceRefs) {
            requireDistributedSourceCoverage(items, allowedSourceRefs)
        }

        return OfflineSummaryIntermediate(
            schemaVersion = schemaVersion,
            synopsis = synopsis,
            items = items,
        )
    }

    private fun requireDistributedSourceCoverage(
        items: List<OfflineSummaryIntermediateItem>,
        allowedSourceRefs: Set<String>,
    ) {
        val orderedAllowed = allowedSourceRefs.toList()
        if (orderedAllowed.size < DISTRIBUTED_COVERAGE_MIN_SOURCE_REFS) return

        val covered = items.flatMapTo(LinkedHashSet()) { it.sourceRefs }
        repeat(DISTRIBUTED_COVERAGE_BUCKETS) { bucketIndex ->
            val start = orderedAllowed.size * bucketIndex / DISTRIBUTED_COVERAGE_BUCKETS
            val end = orderedAllowed.size * (bucketIndex + 1) / DISTRIBUTED_COVERAGE_BUCKETS
            val bucket = orderedAllowed.subList(start, end)
            if (bucket.none(covered::contains)) {
                throw OfflineSummaryIntermediateException(
                    OfflineSummaryIntermediateErrorCode.INSUFFICIENT_SOURCE_COVERAGE,
                    "intermediate sourceRefs must cover the beginning, middle and end of longer input",
                )
            }
        }
    }

    private fun requireTextBounds(
        value: String,
        maxChars: Int,
        field: String,
    ) {
        if (value.isBlank() || value.length > maxChars) invalid("$field out of bounds")
    }

    private fun JsonObject.required(key: String) =
        this[key]
            ?: throw OfflineSummaryIntermediateException(
                OfflineSummaryIntermediateErrorCode.MISSING_FIELD,
                "missing field: $key",
            )

    private fun JsonObject.requiredString(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull
            ?: throw OfflineSummaryIntermediateException(
                OfflineSummaryIntermediateErrorCode.MISSING_FIELD,
                "missing string field: $key",
            )

    private fun JsonObject.requireExactKeys(
        expected: Set<String>,
        path: String,
    ) {
        val missing = expected - keys
        if (missing.isNotEmpty()) {
            throw OfflineSummaryIntermediateException(
                OfflineSummaryIntermediateErrorCode.MISSING_FIELD,
                "$path missing fields: ${missing.joinToString()}",
            )
        }
        val extra = keys - expected
        if (extra.isNotEmpty()) invalid("$path contains unknown fields: ${extra.joinToString()}")
    }

    private inline fun <reified T : Enum<T>> enumValue(
        value: String,
        field: String,
    ): T =
        enumValues<T>().firstOrNull { it.name == value }
            ?: throw OfflineSummaryIntermediateException(
                OfflineSummaryIntermediateErrorCode.INVALID_ENUM,
                "invalid $field: $value",
            )

    private fun invalid(message: String): Nothing =
        throw OfflineSummaryIntermediateException(
            OfflineSummaryIntermediateErrorCode.INVALID_VALUE,
            message,
        )

    private fun looksTruncatedJson(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.startsWith("{") && !trimmed.endsWith("}")
    }
}
