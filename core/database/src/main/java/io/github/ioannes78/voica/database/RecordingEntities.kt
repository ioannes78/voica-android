package io.github.ioannes78.voica.database

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

object RecordingSourceType {
    const val DEVICE_DOWNLOAD = "DEVICE_DOWNLOAD"
    const val LOCAL_IMPORT = "LOCAL_IMPORT"
}

object RecordingState {
    const val ACTIVE = "ACTIVE"
    const val DELETING = "DELETING"
}

object AudioAssetRole {
    const val DEVICE_OPUS = "DEVICE_OPUS"
    const val DEVICE_WAV = "DEVICE_WAV"
    const val IMPORTED_ORIGINAL = "IMPORTED_ORIGINAL"
    const val CANONICAL_WAV = "CANONICAL_WAV"
}

object AudioIntegrityState {
    const val VERIFIED = "VERIFIED"
    const val CORRUPTED = "CORRUPTED"
    const val MISSING = "MISSING"
}

object AudioValidationState {
    const val UNVERIFIED = "UNVERIFIED"
    const val LEGACY_HINT = "LEGACY_HINT"
    const val VALIDATING = "VALIDATING"
    const val VALID = "VALID"
    const val UNSUPPORTED = "UNSUPPORTED"
    const val INVALID = "INVALID"
    const val CORRUPTED = "CORRUPTED"
}

object AudioDerivationState {
    const val NOT_PRESENT = "NOT_PRESENT"
    const val PREPARING = "PREPARING"
    const val DECODING = "DECODING"
    const val NORMALIZING = "NORMALIZING"
    const val WRITING = "WRITING"
    const val VERIFYING = "VERIFYING"
    const val COMMITTING = "COMMITTING"
    const val READY = "READY"
    const val FAILED_RECOVERABLE = "FAILED_RECOVERABLE"
    const val FAILED_PERMANENT = "FAILED_PERMANENT"
    const val CANCELLED = "CANCELLED"
}

@Entity(
    tableName = "recordings",
    indices = [
        Index(value = ["sourceRemoteIdentity"], unique = true),
        Index(value = ["recordedAtLocalIso"]),
        Index(value = ["downloadedAtMs"]),
        Index(value = ["createdAtMs"]),
        Index(value = ["updatedAtMs"]),
        Index(value = ["sourceType"]),
        Index(value = ["state"]),
    ],
)
data class RecordingEntity(
    @PrimaryKey val id: String,
    val sourceType: String,
    val sourceRemoteIdentity: String?,
    val sourceDeviceAddress: String?,
    val originalFilename: String,
    val displayName: String,
    val recordedAtLocalIso: String?,
    val deviceReportedDurationMs: Long?,
    val mediaDurationMs: Long?,
    val downloadedAtMs: Long?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val state: String = RecordingState.ACTIVE,
)

@Entity(
    tableName = "audio_assets",
    foreignKeys = [
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["recordingId"]),
        Index(value = ["recordingId", "role"], unique = true),
        Index(value = ["sha256"]),
    ],
)
data class AudioAssetEntity(
    @PrimaryKey val assetId: String,
    val recordingId: String,
    val role: String,
    val relativePath: String,
    val container: String,
    val codec: String?,
    val sampleFormat: String?,
    val sampleRateHz: Int?,
    val channelCount: Int?,
    val sizeBytes: Long,
    val sha256: String,
    val integrityState: String,
    val formatValidationState: String,
    val createdAtMs: Long,
    val verifiedAtMs: Long?,
)

@Entity(
    tableName = "audio_derivations",
    primaryKeys = ["recordingId", "profileId", "sourceSha256"],
    foreignKeys = [
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["recordingId"]),
        Index(value = ["sourceAssetId"]),
        Index(value = ["outputAssetId"]),
    ],
)
data class AudioDerivationEntity(
    val recordingId: String,
    val profileId: String,
    val sourceSha256: String,
    val sourceAssetId: String,
    val outputAssetId: String?,
    val pipelineVersion: Int,
    val state: String,
    val startedAtMs: Long?,
    val updatedAtMs: Long,
    val completedAtMs: Long?,
    val errorCode: String?,
    val errorDetail: String?,
)

@Entity(tableName = "library_meta")
data class LibraryMetaEntity(
    @PrimaryKey val key: String,
    val value: String,
    val updatedAtMs: Long,
)

@Entity(
    tableName = "migration_diagnostics",
    indices = [Index(value = ["sourcePath", "code"], unique = true)],
)
data class MigrationDiagnosticEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourcePath: String,
    val code: String,
    val detail: String?,
    val observedAtMs: Long,
)

data class RecordingWithAssets(
    @Embedded val recording: RecordingEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "recordingId",
    )
    val assets: List<AudioAssetEntity>,
    @Relation(
        parentColumn = "id",
        entityColumn = "recordingId",
    )
    val derivations: List<AudioDerivationEntity>,
)
