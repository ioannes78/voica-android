package io.github.ioannes78.voica

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.PublicKey
import java.security.Signature

/**
 * B1-1 design probe ONLY. Checks signed evidence syntax/consistency without
 * producing trustedEnrollment, writing a Journal, seeding an owner or calling IPC.
 *
 * Public keys supplied to this API are NOT trusted roots. A real authority must
 * independently bind provisioned hardware/Android-user history to those keys
 * and maintain a non-restorable consumption ledger outside the device.
 */
internal object Stage17P2M4BGrantEvidenceInspection {
    data class Grant(
        val grantId: String,
        val installationChallenge: String,
        val packageName: String,
        val apkSha256: String,
        val packageSignerSha256: String,
        val sourceSha: String,
        val issuedAtEpochSec: Long,
        val expiresAtEpochSec: Long,
        val signature: ByteArray,
    )

    data class ConsumptionReceipt(
        val grantId: String,
        val installationChallenge: String,
        val consumedAtEpochSec: Long,
        val ledgerSequence: Long,
        val signature: ByteArray,
    )

    enum class Result {
        REJECTED,
        SIGNATURES_AND_FIELDS_COHERENT_NOT_AN_ENROLLMENT,
    }

    // Length-prefixed UTF-8 eliminates delimiter and embedded-newline ambiguity.
    private fun canonical(domain: String, fields: List<String>): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeUTF(domain)
            for (field in fields) {
                val bytes = field.toByteArray(Charsets.UTF_8)
                data.writeInt(bytes.size)
                data.write(bytes)
            }
        }
        return output.toByteArray()
    }

    internal fun grantPayload(grant: Grant): ByteArray = canonical(
        "WENZHI_P2_M4B1_GRANT_V1",
        listOf(
            grant.grantId, grant.installationChallenge, grant.packageName,
            grant.apkSha256, grant.packageSignerSha256, grant.sourceSha,
            grant.issuedAtEpochSec.toString(), grant.expiresAtEpochSec.toString(),
        ),
    )

    internal fun receiptPayload(receipt: ConsumptionReceipt): ByteArray = canonical(
        "WENZHI_P2_M4B1_LEDGER_CONSUMPTION_V1",
        listOf(
            receipt.grantId, receipt.installationChallenge,
            receipt.consumedAtEpochSec.toString(), receipt.ledgerSequence.toString(),
        ),
    )

    private fun validSignature(key: PublicKey, data: ByteArray, signature: ByteArray): Boolean {
        if (signature.isEmpty() || !Stage17P2M4BEcKeyShape.isP256(key)) return false
        return try {
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(key)
            verifier.update(data)
            verifier.verify(signature)
        } catch (_: Exception) {
            false
        }
    }

    private fun sha256Hex(value: String): Boolean =
        value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

    private fun uuid(value: String): Boolean =
        Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(value)

    fun inspect(
        grant: Grant,
        receipt: ConsumptionReceipt,
        grantPublicKey: PublicKey?,
        ledgerPublicKey: PublicKey?,
        observedPackage: String,
        observedApkSha256: String,
        observedSignerSha256: String,
        observedSourceSha: String,
        observedInstallationChallenge: String,
        observedAtEpochSec: Long,
    ): Result {
        fun reject() = Result.REJECTED
        if (grantPublicKey == null || ledgerPublicKey == null) return reject()
        val grantKeyBytes = grantPublicKey.encoded ?: return reject()
        val ledgerKeyBytes = ledgerPublicKey.encoded ?: return reject()
        if (grantKeyBytes.contentEquals(ledgerKeyBytes)) return reject()
        if (grant.packageName != Stage17P2M4BContract.LAB_PACKAGE ||
            observedPackage != grant.packageName
        ) return reject()
        if (!sha256Hex(grant.apkSha256) || !sha256Hex(grant.packageSignerSha256) ||
            !sha256Hex(grant.sourceSha)
        ) return reject()
        if (grant.apkSha256 != observedApkSha256 ||
            grant.packageSignerSha256 != observedSignerSha256 ||
            grant.sourceSha != observedSourceSha
        ) return reject()
        if (!uuid(grant.grantId) || !uuid(grant.installationChallenge) ||
            observedInstallationChallenge != grant.installationChallenge
        ) return reject()
        if (grant.issuedAtEpochSec <= 0L || grant.expiresAtEpochSec <= grant.issuedAtEpochSec ||
            grant.expiresAtEpochSec - grant.issuedAtEpochSec > 600L ||
            observedAtEpochSec < grant.issuedAtEpochSec ||
            observedAtEpochSec > grant.expiresAtEpochSec
        ) return reject()
        if (receipt.grantId != grant.grantId ||
            receipt.installationChallenge != grant.installationChallenge ||
            receipt.ledgerSequence <= 0L ||
            receipt.consumedAtEpochSec < grant.issuedAtEpochSec ||
            receipt.consumedAtEpochSec > observedAtEpochSec
        ) return reject()
        if (!validSignature(grantPublicKey, grantPayload(grant), grant.signature) ||
            !validSignature(ledgerPublicKey, receiptPayload(receipt), receipt.signature)
        ) return reject()

        // Important: even a cryptographically consistent pair can be self-issued,
        // restored or replayed without externally established trust and ledger
        // uniqueness. This result MUST NEVER be converted to trustedEnrollment.
        return Result.SIGNATURES_AND_FIELDS_COHERENT_NOT_AN_ENROLLMENT
    }
}
