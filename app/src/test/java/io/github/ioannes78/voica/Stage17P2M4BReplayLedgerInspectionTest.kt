package io.github.ioannes78.voica

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import org.junit.Assert.assertEquals
import org.junit.Test

/** All keys and ledger records here are synthetic, not external authorization. */
class Stage17P2M4BReplayLedgerInspectionTest {
    private val policy = Stage17P2M4BReplayLedgerInspection
    private val key = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val now = 1_900_000_100L
    private val currentGrantId = "123e4567-e89b-12d3-a456-426614174001"
    private val challenge = "123e4567-e89b-12d3-a456-426614174000"

    private fun signed(
        sequence: Long = 4L,
        previousGrant: String = "123e4567-e89b-12d3-a456-426614174099",
        checkpointTime: Long = now - 20L,
        packageName: String = Stage17P2M4BContract.LAB_PACKAGE,
    ): Stage17P2M4BReplayLedgerInspection.Checkpoint {
        val unsigned = Stage17P2M4BReplayLedgerInspection.Checkpoint(
            packageName, sequence, previousGrant, checkpointTime, byteArrayOf(),
        )
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(key.private)
            update(policy.checkpointPayload(unsigned))
            sign()
        }
        return unsigned.copy(signature = signature)
    }

    private fun receipt(sequence: Long = 5L, grantId: String = currentGrantId) =
        Stage17P2M4BGrantEvidenceInspection.ConsumptionReceipt(
            grantId, challenge, now - 10L, sequence, byteArrayOf(),
        )

    @Test fun coherentSignedSequenceIsNotReplayAuthorization() {
        assertEquals(
            policy.Result.ORDER_CONSISTENT_NOT_REPLAY_PROOF,
            policy.inspect(
                signed(), receipt(), key.public,
                Stage17P2M4BContract.LAB_PACKAGE, now,
            ),
        )
        assertEquals(false, Stage17P2M4BContract.IPC_ADMISSION_ENABLED)
        assertEquals(false, Stage17P2M4BContract.FAULT_INJECTION_ENABLED)
    }

    @Test fun missingDuplicateAndOutOfOrderSequenceReject() {
        val denied = policy.Result.REJECTED
        for (sequence in listOf(0L, 4L, 6L, Long.MAX_VALUE)) {
            assertEquals(denied, policy.inspect(
                signed(), receipt(sequence), key.public,
                Stage17P2M4BContract.LAB_PACKAGE, now,
            ))
        }
        assertEquals(denied, policy.inspect(
            signed(previousGrant = currentGrantId), receipt(), key.public,
            Stage17P2M4BContract.LAB_PACKAGE, now,
        ))
        assertEquals(denied, policy.inspect(
            signed(sequence = Long.MAX_VALUE), receipt(), key.public,
            Stage17P2M4BContract.LAB_PACKAGE, now,
        ))
        assertEquals(denied, policy.inspect(
            signed(sequence = -1L), receipt(), key.public,
            Stage17P2M4BContract.LAB_PACKAGE, now,
        ))
    }

    @Test fun signaturesAndChronologyRejectAmbiguity() {
        val denied = policy.Result.REJECTED
        val otherKey = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        assertEquals(denied, policy.inspect(
            signed(), receipt(), otherKey.public,
            Stage17P2M4BContract.LAB_PACKAGE, now,
        ))
        assertEquals(denied, policy.inspect(
            signed().copy(signature = byteArrayOf(1)), receipt(), key.public,
            Stage17P2M4BContract.LAB_PACKAGE, now,
        ))
        assertEquals(denied, policy.inspect(
            signed(packageName = "io.github.ioannes78.voica.qa"), receipt(), key.public,
            Stage17P2M4BContract.LAB_PACKAGE, now,
        ))
        assertEquals(denied, policy.inspect(
            signed(checkpointTime = now + 1L), receipt(), key.public,
            Stage17P2M4BContract.LAB_PACKAGE, now,
        ))
        assertEquals(denied, policy.inspect(
            signed(checkpointTime = now - 5L), receipt(), key.public,
            Stage17P2M4BContract.LAB_PACKAGE, now,
        ))
        assertEquals(denied, policy.inspect(
            signed(), receipt(), null,
            Stage17P2M4BContract.LAB_PACKAGE, now,
        ))
    }
}
