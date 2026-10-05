package io.github.ioannes78.voica

import android.app.ActivityManager
import android.app.Application
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import io.github.ioannes78.voica.model.ModelCandidateValidator
import io.github.ioannes78.voica.model.ModelDescriptor
import io.github.ioannes78.voica.model.ModelStorage
import io.github.ioannes78.voica.sherpa.SherpaModelCandidateValidator
import io.github.ioannes78.voica.sherpa.SherpaModelValidationException
import io.github.ioannes78.voica.sherpa.SherpaModelValidationStage
import java.io.File
import java.util.Properties
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class IsolatedModelValidationStatus {
    VALIDATED,
    STATIC_VALIDATION_FAILED,
    NATIVE_INIT_FAILED,
    NATIVE_INFERENCE_FAILED,
    OUT_OF_MEMORY,
    VALIDATOR_PROCESS_CRASHED,
    TIMEOUT,
}

internal class AndroidIsolatedModelCandidateValidator(
    private val application: Application,
) : ModelCandidateValidator {
    private val validationMutex = Mutex()

    override suspend fun validate(
        descriptor: ModelDescriptor,
        installedDirectory: File,
    ) {
        validationMutex.withLock {
            require(installedDirectory.isDirectory) {
                "STATIC_VALIDATION_FAILED · 模型安装目录不存在"
            }
            memoryPreflight(descriptor)

            val requestId = UUID.randomUUID().toString()
            val resultFile = validationResultFile(application, requestId)
            resultFile.delete()

            val disconnected = AtomicBoolean(false)
            val connection =
                object : ServiceConnection {
                    override fun onServiceConnected(
                        name: ComponentName?,
                        service: IBinder?,
                    ) = Unit

                    override fun onServiceDisconnected(name: ComponentName?) {
                        disconnected.set(true)
                    }

                    override fun onBindingDied(name: ComponentName?) {
                        disconnected.set(true)
                    }

                    override fun onNullBinding(name: ComponentName?) {
                        disconnected.set(true)
                    }
                }

            val intent =
                Intent(application, IsolatedModelValidationService::class.java)
                    .putExtra(EXTRA_REQUEST_ID, requestId)
                    .putExtra(EXTRA_MODEL_ID, descriptor.modelId)
                    .putExtra(EXTRA_VERSION, descriptor.version)
                    .putExtra(EXTRA_REVISION, descriptor.revision)

            val bound =
                application.bindService(
                    intent,
                    connection,
                    Context.BIND_AUTO_CREATE,
                )
            check(bound) {
                "VALIDATOR_PROCESS_CRASHED · 无法启动模型运行验证进程"
            }

            try {
                val deadline = SystemClock.elapsedRealtime() + VALIDATION_TIMEOUT_MS
                while (SystemClock.elapsedRealtime() < deadline) {
                    readValidationResult(resultFile)?.let { result ->
                        when (result.status) {
                            IsolatedModelValidationStatus.VALIDATED -> return@withLock
                            else ->
                                error(
                                    result.status.name +
                                        " · " +
                                        result.message.ifBlank { "模型运行验证失败" },
                                )
                        }
                    }

                    if (disconnected.get()) {
                        delay(PROCESS_DEATH_GRACE_MS)
                        readValidationResult(resultFile)?.let { result ->
                            if (result.status == IsolatedModelValidationStatus.VALIDATED) {
                                return@withLock
                            }
                            error(
                                result.status.name +
                                    " · " +
                                    result.message.ifBlank { "模型运行验证失败" },
                            )
                        }
                        error(
                            IsolatedModelValidationStatus.VALIDATOR_PROCESS_CRASHED.name +
                                " · 模型运行验证进程异常退出；Voica 主进程未受影响",
                        )
                    }
                    delay(POLL_INTERVAL_MS)
                }

                error(
                    IsolatedModelValidationStatus.TIMEOUT.name +
                        " · 模型运行验证超时；Voica 主进程未受影响",
                )
            } finally {
                runCatching { application.unbindService(connection) }
                resultFile.delete()
            }
        }
    }

    private fun memoryPreflight(descriptor: ModelDescriptor) {
        val estimated =
            descriptor.estimatedPeakRamBytes
                ?: descriptor.installedSizeBytes.takeIf { it > 0L }
                ?: return
        if (estimated <= 0L) return

        val manager =
            application.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(memory)

        check(!memory.lowMemory && memory.availMem >= estimated) {
            IsolatedModelValidationStatus.OUT_OF_MEMORY.name +
                " · 当前可用内存不足以安全加载该模型" +
                "（预计峰值 " + formatMiB(estimated) +
                "，当前可用 " + formatMiB(memory.availMem) + "）"
        }
    }

    private fun formatMiB(bytes: Long): String =
        ((bytes.coerceAtLeast(0L) + MIB - 1L) / MIB).toString() + " MiB"

    private companion object {
        const val VALIDATION_TIMEOUT_MS = 240_000L
        const val POLL_INTERVAL_MS = 200L
        const val PROCESS_DEATH_GRACE_MS = 300L
        const val MIB = 1024L * 1024L
    }
}

