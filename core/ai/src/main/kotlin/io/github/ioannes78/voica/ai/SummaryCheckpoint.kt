package io.github.ioannes78.voica.ai

import java.security.MessageDigest

data class SummaryCheckpointRecord(
    val level: Int,
    val chunkIndex: Int,
    val sourceStartOrdinal: Int,
    val sourceEndOrdinalExclusive: Int,
    val startSampleIndex: Long,
    val endSampleIndexExclusive: Long,
    val inputDigest: String,
    val structuredResultJson: String,
) {
    init {
        require(level >= 0)
        require(chunkIndex >= 0)
        require(sourceStartOrdinal >= 0)
        require(sourceEndOrdinalExclusive > sourceStartOrdinal)
        require(startSampleIndex >= 0)
        require(endSampleIndexExclusive > startSampleIndex)
        require(inputDigest.matches(Regex("""[0-9a-f]{64}""")))
        require(structuredResultJson.isNotBlank())
    }
}

interface SummaryCheckpointStore {
    suspend fun load(
        level: Int,
        chunkIndex: Int,
        inputDigest: String,
    ): String?

    suspend fun save(record: SummaryCheckpointRecord)
}

object SummaryCheckpointDigest {
    fun compute(
        model: String,
        taskInstruction: String,
        payload: String,
    ): String {
        val data =
            buildString {
                append("schema=")
                append(SummaryPromptFactory.RESULT_SCHEMA_VERSION)
                append("\nprompt=")
                append(SummaryPromptFactory.PROMPT_VERSION)
                append("\nmodel=")
                append(model)
                append("\ntask=")
                append(taskInstruction)
                append("\npayload=")
                append(payload)
            }.toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256")
            .digest(data)
            .joinToString("") { "%02x".format(it) }
    }
}
