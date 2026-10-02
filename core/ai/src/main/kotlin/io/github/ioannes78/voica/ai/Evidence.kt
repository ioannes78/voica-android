package io.github.ioannes78.voica.ai

class EvidenceRefGenerator {
    fun generate(index: Int): String {
        require(index >= 0)
        return "S" + (index + 1).toString().padStart(5, '0')
    }

    fun assign(
        units: List<TranscriptEvidenceSeed>,
    ): List<EvidenceSourceRef> =
        units.mapIndexed { index, unit ->
            EvidenceSourceRef(
                ref = generate(index),
                sourceKind = unit.sourceKind,
                sourceId = unit.sourceId,
                speakerId = unit.speakerId,
                speakerDisplayName = unit.speakerDisplayName,
                startSampleIndex = unit.startSampleIndex,
                endSampleIndexExclusive = unit.endSampleIndexExclusive,
            )
        }
}

data class TranscriptEvidenceSeed(
    val sourceKind: TranscriptEvidenceSourceKind,
    val sourceId: String,
    val speakerId: String?,
    val speakerDisplayName: String?,
    val startSampleIndex: Long,
    val endSampleIndexExclusive: Long,
)

class EvidenceResolver(
    sources: List<EvidenceSourceRef>,
) {
    private val byRef = sources.associateBy { it.ref }

    init {
        require(byRef.size == sources.size) { "duplicate evidence ref" }
    }

    fun resolve(ref: String): EvidenceSourceRef? = byRef[ref]

    fun validate(refs: Collection<String>): EvidenceValidation {
        val valid = LinkedHashSet<String>()
        val invalid = LinkedHashSet<String>()
        refs.forEach { ref ->
            if (byRef.containsKey(ref)) valid += ref else invalid += ref
        }
        return EvidenceValidation(valid.toList(), invalid.toList())
    }

    fun propagateFromChildren(children: Collection<Collection<String>>): List<String> =
        children
            .asSequence()
            .flatten()
            .filter(byRef::containsKey)
            .distinct()
            .toList()
}

data class EvidenceValidation(
    val validRefs: List<String>,
    val invalidRefs: List<String>,
) {
    val isValid: Boolean
        get() = invalidRefs.isEmpty()
}
