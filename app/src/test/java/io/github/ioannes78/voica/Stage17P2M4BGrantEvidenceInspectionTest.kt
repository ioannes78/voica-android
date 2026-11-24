package io.github.ioannes78.voica

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import org.junit.Assert.assertEquals
import org.junit.Test

/** Synthetic signing keys are generated in JVM tests, never used as authority. */
class Stage17P2M4BGrantEvidenceInspectionTest {
    private val policy = Stage17P2M4BGrantEvidenceInspection
    private val grantKey = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val ledgerKey = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val hexA = "a".repeat(64)
    private val hexB = "b".repeat(64)
    private val hexC = "c".repeat(64)
    private val challenge = "123e4567-e89b-12d3-a456-426614174000"
    private val grantId = "123e4567-e89b-12d3-a456-426614174001"
    private val now = 1_900_000_100L

    private fun sign(key: KeyPair, message: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(key.private)
            update(message)
            sign()
        }

    private fun grant(): Stage17P2M4BGrantEvidenceInspection.Grant {
        val unsigned = Stage17P2M4BGrantEvidenceInspection.Grant(
            grantId, challenge, Stage17P2M4BContract.LAB_PACKAGE,
            hexA, hexB, hexC, now - 50L, now + 50L, byteArrayOf(),
        )
        return unsigned.copy(signature = sign(grantKey, policy.grantPayload(unsigned)))
    }

    private fun receipt(): Stage17P2M4BGrantEvidenceInspection.ConsumptionReceipt {
        val unsigned = Stage17P2M4BGrantEvidenceInspection.ConsumptionReceipt(
            grantId, challenge, now - 10L, 1L, byteArrayOf(),
        )
        return unsigned.copy(signature = sign(ledgerKey, policy.receiptPayload(unsigned)))
    }

    private fun inspect(
        grant: Stage17P2M4BGrantEvidenceInspection.Grant = grant(),
        receipt: Stage17P2M4BGrantEvidenceInspection.ConsumptionReceipt = receipt(),
        signedBy: KeyPair = grantKey,
        consumedBy: KeyPair = ledgerKey,
        observedPackage: String = Stage17P2M4BContract.LAB_PACKAGE,
        observedHash: String = hexA,
        observedChallenge: String = challenge,
        observedNow: Long = now,
    ) = policy.inspect(
        grant, receipt, signedBy.public, consumedBy.public,
        observedPackage, observedHash, hexB, hexC, observedChallenge, observedNow,
    )

    @Test fun validSyntheticSignaturesAreStillNotTrustedEnrollment() {
        assertEquals(
            Stage17P2M4BGrantEvidenceInspection.Result.SIGNATURES_AND_FIELDS_COHERENT_NOT_AN_ENROLLMENT,
            inspect(),
        )
        // The actual broker remains hard-denied regardless of synthetic evidence.
        assertEquals(false, Stage17P2M4BContract.IPC_ADMISSION_ENABLED)
        assertEquals(false, Stage17P2M4BContract.FAULT_INJECTION_ENABLED)
    }

    @Test fun mismatchAndTamperingReject() {
        val invalid = Stage17P2M4BGrantEvidenceInspection.Result.REJECTED
        assertEquals(invalid, inspect(observedPackage = "io.github.ioannes78.voica.qa"))
        assertEquals(invalid, inspect(observedHash = hexB))
        assertEquals(invalid, inspect(observedChallenge = "123e4567-e89b-12d3-a456-426614174099"))
        assertEquals(invalid, inspect(observedNow = now + 80L))
        assertEquals(invalid, inspect(grant = grant().copy(sourceSha = hexB)))
        assertEquals(invalid, inspect(grant = grant().copy(signature = byteArrayOf(1))))
        assertEquals(invalid, inspect(receipt = receipt().copy(ledgerSequence = 2L)))
        assertEquals(invalid, inspect(receipt = receipt().copy(grantId = "123e4567-e89b-12d3-a456-426614174099")))
        assertEquals(invalid, inspect(receipt = receipt().copy(consumedAtEpochSec = now + 1)))
        assertEquals(invalid, inspect(consumedBy = grantKey))
    }

