package io.github.ioannes78.voica.ai

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Stable source snapshot stored with every Stage 17.2 local summary generation. */
object OfflineSummarySourceLineageCodec {
    const val LINEAGE_VERSION: Int = 3
    const val SCHEMA_VERSION: Int = 1

    fun encode(input: StructuredTranscriptInput): String =
        buildJsonObject {
            put("lineageVersion", LINEAGE_VERSION)
            put("schemaVersion", SCHEMA_VERSION)
            put("source", "LOCAL_OFFLINE_SUMMARY")
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
}
