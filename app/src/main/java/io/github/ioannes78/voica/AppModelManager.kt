package io.github.ioannes78.voica

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import io.github.ioannes78.voica.model.DecodingModelCatalogProvider
import io.github.ioannes78.voica.model.DefaultModelInstallBackend
import io.github.ioannes78.voica.model.DefaultModelManager
import io.github.ioannes78.voica.model.HttpsModelCatalogTextSource
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
    const val PRODUCTION_MANIFEST_URL =
        "https://raw.githubusercontent.com/ioannes78/voica-model-channel/main/manifests/production.json"
    const val STAGE13A_STREAMING_CANDIDATE_MANIFEST_URL =
        "https://github.com/ioannes78/voica-model-channel/releases/download/candidate-stage13a-streaming-asr-r1/production.json"
    const val STAGE13A_ALL_CANDIDATE_MANIFEST_URL =
        "https://github.com/ioannes78/voica-model-channel/releases/download/candidate-stage13a-all-r1/production.json"

    private const val PREFERENCES_NAME = "voica-model-channel"
    private const val KEY_DEBUG_MANIFEST_URL = "debug-manifest-url"
    private const val CANDIDATE_RELEASE_PREFIX =
        "/ioannes78/voica-model-channel/releases/download/"

    fun isDebuggable(application: Application): Boolean =
        application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    fun configuredDebugManifestUrl(application: Application): String? {
        if (!isDebuggable(application)) return null
        return application
            .getSharedPreferences(PREFERENCES_NAME, Application.MODE_PRIVATE)
            .getString(KEY_DEBUG_MANIFEST_URL, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.takeIf(::isAllowedDebugManifestUrl)
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

private val STAGE13A_QA5_PRODUCT_MODEL_IDS =
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

    val decodedRemoteCatalogProvider =
        DecodingModelCatalogProvider(
            HttpsModelCatalogTextSource(
                VoicaModelChannel.resolveManifestUrl(application),
            ),
        )
    val productCatalogProvider =
        ModelCatalogProvider { force ->
            val catalog = decodedRemoteCatalogProvider.load(force)
            catalog.copy(
                models =
                    catalog.models.filter { descriptor ->
                        descriptor.modelId in STAGE13A_QA5_PRODUCT_MODEL_IDS
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
            scope = ModelInstallRuntime.processScope,
        )

    ModelInstallRuntime.register(
        manager = durableManager,
        orchestrator = orchestrator,
        journalStore = journalStore,
    )
    return durableManager
}
