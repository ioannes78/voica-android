package io.github.ioannes78.voica.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class OfflineSummarySourceLineageSnapshot(
    val lineageVersion: Int,
    val schemaVersion: Int,
    val source: String,
    val recordingId: String,
    val transcriptionId: String,
    val transcriptionRevisionId: String?,
    val inputContentDigest: String,
    val alignmentId: String?,
    val canonicalAssetId: String,
    val canonicalSha256: String,
    val canonicalProfileId: String,
    val transcriptionMode: String,
    val languageConfig: String,
) {
    init {
        require(lineageVersion > 0)
        require(schemaVersion > 0)
        require(source.isNotBlank())
        require(recordingId.isNotBlank())
        require(transcriptionId.isNotBlank())
        require(inputContentDigest.matches(Regex("^[0-9a-f]{64}$")))
        require(canonicalAssetId.isNotBlank())
        require(canonicalSha256.isNotBlank())
        require(canonicalProfileId.isNotBlank())
        require(transcriptionMode.isNotBlank())
        require(languageConfig.isNotBlank())
    }
}

/** Stable source snapshot stored with every Stage 17 local summary generation. */
object OfflineSummarySourceLineageCodec {
    const val LINEAGE_VERSION: Int = 3
    const val SCHEMA_VERSION: Int = 1
    private const val SOURCE = "LOCAL_OFFLINE_SUMMARY"
    private val json = Json { ignoreUnknownKeys = false; isLenient = false }

    fun encode(input: StructuredTranscriptInput): String =
        buildJsonObject {
            put("lineageVersion", LINEAGE_VERSION)
            put("schemaVersion", SCHEMA_VERSION)
            put("source", SOURCE)
            put("recordingId", input.recordingId)
            put("transcriptionId", input.transcriptionId)
            if (input.transcriptionRevisionId != null) {
                put("transcriptionRevisionId", input.transcriptionRevisionId)
            } else {
                put("transcriptionRevisionId", JsonNull)
            }
            put("inputContentDigest", input.inputContentDigest)
            if (input.alignmentId != null) {
                put("alignmentId", input.alignmentId)
            } else {
                put("alignmentId", JsonNull)
            }
            put("canonicalAssetId", input.canonicalAssetId)
            put("canonicalSha256", input.canonicalSha256)
            put("canonicalProfileId", input.canonicalProfileId)
            put("transcriptionMode", input.transcriptionMode)
            put("languageConfig", input.languageConfig)
        }.toString()

    /**
     * Strictly decodes the immutable local-summary source lineage used by Stage 17.3 resume checks.
     * Historical/provider lineage is deliberately rejected rather than guessed into this shape.
     */
    fun decode(raw: String): OfflineSummarySourceLineageSnapshot {
        val root = json.parseToJsonElement(raw).jsonObject
        val expectedKeys =
            setOf(
                "lineageVersion",
                "schemaVersion",
                "source",
                "recordingId",
                "transcriptionId",
                "transcriptionRevisionId",
                "inputContentDigest",
                "alignmentId",
                "canonicalAssetId",
                "canonicalSha256",
                "canonicalProfileId",
                "transcriptionMode",
                "languageConfig",
            )
        require(root.keys == expectedKeys) { "unsupported local offline-summary lineage fields" }

        val lineageVersion = root.getValue("lineageVersion").jsonPrimitive.int
        val schemaVersion = root.getValue("schemaVersion").jsonPrimitive.int
        val source = root.getValue("source").jsonPrimitive.content
        require(lineageVersion == LINEAGE_VERSION) { "unsupported local summary lineageVersion" }
        require(schemaVersion == SCHEMA_VERSION) { "unsupported local summary lineage schemaVersion" }
        require(source == SOURCE) { "lineage does not belong to local offline summary" }

        return OfflineSummarySourceLineageSnapshot(
            lineageVersion = lineageVersion,
            schemaVersion = schemaVersion,
            source = source,
            recordingId = root.requiredString("recordingId"),
            transcriptionId = root.requiredString("transcriptionId"),
            transcriptionRevisionId = root.nullableString("transcriptionRevisionId"),
            inputContentDigest = root.requiredString("inputContentDigest"),
            alignmentId = root.nullableString("alignmentId"),
            canonicalAssetId = root.requiredString("canonicalAssetId"),
            canonicalSha256 = root.requiredString("canonicalSha256"),
            canonicalProfileId = root.requiredString("canonicalProfileId"),
            transcriptionMode = root.requiredString("transcriptionMode"),
            languageConfig = root.requiredString("languageConfig"),
        )
    }

    private fun kotlinx.serialization.json.JsonObject.requiredString(key: String): String =
        getValue(key).jsonPrimitive.content.also { require(it.isNotBlank()) { "$key must not be blank" } }

    private fun kotlinx.serialization.json.JsonObject.nullableString(key: String): String? =
        getValue(key).jsonPrimitive.contentOrNull?.also {
            require(it.isNotBlank()) { "$key must not be blank when present" }
        }
}
