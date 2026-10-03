package io.github.ioannes78.voica.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

object SearchDocumentTypeValue {
    const val RECORDING = "RECORDING"
    const val FOLDER = "FOLDER"
    const val TAG = "TAG"
    const val TRANSCRIPT_UNIT = "TRANSCRIPT_UNIT"
    const val SUMMARY_TITLE_OVERVIEW = "SUMMARY_TITLE_OVERVIEW"
    const val SUMMARY_ITEM = "SUMMARY_ITEM"
}

object SearchIndexStatusValue {
    const val REBUILD_REQUIRED = "REBUILD_REQUIRED"
    const val REBUILDING = "REBUILDING"
    const val READY = "READY"
    const val FAILED = "FAILED"
}

@Entity(
    tableName = "search_documents",
    foreignKeys = [
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["documentId"], unique = true),
        Index(value = ["recordingId"]),
        Index(value = ["documentType"]),
        Index(value = ["transcriptionId"]),
        Index(value = ["aiSummaryId"]),
        Index(value = ["folderId"]),
        Index(value = ["tagId"]),
        Index(value = ["updatedAtMs"]),
    ],
)
data class SearchDocumentEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val documentId: String,
    val documentType: String,
    val recordingId: String?,
    val transcriptionId: String?,
    val revisionId: String?,
    val sourceAnchorId: String?,
    val aiSummaryId: String?,
    val sectionId: String?,
    val itemId: String?,
    val folderId: String?,
    val tagId: String?,
    val displayTitle: String,
    val displayText: String,
    val indexTitle: String,
    val indexBody: String,
    val updatedAtMs: Long,
)

@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "search_documents_fts")
data class SearchDocumentFtsEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowId: Long,
    val documentId: String,
    val indexTitle: String,
    val indexBody: String,
)

@Entity(tableName = "search_index_state")
data class SearchIndexStateEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val status: String,
    val indexedDocumentCount: Long,
    val startedAtMs: Long?,
    val updatedAtMs: Long,
    val completedAtMs: Long?,
    val errorMessage: String?,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
