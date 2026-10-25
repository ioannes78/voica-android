package io.github.ioannes78.voica.ai

/** Product-level failures for the Stage 17.2 offline summary path. */
enum class OfflineSummaryFailureCode {
    MODEL_NOT_INSTALLED,
    MODEL_NOT_READY,
    INPUT_NOT_AVAILABLE,
    INPUT_TOO_LARGE_FOR_DIRECT_SUMMARY,
    RESOURCE_BLOCKED,
    RUNTIME_BUSY,
    ENGINE_INIT_FAILED,
    GENERATION_FAILED,
    GENERATION_CANCELLED,
    OUTPUT_TRUNCATED,
    OUTPUT_INVALID_JSON,
    OUTPUT_SCHEMA_INVALID,
    OUTPUT_INVALID_EVIDENCE,
    REPAIR_FAILED,
    INTERRUPTED,
    UNKNOWN,
}

/** Frozen Stage 17.2 prompt/result contract identifiers. */
object OfflineSummaryPromptContract {
    const val PROMPT_VERSION: Int = 170201
    const val DEFAULT_OUTPUT_LANGUAGE_TAG: String = "zh-CN"

    /** Keep this coupled to the existing strict result codec rather than creating a second schema. */
    val RESULT_SCHEMA_VERSION: Int = SummaryPromptFactory.RESULT_SCHEMA_VERSION
}

data class RenderedOfflineSummaryPrompt(
    val promptVersion: Int,
    val resultSchemaVersion: Int,
    val text: String,
    val allowedEvidenceRefs: Set<String>,
    val sourceUnitCount: Int,
) {
    init {
        require(promptVersion > 0)
        require(resultSchemaVersion > 0)
        require(text.isNotBlank())
        require(sourceUnitCount > 0)
        require(allowedEvidenceRefs.all(EVIDENCE_REF_PATTERN::matches))
    }

    private companion object {
        val EVIDENCE_REF_PATTERN = Regex("""S\d{5,}""")
    }
}
