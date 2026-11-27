package io.github.ioannes78.voica

import java.security.AlgorithmParameters
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec

/**
 * Exact NIST P-256 / secp256r1 curve shape. NOT an enrollment trust anchor:
 * independently pinned issuer/ledger identities are still mandatory.
 *
 * A same-bit-size EC curve (such as secp256k1) must never silently qualify.
 */
internal object Stage17P2M4BEcKeyShape {
    fun isP256(key: PublicKey): Boolean {
        val ec = key as? ECPublicKey ?: return false
        return try {
            val parameters = AlgorithmParameters.getInstance("EC")
            parameters.init(ECGenParameterSpec("secp256r1"))
            val required = parameters.getParameterSpec(ECParameterSpec::class.java)
            val actual = ec.params
            actual.curve.field == required.curve.field &&
                actual.curve.a == required.curve.a &&
                actual.curve.b == required.curve.b &&
                actual.generator == required.generator &&
                actual.order == required.order &&
                actual.cofactor == required.cofactor
        } catch (_: Exception) {
            false
        }
    }
}
