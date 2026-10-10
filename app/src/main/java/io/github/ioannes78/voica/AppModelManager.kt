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
import io.github.ioannes78.voica.sherpa.SherpaRuntime
import java.io.File
import java.net.URL

object VoicaModelChannel {
    const val BOOTSTRAP_CATALOG_ASSET = "model-catalog-v1.json"
    const val PRODUCTION_MODEL_CHANNEL_COMMIT =
        "be74c7065ce22a5f9b207a7cf1c88d3be0e872ec"
    const val PRODUCTION_MANIFEST_VERSION = 7
    const val PRODUCTION_MANIFEST_DIGEST =
        "8bdb505ce97820cb942bc2855c8a099484b7e22f63ec5567b63f2c1c18d567cf"
    const val PRODUCTION_MANIFEST_URL =
        "https://raw.githubusercontent.com/ioannes78/voica-model-channel/" +
            PRODUCTION_MODEL_CHANNEL_COMMIT +
            "/manifests/production.json"

    private const val PREFERENCES_NAME = "voica-model-channel"
    private const val KEY_DEBUG_MANIFEST_URL = "debug-manifest-url"
    private const val CANDIDATE_RELEASE_PREFIX =
        "/ioannes78/voica-model-channel/releases/download/"

    fun isDebuggable(application: Application): Boolean =
        application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    fun configuredDebugManifestUrl(application: Application): String? {
        if (!isDebuggable(application)) return null
        val preferences =
            application.getSharedPreferences(PREFERENCES_NAME, Application.MODE_PRIVATE)
        val configured =
            preferences
                .getString(KEY_DEBUG_MANIFEST_URL, null)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return null
        if (isPromotedStage13aDebugManifestUrl(configured)) {
            preferences.edit().remove(KEY_DEBUG_MANIFEST_URL).apply()
            return null
        }
        return configured.takeIf(::isAllowedDebugManifestUrl)
    }

    fun resolveManifestUrl(application: Application): String =
        configuredDebugManifestUrl(application) ?: PRODUCTION_MANIFEST_URL

    fun setDebugManifestUrl(
        application: Application,
        manifestUrl: String?,
    ) {
        check(isDebuggable(application)) {
            "candidate model channel override is debug-only"
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

internal fun isPromotedStage13aDebugManifestUrl(value: String): Boolean =
    runCatching {
        val url = URL(value)
        url.protocol.equals("https", ignoreCase = true) &&
            url.host.equals("github.com", ignoreCase = true) &&
            url.query.isNullOrEmpty() &&
            url.ref.isNullOrEmpty() &&
            url.path in PROMOTED_STAGE13A_CANDIDATE_PATHS
    }.getOrDefault(false)

private val PROMOTED_STAGE13A_CANDIDATE_PATHS =
    setOf(
        "/ioannes78/voica-model-channel/releases/download/candidate-stage13a-all-r1/production.json",
        "/ioannes78/voica-model-channel/releases/download/candidate-stage13a-streaming-asr-r1/production.json",
    )

private val PRODUCT_MODEL_IDS =
    setOf(
        Stage8ModelIds.VAD,
        Stage8ModelIds.PUNCTUATION,
        Stage13AOfflineModelIds.SENSEVOICE,
        Stage13AOfflineModelIds.QWEN3_ASR,
        Stage13ARealtimeModelIds.SMALL_BILINGUAL,
        Stage13ARealtimeModelIds.CHINESE_LARGE_CTC,
        Stage9ModelIds.SEGMENTATION,
        Stage13ASpeakerEmbeddingModelIds.CAMP_PLUS,
    )

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
                        descriptor.modelId in PRODUCT_MODEL_IDS
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
