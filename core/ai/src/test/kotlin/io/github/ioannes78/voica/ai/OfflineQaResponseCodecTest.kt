package io.github.ioannes78.voica.ai

import org.junit.Assert.*
import org.junit.Test

class OfflineQaResponseCodecTest {
    private val snapshot = OfflineQaSnapshot.from(
        StructuredTranscriptInput(
            recordingId = "r", transcriptionId = "t", inputContentDigest = "a".repeat(64),
            transcriptionMode = "HIGH_QUALITY", canonicalAssetId = "asset", canonicalSha256 = "b".repeat(64),
            canonicalProfileId = "CANONICAL_PCM16_16000_MONO_WAV_V1", totalSampleCount = 200,
            languageConfig = "zh", alignmentId = null,
            units = listOf("预算50万元，交付日期11月25日", "设备200台").mapIndexed { i, text ->
                StructuredTranscriptUnit(EvidenceSourceRef("S0000" + (i + 1),
                    TranscriptEvidenceSourceKind.TRANSCRIPT_SEGMENT, "seg-$i", null, null, null,
                    false, false, i * 100L, (i + 1) * 100L), text, "zh")
            },
        ),
    )
    private val context = OfflineQaContext(OfflineQaRequest(snapshot.source, "预算？"), snapshot, listOf("S00001"))
    private fun response(text: String = "预算50万元", refs: String = "[\"S00001\"]", status: String = "ANSWERED") =
        """{"schemaVersion":1,"status":"$status","claims":[{"text":"$text","evidenceIds":$refs}]}"""
    private fun reject(raw: String, code: OfflineQaOutputError) {
        val error = runCatching { OfflineQaResponseCodec.decode(raw, context) }.exceptionOrNull()
        assertTrue(error is OfflineQaOutputException)
        assertEquals(code, (error as OfflineQaOutputException).code)
    }

    @Test fun acceptsAnswerPartialAndRefusal() {
        assertEquals(OfflineQaStatus.ANSWERED, OfflineQaResponseCodec.decode(response(), context).status)
        assertEquals(OfflineQaStatus.PARTIAL, OfflineQaResponseCodec.decode(response(status = "PARTIAL"), context).status)
        assertEquals(OfflineQaStatus.INSUFFICIENT_EVIDENCE, OfflineQaResponseCodec.decode(
            """{"schemaVersion":1,"status":"INSUFFICIENT_EVIDENCE","claims":[]}""", context).status)
    }
    @Test fun rejectsUnknownAndDuplicateRefsAndWrongStatus() {
        reject(response(refs = "[\"S00002\"]"), OfflineQaOutputError.INVALID_EVIDENCE)
        reject(response(refs = "[\"S00001\",\"S00001\"]"), OfflineQaOutputError.INVALID_EVIDENCE)
        reject(response(refs = "[]"), OfflineQaOutputError.INVALID_EVIDENCE)
        reject(response(status = "INSUFFICIENT_EVIDENCE"), OfflineQaOutputError.INVALID_STATUS)
    }
    @Test fun rejectsCoercionUnknownFieldsAndDuplicateKeys() {
        reject(response().replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""), OfflineQaOutputError.INVALID_SHAPE)
        reject(response().replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"extra\":0"), OfflineQaOutputError.INVALID_SHAPE)
        reject(response().replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"), OfflineQaOutputError.INVALID_SHAPE)
        reject(response().replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersi\\u006fn\":1"), OfflineQaOutputError.INVALID_SHAPE)
    }
    @Test fun rejectsWrappedTruncatedAndOversizedOutput() {
        reject("前言" + response(), OfflineQaOutputError.INVALID_JSON)
        reject(response().dropLast(1), OfflineQaOutputError.INVALID_JSON)
        reject("x".repeat(8193), OfflineQaOutputError.TOO_LARGE)
        reject(response(text = "字".repeat(81)), OfflineQaOutputError.INVALID_SHAPE)
    }
    @Test fun numericValuesAndUnitsMustComeFromCitedEvidence() {
        OfflineQaResponseCodec.decode(response("预算５０万元"), context)
        OfflineQaResponseCodec.decode(response("交付日期11月25日"), context)
        reject(response("预算500万元"), OfflineQaOutputError.NUMERIC_MISMATCH)
        reject(response("预算50元"), OfflineQaOutputError.NUMERIC_MISMATCH)
        reject(response("设备200台"), OfflineQaOutputError.NUMERIC_MISMATCH)
        reject(response("交付日期11月26日"), OfflineQaOutputError.NUMERIC_MISMATCH)
    }
    @Test fun repairOnlyOnceAndOnlyAfterConfirmedRelease() {
        assertTrue(OfflineQaRepairPolicy.mayRepair(OfflineQaOutputError.INVALID_JSON, 0, true))
        assertFalse(OfflineQaRepairPolicy.mayRepair(OfflineQaOutputError.INVALID_JSON, 1, true))
        assertFalse(OfflineQaRepairPolicy.mayRepair(OfflineQaOutputError.INVALID_JSON, 0, false))
        assertFalse(OfflineQaRepairPolicy.mayRepair(OfflineQaOutputError.INVALID_EVIDENCE, 0, true))
        assertFalse(OfflineQaRepairPolicy.mayRepair(OfflineQaOutputError.NUMERIC_MISMATCH, 0, true))
    }
}