class IsolatedModelValidationService : Service() {
    private val binder = Binder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder {
        val request = ValidationRequest.fromIntent(intent)
        scope.launch {
            validate(request)
        }
        return binder
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun validate(request: ValidationRequest) {
        val result =
            try {
                val storage = ModelStorage(File(noBackupFilesDir, "models"))
                val snapshot =
                    storage.installedSnapshot(
                        modelId = request.modelId,
                        version = request.version,
                        revision = request.revision,
                    ) ?: error("模型候选元数据不存在或静态完整性校验失败")
                val installed =
                    storage.installedVersion(snapshot.descriptor)
                        ?: error("模型文件静态完整性校验失败")

                SherpaModelCandidateValidator().validate(
                    descriptor = snapshot.descriptor,
                    installedDirectory = installed.directory,
                )
                ValidationResult(
                    status = IsolatedModelValidationStatus.VALIDATED,
                    message = "模型运行验证通过",
                )
            } catch (oom: OutOfMemoryError) {
                ValidationResult(
                    status = IsolatedModelValidationStatus.OUT_OF_MEMORY,
                    message = oom.message ?: "模型加载或推理时内存不足",
                )
            } catch (error: SherpaModelValidationException) {
                ValidationResult(
                    status =
                        when (error.stage) {
                            SherpaModelValidationStage.NATIVE_INIT ->
                                IsolatedModelValidationStatus.NATIVE_INIT_FAILED
                            SherpaModelValidationStage.NATIVE_INFERENCE ->
                                IsolatedModelValidationStatus.NATIVE_INFERENCE_FAILED
                        },
                    message = error.cause?.message ?: error.message.orEmpty(),
                )
            } catch (error: Throwable) {
                ValidationResult(
                    status = IsolatedModelValidationStatus.STATIC_VALIDATION_FAILED,
                    message = error.message ?: error::class.java.simpleName,
                )
            }

        writeValidationResult(
            file = validationResultFile(applicationContext, request.requestId),
            result = result,
        )
    }
}

private data class ValidationRequest(
    val requestId: String,
    val modelId: String,
    val version: String,
    val revision: Long,
) {
    companion object {
        fun fromIntent(intent: Intent?): ValidationRequest {
            requireNotNull(intent)
            val requestId =
                intent.getStringExtra(EXTRA_REQUEST_ID)
                    ?.takeIf { it.isNotBlank() }
                    ?: error("missing model validation request id")
            val modelId =
                intent.getStringExtra(EXTRA_MODEL_ID)
                    ?.takeIf { it.isNotBlank() }
                    ?: error("missing model id")
            val version =
                intent.getStringExtra(EXTRA_VERSION)
                    ?.takeIf { it.isNotBlank() }
                    ?: error("missing model version")
            val revision = intent.getLongExtra(EXTRA_REVISION, -1L)
            require(revision >= 1L)
            return ValidationRequest(requestId, modelId, version, revision)
        }
    }
}

private data class ValidationResult(
    val status: IsolatedModelValidationStatus,
    val message: String,
)

private fun validationResultFile(
    context: Context,
    requestId: String,
): File {
    require(requestId.matches(Regex("^[A-Za-z0-9-]+$")))
    val root = File(context.noBackupFilesDir, "model-validation-results")
    check(root.mkdirs() || root.isDirectory)
    return File(root, "$requestId.properties")
}

private fun writeValidationResult(
    file: File,
    result: ValidationResult,
) {
    val properties =
        Properties().apply {
            setProperty("status", result.status.name)
            setProperty("message", result.message)
        }
    val temporary = File(file.parentFile, file.name + ".tmp")
    temporary.outputStream().buffered().use { output ->
        properties.store(output, null)
    }
    if (!temporary.renameTo(file)) {
        temporary.copyTo(file, overwrite = true)
        temporary.delete()
    }
}

private fun readValidationResult(file: File): ValidationResult? {
    if (!file.isFile) return null
    val properties = Properties()
    runCatching {
        file.inputStream().buffered().use(properties::load)
    }.getOrElse {
        return null
    }
    val status =
        runCatching {
            enumValueOf<IsolatedModelValidationStatus>(
                properties.getProperty("status").orEmpty(),
            )
        }.getOrNull() ?: return null
    return ValidationResult(
        status = status,
        message = properties.getProperty("message").orEmpty(),
    )
}

internal const val MODEL_VALIDATOR_PROCESS_SUFFIX = ":model_validator"
private const val EXTRA_REQUEST_ID = "requestId"
private const val EXTRA_MODEL_ID = "modelId"
private const val EXTRA_VERSION = "version"
private const val EXTRA_REVISION = "revision"
