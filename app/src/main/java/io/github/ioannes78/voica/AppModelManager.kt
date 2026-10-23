package io.github.ioannes78.voica

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import io.github.ioannes78.voica.model.DecodingModelCatalogProvider
import io.github.ioannes78.voica.model.DefaultModelInstallBackend
import io.github.ioannes78.voica.model.DefaultModelManager
import io.github.ioannes78.voica.model.HttpsModelCatalogTextSource
import io.github.ioannes78.voica.model.ModelCatalog
import io.github.ioannes78.voica.model.ModelCatalogCodec
import io.github.ioannes78.voica.model.ModelCatalogProvider
import io.github.ioannes78.voica.model.ModelEnvironment
import io.github.ioannes78.voica.model.ModelManager
import io.github.ioannes78.voica.model.ModelStorage
import io.github.ioannes78.voica.model.ModelUseRegistry
import io.github.ioannes78.voica.model.Stage16BCandidateRuntimes
import io.github.ioannes78.voica.sherpa.SherpaRuntime
import java.io.File
import java.net.URL

object VoicaModelChannel {
    const val BOOTSTRAP_CATALOG_ASSET = "model-catalog-v1.json"
    const val PRODUCTION_MODEL_CHANNEL_COMMIT =
        "eeb59214244f392f3e2eecdb90016efbe3a95401"
    const val PRODUCTION_MANIFEST_VERSION = 9
    const val PRODUCTION_MANIFEST_DIGEST =
        "a49da9fdf2dabf2408ba2bb14cf6b5c25c35563052cc6307db8f8095caac0b45"
    const val PRODUCTION_MANIFEST_URL =
        "https://raw.githubusercontent.com/ioannes78/voica-model-channel/" +
            PRODUCTION_MODEL_CHANNEL_COMMIT +
            "/manifests/production.json"

    private const val PREFERENCES_NAME = "voica-model-channel"
    private const val KEY_DEBUG_MANIFEST_URL = "debug-manifest-url"
    private const val CANDIDATE_RELEASE_PREFIX =
        "/ioannes78/voica-model-channel/releases/download/"

    /**
     * Developer-tooling gate. It is true for normal debuggable builds and for the
     * explicitly non-production releaseQa variant, whose BuildConfig flag is false in release.
     */
    fun isDebuggable(application: Application): Boolean =
        candidateModelChannelAllowed(
            isDebuggable =
                application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
            buildAllowsCandidateChannel = BuildConfig.ALLOW_CANDIDATE_MODEL_CHANNEL,
        )

    private fun isCandidateModelChannelAllowed(application: Application): Boolean =
        isDebuggable(application)

    fun configuredDebugManifestUrl(application: Application): String? {
        if (!isCandidateModelChannelAllowed(application)) return null
        val preferences =
            application.getSharedPreferences(PREFERENCES_NAME, Application.MODE_PRIVATE)
        val configured =
            preferences
                .getString(KEY_DEBUG_MANIFEST_URL, null)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return null
        return configured.takeIf(::isAllowedDebugManifestUrl)
    }

    internal fun defaultCandidateManifestUrl(
        candidateChannelAllowed: Boolean,
        buildConfiguredUrl: String,
    ): String? {
        if (!candidateChannelAllowed) return null
        val normalized = buildConfiguredUrl.trim().takeIf { it.isNotEmpty() } ?: return null
        return normalized.takeIf(::isAllowedDebugManifestUrl)
    }

    fun resolveManifestUrl(application: Application): String =
        configuredDebugManifestUrl(application)
            ?: defaultCandidateManifestUrl(
                candidateChannelAllowed = isCandidateModelChannelAllowed(application),
                buildConfiguredUrl = BuildConfig.QA_DEFAULT_MODEL_MANIFEST_URL,
            )
            ?: PRODUCTION_MANIFEST_URL

    fun setDebugManifestUrl(
        application: Application,
        manifestUrl: String?,
    ) {
        check(isCandidateModelChannelAllowed(application)) {
            "candidate model channel override is restricted to debug/QA builds"
        }
        val preferences =
            application.getSharedPreferences(
                PREFERENCES_NAME,
                Application.MODE_PRIVATE,
            )
        val normalized = manifestUrl?.trim().orEmpty()
        if (normalized.isEmpty()) {
            preferences.edit().remove(KEY_DEBUG_MANIFEST_URL).apply()
            return
        }
        require(isAllowedDebugManifestUrl(normalized)) {
            "candidate manifest must be a Voica model-channel release production.json URL"
        }
        preferences.edit().putString(KEY_DEBUG_MANIFEST_URL, normalized).apply()
    }

    internal fun requirePinnedProductionCatalog(catalog: ModelCatalog): ModelCatalog {
        require(catalog.channel == "production") {
            "production model catalog channel mismatch"
        }
        require(catalog.manifestVersion == PRODUCTION_MANIFEST_VERSION) {
            "production model catalog version mismatch"
        }
        require(catalog.manifestDigest == PRODUCTION_MANIFEST_DIGEST) {
            "production model catalog digest mismatch"
        }
        return catalog
    }

    internal fun isAllowedDebugManifestUrl(value: String): Boolean =
        runCatching {
            val url = URL(value)
            url.protocol.equals("https", ignoreCase = true) &&
                url.host.equals("github.com", ignoreCase = true) &&
                url.query.isNullOrEmpty() &&
                url.ref.isNullOrEmpty() &&
                url.path.startsWith(CANDIDATE_RELEASE_PREFIX) &&
                url.path.endsWith("/production.json")
        }.getOrDefault(false)
}

