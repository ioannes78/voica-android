package io.github.ioannes78.voica.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "recording_import_provenance",
    foreignKeys = [
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["importedAtMs"]),
    ],
)
data class RecordingImportProvenanceEntity(
    @PrimaryKey val recordingId: String,
    val importedAtMs: Long,
    val originalDisplayName: String,
    val sourceMimeType: String?,
    val sourceSizeBytes: Long,
    val providerAuthority: String?,
    val sourceLastModifiedMs: Long?,
)

@Entity(
    tableName = "recording_folders",
    indices = [
        Index(value = ["name"], unique = true),
        Index(value = ["updatedAtMs"]),
    ],
)
data class FolderEntity(
    @PrimaryKey val folderId: String,
    val name: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

@Entity(
    tableName = "recording_user_metadata",
    foreignKeys = [
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["folderId"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["folderId"]),
        Index(value = ["isFavorite"]),
        Index(value = ["updatedAtMs"]),
    ],
)
data class RecordingUserMetadataEntity(
    @PrimaryKey val recordingId: String,
    val folderId: String?,
    val isFavorite: Boolean = false,
    val updatedAtMs: Long,
)

@Entity(
    tableName = "recording_tags",
    indices = [
        Index(value = ["name"], unique = true),
        Index(value = ["updatedAtMs"]),
    ],
)
data class TagEntity(
    @PrimaryKey val tagId: String,
    val name: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

@Entity(
    tableName = "recording_tag_cross_refs",
    primaryKeys = ["recordingId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["tagId"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["tagId"]),
    ],
)
data class RecordingTagCrossRef(
    val recordingId: String,
    val tagId: String,
)
