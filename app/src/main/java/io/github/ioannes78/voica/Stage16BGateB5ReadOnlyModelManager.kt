package io.github.ioannes78.voica

import android.app.Application
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import io.github.ioannes78.voica.model.DefaultModelManager
import io.github.ioannes78.voica.model.ModelCandidateValidator
import io.github.ioannes78.voica.model.ModelCatalogCodec
import io.github.ioannes78.voica.model.ModelEnvironment
import io.github.ioannes78.voica.model.ModelManager
import io.github.ioannes78.voica.model.ModelStorage
import io.github.ioannes78.voica.model.ModelUseRegistry
import io.github.ioannes78.voica.model.Stage16BCandidateRuntimes
import io.github.ioannes78.voica.sherpa.SherpaRuntime
import java.io.File

/**
 * Read-only model-manager construction for the QA-only Stage 16B B5 process.
 *
 * B5 only resolves already-active models. It must not initialize the durable model-install runtime,
 * because that path constructs AndroidModelInstallScheduler/WorkManager and would both fail in the
 * lightweight :model_validator process and contaminate the process-level PSS/RTF measurement.
 */
internal fun createStage16BGateB5ReadOnlyModelManager(
    application: Application,
    useRegistry: ModelUseRegistry,
): ModelManager {
    check(VoicaModelChannel.isDebuggable(application)) {
        "Stage 16B B5 read-only model manager is restricted to Debug/QA builds"
    }

    val bundledCatalog =
        application.assets.open(VoicaModelChannel.BOOTSTRAP_CATALOG_ASSET)
            .bufferedReader(Charsets.UTF_8)
            .use { ModelCatalogCodec.decode(it.readText()) }

    val packageInfo =
        application.packageManager.getPackageInfo(
            application.packageName,
            0,
        )
    val appVersionCode = PackageInfoCompat.getLongVersionCode(packageInfo).toInt()
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
    val rejectMutationValidator =
        ModelCandidateValidator { _, _ ->
            error("B5 read-only model manager must not validate or install model candidates")
        }

    return DefaultModelManager(
        bundledCatalog = bundledCatalog,
        remoteCatalogProvider = null,
        environment = environment,
        storage = storage,
        packageDirectory = packageDirectory,
        useRegistry = useRegistry,
        candidateValidator = rejectMutationValidator,
    )
}
