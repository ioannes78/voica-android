package io.github.ioannes78.voica.database

/**
 * Narrow database-module facade used by Stage 13C VAD reuse.
 *
 * Keeping the DAO access here preserves the existing module boundary: callers do not need Room
 * runtime types on their compile classpath just to inspect the currently active diarization run.
 */
class DiarizationActiveRunReader(
    database: VoicaDatabase,
) {
    private val dao = database.diarizationDao()

    suspend fun loadActiveRuns(): List<DiarizationRunEntity> =
        dao.loadActiveRuns(DiarizationStateValue.ACTIVE)
}
