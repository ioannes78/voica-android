package io.github.ioannes78.voica.model

import kotlinx.coroutines.flow.StateFlow

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

enum class ModelPackageFormat {
    ZIP,
    TAR_BZ2,
    SINGLE_FILE,
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
    val packagePath: String = relativePath,
) {
    init {
        require(isSafeRelativePath(relativePath))
        require(isSafeRelativePath(packagePath))
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
    val revision: Long,
    val runtimeId: String,
    val runtimeVersionMin: String?,
    val runtimeVersionMax: String?,
    val languages: Set<String>,
    val capabilities: ModelCapabilities,
    val sourceType: ModelSourceType,
    val builtinAssetPath: String?,
    val packageFormat: ModelPackageFormat?,
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
        require(SAFE_PATH_SEGMENT_REGEX.matches(modelId))
        require(displayName.isNotBlank())
        require(SAFE_PATH_SEGMENT_REGEX.matches(version))
        require(revision >= 1L)
        require(runtimeId.isNotBlank())
        require(languages.isNotEmpty())
        require(installedSizeBytes >= 0L)
        require(minSdk >= 1)
        require(licenseId.isNotBlank())
        require(sourceUrl.isNotBlank())
        require(releaseChannel.isNotBlank())
        require(files.isNotEmpty())

        if (sourceType == ModelSourceType.BUILTIN ||
            sourceType == ModelSourceType.BUILTIN_WITH_OVERRIDE
        ) {
            require(!builtinAssetPath.isNullOrBlank())
        }

        if (!downloadUrl.isNullOrBlank()) {
            require(packageFormat != null)
            require(downloadSizeBytes != null && downloadSizeBytes >= 0L)
            require(packageSha256 != null && SHA256_REGEX.matches(packageSha256.lowercase()))
        }

        if (sourceType == ModelSourceType.MANAGED_DOWNLOAD) {
            require(!downloadUrl.isNullOrBlank())
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
    val revision: Long,
    val sourceType: ModelSourceType,
    val confirmedGood: Boolean,
)

data class ModelAvailability(
    val descriptor: ModelDescriptor,
    val state: ModelState,
    val builtinVersion: String? = null,
    val builtinRevision: Long? = null,
    val installedVersion: String? = null,
    val installedRevision: Long? = null,
    val availableVersion: String? = null,
    val availableRevision: Long? = null,
    val activeVersion: String? = null,
    val activeRevision: Long? = null,
    val updateAvailable: Boolean = false,
)

data class ModelOperationStatus(
    val state: ModelState,
    val downloadedBytes: Long? = null,
    val totalBytes: Long? = null,
    val errorMessage: String? = null,
) {
    init {
        require(downloadedBytes == null || downloadedBytes >= 0L)
        require(totalBytes == null || totalBytes >= 0L)
        if (downloadedBytes != null && totalBytes != null) {
            require(downloadedBytes <= totalBytes)
        }
    }
}

fun interface ModelCatalogProvider {
    suspend fun load(force: Boolean): ModelCatalog
}

fun interface ModelCandidateValidator {
    suspend fun validate(
        descriptor: ModelDescriptor,
        installedDirectory: java.io.File,
    )
}

interface ModelManager {
    val operations: StateFlow<Map<String, ModelOperationStatus>>

    suspend fun catalog(): ModelCatalog

    suspend fun availability(modelId: String): ModelAvailability?

    suspend fun checkForUpdates(force: Boolean = false): ModelCatalog

    suspend fun install(
        modelId: String,
        version: String,
        revision: Long,
    )

    suspend fun cancelInstall(modelId: String)

    suspend fun confirmInstalledVersion(
        modelId: String,
        version: String,
        revision: Long,
    )

    suspend fun removeDownloadedVersion(
        modelId: String,
        version: String,
        revision: Long,
    )

    suspend fun rollback(modelId: String)
}

internal val SHA256_REGEX = Regex("^[0-9a-fA-F]{64}$")
internal val SAFE_PATH_SEGMENT_REGEX = Regex("^[A-Za-z0-9._-]+$")

internal fun isSafeRelativePath(path: String): Boolean =
    path.isNotBlank() &&
        !path.startsWith("/") &&
        !path.startsWith("\\") &&
        ":" !in path &&
        ".." !in path.replace('\\', '/').split('/')
