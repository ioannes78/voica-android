package io.github.ioannes78.voica.ai

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class OfflineSummaryLongRouter(
    private val directRenderer: OfflineSummaryPromptRenderer = OfflineSummaryPromptRenderer(),
    private val directBudgetGate: OfflineSummaryDirectBudgetGate = OfflineSummaryDirectBudgetGate(),
) {
    fun decide(input: StructuredTranscriptInput): OfflineSummaryRouteDecision {
        val assessment = directBudgetGate.assess(directRenderer.render(input))
        return OfflineSummaryRouteDecision(
            route = if (assessment.allowed) OfflineSummaryRoute.DIRECT else OfflineSummaryRoute.LONG,
            directBudget = assessment,
        )
    }
}

class OfflineSummaryChunkPlanner(
    private val estimator: OfflineSummaryTokenEstimator = ConservativeOfflineSummaryTokenEstimator,
    private val mapPromptRenderer: OfflineSummaryMapPromptRenderer = OfflineSummaryMapPromptRenderer(),
    private val targetEstimatedPromptTokens: Int =
        OfflineSummaryLongContract.PLANNER_TARGET_ESTIMATED_PROMPT_TOKENS,
    private val hardMaxEstimatedPromptTokens: Int =
        OfflineSummaryLongContract.HARD_MAX_ESTIMATED_PROMPT_TOKENS,
) {
    init {
        require(targetEstimatedPromptTokens > 0)
        require(hardMaxEstimatedPromptTokens >= targetEstimatedPromptTokens)
        require(
            hardMaxEstimatedPromptTokens == OfflineSummaryDirectBudgetSpec().maxEstimatedPromptTokens,
        ) { "long-summary hard budget must match the frozen direct-summary prompt budget" }
    }

    fun plan(input: StructuredTranscriptInput): OfflineSummaryLongPlan {
        val expandedFragments =
            input.units.flatMapIndexed { unitOrdinal, unit ->
                expandUnit(unitOrdinal, unit)
            }
        if (expandedFragments.isEmpty()) {
            throw OfflineSummaryLongPlanningException("long-summary planner produced no source fragments")
        }
        validateSourceCoverage(input, expandedFragments)

        val chunks = mutableListOf<OfflineSummaryLongChunk>()
        var current = mutableListOf<OfflineSummaryLongSourceFragment>()
        expandedFragments.forEach { fragment ->
            if (current.isEmpty()) {
                current += fragment
            } else {
                val candidate = current + fragment
                if (estimate(candidate) <= targetEstimatedPromptTokens) {
                    current += fragment
                } else {
                    chunks += buildChunk(chunks.size, current)
                    current = mutableListOf(fragment)
                }
            }
        }
        if (current.isNotEmpty()) chunks += buildChunk(chunks.size, current)

        return OfflineSummaryLongPlan(
            pipelineVersion = OfflineSummaryLongContract.PIPELINE_VERSION,
            plannerVersion = OfflineSummaryLongContract.PLANNER_VERSION,
            inputContentDigest = input.inputContentDigest,
            chunks = chunks,
        )
    }

    private fun expandUnit(
        unitOrdinal: Int,
        unit: StructuredTranscriptUnit,
    ): List<OfflineSummaryLongSourceFragment> {
        val original = unit.text.trim()
        val unsplit = makeFragment(unitOrdinal, fragmentOrdinal = null, text = original, unit = unit)
        if (estimate(listOf(unsplit)) <= targetEstimatedPromptTokens) return listOf(unsplit)

        val textParts = splitTextToBudget(unitOrdinal, original, unit)
        if (textParts.size <= 1) {
            throw OfflineSummaryLongPlanningException(
                "source unit ${unitOrdinal + 1} cannot be split below the long-summary prompt budget",
            )
        }
        val fragments =
            textParts.mapIndexed { fragmentOrdinal, text ->
                makeFragment(
                    unitOrdinal = unitOrdinal,
                    fragmentOrdinal = fragmentOrdinal,
                    text = text,
                    unit = unit,
                )
            }
        if (fragments.joinToString(separator = "") { it.text } != original) {
            throw OfflineSummaryLongPlanningException("fallback split changed source text")
        }
        fragments.forEach { fragment ->
            val estimate = estimate(listOf(fragment))
            if (estimate > hardMaxEstimatedPromptTokens) {
                throw OfflineSummaryLongPlanningException(
                    "fragment ${fragment.sourceRef} exceeds hard prompt budget: $estimate",
                )
            }
        }
        return fragments
    }

    private fun splitTextToBudget(
        unitOrdinal: Int,
        text: String,
        unit: StructuredTranscriptUnit,
    ): List<String> {
        val semanticPieces = splitAtSemanticBoundaries(text)
        if (semanticPieces.size > 1) {
            val grouped = mutableListOf<String>()
            var current = ""
            semanticPieces.forEach { piece ->
                val candidate = current + piece
                if (current.isEmpty() || fitsStandalone(unitOrdinal, candidate, unit)) {
                    current = candidate
                } else {
                    grouped += current
                    current = ""
                    if (fitsStandalone(unitOrdinal, piece, unit)) {
                        current = piece
                    } else {
                        grouped += splitByCodePointBudget(unitOrdinal, piece, unit)
                    }
                }
            }
            if (current.isNotEmpty()) grouped += current
            if (grouped.size > 1 && grouped.all { fitsStandalone(unitOrdinal, it, unit) }) {
                return grouped
            }
        }
        return splitByCodePointBudget(unitOrdinal, text, unit)
    }

    private fun splitAtSemanticBoundaries(text: String): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        text.forEachIndexed { index, ch ->
            if (ch == '\n' || ch in SENTENCE_BOUNDARY_CHARS) {
                val end = index + 1
                if (end > start) result += text.substring(start, end)
                start = end
            }
        }
        if (start < text.length) result += text.substring(start)
        return result.filter { it.isNotEmpty() }
    }

    private fun splitByCodePointBudget(
        unitOrdinal: Int,
        text: String,
        unit: StructuredTranscriptUnit,
    ): List<String> {
        val codePoints = text.codePoints().toArray()
        val result = mutableListOf<String>()
        var start = 0
        while (start < codePoints.size) {
            var low = 1
            var high = codePoints.size - start
            var best = 0
            while (low <= high) {
                val mid = low + (high - low) / 2
                val candidate = codePointSlice(codePoints, start, start + mid)
                if (fitsStandalone(unitOrdinal, candidate, unit)) {
                    best = mid
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }
            if (best == 0) {
                throw OfflineSummaryLongPlanningException(
                    "MAP prompt overhead leaves no room for source unit ${unitOrdinal + 1}",
                )
            }
            result += codePointSlice(codePoints, start, start + best)
            start += best
        }
        return result
    }

    private fun fitsStandalone(
        unitOrdinal: Int,
        text: String,
        unit: StructuredTranscriptUnit,
    ): Boolean {
        val provisional =
            makeFragment(
                unitOrdinal = unitOrdinal,
                fragmentOrdinal = 998,
                text = text,
                unit = unit,
            )
        return estimate(listOf(provisional)) <= targetEstimatedPromptTokens
    }

    private fun makeFragment(
        unitOrdinal: Int,
        fragmentOrdinal: Int?,
        text: String,
        unit: StructuredTranscriptUnit,
    ): OfflineSummaryLongSourceFragment {
        val baseRef = "U" + (unitOrdinal + 1).toString().padStart(5, '0')
        val sourceRef =
            if (fragmentOrdinal == null) {
                baseRef
            } else {
                baseRef + ":F" + (fragmentOrdinal + 1).toString().padStart(3, '0')
            }
        return OfflineSummaryLongSourceFragment(
            sourceRef = sourceRef,
            sourceUnitOrdinal = unitOrdinal,
            fragmentOrdinal = fragmentOrdinal,
            text = text,
            evidenceRef = unit.evidence?.ref,
            speakerDisplayName = unit.effectiveSpeakerDisplayName,
            detectedLanguage = unit.detectedLanguage,
        )
    }

    private fun buildChunk(
        index: Int,
        fragments: List<OfflineSummaryLongSourceFragment>,
    ): OfflineSummaryLongChunk {
        val estimated = estimate(fragments)
        if (estimated > hardMaxEstimatedPromptTokens) {
            throw OfflineSummaryLongPlanningException(
                "chunk $index exceeds hard prompt budget: $estimated > $hardMaxEstimatedPromptTokens",
            )
        }
        val start = fragments.minOf { it.sourceUnitOrdinal }
        val endExclusive = fragments.maxOf { it.sourceUnitOrdinal } + 1
        return OfflineSummaryLongChunk(
            index = index,
            sourceUnitStartOrdinal = start,
            sourceUnitEndOrdinalExclusive = endExclusive,
            sourceDigest = digestFragments(fragments),
            fragments = fragments.toList(),
            estimatedPromptTokens = estimated,
        )
    }

    private fun estimate(fragments: List<OfflineSummaryLongSourceFragment>): Int =
        estimator.estimateTokens(mapPromptRenderer.render(fragments).text)

    private fun validateSourceCoverage(
        input: StructuredTranscriptInput,
        fragments: List<OfflineSummaryLongSourceFragment>,
    ) {
        val refs = fragments.map { it.sourceRef }
        if (refs.size != refs.toSet().size) {
            throw OfflineSummaryLongPlanningException("long-summary source refs are not unique")
        }
        input.units.forEachIndexed { ordinal, unit ->
            val reconstructed =
                fragments
                    .filter { it.sourceUnitOrdinal == ordinal }
                    .sortedBy { it.fragmentOrdinal ?: -1 }
                    .joinToString(separator = "") { it.text }
            if (reconstructed != unit.text.trim()) {
                throw OfflineSummaryLongPlanningException(
                    "source unit ${ordinal + 1} coverage is incomplete or duplicated",
                )
            }
        }
    }

    private fun digestFragments(fragments: List<OfflineSummaryLongSourceFragment>): String {
        val canonical =
            fragments.joinToString(separator = "\u001e") { fragment ->
                listOf(
                    fragment.sourceRef,
                    fragment.evidenceRef.orEmpty(),
                    fragment.speakerDisplayName.orEmpty(),
                    fragment.detectedLanguage.orEmpty(),
                    fragment.text,
                ).joinToString("\u001f")
            }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun codePointSlice(
        codePoints: IntArray,
        start: Int,
        endExclusive: Int,
    ): String =
        buildString {
            for (index in start until endExclusive) {
                append(String(Character.toChars(codePoints[index])))
            }
        }

    private companion object {
        val SENTENCE_BOUNDARY_CHARS = setOf('。', '！', '？', '!', '?', '；', ';')
    }
}

class OfflineSummaryReductionPlanner(
    private val estimator: OfflineSummaryTokenEstimator = ConservativeOfflineSummaryTokenEstimator,
    private val reducePromptRenderer: OfflineSummaryReducePromptRenderer = OfflineSummaryReducePromptRenderer(),
    private val targetEstimatedPromptTokens: Int =
        OfflineSummaryLongContract.PLANNER_TARGET_ESTIMATED_PROMPT_TOKENS,
    private val hardMaxEstimatedPromptTokens: Int =
        OfflineSummaryLongContract.HARD_MAX_ESTIMATED_PROMPT_TOKENS,
) {
    init {
        require(targetEstimatedPromptTokens > 0)
        require(hardMaxEstimatedPromptTokens >= targetEstimatedPromptTokens)
    }

    fun group(nodes: List<OfflineSummaryValidatedIntermediateNode>): List<OfflineSummaryReduceGroup> {
        require(nodes.isNotEmpty())
        val groups = mutableListOf<OfflineSummaryReduceGroup>()
        var current = mutableListOf<OfflineSummaryValidatedIntermediateNode>()
        nodes.forEach { node ->
            if (current.isEmpty()) {
                current += node
            } else {
                val candidate = current + node
                if (estimate(candidate) <= targetEstimatedPromptTokens) {
                    current += node
                } else {
                    groups += buildGroup(groups.size, current)
                    current = mutableListOf(node)
                }
            }
        }
        if (current.isNotEmpty()) groups += buildGroup(groups.size, current)
        return groups
    }

    private fun buildGroup(
        index: Int,
        nodes: List<OfflineSummaryValidatedIntermediateNode>,
    ): OfflineSummaryReduceGroup {
        val estimated = estimate(nodes)
        if (estimated > hardMaxEstimatedPromptTokens) {
            throw OfflineSummaryLongPlanningException(
                "reduce group $index exceeds hard prompt budget: $estimated > $hardMaxEstimatedPromptTokens",
            )
        }
        return OfflineSummaryReduceGroup(
            index = index,
            nodes = nodes.toList(),
            estimatedPromptTokens = estimated,
        )
    }

    private fun estimate(nodes: List<OfflineSummaryValidatedIntermediateNode>): Int =
        estimator.estimateTokens(reducePromptRenderer.render(nodes).text)
}