    @Test fun invalidSignedClaimCannotBeFixedByReSigningWithoutExternalAuthority() {
        val invalid = Stage17P2M4BGrantEvidenceInspection.Result.REJECTED
        val unsigned = grant().copy(packageName = "io.github.ioannes78.voica.qa", signature = byteArrayOf())
        val signed = unsigned.copy(signature = sign(grantKey, policy.grantPayload(unsigned)))
        assertEquals(invalid, inspect(grant = signed))
        val missingLedger = receipt().copy(signature = byteArrayOf())
        assertEquals(invalid, inspect(receipt = missingLedger))
    }
    @Test fun independentlySignedButInconsistentTimeAndIdentityReject() {
        val invalid = Stage17P2M4BGrantEvidenceInspection.Result.REJECTED
        // Re-sign to prove negative checks are structural, not merely a bad
        // signature inherited from copy(). No test key is a trusted authority.
        fun reSign(input: Stage17P2M4BGrantEvidenceInspection.Grant): Stage17P2M4BGrantEvidenceInspection.Grant =
            input.copy(signature = sign(grantKey, policy.grantPayload(input)))
        fun reSignReceipt(input: Stage17P2M4BGrantEvidenceInspection.ConsumptionReceipt): Stage17P2M4BGrantEvidenceInspection.ConsumptionReceipt =
            input.copy(signature = sign(ledgerKey, policy.receiptPayload(input)))

        assertEquals(invalid, inspect(grant = reSign(grant().copy(expiresAtEpochSec = now - 51L))))
        assertEquals(invalid, inspect(grant = reSign(grant().copy(expiresAtEpochSec = now + 601L))))
        assertEquals(invalid, inspect(grant = reSign(grant().copy(issuedAtEpochSec = now + 1L))))
        assertEquals(invalid, inspect(grant = reSign(grant().copy(installationChallenge =
            "123e4567-e89b-12d3-a456-426614174099"))))
        assertEquals(invalid, inspect(grant = reSign(grant().copy(packageSignerSha256 = hexA))))
        assertEquals(invalid, inspect(grant = reSign(grant().copy(apkSha256 = "A".repeat(64)))))
        assertEquals(invalid, inspect(grant = reSign(grant().copy(grantId = "malformed"))))
        assertEquals(invalid, inspect(receipt = reSignReceipt(receipt().copy(ledgerSequence = 0L))))
        assertEquals(invalid, inspect(receipt = reSignReceipt(receipt().copy(
            consumedAtEpochSec = now - 51L))))
        assertEquals(invalid, inspect(receipt = reSignReceipt(receipt().copy(
            installationChallenge = "123e4567-e89b-12d3-a456-426614174099"))))
    }

    @Test fun ecP384KeysMustNotBeTreatedAsP256Evidence() {
        val denied = Stage17P2M4BGrantEvidenceInspection.Result.REJECTED
        val p384 = KeyPairGenerator.getInstance("EC").apply { initialize(384) }.generateKeyPair()
        val grantUnsigned = grant().copy(signature = byteArrayOf())
        val forgedGrant = grantUnsigned.copy(
            signature = sign(p384, policy.grantPayload(grantUnsigned)),
        )
        assertEquals(denied, inspect(grant = forgedGrant, signedBy = p384))

        val receiptUnsigned = receipt().copy(signature = byteArrayOf())
        val forgedReceipt = receiptUnsigned.copy(
            signature = sign(p384, policy.receiptPayload(receiptUnsigned)),
        )
        assertEquals(denied, inspect(receipt = forgedReceipt, consumedBy = p384))
    }

    @Test fun substitutedPublicKeyAndMissingLedgerSignatureReject() {
        val invalid = Stage17P2M4BGrantEvidenceInspection.Result.REJECTED
        val otherKey = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        assertEquals(invalid, inspect(signedBy = otherKey))
        assertEquals(invalid, inspect(consumedBy = otherKey))
        assertEquals(invalid, inspect(receipt = receipt().copy(signature = byteArrayOf())))
        // Same key for grant and consumption is forbidden even for test evidence.
        assertEquals(invalid, inspect(consumedBy = grantKey))
    }

}
