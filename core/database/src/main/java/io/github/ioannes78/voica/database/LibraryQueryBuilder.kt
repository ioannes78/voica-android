package io.github.ioannes78.voica.database

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery

internal object LibraryQueryBuilder {
    fun build(criteria: LibraryQueryCriteria): SupportSQLiteQuery {
        require(!(criteria.folderId != null && criteria.uncategorizedOnly)) {
            "folderId and uncategorizedOnly are mutually exclusive"
        }

        val args = mutableListOf<Any>()
        val where = mutableListOf("r.state = ?")
        args += RecordingState.ACTIVE

        val search = criteria.query.trim()
        if (search.isNotEmpty()) {
            val like = "%${escapeLike(search.lowercase())}%"
            val matchQuery = CjkSearchTokenizer.toMatchQuery(search)
            val effectiveContentClause =
                if (matchQuery.isNotBlank()) {
                    """
                    OR EXISTS (
                        SELECT 1
                        FROM search_documents search_d
                        JOIN search_documents_fts
                          ON search_documents_fts.documentId = search_d.documentId
                        WHERE search_d.recordingId = r.id
                          AND search_d.documentType IN (
                              'TRANSCRIPT_UNIT',
                              'SUMMARY_TITLE_OVERVIEW',
                              'SUMMARY_ITEM'
                          )
                          AND search_documents_fts MATCH ?
                    )
                    """.trimIndent()
                } else {
                    ""
                }
            where +=
                """
                (
                    LOWER(r.displayName) LIKE ? ESCAPE CHAR(92) OR
                    LOWER(r.originalFilename) LIKE ? ESCAPE CHAR(92) OR
                    LOWER(COALESCE(r.sourceRemoteIdentity, '')) LIKE ? ESCAPE CHAR(92) OR
                    LOWER(COALESCE(r.sourceDeviceAddress, '')) LIKE ? ESCAPE CHAR(92) OR
                    LOWER(COALESCE(f.name, '')) LIKE ? ESCAPE CHAR(92) OR
                    LOWER(COALESCE(ip.originalDisplayName, '')) LIKE ? ESCAPE CHAR(92) OR
                    LOWER(COALESCE(ip.sourceMimeType, '')) LIKE ? ESCAPE CHAR(92) OR
                    LOWER(COALESCE(ip.providerAuthority, '')) LIKE ? ESCAPE CHAR(92) OR
                    EXISTS (
                        SELECT 1
                        FROM recording_tag_cross_refs search_rt
                        JOIN recording_tags search_t ON search_t.tagId = search_rt.tagId
                        WHERE search_rt.recordingId = r.id
                          AND LOWER(search_t.name) LIKE ? ESCAPE CHAR(92)
                    )
                    $effectiveContentClause
                )
                """.trimIndent()
            repeat(9) { args += like }
            if (matchQuery.isNotBlank()) {
                args += matchQuery
            }
        }

        if (criteria.favoriteOnly) {
            where += "COALESCE(um.isFavorite, 0) = 1"
        }

        criteria.folderId?.let {
            where += "um.folderId = ?"
            args += it
        }
        if (criteria.uncategorizedOnly) {
            where += "um.folderId IS NULL"
        }

        if (criteria.tagIds.isNotEmpty()) {
            val ids = criteria.tagIds.sorted()
            where +=
                """
                (
                    SELECT COUNT(DISTINCT filter_rt.tagId)
                    FROM recording_tag_cross_refs filter_rt
                    WHERE filter_rt.recordingId = r.id
                      AND filter_rt.tagId IN (${placeholders(ids.size)})
                ) = ?
                """.trimIndent()
            args.addAll(ids)
            args += ids.size
        }

        if (criteria.sourceTypes.isNotEmpty()) {
            val sources = criteria.sourceTypes.sorted()
            where += "r.sourceType IN (${placeholders(sources.size)})"
            args.addAll(sources)
        }

        if (criteria.completedTranscriptionOnly) {
            where +=
                """
                EXISTS (
                    SELECT 1 FROM transcriptions tx
                    WHERE tx.recordingId = r.id
                      AND tx.state = 'COMPLETED'
                )
                """.trimIndent()
        }

        if (criteria.completedSummaryOnly) {
            where +=
                """
                EXISTS (
                    SELECT 1 FROM ai_summaries summary
                    WHERE summary.recordingId = r.id
                      AND summary.status = 'COMPLETED'
                )
                """.trimIndent()
        }

        val searchDateFromLocalIso = criteria.searchDateFromLocalIso
        val searchDateToLocalIso = criteria.searchDateToLocalIsoExclusive
        val searchDateFromMs = criteria.searchDateFromMs
        val searchDateToMs = criteria.searchDateToMsExclusive
        if (
            searchDateFromLocalIso != null &&
            searchDateToLocalIso != null &&
            searchDateFromMs != null &&
            searchDateToMs != null
        ) {
            where +=
                """
                (
                    (
                        r.recordedAtLocalIso IS NOT NULL AND
                        r.recordedAtLocalIso >= ? AND
                        r.recordedAtLocalIso < ?
                    ) OR (
                        r.downloadedAtMs IS NOT NULL AND
                        r.downloadedAtMs >= ? AND
                        r.downloadedAtMs < ?
                    ) OR (
                        ip.importedAtMs IS NOT NULL AND
                        ip.importedAtMs >= ? AND
                        ip.importedAtMs < ?
                    ) OR (
                        r.createdAtMs >= ? AND
                        r.createdAtMs < ?
                    )
                )
                """.trimIndent()
            args += searchDateFromLocalIso
            args += searchDateToLocalIso
            args += searchDateFromMs
            args += searchDateToMs
            args += searchDateFromMs
            args += searchDateToMs
            args += searchDateFromMs
            args += searchDateToMs
        }

        criteria.recordedFromLocalIso?.let {
            where += "r.recordedAtLocalIso >= ?"
            args += it
        }
        criteria.recordedToLocalIsoExclusive?.let {
            where += "r.recordedAtLocalIso < ?"
            args += it
        }
        criteria.addedFromMs?.let {
            where += "r.createdAtMs >= ?"
            args += it
        }
        criteria.addedToMsExclusive?.let {
            where += "r.createdAtMs < ?"
            args += it
        }

        val orderBy =
            when (criteria.sort) {
                LibrarySort.RECORDED ->
                    "r.recordedAtLocalIso DESC, r.createdAtMs DESC, r.id DESC"
                LibrarySort.DOWNLOADED ->
                    "COALESCE(r.downloadedAtMs, -1) DESC, r.createdAtMs DESC, r.id DESC"
                LibrarySort.ADDED ->
                    "r.createdAtMs DESC, r.id DESC"
                LibrarySort.UPDATED ->
                    "r.updatedAtMs DESC, r.id DESC"
                LibrarySort.NAME ->
                    "r.displayName COLLATE NOCASE ASC, r.createdAtMs DESC, r.id DESC"
                LibrarySort.SIZE ->
                    "COALESCE(bytes.physicalAudioBytes, 0) DESC, r.createdAtMs DESC, r.id DESC"
                LibrarySort.FAVORITE ->
                    "COALESCE(um.isFavorite, 0) DESC, r.updatedAtMs DESC, r.id DESC"
            }

        val sql =
            """
            WITH physical_bytes AS (
                SELECT recordingId, SUM(sizeBytes) AS physicalAudioBytes
                FROM (
                    SELECT recordingId, relativePath, MAX(sizeBytes) AS sizeBytes
                    FROM audio_assets
                    GROUP BY recordingId, relativePath
                )
                GROUP BY recordingId
            ),
            tag_names AS (
                SELECT rt.recordingId,
                       GROUP_CONCAT(t.name, CHAR(31)) AS tagNamesEncoded
                FROM recording_tag_cross_refs rt
                JOIN recording_tags t ON t.tagId = rt.tagId
                GROUP BY rt.recordingId
            )
            SELECT
                r.id AS id,
                r.sourceType AS sourceType,
                r.originalFilename AS originalFilename,
                r.displayName AS displayName,
                r.recordedAtLocalIso AS recordedAtLocalIso,
                r.deviceReportedDurationMs AS deviceReportedDurationMs,
                r.mediaDurationMs AS mediaDurationMs,
                r.downloadedAtMs AS downloadedAtMs,
                r.createdAtMs AS createdAtMs,
                r.updatedAtMs AS updatedAtMs,
                COALESCE(um.isFavorite, 0) AS isFavorite,
                um.folderId AS folderId,
                f.name AS folderName,
                COALESCE(bytes.physicalAudioBytes, 0) AS physicalAudioBytes,
                CASE WHEN EXISTS (
                    SELECT 1 FROM transcriptions tx
                    WHERE tx.recordingId = r.id
                      AND tx.state = 'COMPLETED'
                ) THEN 1 ELSE 0 END AS hasCompletedTranscription,
                CASE WHEN EXISTS (
                    SELECT 1 FROM ai_summaries summary
                    WHERE summary.recordingId = r.id
                      AND summary.status = 'COMPLETED'
                ) THEN 1 ELSE 0 END AS hasCompletedSummary,
                tags.tagNamesEncoded AS tagNamesEncoded
            FROM recordings r
            LEFT JOIN recording_user_metadata um ON um.recordingId = r.id
            LEFT JOIN recording_folders f ON f.folderId = um.folderId
            LEFT JOIN recording_import_provenance ip ON ip.recordingId = r.id
            LEFT JOIN physical_bytes bytes ON bytes.recordingId = r.id
            LEFT JOIN tag_names tags ON tags.recordingId = r.id
            WHERE ${where.joinToString("\nAND ")}
            ORDER BY $orderBy
            """.trimIndent()

        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }

    private fun placeholders(count: Int): String =
        List(count) { "?" }.joinToString(", ")

    private fun escapeLike(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
}
