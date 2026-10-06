package io.github.ioannes78.voica

import io.github.ioannes78.voica.model.ModelDescriptorSnapshot
import io.github.ioannes78.voica.model.ModelDescriptorSnapshotCodec
import io.github.ioannes78.voica.model.ModelInstallTransientProtection
import io.github.ioannes78.voica.model.modelInstallPackagePartName
import io.github.ioannes78.voica.model.modelInstallStagingDirectoryName
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

private val MODEL_INSTALL_OPERATION_ID_REGEX = Regex("^[A-Za-z0-9._-]+$")

enum class ModelInstallOrigin {
    MANUAL,
    AUTO_SMALL,
}

enum class ModelInstallPhase {
    REQUESTED,
    DOWNLOAD,
    VERIFY,
    EXTRACT,
    FILE_VERIFY,
    RUNTIME_VALIDATE,
    ATOMIC_ACTIVATE,
    READY,
    INTERRUPTED,
    FAILED_RECOVERABLE,
    FAILED_INTEGRITY,
    FAILED_RUNTIME,
    FAILED_CONFIGURATION,
    CANCELLED,
    ;

    val terminal: Boolean
        get() =
            this == READY ||
                this == FAILED_INTEGRITY ||
                this == FAILED_RUNTIME ||
                this == FAILED_CONFIGURATION ||
                this == CANCELLED

    val protectsTransientStorage: Boolean
        get() = !terminal
}

enum class ModelInstallExecutorKind {
    NONE,
    UIDT,
    WORK_MANAGER_DOWNLOAD,
    WORK_MANAGER_FINALIZE,
}

data class ModelInstallJournalRecord(
    val operationId: String,
    val snapshot: ModelDescriptorSnapshot,
    val origin: ModelInstallOrigin,
    val phase: ModelInstallPhase,
    val cancelRequested: Boolean = false,
    val requiresUserResume: Boolean = false,
    val attempt: Int = 0,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = snapshot.descriptor.downloadSizeBytes,
    val executorKind: ModelInstallExecutorKind = ModelInstallExecutorKind.NONE,
    val executorGeneration: Long = 1L,
    val lastFailureCode: String? = null,
    val lastFailureMessage: String? = null,
    val createdAtMs: Long,
    val updatedAtMs: Long,
) {
    init {
        require(MODEL_INSTALL_OPERATION_ID_REGEX.matches(operationId))
        require(attempt >= 0)
        require(downloadedBytes >= 0L)
        require(totalBytes == null || totalBytes >= 0L)
        require(totalBytes == null || downloadedBytes <= totalBytes)
        require(executorGeneration >= 1L)
    }

    val modelId: String
        get() = snapshot.descriptor.modelId
}

