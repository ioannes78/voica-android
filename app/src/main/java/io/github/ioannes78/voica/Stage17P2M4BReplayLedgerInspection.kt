package io.github.ioannes78.voica

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.PublicKey
import java.security.Signature

/**
 * NON-AUTHORIZING B1-1 design probe for externally reported ledger order.
 * A caller-supplied checkpoint/public key can be stale, forged, or restored.
 * This class does not store or consume a grant, arm a journal, or attest a device.
 */
internal object Stage17P2M4BReplayLedgerInspection {
    data class Checkpoint(
        val packageName: String,
        val previousSequence: Long,
        val previousGrantId: String,
        val checkpointTimeEpochSec: Long,
        val signature: ByteArray,
    )

    enum class Result {
        REJECTED,
        ORDER_CONSISTENT_NOT_REPLAY_PROOF,
    }

    internal fun checkpointPayload(checkpoint: Checkpoint): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeUTF("WENZHI_P2_M4B1_LEDGER_CHECKPOINT_V1")
            for (field in listOf(
                checkpoint.packageName,
                checkpoint.previousSequence.toString(),
                checkpoint.previousGrantId,
                checkpoint.checkpointTimeEpochSec.toString(),
            )) {
                val bytes = field.toByteArray(Charsets.UTF_8)
                data.writeInt(bytes.size)
                data.write(bytes)
            }
        }
        return output.toByteArray()
    }

    /**
     * A checkpoint is NOT independently trusted just because its signature
     * verifies with a caller-provided key. Only an external transactional ledger
     * can establish that the sequence and grant ID have never been consumed.
     */
    fun inspect(
        checkpoint: Checkpoint,
        receipt: Stage17P2M4BGrantEvidenceInspection.ConsumptionReceipt,
        checkpointPublicKey: PublicKey?,
        observedPackage: String,
        observedAtEpochSec: Long,
    ): Result {
        if (checkpointPublicKey == null || checkpointPublicKey.algorithm != "EC" ||
            checkpoint.signature.isEmpty()
        ) return Result.REJECTED
        if (checkpoint.packageName != Stage17P2M4BContract.LAB_PACKAGE ||
            observedPackage != checkpoint.packageName
        ) return Result.REJECTED
        if (checkpoint.previousSequence < 0 ||
            checkpoint.previousSequence == Long.MAX_VALUE ||
            receipt.ledgerSequence != checkpoint.previousSequence + 1L
        ) return Result.REJECTED
        if (checkpoint.previousGrantId == receipt.grantId ||
            receipt.grantId.isBlank()
        ) return Result.REJECTED
        if (checkpoint.checkpointTimeEpochSec <= 0L ||
            observedAtEpochSec < checkpoint.checkpointTimeEpochSec ||
            receipt.consumedAtEpochSec < checkpoint.checkpointTimeEpochSec ||
            receipt.consumedAtEpochSec > observedAtEpochSec
        ) return Result.REJECTED
        val valid = try {
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(checkpointPublicKey)
            verifier.update(checkpointPayload(checkpoint))
            verifier.verify(checkpoint.signature)
        } catch (_: Exception) {
            false
        }
        if (!valid) return Result.REJECTED

        // No durable, external, non-rollbackable state is available in this
        // helper. It must never be used as an actual anti-replay admission gate.
        return Result.ORDER_CONSISTENT_NOT_REPLAY_PROOF
    }
}
