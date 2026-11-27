package io.github.ioannes78.voica.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineQaContractsTest {
    private val anchor = EvidenceSourceRef(
        "S00001", TranscriptEvidenceSourceKind.TRANSCRIPT_SEGMENT, "segment",
        "speaker", "Speaker", null, false, false, 0, 100,
    )
    private val input = StructuredTranscriptInput(
        recordingId = "recording", transcriptionId = "transcript",
        inputContentDigest = "a".repeat(64), transcriptionMode = "HIGH_QUALITY",
        canonicalAssetId = "asset", canonicalSha256 = "b".repeat(64),
        canonicalProfileId = "CANONICAL_PCM16_16000_MONO_WAV_V1",
        totalSampleCount = 200, languageConfig = "zh", alignmentId = "alignment",
        units = listOf(StructuredTranscriptUnit(anchor, "预算五十万元", "zh")),
    )

    @Test
    fun digestBindsSourceAndAnchorEvenWhenTextDigestIsUnchanged() {
        val original = OfflineQaSnapshot.from(input).source
        assertEquals(original, OfflineQaSnapshot.from(input.copy()).source)
        val changed = listOf(
            input.copy(recordingId = "another"),
            input.copy(transcriptionRevisionId = "revision"),
            input.copy(alignmentId = null),
            input.copy(canonicalAssetId = "new"),
            input.copy(canonicalSha256 = "c".repeat(64)),
            input.copy(units = listOf(input.units.single().copy(evidence = anchor.copy(endSampleIndexExclusive = 101)))),
            input.copy(units = listOf(input.units.single().copy(evidence = anchor.copy(speakerId = "another")))),
            input.copy(units = listOf(input.units.single().copy(text = "预算五百万元"))),
        )
        changed.forEach { assertNotEquals(original.snapshotDigest, OfflineQaSnapshot.from(it).source.snapshotDigest) }
    }

    @Test
    fun unanchoredRevisionIsNotCitableAndStillChangesDigest() {
        val revised = input.copy(transcriptionRevisionId = "revision")
        val extra = StructuredTranscriptUnit(null, "新增文字", "zh")
        val snapshot = OfflineQaSnapshot.from(revised.copy(units = revised.units + extra))
        assertEquals(1, snapshot.evidence.size)
        assertTrue(snapshot.evidence.single().isManualRevision)
        assertNotEquals(OfflineQaSnapshot.from(revised).source.snapshotDigest, snapshot.source.snapshotDigest)
    }

    @Test
    fun rejectsCrossSourceAndUnknownCandidates() {
        val snapshot = OfflineQaSnapshot.from(input)
        val request = OfflineQaRequest(snapshot.source, "预算？")
        reject { OfflineQaContext(request, OfflineQaSnapshot.from(input.copy(recordingId = "other")), listOf("S00001")) }
        reject { OfflineQaContext(request, snapshot, listOf("S00002")) }
        reject { OfflineQaContext(request, snapshot, listOf("S00001", "S00001")) }
    }

    @Test
    fun scopeAndQuestionLimitsAreEnforced() {
        val snapshot = OfflineQaSnapshot.from(input)
        reject { OfflineQaRequest(snapshot.source, " ") }
        reject { OfflineQaRequest(snapshot.source, "问".repeat(513)) }
        OfflineQaRequest(snapshot.source, "😀".repeat(512))
        reject { OfflineQaRequest(snapshot.source, "问", sampleRange = OfflineQaSampleRange(0, 201)) }
        reject { OfflineQaContext(OfflineQaRequest(snapshot.source, "问", speakerId = "other"), snapshot, listOf("S00001")) }
        reject { OfflineQaContext(OfflineQaRequest(snapshot.source, "问", sampleRange = OfflineQaSampleRange(100, 200)), snapshot, listOf("S00001")) }
    }

    @Test
    fun claimsMustUseRequestAllowlistAndRefusalHasNoClaims() {
        val snapshot = OfflineQaSnapshot.from(input)
        val request = OfflineQaRequest(snapshot.source, "预算？")
        val context = OfflineQaContext(request, snapshot, listOf("S00001"))
        val claim = OfflineQaClaim("五十万元", listOf("S00001"))
        assertEquals(1, OfflineQaResult(context, OfflineQaStatus.ANSWERED, listOf(claim)).claims.size)
        OfflineQaResult(context, OfflineQaStatus.PARTIAL, listOf(claim))
        OfflineQaResult(context, OfflineQaStatus.INSUFFICIENT_EVIDENCE, emptyList())
        reject { OfflineQaClaim("事实", emptyList()) }
        reject { OfflineQaClaim("事实", listOf("S00001", "S00001")) }
        reject { OfflineQaResult(context, OfflineQaStatus.ANSWERED, emptyList()) }
        reject { OfflineQaResult(context, OfflineQaStatus.INSUFFICIENT_EVIDENCE, listOf(claim)) }
        reject { OfflineQaResult(context, OfflineQaStatus.ANSWERED, listOf(OfflineQaClaim("事实", listOf("S00002")))) }
        reject { OfflineQaResult(OfflineQaContext(request, snapshot, emptyList()), OfflineQaStatus.ANSWERED, listOf(claim)) }
    }

    @Test
    fun mutableCallerListsCannotChangeValidatedClaimsOrSnapshot() {
        val units = input.units.toMutableList()
        val snapshot = OfflineQaSnapshot.from(input.copy(units = units))
        units.clear()
        assertEquals(1, snapshot.evidence.size)
        val refs = mutableListOf("S00001")
        val claim = OfflineQaClaim("事实", refs)
        refs.clear()
        assertEquals(listOf("S00001"), claim.evidenceIds)
        val context = OfflineQaContext(OfflineQaRequest(snapshot.source, "问"), snapshot, listOf("S00001"))
        val claims = mutableListOf(claim)
        val result = OfflineQaResult(context, OfflineQaStatus.ANSWERED, claims)
        claims.clear()
        assertEquals(1, result.claims.size)
    }

    private fun reject(block: () -> Unit) {
        assertTrue(runCatching(block).exceptionOrNull() is IllegalArgumentException)
    }
}
