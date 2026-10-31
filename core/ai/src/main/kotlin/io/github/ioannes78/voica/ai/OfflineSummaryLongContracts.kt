package io.github.ioannes78.voica.ai

object OfflineSummaryLongContract {
    const val PIPELINE_VERSION = 173004
    const val PLANNER_VERSION = 173001
    const val MAP_PROMPT_VERSION = 173103
    const val REDUCE_PROMPT_VERSION = 173204
    const val FINAL_PROMPT_VERSION = 173303
    const val INTERMEDIATE_SCHEMA_VERSION = 1

    const val PLANNER_TARGET_ESTIMATED_PROMPT_TOKENS = 2_048
    const val HARD_MAX_ESTIMATED_PROMPT_TOKENS = 2_528

    const val MAX_INTERMEDIATE_ITEMS = 6
    const val MAX_INTERMEDIATE_SYNOPSIS_CHARS = 96
    const val MAX_INTERMEDIATE_ITEM_TEXT_CHARS = 72
    const val MAX_SOURCE_REFS_PER_ITEM = 3
    const val MAX_EVIDENCE_REFS_PER_ITEM = 3
}

enum class OfflineSummaryRoute {
    DIRECT,
    LONG,
}

enum class OfflineSummaryLongNodeType {
    MAP,
    REDUCE,
    FINAL,
}

enum class OfflineSummaryLongNodeStatus {
    PENDING,
    RUNNING,
    VALIDATED,
    FAILED,
    INTERRUPTED,
}

enum class OfflineSummaryIntermediateItemType {
    KEY_POINT,
    DECISION,
    ACTION_ITEM,
    RISK,
    QUESTION,
    FACT,
    FOLLOW_UP,
    OTHER,
}

data class OfflineSummaryIntermediateItem(
    val id: String,
    val type: OfflineSummaryIntermediateItemType,
    val text: String,
    val sourceRefs: List<String>,
    val evidenceRefs: List<String>,
    val epistemicStatus: AiEpistemicStatus,
)

data class OfflineSummaryIntermediate(
    val schemaVersion: Int,
    val synopsis: String,
    val items: List<OfflineSummaryIntermediateItem>,
)

data class OfflineSummaryLongSourceFragment(
    val sourceRef: String,
    /** Zero-based ordinal in the frozen StructuredTranscriptInput. */
    val sourceUnitOrdinal: Int,
    /** Null when the original source unit did not need fallback splitting. */
    val fragmentOrdinal: Int?,
    val text: String,
    val evidenceRef: String?,
    val speakerDisplayName: String?,
    val detectedLanguage: String?,
) {
    init {
        require(SOURCE_REF_PATTERN.matches(sourceRef)) { "invalid long-summary source ref: $sourceRef" }
        require(sourceUnitOrdinal >= 0)
        require(fragmentOrdinal == null || fragmentOrdinal >= 0)
        require(text.isNotBlank())
        require(evidenceRef == null || EVIDENCE_REF_PATTERN.matches(evidenceRef))
        require(speakerDisplayName == null || speakerDisplayName.isNotBlank())
        require(detectedLanguage == null || detectedLanguage.isNotBlank())
    }

    private companion object {
        val SOURCE_REF_PATTERN = Regex("""U\d{5}(?::F\d{3,})?""")
        val EVIDENCE_REF_PATTERN = Regex("""S\d{5,}""")
    }
}

data class OfflineSummaryLongChunk(
    val index: Int,
    val sourceUnitStartOrdinal: Int,
    val sourceUnitEndOrdinalExclusive: Int,
    val sourceDigest: String,
    val fragments: List<OfflineSummaryLongSourceFragment>,
    val estimatedPromptTokens: Int,
) {
    init {
        require(index >= 0)
        require(sourceUnitStartOrdinal >= 0)
        require(sourceUnitEndOrdinalExclusive > sourceUnitStartOrdinal)
        require(sourceDigest.matches(Regex("^[0-9a-f]{64}$")))
        require(fragments.isNotEmpty())
        require(estimatedPromptTokens >= 0)
        require(fragments.all { it.sourceUnitOrdinal in sourceUnitStartOrdinal until sourceUnitEndOrdinalExclusive })
        require(fragments.zipWithNext().all { (left, right) ->
            left.sourceUnitOrdinal <= right.sourceUnitOrdinal
        })
    }

    val allowedSourceRefs: Set<String>
        get() = fragments.mapTo(LinkedHashSet()) { it.sourceRef }

    val allowedEvidenceRefs: Set<String>
        get() = fragments.mapNotNullTo(LinkedHashSet()) { it.evidenceRef }
}

data class OfflineSummaryLongPlan(
    val pipelineVersion: Int,
    val plannerVersion: Int,
    val inputContentDigest: String,
    val chunks: List<OfflineSummaryLongChunk>,
) {
    init {
        require(pipelineVersion == OfflineSummaryLongContract.PIPELINE_VERSION)
        require(plannerVersion == OfflineSummaryLongContract.PLANNER_VERSION)
        require(inputContentDigest.matches(Regex("^[0-9a-f]{64}$")))
        require(chunks.isNotEmpty())
        require(chunks.map { it.index } == chunks.indices.toList())
    }
}

data class OfflineSummaryRouteDecision(
    val route: OfflineSummaryRoute,
    val directBudget: OfflineSummaryBudgetAssessment,
) {
    init {
        require((route == OfflineSummaryRoute.DIRECT) == directBudget.allowed)
    }
}

data class RenderedOfflineSummaryLongPrompt(
    val promptVersion: Int,
    val resultSchemaVersion: Int,
    val text: String,
    val allowedSourceRefs: Set<String>,
    val allowedEvidenceRefs: Set<String>,
    val inputNodeCount: Int,
) {
    init {
        require(promptVersion > 0)
        require(resultSchemaVersion > 0)
        require(text.isNotBlank())
        require(inputNodeCount >= 0)
        require(allowedSourceRefs.all { it.matches(Regex("""U\d{5}(?::F\d{3,})?""")) })
        require(allowedEvidenceRefs.all { it.matches(Regex("""S\d{5,}""")) })
    }
}

data class OfflineSummaryValidatedIntermediateNode(
    val nodeId: String,
    val summary: OfflineSummaryIntermediate,
    val sourceRefs: Set<String>,
    val evidenceRefs: Set<String>,
) {
    init {
        require(nodeId.isNotBlank())
        require(sourceRefs.isNotEmpty())
        require(summary.items.flatMap { it.sourceRefs }.all(sourceRefs::contains))
        require(summary.items.flatMap { it.evidenceRefs }.all(evidenceRefs::contains))
    }
}

data class OfflineSummaryReduceGroup(
    val index: Int,
    val nodes: List<OfflineSummaryValidatedIntermediateNode>,
    val estimatedPromptTokens: Int,
) {
    init {
        require(index >= 0)
        require(nodes.isNotEmpty())
        require(estimatedPromptTokens >= 0)
    }
}

class OfflineSummaryLongPlanningException(message: String) : IllegalArgumentException(message)