class ModelInstallJournalStore(
    rootDirectory: File,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val root = rootDirectory.canonicalFile
    private val lock = Any()

    init {
        require(root.mkdirs() || root.isDirectory)
    }

    fun newRecord(
        operationId: String,
        snapshot: ModelDescriptorSnapshot,
        origin: ModelInstallOrigin,
    ): ModelInstallJournalRecord {
        val now = nowMs()
        return ModelInstallJournalRecord(
            operationId = operationId,
            snapshot = snapshot,
            origin = origin,
            phase = ModelInstallPhase.REQUESTED,
            createdAtMs = now,
            updatedAtMs = now,
        )
    }

    fun write(record: ModelInstallJournalRecord): ModelInstallJournalRecord =
        synchronized(lock) {
            val normalized = record.copy(updatedAtMs = nowMs())
            writeAtomic(fileFor(normalized.operationId), encode(normalized))
            normalized
        }

    fun read(operationId: String): ModelInstallJournalRecord? =
        synchronized(lock) {
            val file = fileFor(operationId)
            if (!file.isFile) return@synchronized null
            decode(load(file))
        }

    fun readAll(): List<ModelInstallJournalRecord> =
        synchronized(lock) {
            root.listFiles()
                .orEmpty()
                .filter { it.isFile && it.name.endsWith(JOURNAL_SUFFIX) }
                .mapNotNull { file ->
                    runCatching { decode(load(file)) }.getOrNull()
                }
                .sortedWith(
                    compareBy<ModelInstallJournalRecord> { it.createdAtMs }
                        .thenBy { it.operationId },
                )
        }

    fun latestForModel(modelId: String): ModelInstallJournalRecord? =
        readAll()
            .filter { it.modelId == modelId }
            .maxByOrNull { it.updatedAtMs }

    fun activeForModel(modelId: String): ModelInstallJournalRecord? =
        readAll()
            .filter { it.modelId == modelId && !it.phase.terminal }
            .maxByOrNull { it.updatedAtMs }

    fun delete(operationId: String): Boolean =
        synchronized(lock) {
            val file = fileFor(operationId)
            !file.exists() || file.delete()
        }

    fun transientProtection(): ModelInstallTransientProtection {
        val protectedRecords = readAll().filter { it.phase.protectsTransientStorage }
        return ModelInstallTransientProtection(
            packagePartNames =
                protectedRecords
                    .mapTo(linkedSetOf()) { modelInstallPackagePartName(it.snapshot) },
            stagingDirectoryNames =
                protectedRecords
                    .mapTo(linkedSetOf()) { modelInstallStagingDirectoryName(it.snapshot) },
        )
    }

    private fun fileFor(operationId: String): File {
        require(MODEL_INSTALL_OPERATION_ID_REGEX.matches(operationId))
        return File(root, operationId + JOURNAL_SUFFIX)
    }

    private fun encode(record: ModelInstallJournalRecord): Properties {
        val properties = Properties()
        properties.setProperty("schemaVersion", SCHEMA_VERSION.toString())
        properties.setProperty("operationId", record.operationId)
        properties.setProperty("origin", record.origin.name)
        properties.setProperty("phase", record.phase.name)
        properties.setProperty("cancelRequested", record.cancelRequested.toString())
        properties.setProperty("requiresUserResume", record.requiresUserResume.toString())
        properties.setProperty("attempt", record.attempt.toString())
        properties.setProperty("downloadedBytes", record.downloadedBytes.toString())
        record.totalBytes?.let { properties.setProperty("totalBytes", it.toString()) }
        properties.setProperty("executorKind", record.executorKind.name)
        properties.setProperty("executorGeneration", record.executorGeneration.toString())
        record.lastFailureCode?.let { properties.setProperty("lastFailureCode", it) }
        record.lastFailureMessage?.let { properties.setProperty("lastFailureMessage", it) }
        properties.setProperty("createdAtMs", record.createdAtMs.toString())
        properties.setProperty("updatedAtMs", record.updatedAtMs.toString())

        val snapshotProperties = ModelDescriptorSnapshotCodec.encode(record.snapshot)
        snapshotProperties.stringPropertyNames().forEach { key ->
            properties.setProperty(
                SNAPSHOT_PREFIX + key,
                snapshotProperties.getProperty(key),
            )
        }
        return properties
    }

    private fun decode(properties: Properties): ModelInstallJournalRecord {
        require(properties.requiredInt("schemaVersion") == SCHEMA_VERSION) {
            "unsupported model install journal schema"
        }
        val snapshotProperties = Properties()
        properties.stringPropertyNames()
            .filter { it.startsWith(SNAPSHOT_PREFIX) }
            .forEach { key ->
                snapshotProperties.setProperty(
                    key.removePrefix(SNAPSHOT_PREFIX),
                    properties.getProperty(key),
                )
            }
        val snapshot = ModelDescriptorSnapshotCodec.decode(snapshotProperties)
        return ModelInstallJournalRecord(
            operationId = properties.required("operationId"),
            snapshot = snapshot,
            origin = enumValueOf(properties.required("origin")),
            phase = enumValueOf(properties.required("phase")),
            cancelRequested = properties.requiredBoolean("cancelRequested"),
            requiresUserResume = properties.requiredBoolean("requiresUserResume"),
            attempt = properties.requiredInt("attempt"),
            downloadedBytes = properties.requiredLong("downloadedBytes"),
            totalBytes = properties.optionalLong("totalBytes"),
            executorKind = enumValueOf(properties.required("executorKind")),
            executorGeneration = properties.requiredLong("executorGeneration"),
            lastFailureCode = properties.optional("lastFailureCode"),
            lastFailureMessage = properties.optional("lastFailureMessage"),
            createdAtMs = properties.requiredLong("createdAtMs"),
            updatedAtMs = properties.requiredLong("updatedAtMs"),
        )
    }

    private fun load(file: File): Properties =
        Properties().apply {
            file.inputStream().use { input -> load(input) }
        }

    private fun writeAtomic(target: File, properties: Properties) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.outputStream().use { output ->
            properties.store(output, "Voica durable model install journal")
        }
        try {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun Properties.required(key: String): String =
        getProperty(key)?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("missing model install journal field: $key")

    private fun Properties.optional(key: String): String? =
        getProperty(key)?.takeIf { it.isNotEmpty() }

    private fun Properties.requiredLong(key: String): Long = required(key).toLong()

    private fun Properties.optionalLong(key: String): Long? = optional(key)?.toLong()

    private fun Properties.requiredInt(key: String): Int = required(key).toInt()

    private fun Properties.requiredBoolean(key: String): Boolean = required(key).toBooleanStrict()

    private companion object {
        const val SCHEMA_VERSION = 1
        const val JOURNAL_SUFFIX = ".properties"
        const val SNAPSHOT_PREFIX = "snapshot."
    }
}
