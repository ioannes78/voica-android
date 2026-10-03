package io.github.ioannes78.voica.database

import androidx.room.withTransaction
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class LegacyImportReport(
    val skippedBecauseAlreadyImported: Boolean,
    val importedRecordings: Int,
    val importedAssets: Int,
    val diagnostics: Int,
)

data class LibraryDeleteResult(
    val deleted: Boolean,
    val failedPaths: List<String> = emptyList(),
)

data class CanonicalConversionSource(
    val recordingId: String,
    val sourceAssetId: String,
    val sourceRole: String,
    val sourceContainer: String,
    val sourceRelativePath: String,
    val sourceSha256: String,
    val sourceSizeBytes: Long,
    val deviceReportedDurationMs: Long?,
    val existingCanonical: RecordingAsset?,
    val derivationState: String?,
)

data class InterruptedCanonicalDerivation(
    val recordingId: String,
    val sourceAssetId: String,
    val sourceSha256: String,
    val profileId: String,
    val pipelineVersion: Int,
    val state: String,
)

data class CanonicalWavRegistration(
    val recordingId: String,
    val sourceAssetId: String,
    val sourceSha256: String,
    val profileId: String,
    val pipelineVersion: Int,
    val relativePath: String,
    val sizeBytes: Long,
    val sha256: String,
    val sampleRateHz: Int,
    val channelCount: Int,
    val verifiedAtMs: Long,
)

data class CanonicalTranscriptionLineage(
    val recordingId: String,
    val canonicalAssetId: String,
    val canonicalSha256: String,
    val canonicalProfileId: String,
    val canonicalPipelineVersion: Int,
)

data class DownloadedDeviceAsset(
    val sourceRemoteIdentity: String,
    val sourceDeviceAddress: String,
    val sourceFormat: LegacyAssetFormat,
    val displayFilename: String,
    val relativePath: String,
    val recordedAtLocalIso: String?,
    val deviceReportedDurationMs: Long?,
    val downloadedAtMs: Long,
    val sizeBytes: Long,
    val sha256: String,
    val container: String,
)

