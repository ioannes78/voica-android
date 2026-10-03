package io.github.ioannes78.voica.database

enum class LibrarySort {
    RECORDED,
    DOWNLOADED,
    ADDED,
    UPDATED,
    NAME,
    SIZE,
    FAVORITE,
}

data class LibraryQueryCriteria(
    val query: String = "",
    val sort: LibrarySort = LibrarySort.RECORDED,
    val favoriteOnly: Boolean = false,
    val folderId: String? = null,
    val uncategorizedOnly: Boolean = false,
    val tagIds: Set<String> = emptySet(),
    val sourceTypes: Set<String> = emptySet(),
    val completedTranscriptionOnly: Boolean = false,
    val completedSummaryOnly: Boolean = false,
    val searchDateFromLocalIso: String? = null,
    val searchDateToLocalIsoExclusive: String? = null,
    val searchDateFromMs: Long? = null,
    val searchDateToMsExclusive: Long? = null,
    val recordedFromLocalIso: String? = null,
    val recordedToLocalIsoExclusive: String? = null,
    val addedFromMs: Long? = null,
    val addedToMsExclusive: Long? = null,
)

data class RecordingLibraryProjection(
    val id: String,
    val sourceType: String,
    val originalFilename: String,
    val displayName: String,
    val recordedAtLocalIso: String?,
    val deviceReportedDurationMs: Long?,
    val mediaDurationMs: Long?,
    val downloadedAtMs: Long?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val isFavorite: Boolean,
    val folderId: String?,
    val folderName: String?,
    val physicalAudioBytes: Long,
    val hasCompletedTranscription: Boolean,
    val hasCompletedSummary: Boolean,
    val tagNamesEncoded: String?,
)

data class RecordingLibraryRow(
    val id: String,
    val sourceType: String,
    val originalFilename: String,
    val displayName: String,
    val recordedAtLocalIso: String?,
    val deviceReportedDurationMs: Long?,
    val mediaDurationMs: Long?,
    val downloadedAtMs: Long?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val isFavorite: Boolean,
    val folderId: String?,
    val folderName: String?,
    val physicalAudioBytes: Long,
    val hasCompletedTranscription: Boolean,
    val hasCompletedSummary: Boolean,
    val tagNames: List<String>,
)

internal fun RecordingLibraryProjection.toLibraryRow(): RecordingLibraryRow =
    RecordingLibraryRow(
        id = id,
        sourceType = sourceType,
        originalFilename = originalFilename,
        displayName = displayName,
        recordedAtLocalIso = recordedAtLocalIso,
        deviceReportedDurationMs = deviceReportedDurationMs,
        mediaDurationMs = mediaDurationMs,
        downloadedAtMs = downloadedAtMs,
        createdAtMs = createdAtMs,
        updatedAtMs = updatedAtMs,
        isFavorite = isFavorite,
        folderId = folderId,
        folderName = folderName,
        physicalAudioBytes = physicalAudioBytes,
        hasCompletedTranscription = hasCompletedTranscription,
        hasCompletedSummary = hasCompletedSummary,
        tagNames =
            tagNamesEncoded
                ?.split(TAG_LIST_SEPARATOR)
                ?.filter(String::isNotBlank)
                .orEmpty(),
    )

internal const val TAG_LIST_SEPARATOR = "\u001f"
