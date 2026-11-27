package io.github.ioannes78.voica.ai

import java.io.DataOutputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Collections

/** Q&A-only lineage. Positional evidence refs are meaningful only within this snapshot. */
data class OfflineQaSourceIdentity(
    val recordingId: String,
    val transcriptionId: String,
    val revisionId: String?,
    val alignmentId: String?,
    val canonicalAssetId: String,
    val canonicalSha256: String,
    val canonicalProfileId: String,
    val totalSampleCount: Long,
    val snapshotDigest: String,
) {
    init {
        require(listOf(recordingId, transcriptionId, canonicalAssetId, canonicalProfileId).all { it.isNotBlank() })
        require(revisionId == null || revisionId.isNotBlank())
        require(alignmentId == null || alignmentId.isNotBlank())
        require(SHA256.matches(canonicalSha256))
        require(SHA256.matches(snapshotDigest))
        require(totalSampleCount >= 0)
    }

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

data class OfflineQaSampleRange(val start: Long, val endExclusive: Long) {
    init {
        require(start >= 0 && endExclusive > start)
    }
}

data class OfflineQaEvidence internal constructor(
    val source: OfflineQaSourceIdentity,
    val anchor: EvidenceSourceRef,
    val excerpt: String,
    val speakerId: String?,
    val speakerDisplayName: String?,
) {
    init {
        require(excerpt.isNotBlank())
        require(anchor.endSampleIndexExclusive <= source.totalSampleCount)
        require(anchor.speakerId == null || anchor.speakerId == speakerId)
        require(anchor.speakerDisplayName == null || anchor.speakerDisplayName == speakerDisplayName)
    }

    val isManualRevision: Boolean get() = source.revisionId != null
}

/** Immutable collection of anchored evidence. Unanchored revision text is hashed but not citable. */
class OfflineQaSnapshot private constructor(
    val source: OfflineQaSourceIdentity,
    evidence: List<OfflineQaEvidence>,
) {
    val evidence: List<OfflineQaEvidence> = immutableList(evidence)
    private val byRef = this.evidence.associateBy { it.anchor.ref }

    fun resolve(ref: String): OfflineQaEvidence? = byRef[ref]

    companion object {
        fun from(input: StructuredTranscriptInput): OfflineQaSnapshot {
            val units = input.units.toList()
            val frozen = input.copy(units = units)
            val source = OfflineQaSourceIdentity(
                recordingId = frozen.recordingId,
                transcriptionId = frozen.transcriptionId,
                revisionId = frozen.transcriptionRevisionId,
                alignmentId = frozen.alignmentId,
                canonicalAssetId = frozen.canonicalAssetId,
                canonicalSha256 = frozen.canonicalSha256,
                canonicalProfileId = frozen.canonicalProfileId,
                totalSampleCount = frozen.totalSampleCount,
                snapshotDigest = digest(frozen),
            )
            return OfflineQaSnapshot(source, units.mapNotNull { unit ->
                unit.evidence?.let { anchor ->
                    OfflineQaEvidence(source, anchor, unit.text, unit.effectiveSpeakerId, unit.effectiveSpeakerDisplayName)
                }
            })
        }

        /** Versioned, length-prefixed UTF-8 encoding; null differs from empty. */
        private fun digest(input: StructuredTranscriptInput): String {
            val hash = MessageDigest.getInstance("SHA-256")
            DataOutputStream(DigestOutputStream(object : OutputStream() {
                override fun write(b: Int) = Unit
                override fun write(b: ByteArray, off: Int, len: Int) = Unit
            }, hash)).use { out ->
                fun field(value: String?) {
                    if (value == null) {
                        out.writeInt(-1)
                    } else {
                        val bytes = value.toByteArray(Charsets.UTF_8)
                        out.writeInt(bytes.size)
                        out.write(bytes)
                    }
                }
                field("OFFLINE_QA_SNAPSHOT_V1")
                field(input.recordingId)
                field(input.transcriptionId)
                field(input.transcriptionRevisionId)
                field(input.alignmentId)
                field(input.canonicalAssetId)
                field(input.canonicalSha256)
                field(input.canonicalProfileId)
                out.writeLong(input.totalSampleCount)
                field(input.inputContentDigest)
                field(input.transcriptionMode)
                field(input.languageConfig)
                field(input.inputMode.name)
                out.writeInt(input.units.size)
                input.units.forEach { unit ->
                    field(unit.text)
                    field(unit.detectedLanguage)
                    field(unit.speakerId)
                    field(unit.speakerDisplayName)
                    val anchor = unit.evidence
                    out.writeBoolean(anchor != null)
                    if (anchor != null) {
                        field(anchor.ref)
                        field(anchor.sourceKind.name)
                        field(anchor.sourceId)
                        field(anchor.speakerId)
                        field(anchor.speakerDisplayName)
                        field(anchor.assignmentQuality)
                        out.writeBoolean(anchor.overlap)
                        out.writeBoolean(anchor.ambiguous)
                        out.writeLong(anchor.startSampleIndex)
                        out.writeLong(anchor.endSampleIndexExclusive)
                    }
                }
            }
            return hash.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}

data class OfflineQaRequest(
    val source: OfflineQaSourceIdentity,
    val question: String,
    val speakerId: String? = null,
    val sampleRange: OfflineQaSampleRange? = null,
) {
    init {
        require(question.isNotBlank() && question.codePointCount(0, question.length) <= MAX_QUESTION_CODE_POINTS)
        require(speakerId == null || speakerId.isNotBlank())
        require(sampleRange == null || sampleRange.endExclusive <= source.totalSampleCount)
    }

    companion object {
        const val MAX_QUESTION_CODE_POINTS = 512
    }
}

enum class OfflineQaStatus { ANSWERED, PARTIAL, INSUFFICIENT_EVIDENCE }

enum class OfflineQaFailureCode {
    SOURCE_CHANGED, SOURCE_UNAVAILABLE, INVALID_OUTPUT, INVALID_EVIDENCE,
    INPUT_TOO_LARGE, MODEL_NOT_INSTALLED, MODEL_NOT_READY, RESOURCE_BLOCKED,
    RUNTIME_BUSY, RUNTIME_POISONED, GENERATION_FAILED, CANCELLED,
}

/** Structural claim only: valid references do not establish semantic truth. */
class OfflineQaClaim(val text: String, evidenceIds: List<String>) {
    val evidenceIds: List<String> = immutableList(evidenceIds)

    init {
        require(text.isNotBlank())
        require(this.evidenceIds.isNotEmpty())
        require(this.evidenceIds.all { Regex("S[0-9]{5,}").matches(it) })
        require(this.evidenceIds.distinct().size == this.evidenceIds.size)
    }
}

/** Request-scoped allowlist, constructed from the actual selected snapshot evidence. */
class OfflineQaContext(
    val request: OfflineQaRequest,
    snapshot: OfflineQaSnapshot,
    candidateRefs: List<String>,
) {
    val candidates: List<OfflineQaEvidence>

    init {
        require(request.source == snapshot.source)
        require(candidateRefs.distinct().size == candidateRefs.size)
        candidates = immutableList(candidateRefs.map { ref ->
            val evidence = requireNotNull(snapshot.resolve(ref)) { "unknown candidate ref" }
            require(request.speakerId == null || evidence.speakerId == request.speakerId)
            val range = request.sampleRange
            require(range == null || (evidence.anchor.startSampleIndex < range.endExclusive &&
                evidence.anchor.endSampleIndexExclusive > range.start))
            evidence
        })
    }

    val allowedRefs: Set<String> get() = candidates.map { it.anchor.ref }.toSet()
}

/** Only structural validation is performed here; P4 adds output/semantic policy checks. */
class OfflineQaResult(
    val context: OfflineQaContext,
    val status: OfflineQaStatus,
    claims: List<OfflineQaClaim>,
) {
    val claims: List<OfflineQaClaim> = immutableList(claims)

    init {
        require((status == OfflineQaStatus.INSUFFICIENT_EVIDENCE) == this.claims.isEmpty())
        val allowed = context.allowedRefs
        require(this.claims.all { claim -> claim.evidenceIds.all { it in allowed } })
    }
}

private fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))
