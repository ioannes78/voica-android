package io.github.ioannes78.voica

import android.app.Application
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import io.github.ioannes78.voica.model.DecodingModelCatalogProvider
import io.github.ioannes78.voica.model.DefaultModelManager
import io.github.ioannes78.voica.model.HttpsModelCatalogTextSource
import io.github.ioannes78.voica.model.ModelCatalogCodec
import io.github.ioannes78.voica.model.ModelEnvironment
import io.github.ioannes78.voica.model.ModelManager
import io.github.ioannes78.voica.model.ModelStorage
import io.github.ioannes78.voica.model.ModelUseRegistry
import io.github.ioannes78.voica.sherpa.SherpaRuntime
import io.github.ioannes78.voica.sherpa.SherpaModelCandidateValidator
import java.io.File

object VoicaModelChannel {
    const val BOOTSTRAP_CATALOG_ASSET = "model-catalog-v1.json"
    const val PRODUCTION_MANIFEST_URL =
        "https://raw.githubusercontent.com/ioannes78/voica-model-channel/main/manifests/production.json"
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

    return DefaultModelManager(
        bundledCatalog = bundledCatalog,
        remoteCatalogProvider =
            DecodingModelCatalogProvider(
                HttpsModelCatalogTextSource(
                    VoicaModelChannel.PRODUCTION_MANIFEST_URL,
                ),
            ),
        environment =
            ModelEnvironment(
                runtimeId = SherpaRuntime.RUNTIME_ID,
                runtimeVersion = SherpaRuntime.RUNTIME_VERSION,
                abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a",
                sdkInt = Build.VERSION.SDK_INT,
                appVersionCode = appVersionCode,
            ),
        storage = ModelStorage(File(application.noBackupFilesDir, "models")),
        packageDirectory = File(application.cacheDir, "model-packages"),
        useRegistry = useRegistry,
        candidateValidator = SherpaModelCandidateValidator(),
    )
}
