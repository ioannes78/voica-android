package io.github.ioannes78.voica.ai

enum class AiInputMode {
    TRANSCRIPT_TEXT,
    DIRECT_AUDIO,
}

enum class AiSummaryMode {
    SMART,
    PRESET,
    CUSTOM,
}

enum class AiContentType {
    MEETING,
    INTERVIEW,
    CLASS_OR_TRAINING,
    WORK_REPORT,
    PROJECT_DISCUSSION,
    SALES_CONVERSATION,
    BRAINSTORM,
    PERSONAL_NOTE,
    GENERAL,
    OTHER,
}

enum class AiSummarySectionType {
    SUMMARY,
    KEY_POINT,
    DECISION,
    ACTION_ITEM,
    RISK,
    QUESTION,
    FACT,
    QA,
    KNOWLEDGE_POINT,
    FOLLOW_UP,
    CUSTOM,
}

enum class AiEpistemicStatus {
    TRANSCRIPT_STATED,
    AI_SYNTHESIS,
    UNCONFIRMED,
}

enum class AiGenerationStatus {
    CREATED,
    PREPARING,
    ANALYZING,
    PLANNING,
    MAPPING,
    REDUCING,
    VALIDATING,
    COMPLETED,
    FAILED,
    CANCELLED,
    INTERRUPTED,
}

enum class TranscriptEvidenceSourceKind {
    SPEAKER_SPAN,
    TRANSCRIPT_SEGMENT,
}

data class EvidenceSourceRef(
    val ref: String,
    val sourceKind: TranscriptEvidenceSourceKind,
    val sourceId: String,
    val speakerId: String?,
    val speakerDisplayName: String?,
    val assignmentQuality: String?,
    val overlap: Boolean,
    val ambiguous: Boolean,
    val startSampleIndex: Long,
    val endSampleIndexExclusive: Long,
) {
    init {
        require(REF_PATTERN.matches(ref))
        require(sourceId.isNotBlank())
        require(startSampleIndex >= 0L)
        require(endSampleIndexExclusive > startSampleIndex)
    }

    companion object {
        private val REF_PATTERN = Regex("""S\d{5,}""")
    }
}

data class StructuredTranscriptUnit(
    val evidence: EvidenceSourceRef?,
    val text: String,
    val detectedLanguage: String?,
) {
    init {
        require(text.isNotBlank())
    }
}

data class StructuredTranscriptInput(
    val recordingId: String,
    val transcriptionId: String,
    val transcriptionRevisionId: String? = null,
    val inputContentDigest: String = "0000000000000000000000000000000000000000000000000000000000000000",
    val transcriptionMode: String,
    val canonicalAssetId: String,
    val canonicalSha256: String,
    val canonicalProfileId: String,
    val totalSampleCount: Long,
    val languageConfig: String,
    val alignmentId: String?,
    val units: List<StructuredTranscriptUnit>,
    val inputMode: AiInputMode = AiInputMode.TRANSCRIPT_TEXT,
) {
    init {
        require(recordingId.isNotBlank())
        require(transcriptionId.isNotBlank())
        require(inputContentDigest.matches(Regex("^[0-9a-f]{64}$")))
        require(canonicalAssetId.isNotBlank())
        require(canonicalSha256.isNotBlank())
        require(canonicalProfileId.isNotBlank())
        require(totalSampleCount >= 0L)
        require(inputMode == AiInputMode.TRANSCRIPT_TEXT)
        require(units.isNotEmpty()) { "completed transcript must contain text for AI summary" }
        val anchored = units.mapNotNull { it.evidence }
        require(anchored.map { it.ref }.toSet().size == anchored.size) { "duplicate evidence ref" }
        anchored.forEach { evidence ->
            require(evidence.endSampleIndexExclusive <= totalSampleCount)
        }
        require(
            anchored.zipWithNext().all { (left, right) ->
                left.startSampleIndex <= right.startSampleIndex
            },
        )
    }

    val finalText: String
        get() = units.joinToString(separator = "\n") { it.text }
}

data class AiSummaryItem(
    val id: String,
    val text: String,
    val evidenceRefs: List<String>,
    val epistemicStatus: AiEpistemicStatus,
    val attributes: Map<String, String> = emptyMap(),
)

data class AiSummarySection(
    val id: String,
    val type: AiSummarySectionType,
    val label: String,
    val items: List<AiSummaryItem>,
)

data class AiSummaryResult(
    val schemaVersion: Int,
    val contentType: AiContentType,
    val classificationConfidence: Double?,
    val title: String,
    val overview: String,
    val sections: List<AiSummarySection>,
) {
    init {
        require(schemaVersion >= 1)
        require(classificationConfidence == null || classificationConfidence in 0.0..1.0)
    }
}