class RecordingLibraryRepository(
    private val database: VoicaDatabase,
    private val recordingsRoot: File,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.recordingDao()

    val recordings: Flow<List<RecordingLibraryItem>> =
        dao.observeAll().map { rows -> rows.map(RecordingWithAssets::toLibraryItem) }

    val folders: Flow<List<FolderEntity>> = dao.observeFolders()

    val tags: Flow<List<TagEntity>> = dao.observeTags()

    fun observeLibrary(criteria: LibraryQueryCriteria): Flow<List<RecordingLibraryRow>> =
        dao.observeLibrary(LibraryQueryBuilder.build(criteria))
            .map { rows -> rows.map(RecordingLibraryProjection::toLibraryRow) }

    suspend fun setFavorite(recordingIds: Collection<String>, favorite: Boolean): Int {
        val ids = recordingIds.distinct()
        if (ids.isEmpty()) return 0
        return dao.setFavorite(ids, favorite, nowMs())
    }

    suspend fun moveToFolder(recordingIds: Collection<String>, folderId: String?): Int {
        val ids = recordingIds.distinct()
        if (ids.isEmpty()) return 0
        if (folderId != null) {
            requireNotNull(dao.findFolder(folderId)) { "folder does not exist" }
        }
        return dao.moveToFolder(ids, folderId, nowMs())
    }

    suspend fun addTag(recordingIds: Collection<String>, tagId: String): Int {
        val ids = recordingIds.distinct()
        if (ids.isEmpty()) return 0
        requireNotNull(dao.findTag(tagId)) { "tag does not exist" }
        return dao.insertRecordingTagCrossRefs(
            ids.map { recordingId ->
                RecordingTagCrossRef(recordingId = recordingId, tagId = tagId)
            },
        ).count { it != -1L }
    }

    suspend fun removeTag(recordingIds: Collection<String>, tagId: String): Int {
        val ids = recordingIds.distinct()
        if (ids.isEmpty()) return 0
        return dao.removeTagFromRecordings(ids, tagId)
    }

    suspend fun createFolder(name: String): FolderEntity =
        database.withTransaction {
            val normalized = normalizeLibraryName(name, maxLength = 60)
            dao.findFolderByName(normalized)?.let { return@withTransaction it }
            val now = nowMs()
            FolderEntity(
                folderId = UUID.randomUUID().toString(),
                name = normalized,
                createdAtMs = now,
                updatedAtMs = now,
            ).also(dao::insertFolder)
        }

    suspend fun renameFolder(folderId: String, name: String): Boolean =
        database.withTransaction {
            val normalized = normalizeLibraryName(name, maxLength = 60)
            val conflict = dao.findFolderByName(normalized)
            if (conflict != null && conflict.folderId != folderId) return@withTransaction false
            dao.renameFolder(folderId, normalized, nowMs()) == 1
        }

    suspend fun deleteFolder(folderId: String): Boolean =
        dao.deleteFolder(folderId) == 1

    suspend fun createTag(name: String): TagEntity =
        database.withTransaction {
            val normalized = normalizeLibraryName(name, maxLength = 40)
            dao.findTagByName(normalized)?.let { return@withTransaction it }
            val now = nowMs()
            TagEntity(
                tagId = UUID.randomUUID().toString(),
                name = normalized,
                createdAtMs = now,
                updatedAtMs = now,
            ).also(dao::insertTag)
        }

    suspend fun renameTag(tagId: String, name: String): Boolean =
        database.withTransaction {
            val normalized = normalizeLibraryName(name, maxLength = 40)
            val conflict = dao.findTagByName(normalized)
            if (conflict != null && conflict.tagId != tagId) return@withTransaction false
            dao.renameTag(tagId, normalized, nowMs()) == 1
        }

    suspend fun deleteTag(tagId: String): Boolean =
        dao.deleteTag(tagId) == 1

    suspend fun importLegacyStage5IfNeeded(): LegacyImportReport {
        if (dao.readMeta(LEGACY_IMPORT_META_KEY) == LEGACY_IMPORT_VERSION) {
            return LegacyImportReport(
                skippedBecauseAlreadyImported = true,
                importedRecordings = 0,
                importedAssets = 0,
                diagnostics = 0,
            )
        }

        val scan = LegacyStage5Scanner(recordingsRoot).scan()
        var insertedRecordings = 0
        var insertedAssets = 0
        val now = nowMs()

        database.withTransaction {
            scan.candidates
                .groupBy { it.recordingId }
                .forEach { (_, assets) ->
                    val preferred = assets.firstOrNull {
                        it.sourceFormat == LegacyAssetFormat.OPUS
                    } ?: assets.first()
                    val recording = preferred.toRecordingEntity(now)
                    if (dao.insertRecordingIgnore(recording) != -1L) {
                        insertedRecordings += 1
                    }
                    dao.insertUserMetadataIgnore(
                        RecordingUserMetadataEntity(
                            recordingId = recording.id,
                            folderId = null,
                            isFavorite = false,
                            updatedAtMs = now,
                        ),
                    )
                    assets.forEach { candidate ->
                        if (dao.insertAssetIgnore(candidate.toAssetEntity(now)) != -1L) {
                            insertedAssets += 1
                        }
                    }
                }

            scan.issues.forEach { issue ->
                dao.insertMigrationDiagnostic(
                    MigrationDiagnosticEntity(
                        sourcePath = issue.sourcePath,
                        code = issue.code,
                        detail = issue.detail,
                        observedAtMs = now,
                    ),
                )
            }

            dao.putMeta(
                LibraryMetaEntity(
                    key = LEGACY_IMPORT_META_KEY,
                    value = LEGACY_IMPORT_VERSION,
                    updatedAtMs = now,
                ),
            )
        }

        return LegacyImportReport(
            skippedBecauseAlreadyImported = false,
            importedRecordings = insertedRecordings,
            importedAssets = insertedAssets,
            diagnostics = scan.issues.size,
        )
    }

    suspend fun registerDownloadedDeviceAsset(asset: DownloadedDeviceAsset): String {
        require(asset.sizeBytes >= 0L) { "sizeBytes must be non-negative" }
        require(SHA256.matches(asset.sha256.lowercase())) { "invalid SHA-256" }
        val recordingId = stableRecordingId(asset.sourceRemoteIdentity)
        val role = when (asset.sourceFormat) {
            LegacyAssetFormat.OPUS -> AudioAssetRole.DEVICE_OPUS
            LegacyAssetFormat.WAV -> AudioAssetRole.DEVICE_WAV
        }
        val assetId = when (asset.sourceFormat) {
            LegacyAssetFormat.OPUS -> recordingId
            LegacyAssetFormat.WAV -> stableRecordingId(asset.sourceRemoteIdentity + "|format=WAV")
        }
        val now = nowMs()

        database.withTransaction {
            dao.insertRecordingIgnore(
                RecordingEntity(
                    id = recordingId,
                    sourceType = RecordingSourceType.DEVICE_DOWNLOAD,
                    sourceRemoteIdentity = asset.sourceRemoteIdentity,
                    sourceDeviceAddress = asset.sourceDeviceAddress,
                    originalFilename = asset.displayFilename,
                    displayName =
                        RecordingDisplayNamePolicy.defaultDisplayName(
                            asset.displayFilename,
                        ),
                    recordedAtLocalIso = asset.recordedAtLocalIso,
                    deviceReportedDurationMs = asset.deviceReportedDurationMs,
                    downloadedAtMs = asset.downloadedAtMs,
                    createdAtMs = asset.downloadedAtMs,
                    updatedAtMs = now,
                ),
            )
            dao.insertUserMetadataIgnore(
                RecordingUserMetadataEntity(
                    recordingId = recordingId,
                    folderId = null,
                    isFavorite = false,
                    updatedAtMs = now,
                ),
            )
            dao.upsertAsset(
                AudioAssetEntity(
                    assetId = assetId,
                    recordingId = recordingId,
                    role = role,
                    relativePath = asset.relativePath,
                    container = asset.container,
                    codec = if (asset.sourceFormat == LegacyAssetFormat.OPUS) "OPUS" else null,
                    sampleFormat = null,
                    sampleRateHz = null,
                    channelCount = null,
                    sizeBytes = asset.sizeBytes,
                    sha256 = asset.sha256.lowercase(),
                    integrityState = AudioIntegrityState.VERIFIED,
                    formatValidationState = AudioValidationState.UNVERIFIED,
                    createdAtMs = asset.downloadedAtMs,
                    verifiedAtMs = now,
                ),
            )
        }
        return recordingId
    }

    suspend fun findDeviceAsset(
        remoteIdentity: String,
        format: LegacyAssetFormat,
    ): RecordingAsset? {
        val recordingId = stableRecordingId(remoteIdentity)
        val role = when (format) {
            LegacyAssetFormat.OPUS -> AudioAssetRole.DEVICE_OPUS
            LegacyAssetFormat.WAV -> AudioAssetRole.DEVICE_WAV
        }
        val asset = dao.findAsset(recordingId, role) ?: return null
        if (asset.integrityState != AudioIntegrityState.VERIFIED) return null
        return RecordingAsset(
            assetId = asset.assetId,
            role = asset.role,
            relativePath = asset.relativePath,
            container = asset.container,
            codec = asset.codec,
            sampleFormat = asset.sampleFormat,
            sampleRateHz = asset.sampleRateHz,
            channelCount = asset.channelCount,
            sizeBytes = asset.sizeBytes,
            sha256 = asset.sha256,
            integrityState = asset.integrityState,
            formatValidationState = asset.formatValidationState,
        )
    }

    suspend fun isDeviceAssetAvailable(
        remoteIdentity: String,
        format: LegacyAssetFormat,
    ): Boolean {
        val asset = findDeviceAsset(remoteIdentity, format) ?: return false
        val file = File(recordingsRoot, asset.relativePath)
        return file.isFile && file.length() == asset.sizeBytes
    }

    suspend fun loadCanonicalTranscriptionLineage(
        recordingId: String,
        profileId: String,
    ): CanonicalTranscriptionLineage? {
        val row = dao.findWithAssets(recordingId) ?: return null
        if (row.recording.state != RecordingState.ACTIVE) return null

        val canonical =
            row.assets.firstOrNull { asset ->
                asset.role == AudioAssetRole.CANONICAL_WAV &&
                    asset.integrityState == AudioIntegrityState.VERIFIED &&
                    asset.formatValidationState == AudioValidationState.VALID
            } ?: return null

        val derivation =
            row.derivations
                .filter { item ->
                    item.profileId == profileId &&
                        item.outputAssetId == canonical.assetId &&
                        item.state == AudioDerivationState.READY
                }
                .maxByOrNull { it.updatedAtMs }
                ?: return null

        return CanonicalTranscriptionLineage(
            recordingId = recordingId,
            canonicalAssetId = canonical.assetId,
            canonicalSha256 = canonical.sha256,
            canonicalProfileId = derivation.profileId,
            canonicalPipelineVersion = derivation.pipelineVersion,
        )
    }

    suspend fun loadCanonicalConversionSource(
        recordingId: String,
        profileId: String,
    ): CanonicalConversionSource? {
        val row = dao.findWithAssets(recordingId) ?: return null
        val source =
            row.assets.firstOrNull {
                it.role == AudioAssetRole.DEVICE_OPUS &&
                    it.integrityState == AudioIntegrityState.VERIFIED
            } ?: row.assets.firstOrNull {
                it.role == AudioAssetRole.DEVICE_WAV &&
                    it.integrityState == AudioIntegrityState.VERIFIED
            } ?: return null
        val existingCanonical = row.assets.firstOrNull {
            it.role == AudioAssetRole.CANONICAL_WAV &&
                it.integrityState == AudioIntegrityState.VERIFIED
        }?.toModel()
        val derivation = dao.findDerivation(
            recordingId = recordingId,
            profileId = profileId,
            sourceSha256 = source.sha256,
        )
        return CanonicalConversionSource(
            recordingId = recordingId,
            sourceAssetId = source.assetId,
            sourceRole = source.role,
            sourceContainer = source.container,
            sourceRelativePath = source.relativePath,
            sourceSha256 = source.sha256,
            sourceSizeBytes = source.sizeBytes,
            deviceReportedDurationMs = row.recording.deviceReportedDurationMs,
            existingCanonical = existingCanonical,
            derivationState = derivation?.state,
        )
    }

    suspend fun updateSourceValidation(
        assetId: String,
        validationState: String,
        container: String,
        codec: String? = "OPUS",
        sampleFormat: String? = null,
        sampleRateHz: Int? = null,
        channelCount: Int? = null,
        integrityState: String = AudioIntegrityState.VERIFIED,
    ): Boolean =
        dao.updateAssetValidation(
            assetId = assetId,
            integrityState = integrityState,
            validationState = validationState,
            container = container,
            codec = codec,
            sampleFormat = sampleFormat,
            sampleRateHz = sampleRateHz,
            channelCount = channelCount,
            verifiedAtMs = nowMs(),
        ) == 1

    suspend fun markAssetIntegrity(
        assetId: String,
        integrityState: String,
        validationState: String,
    ): Boolean =
        dao.updateAssetIntegrity(
            assetId = assetId,
            integrityState = integrityState,
            validationState = validationState,
            verifiedAtMs = nowMs(),
        ) == 1

    suspend fun updateCanonicalDerivation(
        recordingId: String,
        sourceAssetId: String,
        sourceSha256: String,
        profileId: String,
        pipelineVersion: Int,
        state: String,
        outputAssetId: String? = null,
        errorCode: String? = null,
        errorDetail: String? = null,
    ) {
        val now = nowMs()
        val previous = dao.findDerivation(recordingId, profileId, sourceSha256)
        val terminal = state == AudioDerivationState.READY ||
            state == AudioDerivationState.FAILED_RECOVERABLE ||
            state == AudioDerivationState.FAILED_PERMANENT ||
            state == AudioDerivationState.CANCELLED
        dao.upsertDerivation(
            AudioDerivationEntity(
                recordingId = recordingId,
                profileId = profileId,
                sourceSha256 = sourceSha256,
                sourceAssetId = sourceAssetId,
                outputAssetId = outputAssetId ?: previous?.outputAssetId,
                pipelineVersion = pipelineVersion,
                state = state,
                startedAtMs = previous?.startedAtMs ?: now,
                updatedAtMs = now,
                completedAtMs = if (terminal) now else null,
                errorCode = errorCode,
                errorDetail = errorDetail,
            ),
        )
    }

    suspend fun loadInterruptedCanonicalDerivations(
        profileId: String,
    ): List<InterruptedCanonicalDerivation> =
        dao.findDerivationsByStates(
            profileId = profileId,
            states = ACTIVE_DERIVATION_STATES,
        ).map { derivation ->
            InterruptedCanonicalDerivation(
                recordingId = derivation.recordingId,
                sourceAssetId = derivation.sourceAssetId,
                sourceSha256 = derivation.sourceSha256,
                profileId = derivation.profileId,
                pipelineVersion = derivation.pipelineVersion,
                state = derivation.state,
            )
        }

    suspend fun reconcileInterruptedCanonicalDerivations(profileId: String): Int =
        dao.failActiveDerivations(
            profileId = profileId,
            activeStates = ACTIVE_DERIVATION_STATES,
            failedState = AudioDerivationState.FAILED_RECOVERABLE,
            updatedAtMs = nowMs(),
            errorCode = "PROCESS_INTERRUPTED",
            errorDetail = "previous conversion was interrupted before completion",
        )

    suspend fun loadRecording(recordingId: String): RecordingLibraryItem? =
        dao.findWithAssets(recordingId)?.toLibraryItem()

    suspend fun commitCanonicalWav(registration: CanonicalWavRegistration) {
        val assetId = canonicalAssetId(registration.recordingId, registration.profileId)
        database.withTransaction {
            dao.upsertAsset(
                AudioAssetEntity(
                    assetId = assetId,
                    recordingId = registration.recordingId,
                    role = AudioAssetRole.CANONICAL_WAV,
                    relativePath = registration.relativePath,
                    container = "WAV",
                    codec = "PCM",
                    sampleFormat = "PCM16_LE",
                    sampleRateHz = registration.sampleRateHz,
                    channelCount = registration.channelCount,
                    sizeBytes = registration.sizeBytes,
                    sha256 = registration.sha256.lowercase(),
                    integrityState = AudioIntegrityState.VERIFIED,
                    formatValidationState = AudioValidationState.VALID,
                    createdAtMs = registration.verifiedAtMs,
                    verifiedAtMs = registration.verifiedAtMs,
                ),
            )
            val previous = dao.findDerivation(
                registration.recordingId,
                registration.profileId,
                registration.sourceSha256,
            )
            dao.upsertDerivation(
                AudioDerivationEntity(
                    recordingId = registration.recordingId,
                    profileId = registration.profileId,
                    sourceSha256 = registration.sourceSha256,
                    sourceAssetId = registration.sourceAssetId,
                    outputAssetId = assetId,
                    pipelineVersion = registration.pipelineVersion,
                    state = AudioDerivationState.READY,
                    startedAtMs = previous?.startedAtMs ?: registration.verifiedAtMs,
                    updatedAtMs = registration.verifiedAtMs,
                    completedAtMs = registration.verifiedAtMs,
                    errorCode = null,
                    errorDetail = null,
                ),
            )
        }
    }

    suspend fun rename(recordingId: String, requestedName: String): Boolean {
        val displayName = sanitizeDisplayName(requestedName) ?: return false
        return dao.rename(recordingId, displayName, nowMs()) == 1
    }

    suspend fun deleteLocalRecording(recordingId: String): LibraryDeleteResult {
        val recording = dao.findWithAssets(recordingId)
            ?: return LibraryDeleteResult(deleted = true)
        dao.updateState(recordingId, RecordingState.DELETING, nowMs())

        val failed = deleteManagedFiles(recording)
        return if (failed.isEmpty()) {
            dao.deleteRecording(recordingId)
            LibraryDeleteResult(deleted = true)
        } else {
            LibraryDeleteResult(deleted = false, failedPaths = failed)
        }
    }

    suspend fun reconcilePendingDeletes() {
        dao.findByState(RecordingState.DELETING).forEach { recording ->
            val failed = deleteManagedFiles(recording)
            if (failed.isEmpty()) {
                dao.deleteRecording(recording.recording.id)
            }
        }
    }

    private fun deleteManagedFiles(recording: RecordingWithAssets): List<String> {
        val candidates = buildList {
            recording.assets.forEach { asset ->
                add(File(recordingsRoot, asset.relativePath))
                if (asset.relativePath.startsWith("completed/")) {
                    add(File(recordingsRoot, "completed/${asset.assetId}.properties"))
                    add(File(recordingsRoot, "completed/${asset.assetId}.properties.part"))
                }
            }
        }.distinctBy { it.path }

        return candidates.mapNotNull { file ->
            if (!isManagedPath(file)) {
                file.absolutePath
            } else if (!file.exists() || file.delete()) {
                null
            } else {
                file.absolutePath
            }
        }
    }

    suspend fun normalizeStandardDeviceDisplayNames(): Int {
        var updated = 0
        val now = nowMs()
        dao.allRecordings().forEach { recording ->
            if (
                RecordingDisplayNamePolicy.shouldNormalizeExisting(
                    displayName = recording.displayName,
                    originalFilename = recording.originalFilename,
                )
            ) {
                val normalized =
                    RecordingDisplayNamePolicy.defaultDisplayName(
                        recording.displayName,
                    )
                if (
                    normalized != recording.displayName &&
                    dao.rename(recording.id, normalized, now) == 1
                ) {
                    updated += 1
                }
            }
        }
        return updated
    }

    suspend fun migrationDiagnostics(): List<MigrationDiagnosticEntity> =
        dao.migrationDiagnostics()

    private fun sanitizeDisplayName(value: String): String? {
        val cleaned = value
            .replace(' ', '_')
            .map { ch -> if (ch.isISOControl()) '_' else ch }
            .joinToString("")
            .trim()
            .take(MAX_DISPLAY_NAME)
        return cleaned.takeIf { it.isNotBlank() }
    }

    private fun isManagedPath(file: File): Boolean {
        val root = recordingsRoot.canonicalFile
        val candidate = file.canonicalFile
        return candidate.path == root.path ||
            candidate.path.startsWith(root.path + File.separator)
    }

    private fun stableRecordingId(remoteIdentity: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(remoteIdentity.encodeToByteArray())
            .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun canonicalAssetId(recordingId: String, profileId: String): String =
        stableRecordingId("$recordingId|canonical=$profileId")

    private fun normalizeLibraryName(name: String, maxLength: Int): String {
        val normalized = name.trim().replace(Regex("\\s+"), " ")
        require(normalized.isNotEmpty()) { "name must not be blank" }
        require(normalized.length <= maxLength) { "name is too long" }
        require(normalized.none { it.isISOControl() }) { "name contains control characters" }
        return normalized
    }

    private companion object {
        const val LEGACY_IMPORT_META_KEY = "legacy_stage5_import"
        const val LEGACY_IMPORT_VERSION = "1"
        const val MAX_DISPLAY_NAME = 160
        val SHA256 = Regex("^[0-9a-f]{64}$")
        val ACTIVE_DERIVATION_STATES = listOf(
            AudioDerivationState.PREPARING,
            AudioDerivationState.DECODING,
            AudioDerivationState.NORMALIZING,
            AudioDerivationState.WRITING,
            AudioDerivationState.VERIFYING,
            AudioDerivationState.COMMITTING,
        )
    }
}

private fun LegacyStage5Candidate.toRecordingEntity(now: Long): RecordingEntity =
    RecordingEntity(
        id = recordingId,
        sourceType = RecordingSourceType.DEVICE_DOWNLOAD,
        sourceRemoteIdentity = sourceRemoteIdentity,
        sourceDeviceAddress = sourceDeviceAddress,
        originalFilename = displayFilename,
        displayName =
            RecordingDisplayNamePolicy.defaultDisplayName(displayFilename),
        recordedAtLocalIso = recordedAtLocalIso,
        deviceReportedDurationMs = deviceReportedDurationMs,
        downloadedAtMs = downloadedAtMs,
        createdAtMs = downloadedAtMs,
        updatedAtMs = now,
    )

private fun LegacyStage5Candidate.toAssetEntity(now: Long): AudioAssetEntity =
    AudioAssetEntity(
        assetId = legacyAssetId,
        recordingId = recordingId,
        role = when (sourceFormat) {
            LegacyAssetFormat.OPUS -> AudioAssetRole.DEVICE_OPUS
            LegacyAssetFormat.WAV -> AudioAssetRole.DEVICE_WAV
        },
        relativePath = relativePath,
        container = legacyContainerHint,
        codec = if (sourceFormat == LegacyAssetFormat.OPUS) "OPUS" else null,
        sampleFormat = null,
        sampleRateHz = null,
        channelCount = null,
        sizeBytes = sizeBytes,
        sha256 = sha256,
        integrityState = AudioIntegrityState.VERIFIED,
        formatValidationState = AudioValidationState.LEGACY_HINT,
        createdAtMs = downloadedAtMs,
        verifiedAtMs = now,
    )


private fun AudioAssetEntity.toModel(): RecordingAsset =
    RecordingAsset(
        assetId = assetId,
        role = role,
        relativePath = relativePath,
        container = container,
        codec = codec,
        sampleFormat = sampleFormat,
        sampleRateHz = sampleRateHz,
        channelCount = channelCount,
        sizeBytes = sizeBytes,
        sha256 = sha256,
        integrityState = integrityState,
        formatValidationState = formatValidationState,
    )
