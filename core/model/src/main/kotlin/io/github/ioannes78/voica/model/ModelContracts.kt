package io.github.ioannes78.voica.model

enum class ModelKind {
    ASR_STREAMING,
    ASR_SECOND_PASS,
    VAD,
    PUNCTUATION,
    SPEAKER,
    ASR_LARGE,
}

enum class ModelSourceType {
    BUILTIN,
    MANAGED_DOWNLOAD,
    BUILTIN_WITH_OVERRIDE,
}

enum class ModelUpdatePolicy {
    MANUAL,
    AUTO_SMALL_MODELS,
    AUTO_ALL,
}

enum class ModelState {
    NOT_INSTALLED,
    DOWNLOADING,
    VERIFYING,
    INSTALLED,
    LOAD_FAILED,
    CORRUPTED,
    INCOMPATIBLE,
}

enum class RedistributionPolicy {
    VOICA_MIRROR_ALLOWED,
    UPSTREAM_ONLY,
    NO_REDISTRIBUTION,
}

data class ModelFileDescriptor(
    val relativePath: String,
    val sizeBytes: Long,
    val sha256: String,
) {
    init {
        require(relativePath.isNotBlank())
        require(!relativePath.startsWith("/"))
        require(".." !in relativePath.split('/'))
        require(sizeBytes >= 0L)
        require(SHA256_REGEX.matches(sha256.lowercase()))
    }
}

data class ModelCapabilities(
    val supportsStreaming: Boolean = false,
    val supportsPartial: Boolean = false,
    val supportsTokenTiming: Boolean = false,
    val supportsLanguageDetection: Boolean = false,
    val supportsConfidence: Boolean = false,
    val supportsInverseTextNormalization: Boolean = false,
    val supportsSecondPass: Boolean = false,
    val supportsHotwords: Boolean = false,
)

data class ModelDescriptor(
    val modelId: String,
    val kind: ModelKind,
    val displayName: String,
    val version: String,
    val runtimeId: String,
    val runtimeVersionMin: String?,
    val runtimeVersionMax: String?,
    val languages: Set<String>,
    val capabilities: ModelCapabilities,
    val sourceType: ModelSourceType,
    val downloadUrl: String?,
    val mirrors: List<String> = emptyList(),
    val downloadSizeBytes: Long?,
    val installedSizeBytes: Long,
    val packageSha256: String?,
    val files: List<ModelFileDescriptor>,
    val abis: Set<String>,
    val minSdk: Int,
    val appVersionMin: Int?,
    val appVersionMax: Int?,
    val licenseId: String,
    val licenseUrl: String?,
    val sourceUrl: String,
    val homepage: String?,
    val attribution: String,
    val redistributionPolicy: RedistributionPolicy,
    val releaseChannel: String,
    val autoUpdateEligible: Boolean,
    val deprecated: Boolean = false,
    val criticalUpdate: Boolean = false,
) {
    init {
        require(modelId.isNotBlank())
        require(displayName.isNotBlank())
        require(version.isNotBlank())
        require(runtimeId.isNotBlank())
        require(languages.isNotEmpty())
        require(installedSizeBytes >= 0L)
        require(minSdk >= 1)
        require(licenseId.isNotBlank())
        require(sourceUrl.isNotBlank())
        require(releaseChannel.isNotBlank())
        if (sourceType == ModelSourceType.MANAGED_DOWNLOAD) {
            require(!downloadUrl.isNullOrBlank())
            require(downloadSizeBytes != null && downloadSizeBytes >= 0L)
            require(packageSha256 != null && SHA256_REGEX.matches(packageSha256.lowercase()))
        }
    }
}

data class ModelCatalog(
    val catalogVersion: Int,
    val manifestVersion: Int,
    val channel: String,
    val publishedAt: String?,
    val manifestDigest: String,
    val signature: String? = null,
    val keyId: String? = null,
    val models: List<ModelDescriptor>,
) {
    init {
        require(catalogVersion >= 1)
        require(manifestVersion >= 1)
        require(channel.isNotBlank())
        require(SHA256_REGEX.matches(manifestDigest.lowercase()))
        require(models.map { it.modelId }.distinct().size == models.size)
    }

    fun model(modelId: String): ModelDescriptor? =
        models.firstOrNull { it.modelId == modelId }
}

data class InstalledModel(
    val modelId: String,
    val version: String,
    val sourceType: ModelSourceType,
    val confirmedGood: Boolean,
)

data class ModelAvailability(
    val descriptor: ModelDescriptor,
    val state: ModelState,
    val builtinVersion: String? = null,
    val installedVersion: String? = null,
    val availableVersion: String? = null,
    val activeVersion: String? = null,
    val updateAvailable: Boolean = false,
)

interface ModelManager {
    suspend fun catalog(): ModelCatalog

    suspend fun availability(modelId: String): ModelAvailability?

    suspend fun checkForUpdates(force: Boolean = false): ModelCatalog

    suspend fun install(modelId: String, version: String)

    suspend fun cancelInstall(modelId: String)

    suspend fun removeDownloadedVersion(modelId: String, version: String)

    suspend fun rollback(modelId: String)
}

private val SHA256_REGEX = Regex("^[0-9a-fA-F]{64}$")