internal fun candidateModelChannelAllowed(
    isDebuggable: Boolean,
    buildAllowsCandidateChannel: Boolean,
): Boolean = isDebuggable || buildAllowsCandidateChannel

internal const val STAGE16B_FUNASR_NANO_CANDIDATE_ID = "funasr-nano-int8"
internal const val STAGE16B_FSMN_VAD_CANDIDATE_ID = "fsmn-vad-int8"
internal const val STAGE16B_TEN_VAD_CANDIDATE_ID = "ten-vad-fp32"

private val PRODUCT_MODEL_IDS =
    setOf(
        Stage8ModelIds.VAD,
        Stage8ModelIds.PUNCTUATION,
        Stage13AOfflineModelIds.SENSEVOICE,
        Stage13AOfflineModelIds.FUNASR_NANO,
        Stage13ARealtimeModelIds.SMALL_BILINGUAL,
        Stage13ARealtimeModelIds.CHINESE_LARGE_CTC,
        Stage9ModelIds.SEGMENTATION,
        Stage13ASpeakerEmbeddingModelIds.CAMP_PLUS,
        STAGE16B_FSMN_VAD_CANDIDATE_ID,
    )

private val DEBUG_CANDIDATE_MODEL_IDS =
    setOf(
        STAGE16B_TEN_VAD_CANDIDATE_ID,
    )

internal fun allowedModelIdsForManifest(productionManifestPinned: Boolean): Set<String> =
    if (productionManifestPinned) {
        PRODUCT_MODEL_IDS
    } else {
        PRODUCT_MODEL_IDS + DEBUG_CANDIDATE_MODEL_IDS
    }

fun createVoicaModelManager(
    application: Application,
    useRegistry: ModelUseRegistry,
): ModelManager {
    val bundledCatalogText =
        application.assets.open(VoicaModelChannel.BOOTSTRAP_CATALOG_ASSET)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
    val bundledCatalog = ModelCatalogCodec.decode(bundledCatalogText)

    val packageInfo =
        application.packageManager.getPackageInfo(
            application.packageName,
            0,
        )
    val appVersionCode =
        PackageInfoCompat.getLongVersionCode(packageInfo).toInt()

    val resolvedManifestUrl = VoicaModelChannel.resolveManifestUrl(application)
    val productionManifestPinned =
        resolvedManifestUrl == VoicaModelChannel.PRODUCTION_MANIFEST_URL
    val allowedModelIds = allowedModelIdsForManifest(productionManifestPinned)
    val decodedRemoteCatalogProvider =
        DecodingModelCatalogProvider(
            HttpsModelCatalogTextSource(resolvedManifestUrl),
        )
    val productCatalogProvider =
        ModelCatalogProvider { force ->
            val decoded = decodedRemoteCatalogProvider.load(force)
            val catalog =
                if (productionManifestPinned) {
                    VoicaModelChannel.requirePinnedProductionCatalog(decoded)
                } else {
                    decoded
                }
            catalog.copy(
                models =
                    catalog.models.filter { descriptor ->
                        descriptor.modelId in allowedModelIds
                    },
            )
        }

    val environment =
        ModelEnvironment(
            runtimeId = SherpaRuntime.RUNTIME_ID,
            runtimeVersion = SherpaRuntime.RUNTIME_VERSION,
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a",
            sdkInt = Build.VERSION.SDK_INT,
            appVersionCode = appVersionCode,
            additionalRuntimes =
                mapOf(
                    Stage16BCandidateRuntimes.FSMN_VAD_ID to
                        Stage16BCandidateRuntimes.FSMN_VAD_VERSION,
                ),
        )
    val storage = ModelStorage(File(application.noBackupFilesDir, "models"))
    val packageDirectory = File(application.cacheDir, "model-packages")
    val candidateValidator = AndroidIsolatedModelCandidateValidator(application)

    val baseManager =
        DefaultModelManager(
            bundledCatalog = bundledCatalog,
            remoteCatalogProvider = productCatalogProvider,
            environment = environment,
            storage = storage,
            packageDirectory = packageDirectory,
            useRegistry = useRegistry,
            candidateValidator = candidateValidator,
        )
    val installBackend =
        DefaultModelInstallBackend(
            modelManager = baseManager,
            environment = environment,
            storage = storage,
            packageDirectory = packageDirectory,
            useRegistry = useRegistry,
            candidateValidator = candidateValidator,
        )
    val journalStore =
        ModelInstallJournalStore(
            File(application.noBackupFilesDir, "model-install-journal"),
        )
    val startupInterruptedOperationIds =
        ModelInstallStartupRecoveryPolicy(
            application = application,
            journalStore = journalStore,
        ).interruptManualInstallsAfterUserRequestedExit()
    val scheduler = AndroidModelInstallScheduler(application)
    val orchestrator =
        ModelInstallOrchestrator(
            backend = installBackend,
            journalStore = journalStore,
            scheduler = scheduler,
        )
    val durableManager =
        DurableAwareModelManager(
            delegate = baseManager,
            orchestrator = orchestrator,
            backend = installBackend,
            journalStore = journalStore,
            scheduler = scheduler,
            scope = ModelInstallRuntime.processScope,
        )

    ModelInstallRuntime.register(
        application = application,
        manager = durableManager,
        orchestrator = orchestrator,
        journalStore = journalStore,
        scheduler = scheduler,
        startupInterruptedOperationIds = startupInterruptedOperationIds,
    )
    return durableManager
}
