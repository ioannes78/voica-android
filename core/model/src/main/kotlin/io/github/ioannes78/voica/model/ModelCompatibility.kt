package io.github.ioannes78.voica.model

data class ModelEnvironment(
    val runtimeId: String,
    val runtimeVersion: String,
    val abi: String,
    val sdkInt: Int,
    val appVersionCode: Int,
    val additionalRuntimes: Map<String, String> = emptyMap(),
) {
    init {
        require(runtimeId.isNotBlank()) { "primary runtimeId must not be blank" }
        require(runtimeVersion.isNotBlank()) { "primary runtimeVersion must not be blank" }
        require(runtimeId !in additionalRuntimes) {
            "primary runtime must not be duplicated in additionalRuntimes"
        }
        additionalRuntimes.forEach { (id, version) ->
            require(id.isNotBlank()) { "additional runtimeId must not be blank" }
            require(version.isNotBlank()) { "additional runtimeVersion must not be blank" }
        }
    }

    fun runtimeVersionFor(requiredRuntimeId: String): String? =
        if (requiredRuntimeId == runtimeId) {
            runtimeVersion
        } else {
            additionalRuntimes[requiredRuntimeId]
        }
}

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
    val availableRuntimeVersion =
        environment.runtimeVersionFor(runtimeId)
            ?: return ModelCompatibility(false, ModelIncompatibilityReason.RUNTIME_ID)

    if (runtimeVersionMin != null &&
        compareDottedVersions(availableRuntimeVersion, runtimeVersionMin) < 0
    ) {
        return ModelCompatibility(false, ModelIncompatibilityReason.RUNTIME_TOO_OLD)
    }
    if (runtimeVersionMax != null &&
        compareDottedVersions(availableRuntimeVersion, runtimeVersionMax) > 0
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
