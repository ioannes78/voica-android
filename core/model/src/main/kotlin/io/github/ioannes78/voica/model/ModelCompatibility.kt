package io.github.ioannes78.voica.model

data class ModelEnvironment(
    val runtimeId: String,
    val runtimeVersion: String,
    val abi: String,
    val sdkInt: Int,
    val appVersionCode: Int,
)

enum class ModelIncompatibilityReason {
    RUNTIME_ID,
    RUNTIME_TOO_OLD,
    RUNTIME_TOO_NEW,
    ABI,
    SDK,
    APP_TOO_OLD,
    APP_TOO_NEW,
}

data class ModelCompatibility(
    val compatible: Boolean,
    val reason: ModelIncompatibilityReason? = null,
)

fun ModelDescriptor.compatibilityWith(
    environment: ModelEnvironment,
): ModelCompatibility {
    if (runtimeId != environment.runtimeId) {
        return ModelCompatibility(false, ModelIncompatibilityReason.RUNTIME_ID)
    }
    if (runtimeVersionMin != null &&
        compareDottedVersions(environment.runtimeVersion, runtimeVersionMin) < 0
    ) {
        return ModelCompatibility(false, ModelIncompatibilityReason.RUNTIME_TOO_OLD)
    }
    if (runtimeVersionMax != null &&
        compareDottedVersions(environment.runtimeVersion, runtimeVersionMax) > 0
    ) {
        return ModelCompatibility(false, ModelIncompatibilityReason.RUNTIME_TOO_NEW)
    }
    if (abis.isNotEmpty() && environment.abi !in abis) {
        return ModelCompatibility(false, ModelIncompatibilityReason.ABI)
    }
    if (environment.sdkInt < minSdk) {
        return ModelCompatibility(false, ModelIncompatibilityReason.SDK)
    }
    if (appVersionMin != null && environment.appVersionCode < appVersionMin) {
        return ModelCompatibility(false, ModelIncompatibilityReason.APP_TOO_OLD)
    }
    if (appVersionMax != null && environment.appVersionCode > appVersionMax) {
        return ModelCompatibility(false, ModelIncompatibilityReason.APP_TOO_NEW)
    }
    return ModelCompatibility(true)
}

fun isModelUpdateAvailable(
    installedRevision: Long?,
    remoteRevision: Long,
): Boolean = installedRevision != null && remoteRevision > installedRevision

internal fun compareDottedVersions(left: String, right: String): Int {
    val a = numericVersionParts(left)
    val b = numericVersionParts(right)
    val size = maxOf(a.size, b.size)
    repeat(size) { index ->
        val av = a.getOrElse(index) { 0 }
        val bv = b.getOrElse(index) { 0 }
        if (av != bv) return av.compareTo(bv)
    }
    return 0
}

private fun numericVersionParts(value: String): List<Int> {
    val normalized = value.trim().removePrefix("v")
    require(normalized.isNotEmpty()) { "empty version" }
    return normalized.split('.').map { part ->
        val numeric = part.takeWhile { it.isDigit() }
        require(numeric.isNotEmpty()) { "non-numeric runtime version: $value" }
        numeric.toInt()
    }
}
