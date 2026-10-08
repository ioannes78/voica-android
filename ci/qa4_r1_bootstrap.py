from pathlib import Path


def read(path: str) -> str:
    return Path(path).read_text(encoding="utf-8")


def write(path: str, content: str) -> None:
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")


def replace_once(path: str, old: str, new: str) -> None:
    content = read(path)
    if old not in content:
        raise SystemExit(f"required source block not found in {path}: {old[:80]!r}")
    write(path, content.replace(old, new, 1))


# Room v11: stale-summary acknowledgement is durable and independent from candidate dismissals.
migration = "core/database/src/main/java/io/github/ioannes78/voica/database/Stage13B5DatabaseMigration.kt"
if "MIGRATION_10_11" not in read(migration):
    replace_once(
        migration,
        "internal val MIGRATION_7_8_SQL =",
        '''val MIGRATION_10_11: Migration =\n    object : Migration(10, 11) {\n        override fun migrate(db: SupportSQLiteDatabase) {\n            MIGRATION_10_11_SQL.forEach(db::execSQL)\n        }\n\n        override fun migrate(connection: SQLiteConnection) {\n            MIGRATION_10_11_SQL.forEach(connection::execSQL)\n        }\n    }\n\ninternal val MIGRATION_7_8_SQL =''',
    )
    content = read(migration).rstrip() + '''\n\ninternal val MIGRATION_10_11_SQL =\n    listOf(\n        "ALTER TABLE recording_candidate_attention ADD COLUMN dismissedStaleSummaryFingerprint TEXT",\n    )\n'''
    write(migration, content)


database = "core/database/src/main/java/io/github/ioannes78/voica/database/VoicaDatabase.kt"
if "version = 11" not in read(database):
    replace_once(database, "    version = 10,", "    version = 11,")
    replace_once(
        database,
        "                    MIGRATION_9_10,\n",
        "                    MIGRATION_9_10,\n                    MIGRATION_10_11,\n",
    )


durable = "core/database/src/main/java/io/github/ioannes78/voica/database/Stage13B5Qa4DurableState.kt"
if "dismissedStaleSummaryFingerprint" not in read(durable):
    replace_once(
        durable,
        "    val updatedAtMs: Long,\n)",
        "    val updatedAtMs: Long,\n    val dismissedStaleSummaryFingerprint: String? = null,\n)",
    )

if "fun observeTranscriptionCandidates()" not in read(durable):
    replace_once(
        durable,
        "    @Insert(onConflict = OnConflictStrategy.REPLACE)\n    suspend fun upsertCandidateAttention(entity: RecordingCandidateAttentionEntity)\n\n",
        '''    @Insert(onConflict = OnConflictStrategy.REPLACE)\n    suspend fun upsertCandidateAttention(entity: RecordingCandidateAttentionEntity)\n\n    @Query(\n        """\n        SELECT candidate.*\n        FROM transcriptions AS candidate\n        INNER JOIN recording_content_selection AS selection\n            ON selection.recordingId = candidate.recordingId\n        INNER JOIN transcriptions AS current\n            ON current.id = selection.currentTranscriptionId\n        LEFT JOIN recording_candidate_attention AS attention\n            ON attention.recordingId = candidate.recordingId\n        WHERE candidate.state = 'COMPLETED'\n          AND candidate.id != current.id\n          AND candidate.id = (\n              SELECT latest.id\n              FROM transcriptions AS latest\n              WHERE latest.recordingId = candidate.recordingId\n                AND latest.state = 'COMPLETED'\n                AND latest.id != current.id\n              ORDER BY latest.completedAtMs DESC, latest.createdAtMs DESC\n              LIMIT 1\n          )\n          AND (\n              COALESCE(candidate.completedAtMs, candidate.createdAtMs) >\n                  COALESCE(current.completedAtMs, current.createdAtMs)\n              OR (\n                  COALESCE(candidate.completedAtMs, candidate.createdAtMs) =\n                      COALESCE(current.completedAtMs, current.createdAtMs)\n                  AND candidate.createdAtMs > current.createdAtMs\n              )\n          )\n          AND (\n              attention.dismissedTranscriptionCandidateId IS NULL\n              OR attention.dismissedTranscriptionCandidateId != candidate.id\n          )\n        ORDER BY candidate.updatedAtMs DESC, candidate.createdAtMs DESC\n        """,\n    )\n    fun observeTranscriptionCandidates(): Flow<List<TranscriptionEntity>>\n\n    @Query(\n        """\n        SELECT candidate.*\n        FROM ai_summaries AS candidate\n        INNER JOIN recording_content_selection AS selection\n            ON selection.recordingId = candidate.recordingId\n        INNER JOIN ai_summaries AS current\n            ON current.id = selection.currentAiSummaryId\n        LEFT JOIN recording_candidate_attention AS attention\n            ON attention.recordingId = candidate.recordingId\n        WHERE candidate.status = 'COMPLETED'\n          AND candidate.id != current.id\n          AND candidate.id = (\n              SELECT latest.id\n              FROM ai_summaries AS latest\n              WHERE latest.recordingId = candidate.recordingId\n                AND latest.status = 'COMPLETED'\n                AND latest.id != current.id\n              ORDER BY latest.completedAtMs DESC, latest.createdAtMs DESC\n              LIMIT 1\n          )\n          AND (\n              COALESCE(candidate.completedAtMs, candidate.createdAtMs) >\n                  COALESCE(current.completedAtMs, current.createdAtMs)\n              OR (\n                  COALESCE(candidate.completedAtMs, candidate.createdAtMs) =\n                      COALESCE(current.completedAtMs, current.createdAtMs)\n                  AND candidate.createdAtMs > current.createdAtMs\n              )\n          )\n          AND (\n              attention.dismissedAiSummaryCandidateId IS NULL\n              OR attention.dismissedAiSummaryCandidateId != candidate.id\n          )\n        ORDER BY candidate.updatedAtMs DESC, candidate.createdAtMs DESC\n        """,\n    )\n    fun observeAiSummaryCandidates(): Flow<List<AiSummaryEntity>>\n\n''',
    )

if "fun observeTranscriptionCandidates(): Flow<List<TranscriptionEntity>> =" not in read(durable):
    replace_once(
        durable,
        "    fun observeCandidateAttention(recordingId: String): Flow<RecordingCandidateAttentionEntity?> =\n        dao.observeCandidateAttention(recordingId)\n\n",
        '''    fun observeCandidateAttention(recordingId: String): Flow<RecordingCandidateAttentionEntity?> =\n        dao.observeCandidateAttention(recordingId)\n\n    fun observeTranscriptionCandidates(): Flow<List<TranscriptionEntity>> =\n        dao.observeTranscriptionCandidates()\n\n    fun observeAiSummaryCandidates(): Flow<List<AiSummaryEntity>> =\n        dao.observeAiSummaryCandidates()\n\n''',
    )

if "suspend fun dismissStaleSummary(" not in read(durable):
    replace_once(
        durable,
        "    suspend fun clearTranscriptionCandidateDismissal(recordingId: String) {\n",
        '''    suspend fun dismissStaleSummary(\n        recordingId: String,\n        fingerprint: String,\n    ) {\n        require(recordingId.isNotBlank())\n        require(fingerprint.isNotBlank())\n        val current = dao.findCandidateAttention(recordingId)\n        dao.upsertCandidateAttention(\n            (current ?: RecordingCandidateAttentionEntity(\n                recordingId = recordingId,\n                dismissedTranscriptionCandidateId = null,\n                dismissedAiSummaryCandidateId = null,\n                updatedAtMs = nowMs(),\n            )).copy(\n                dismissedStaleSummaryFingerprint = fingerprint,\n                updatedAtMs = nowMs(),\n            ),\n        )\n    }\n\n    suspend fun clearTranscriptionCandidateDismissal(recordingId: String) {\n''',
    )


# Unified durable in-app projection with page-local suppression only (view != resolved).
global_attention = "app/src/main/java/io/github/ioannes78/voica/Stage13B5Qa4GlobalAttention.kt"
write(
    global_attention,
    '''package io.github.ioannes78.voica\n\nimport androidx.compose.foundation.clickable\nimport androidx.compose.foundation.layout.Arrangement\nimport androidx.compose.foundation.layout.Box\nimport androidx.compose.foundation.layout.Column\nimport androidx.compose.foundation.layout.Row\nimport androidx.compose.foundation.layout.fillMaxSize\nimport androidx.compose.foundation.layout.fillMaxWidth\nimport androidx.compose.foundation.layout.padding\nimport androidx.compose.material3.Card\nimport androidx.compose.material3.MaterialTheme\nimport androidx.compose.material3.Text\nimport androidx.compose.runtime.Composable\nimport androidx.compose.runtime.collectAsState\nimport androidx.compose.runtime.getValue\nimport androidx.compose.ui.Alignment\nimport androidx.compose.ui.Modifier\nimport androidx.compose.ui.unit.dp\nimport io.github.ioannes78.voica.database.AiSummaryEntity\nimport io.github.ioannes78.voica.database.AiSummaryStateValue\nimport io.github.ioannes78.voica.database.RecordingLibraryItem\nimport io.github.ioannes78.voica.database.TranscriptionEntity\nimport io.github.ioannes78.voica.database.TranscriptionStateValue\nimport io.github.ioannes78.voica.ui.library.RecordingDetailDestination\nimport kotlinx.coroutines.flow.MutableStateFlow\nimport kotlinx.coroutines.flow.StateFlow\nimport kotlinx.coroutines.flow.asStateFlow\n\ninternal data class Qa4VisibleDetailContext(\n    val recordingId: String,\n    val destination: RecordingDetailDestination,\n)\n\ninternal object Stage13B5Qa4PageVisibility {\n    private val mutableCurrent = MutableStateFlow<Qa4VisibleDetailContext?>(null)\n    val current: StateFlow<Qa4VisibleDetailContext?> = mutableCurrent.asStateFlow()\n\n    fun update(recordingId: String, destination: RecordingDetailDestination?) {\n        mutableCurrent.value =\n            destination?.let { Qa4VisibleDetailContext(recordingId = recordingId, destination = it) }\n    }\n\n    fun clear(recordingId: String) {\n        if (mutableCurrent.value?.recordingId == recordingId) {\n            mutableCurrent.value = null\n        }\n    }\n}\n\ninternal data class Qa4GlobalAttentionItem(\n    val key: String,\n    val recordingId: String,\n    val recordingName: String,\n    val label: String,\n    val destination: RecordingDetailDestination,\n)\n\n@Composable\ninternal fun Stage13B5Qa4GlobalAttentionHost(\n    container: AppContainer,\n    content: @Composable () -> Unit,\n) {\n    val transcriptionAttention by\n        container.stage13B5Qa4Repository.observeTranscriptionAttention()\n            .collectAsState(initial = emptyList())\n    val aiSummaryAttention by\n        container.stage13B5Qa4Repository.observeAiSummaryAttention()\n            .collectAsState(initial = emptyList())\n    val transcriptionCandidates by\n        container.stage13B5Qa4Repository.observeTranscriptionCandidates()\n            .collectAsState(initial = emptyList())\n    val aiSummaryCandidates by\n        container.stage13B5Qa4Repository.observeAiSummaryCandidates()\n            .collectAsState(initial = emptyList())\n    val recordings by\n        container.recordingLibraryRepository.recordings\n            .collectAsState(initial = emptyList())\n    val visibleDetail by Stage13B5Qa4PageVisibility.current.collectAsState()\n    val items =\n        filterQa4GlobalAttentionItems(\n            items =\n                buildQa4GlobalAttentionItems(\n                    transcriptionAttention = transcriptionAttention,\n                    aiSummaryAttention = aiSummaryAttention,\n                    transcriptionCandidates = transcriptionCandidates,\n                    aiSummaryCandidates = aiSummaryCandidates,\n                    recordings = recordings,\n                ),\n            visibleDetail = visibleDetail,\n        )\n\n    Column(modifier = Modifier.fillMaxSize()) {\n        if (items.isNotEmpty()) {\n            Qa4GlobalAttentionBar(\n                items = items,\n                onOpen = { item ->\n                    AppRecordingNavigation.publish(\n                        recordingId = item.recordingId,\n                        destination = item.destination,\n                    )\n                },\n            )\n        }\n        Box(\n            modifier =\n                Modifier\n                    .weight(1f)\n                    .fillMaxWidth(),\n        ) {\n            content()\n        }\n    }\n}\n\ninternal fun filterQa4GlobalAttentionItems(\n    items: List<Qa4GlobalAttentionItem>,\n    visibleDetail: Qa4VisibleDetailContext?,\n): List<Qa4GlobalAttentionItem> =\n    if (visibleDetail == null) {\n        items\n    } else {\n        items.filterNot { item ->\n            item.recordingId == visibleDetail.recordingId &&\n                item.destination == visibleDetail.destination\n        }\n    }\n\ninternal fun buildQa4GlobalAttentionItems(\n    transcriptionAttention: List<TranscriptionEntity>,\n    aiSummaryAttention: List<AiSummaryEntity>,\n    recordings: List<RecordingLibraryItem>,\n    transcriptionCandidates: List<TranscriptionEntity> = emptyList(),\n    aiSummaryCandidates: List<AiSummaryEntity> = emptyList(),\n): List<Qa4GlobalAttentionItem> {\n    fun recordingName(recordingId: String): String =\n        recordings.firstOrNull { it.id == recordingId }?.displayName ?: "录音"\n\n    return buildList {\n        transcriptionAttention.forEach { transcription ->\n            add(\n                Qa4GlobalAttentionItem(\n                    key = "transcription-attention:${transcription.id}",\n                    recordingId = transcription.recordingId,\n                    recordingName = recordingName(transcription.recordingId),\n                    label =\n                        if (transcription.state == TranscriptionStateValue.INTERRUPTED) {\n                            "转写已中断"\n                        } else {\n                            "转写失败"\n                        },\n                    destination = RecordingDetailDestination.TRANSCRIPT,\n                ),\n            )\n        }\n        aiSummaryAttention.forEach { summary ->\n            add(\n                Qa4GlobalAttentionItem(\n                    key = "summary-attention:${summary.id}",\n                    recordingId = summary.recordingId,\n                    recordingName = recordingName(summary.recordingId),\n                    label =\n                        when (summary.status) {\n                            AiSummaryStateValue.AMBIGUOUS_REMOTE_RESULT -> "总结状态待确认"\n                            AiSummaryStateValue.INTERRUPTED -> "总结已中断"\n                            else -> "总结失败"\n                        },\n                    destination = RecordingDetailDestination.SUMMARY,\n                ),\n            )\n        }\n        transcriptionCandidates.forEach { transcription ->\n            add(\n                Qa4GlobalAttentionItem(\n                    key = "transcription-candidate:${transcription.id}",\n                    recordingId = transcription.recordingId,\n                    recordingName = recordingName(transcription.recordingId),\n                    label = "新的转写结果已生成",\n                    destination = RecordingDetailDestination.TRANSCRIPT,\n                ),\n            )\n        }\n        aiSummaryCandidates.forEach { summary ->\n            add(\n                Qa4GlobalAttentionItem(\n                    key = "summary-candidate:${summary.id}",\n                    recordingId = summary.recordingId,\n                    recordingName = recordingName(summary.recordingId),\n                    label = "新的总结结果已生成",\n                    destination = RecordingDetailDestination.SUMMARY,\n                ),\n            )\n        }\n    }\n}\n\n@Composable\nprivate fun Qa4GlobalAttentionBar(\n    items: List<Qa4GlobalAttentionItem>,\n    onOpen: (Qa4GlobalAttentionItem) -> Unit,\n) {\n    Card(modifier = Modifier.fillMaxWidth()) {\n        Column(\n            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),\n            verticalArrangement = Arrangement.spacedBy(3.dp),\n        ) {\n            Text(\n                if (items.size == 1) "任务通知" else "${items.size} 条任务通知",\n                style = MaterialTheme.typography.labelMedium,\n                color = MaterialTheme.colorScheme.onSurfaceVariant,\n            )\n            items.forEach { item ->\n                Row(\n                    modifier =\n                        Modifier\n                            .fillMaxWidth()\n                            .clickable { onOpen(item) }\n                            .padding(vertical = 3.dp),\n                    verticalAlignment = Alignment.CenterVertically,\n                    horizontalArrangement = Arrangement.spacedBy(8.dp),\n                ) {\n                    Text(\n                        item.label + " · " + item.recordingName,\n                        modifier = Modifier.weight(1f),\n                        style = MaterialTheme.typography.bodyMedium,\n                        maxLines = 1,\n                    )\n                    Text(\n                        "查看 ›",\n                        style = MaterialTheme.typography.labelLarge,\n                        color = MaterialTheme.colorScheme.primary,\n                    )\n                }\n            }\n        }\n    }\n}\n''',
)


# Current recording/destination visibility is projection-only; leaving the page restores global notices.
detail_screen = "app/src/main/java/io/github/ioannes78/voica/ui/library/RecordingDetailScreen.kt"
if "DisposableEffect" not in read(detail_screen):
    replace_once(
        detail_screen,
        "import androidx.compose.runtime.Composable\n",
        "import androidx.compose.runtime.Composable\nimport androidx.compose.runtime.DisposableEffect\n",
    )
if "Stage13B5Qa4PageVisibility" not in read(detail_screen):
    replace_once(
        detail_screen,
        "import io.github.ioannes78.voica.SpeechBenchmarkRunner\n",
        "import io.github.ioannes78.voica.SpeechBenchmarkRunner\nimport io.github.ioannes78.voica.Stage13B5Qa4PageVisibility\n",
    )
if "Stage13B5Qa4PageVisibility.clear(recording.id)" not in read(detail_screen):
    replace_once(
        detail_screen,
        ") {\n    RecordingDetailProductScreen(\n",
        ") {\n    DisposableEffect(recording.id) {\n        onDispose { Stage13B5Qa4PageVisibility.clear(recording.id) }\n    }\n\n    RecordingDetailProductScreen(\n",
    )
    replace_once(
        detail_screen,
        "        onDestinationChanged = onDestinationChanged,\n",
        '''        onDestinationChanged = { destination ->\n            Stage13B5Qa4PageVisibility.update(recording.id, destination)\n            onDestinationChanged(destination)\n        },\n''',
    )


lifecycle_vm = "app/src/main/java/io/github/ioannes78/voica/Stage13B5Qa4LifecycleViewModel.kt"
if "fun dismissStaleSummary(" not in read(lifecycle_vm):
    replace_once(
        lifecycle_vm,
        "    fun ignoreTranscriptionAttention(transcriptionId: String) {\n",
        '''    fun dismissStaleSummary(fingerprint: String) {\n        val recordingId = boundRecordingId ?: return\n        if (fingerprint.isBlank()) return\n        viewModelScope.launch {\n            qa4Repository.dismissStaleSummary(recordingId, fingerprint)\n        }\n    }\n\n    fun ignoreTranscriptionAttention(transcriptionId: String) {\n''',
    )


stale_policy = "app/src/main/java/io/github/ioannes78/voica/ui/ai/SummaryStalePolicy.kt"
if "summaryStaleFingerprint" not in read(stale_policy):
    write(
        stale_policy,
        read(stale_policy).rstrip()
        + '''\n\ninternal fun summaryStaleFingerprint(\n    summaryId: String,\n    effective: EffectiveTranscriptionRef?,\n): String =\n    listOf(\n        summaryId,\n        effective?.transcriptionId.orEmpty(),\n        effective?.revisionId.orEmpty(),\n    ).joinToString("|")\n''',
    )


# Durable candidate result system notifications share candidate dismissal/adoption lifecycle.
candidate_controller = "app/src/main/java/io/github/ioannes78/voica/Stage13B5Qa4CandidateNotificationController.kt"
write(
    candidate_controller,
    '''package io.github.ioannes78.voica\n\nimport android.app.Notification\nimport android.app.NotificationChannel\nimport android.app.NotificationManager\nimport android.content.Context\nimport io.github.ioannes78.voica.database.RecordingLibraryItem\nimport io.github.ioannes78.voica.database.RecordingLibraryRepository\nimport io.github.ioannes78.voica.database.Stage13B5Qa4Repository\nimport io.github.ioannes78.voica.ui.library.RecordingDetailDestination\nimport kotlinx.coroutines.CoroutineScope\nimport kotlinx.coroutines.flow.combine\nimport kotlinx.coroutines.launch\n\nclass Stage13B5Qa4CandidateNotificationController(\n    private val context: Context,\n    scope: CoroutineScope,\n    private val recordingRepository: RecordingLibraryRepository,\n    private val qa4Repository: Stage13B5Qa4Repository,\n) {\n    private val notificationManager = context.getSystemService(NotificationManager::class.java)\n    private var transcriptionNotificationIds: Set<Int> = emptySet()\n    private var aiSummaryNotificationIds: Set<Int> = emptySet()\n\n    init {\n        createChannel()\n        scope.launch {\n            combine(\n                qa4Repository.observeTranscriptionCandidates(),\n                recordingRepository.recordings,\n            ) { candidates, recordings -> candidates to recordings }\n                .collect { (candidates, recordings) ->\n                    val nextIds = mutableSetOf<Int>()\n                    candidates.forEach { candidate ->\n                        val notificationId = transcriptionCandidateNotificationId(candidate.id)\n                        nextIds += notificationId\n                        notificationManager.notify(\n                            notificationId,\n                            buildNotification(\n                                notificationId = notificationId,\n                                title = "转写已完成",\n                                text = "新的转写结果已生成",\n                                recordingId = candidate.recordingId,\n                                recordingName = recordingName(recordings, candidate.recordingId),\n                                destination = RecordingDetailDestination.TRANSCRIPT,\n                            ),\n                        )\n                    }\n                    (transcriptionNotificationIds - nextIds).forEach(notificationManager::cancel)\n                    transcriptionNotificationIds = nextIds\n                }\n        }\n        scope.launch {\n            combine(\n                qa4Repository.observeAiSummaryCandidates(),\n                recordingRepository.recordings,\n            ) { candidates, recordings -> candidates to recordings }\n                .collect { (candidates, recordings) ->\n                    val nextIds = mutableSetOf<Int>()\n                    candidates.forEach { candidate ->\n                        val notificationId = aiSummaryCandidateNotificationId(candidate.id)\n                        nextIds += notificationId\n                        notificationManager.notify(\n                            notificationId,\n                            buildNotification(\n                                notificationId = notificationId,\n                                title = "AI 总结已完成",\n                                text = "新的总结结果已生成",\n                                recordingId = candidate.recordingId,\n                                recordingName = recordingName(recordings, candidate.recordingId),\n                                destination = RecordingDetailDestination.SUMMARY,\n                            ),\n                        )\n                    }\n                    (aiSummaryNotificationIds - nextIds).forEach(notificationManager::cancel)\n                    aiSummaryNotificationIds = nextIds\n                }\n        }\n    }\n\n    private fun buildNotification(\n        notificationId: Int,\n        title: String,\n        text: String,\n        recordingId: String,\n        recordingName: String,\n        destination: RecordingDetailDestination,\n    ): Notification =\n        Notification.Builder(context, CHANNEL_ID)\n            .setSmallIcon(android.R.drawable.stat_sys_download_done)\n            .setContentTitle(title)\n            .setContentText(text)\n            .setSubText(recordingName)\n            .setContentIntent(\n                recordingOpenPendingIntent(\n                    context = context,\n                    requestCode = notificationId,\n                    recordingId = recordingId,\n                    destination = destination,\n                ),\n            )\n            .setOnlyAlertOnce(true)\n            .setOngoing(false)\n            .setAutoCancel(false)\n            .setCategory(Notification.CATEGORY_STATUS)\n            .build()\n\n    private fun createChannel() {\n        notificationManager.createNotificationChannel(\n            NotificationChannel(\n                CHANNEL_ID,\n                "任务结果",\n                NotificationManager.IMPORTANCE_LOW,\n            ).apply {\n                description = "转写或 AI 总结生成新结果时提醒"\n                setShowBadge(true)\n            },\n        )\n    }\n\n    private fun recordingName(recordings: List<RecordingLibraryItem>, recordingId: String): String =\n        recordings.firstOrNull { it.id == recordingId }?.displayName ?: "录音"\n\n    private fun transcriptionCandidateNotificationId(id: String): Int =\n        TRANSCRIPTION_CANDIDATE_BASE + stableOffset(id)\n\n    private fun aiSummaryCandidateNotificationId(id: String): Int =\n        AI_SUMMARY_CANDIDATE_BASE + stableOffset(id)\n\n    private fun stableOffset(id: String): Int = id.hashCode() and 0x000f_ffff\n\n    private companion object {\n        const val CHANNEL_ID = "voica-task-result"\n        const val TRANSCRIPTION_CANDIDATE_BASE = 160_000_000\n        const val AI_SUMMARY_CANDIDATE_BASE = 170_000_000\n    }\n}\n''',
)


# App scope owns candidate notification restoration/cancellation.
application = "app/src/main/java/io/github/ioannes78/voica/VoicaApplication.kt"
if "stage13B5Qa4CandidateNotificationController" not in read(application):
    replace_once(
        application,
        '''    val longTaskNotificationController =\n        LongTaskNotificationController(\n            context = application,\n            scope = applicationScope,\n            recordingRepository = recordingLibraryRepository,\n            transcriptionCoordinator = transcriptionCoordinator,\n            diarizationCoordinator = diarizationCoordinator,\n            aiSummaryCoordinator = aiSummaryCoordinator,\n            aiSummaryRepository = aiSummaryRepository,\n            stage13B5Qa4Repository = stage13B5Qa4Repository,\n        )\n\n''',
        '''    val longTaskNotificationController =\n        LongTaskNotificationController(\n            context = application,\n            scope = applicationScope,\n            recordingRepository = recordingLibraryRepository,\n            transcriptionCoordinator = transcriptionCoordinator,\n            diarizationCoordinator = diarizationCoordinator,\n            aiSummaryCoordinator = aiSummaryCoordinator,\n            aiSummaryRepository = aiSummaryRepository,\n            stage13B5Qa4Repository = stage13B5Qa4Repository,\n        )\n\n    val stage13B5Qa4CandidateNotificationController =\n        Stage13B5Qa4CandidateNotificationController(\n            context = application,\n            scope = applicationScope,\n            recordingRepository = recordingLibraryRepository,\n            qa4Repository = stage13B5Qa4Repository,\n        )\n\n''',
    )


# Legacy in-process terminal notices remain only for diarization. Transcription/summary terminal and
# candidate notices are now durable Room projections in Stage13B5Qa4GlobalAttentionHost.
voica_app = "app/src/main/java/io/github/ioannes78/voica/ui/VoicaApp.kt"
if "task.key.startsWith(\"diarization-\")" not in read(voica_app):
    replace_once(
        voica_app,
        "    val liveTerminalTasks = liveGlobalTasks.filter { it.terminal }\n",
        '''    val liveTerminalTasks =\n        liveGlobalTasks.filter { task ->\n            task.terminal && task.key.startsWith("diarization-")\n        }\n''',
    )


# Stale summary fingerprint is tied to current summary + effective transcription/revision.
ai_vm = "app/src/main/java/io/github/ioannes78/voica/ui/ai/AiSummaryViewModel.kt"
if "mutableStaleFingerprint" not in read(ai_vm):
    replace_once(
        ai_vm,
        '''    private val mutableStale = MutableStateFlow(false)\n    val stale: StateFlow<Boolean> = mutableStale.asStateFlow()\n\n''',
        '''    private val mutableStale = MutableStateFlow(false)\n    val stale: StateFlow<Boolean> = mutableStale.asStateFlow()\n\n    private val mutableStaleFingerprint = MutableStateFlow<String?>(null)\n    val staleFingerprint: StateFlow<String?> = mutableStaleFingerprint.asStateFlow()\n\n''',
    )
    replace_once(
        ai_vm,
        '''        mutableAttention.value = null\n        mutableStale.value = false\n        mutableNotice.value = null\n''',
        '''        mutableAttention.value = null\n        mutableStale.value = false\n        mutableStaleFingerprint.value = null\n        mutableNotice.value = null\n''',
    )
    replace_once(
        ai_vm,
        '''        if (target == null) {\n            mutableSelected.value = null\n            mutableStale.value = false\n        } else if (mutableSelected.value?.entity?.id != target.id) {\n''',
        '''        if (target == null) {\n            mutableSelected.value = null\n            mutableStale.value = false\n            mutableStaleFingerprint.value = null\n        } else if (mutableSelected.value?.entity?.id != target.id) {\n''',
    )
    replace_once(
        ai_vm,
        '''        if (summary == null || summary.status != AiSummaryStateValue.COMPLETED) {\n            mutableStale.value = false\n            return\n        }\n        val effective = contentRepository.resolveEffectiveTranscription(recordingId)\n        val lineage = parseSummaryLineage(summary.sourceLineageSnapshot)\n        mutableStale.value =\n            isSummaryStale(\n                lineageTranscriptionId = lineage.transcriptionId,\n                lineageRevisionId = lineage.revisionId,\n                effective = effective,\n            )\n''',
        '''        if (summary == null || summary.status != AiSummaryStateValue.COMPLETED) {\n            mutableStale.value = false\n            mutableStaleFingerprint.value = null\n            return\n        }\n        val effective = contentRepository.resolveEffectiveTranscription(recordingId)\n        val lineage = parseSummaryLineage(summary.sourceLineageSnapshot)\n        val stale =\n            isSummaryStale(\n                lineageTranscriptionId = lineage.transcriptionId,\n                lineageRevisionId = lineage.revisionId,\n                effective = effective,\n            )\n        mutableStale.value = stale\n        mutableStaleFingerprint.value =\n            if (stale) summaryStaleFingerprint(summary.id, effective) else null\n''',
    )


ai_card = "app/src/main/java/io/github/ioannes78/voica/ui/ai/AiSummaryProductCard.kt"
if "dismissedStaleSummaryFingerprint" not in read(ai_card):
    replace_once(
        ai_card,
        '''    showTransientHeader: Boolean = true,\n    attentionOverride: AiSummaryEntity? = null,\n) {\n''',
        '''    showTransientHeader: Boolean = true,\n    attentionOverride: AiSummaryEntity? = null,\n    dismissedStaleSummaryFingerprint: String? = null,\n    onIgnoreStale: (String) -> Unit = {},\n) {\n''',
    )
    replace_once(
        ai_card,
        '''    val stale by viewModel.stale.collectAsState()\n    val provider by viewModel.provider.collectAsState()\n''',
        '''    val stale by viewModel.stale.collectAsState()\n    val staleFingerprint by viewModel.staleFingerprint.collectAsState()\n    val provider by viewModel.provider.collectAsState()\n''',
    )
    replace_once(
        ai_card,
        '''        if (stale && selected?.entity?.status == AiSummaryStateValue.COMPLETED) {\n            Surface(\n                modifier = Modifier.fillMaxWidth(),\n                shape = MaterialTheme.shapes.medium,\n                tonalElevation = 1.dp,\n            ) {\n                Row(\n                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),\n                    verticalAlignment = Alignment.CenterVertically,\n                    horizontalArrangement = Arrangement.spacedBy(8.dp),\n                ) {\n                    Text(\n                        "当前总结基于较早的转写内容",\n                        modifier = Modifier.weight(1f),\n                        style = MaterialTheme.typography.bodyMedium,\n                    )\n                    TextButton(\n                        enabled = running == null,\n                        onClick = { generationOpen = true },\n                    ) {\n                        Text("生成新总结")\n                    }\n                }\n            }\n        }\n''',
        '''        val visibleStaleFingerprint =\n            staleFingerprint?.takeUnless { it == dismissedStaleSummaryFingerprint }\n        if (\n            stale &&\n            visibleStaleFingerprint != null &&\n            selected?.entity?.status == AiSummaryStateValue.COMPLETED\n        ) {\n            Surface(\n                modifier = Modifier.fillMaxWidth(),\n                shape = MaterialTheme.shapes.medium,\n                tonalElevation = 1.dp,\n            ) {\n                Row(\n                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),\n                    verticalAlignment = Alignment.CenterVertically,\n                    horizontalArrangement = Arrangement.spacedBy(8.dp),\n                ) {\n                    Text(\n                        "当前总结基于较早的转写内容",\n                        modifier = Modifier.weight(1f),\n                        style = MaterialTheme.typography.bodyMedium,\n                    )\n                    TextButton(\n                        enabled = running == null,\n                        onClick = { generationOpen = true },\n                    ) {\n                        Text("生成新总结")\n                    }\n                    TextButton(\n                        onClick = { onIgnoreStale(visibleStaleFingerprint) },\n                    ) {\n                        Text("忽略")\n                    }\n                }\n            }\n        }\n''',
    )


detail_product = "app/src/main/java/io/github/ioannes78/voica/ui/library/RecordingDetailProductScreen.kt"
if "dismissedStaleSummaryFingerprint =" not in read(detail_product):
    replace_once(
        detail_product,
        '''                                        showTransientHeader = false,\n                                    )\n''',
        '''                                        showTransientHeader = false,\n                                        dismissedStaleSummaryFingerprint =\n                                            candidateAttention?.dismissedStaleSummaryFingerprint,\n                                        onIgnoreStale = qa4LifecycleViewModel::dismissStaleSummary,\n                                    )\n''',
    )


# Tests: stale fingerprint semantics, durable dismissal isolation, durable candidate projection,
# page-local suppression, and v10->v11 migration.
stale_test = "app/src/test/java/io/github/ioannes78/voica/ui/ai/SummaryStalePolicyTest.kt"
if "staleFingerprintChangesWithEffectiveTranscript" not in read(stale_test):
    replace_once(
        stale_test,
        "import org.junit.Assert.assertFalse\n",
        "import org.junit.Assert.assertEquals\nimport org.junit.Assert.assertFalse\nimport org.junit.Assert.assertNotEquals\n",
    )
    replace_once(
        stale_test,
        "}\n",
        '''    @Test\n    fun staleFingerprintChangesWithEffectiveTranscript() {\n        val first = summaryStaleFingerprint("summary", EffectiveTranscriptionRef("t1", null))\n        val same = summaryStaleFingerprint("summary", EffectiveTranscriptionRef("t1", null))\n        val changedRevision = summaryStaleFingerprint("summary", EffectiveTranscriptionRef("t1", "r1"))\n        val changedTranscript = summaryStaleFingerprint("summary", EffectiveTranscriptionRef("t2", null))\n\n        assertEquals(first, same)\n        assertNotEquals(first, changedRevision)\n        assertNotEquals(first, changedTranscript)\n    }\n}\n''',
    )


durable_test = "core/database/src/test/java/io/github/ioannes78/voica/database/Stage13B5Qa4DurableStateTest.kt"
if "dismissedStaleSummaryFingerprint" not in read(durable_test):
    replace_once(
        durable_test,
        '''        assertEquals("tx-candidate", attention?.dismissedTranscriptionCandidateId)\n        assertEquals("summary-candidate", attention?.dismissedAiSummaryCandidateId)\n\n        repository.clearTranscriptionCandidateDismissal(RECORDING_ID)\n''',
        '''        assertEquals("tx-candidate", attention?.dismissedTranscriptionCandidateId)\n        assertEquals("summary-candidate", attention?.dismissedAiSummaryCandidateId)\n\n        repository.dismissStaleSummary(RECORDING_ID, "summary-current|tx-current|revision-1")\n        attention = repository.observeCandidateAttention(RECORDING_ID).first()\n        assertEquals("tx-candidate", attention?.dismissedTranscriptionCandidateId)\n        assertEquals("summary-candidate", attention?.dismissedAiSummaryCandidateId)\n        assertEquals(\n            "summary-current|tx-current|revision-1",\n            attention?.dismissedStaleSummaryFingerprint,\n        )\n\n        repository.clearTranscriptionCandidateDismissal(RECORDING_ID)\n''',
    )
    replace_once(
        durable_test,
        '''        assertNull(attention?.dismissedTranscriptionCandidateId)\n        assertEquals("summary-candidate", attention?.dismissedAiSummaryCandidateId)\n    }\n\n''',
        '''        assertNull(attention?.dismissedTranscriptionCandidateId)\n        assertEquals("summary-candidate", attention?.dismissedAiSummaryCandidateId)\n        assertEquals(\n            "summary-current|tx-current|revision-1",\n            attention?.dismissedStaleSummaryFingerprint,\n        )\n    }\n\n    @Test\n    fun transcriptionCandidateProjectionIsDurableUntilDismissed() = runBlocking {\n        val contentRepository = Stage12CContentRepository(database, nowMs = { clock++ })\n        insertTranscription(\n            id = "tx-current",\n            state = TranscriptionStateValue.COMPLETED,\n            createdAtMs = 100L,\n        )\n        contentRepository.onTranscriptionCompleted(RECORDING_ID, "tx-current")\n\n        insertTranscription(\n            id = "tx-candidate",\n            state = TranscriptionStateValue.COMPLETED,\n            createdAtMs = 200L,\n        )\n        contentRepository.onTranscriptionCompleted(RECORDING_ID, "tx-candidate")\n\n        assertEquals(\n            listOf("tx-candidate"),\n            repository.observeTranscriptionCandidates().first().map { it.id },\n        )\n\n        repository.dismissTranscriptionCandidate(RECORDING_ID, "tx-candidate")\n        assertTrue(repository.observeTranscriptionCandidates().first().isEmpty())\n    }\n\n''',
    )


global_test = "app/src/test/java/io/github/ioannes78/voica/Stage13B5Qa4GlobalAttentionTest.kt"
if "candidateProjectionIsSuppressedOnlyOnMatchingDetailPage" not in read(global_test):
    replace_once(
        global_test,
        "    private fun transcription(state: String): TranscriptionEntity =\n",
        '''    @Test\n    fun candidateProjectionIsSuppressedOnlyOnMatchingDetailPage() {\n        val items =\n            buildQa4GlobalAttentionItems(\n                transcriptionAttention = emptyList(),\n                aiSummaryAttention = emptyList(),\n                recordings = emptyList(),\n                transcriptionCandidates = listOf(transcription(TranscriptionStateValue.COMPLETED)),\n                aiSummaryCandidates = listOf(summary(AiSummaryStateValue.COMPLETED)),\n            )\n\n        assertEquals(2, items.size)\n        assertEquals("新的转写结果已生成", items[0].label)\n        assertEquals("新的总结结果已生成", items[1].label)\n\n        val filtered =\n            filterQa4GlobalAttentionItems(\n                items = items,\n                visibleDetail =\n                    Qa4VisibleDetailContext(\n                        recordingId = "recording",\n                        destination = RecordingDetailDestination.TRANSCRIPT,\n                    ),\n            )\n\n        assertEquals(1, filtered.size)\n        assertEquals(RecordingDetailDestination.SUMMARY, filtered.single().destination)\n    }\n\n    private fun transcription(state: String): TranscriptionEntity =\n''',
    )


migration_test = "core/database/src/test/java/io/github/ioannes78/voica/database/Stage13B5Qa4MigrationTest.kt"
if "migration10To11PreservesCandidateStateAndAddsStaleAcknowledgement" not in read(migration_test):
    replace_once(
        migration_test,
        "    private companion object {\n",
        '''    @Test\n    fun migration10To11PreservesCandidateStateAndAddsStaleAcknowledgement() {\n        val helper =\n            MigrationTestHelper(\n                InstrumentationRegistry.getInstrumentation(),\n                VoicaDatabase::class.java,\n            )\n\n        val v10 = helper.createDatabase(TEST_DB_V11, 10)\n        try {\n            v10.execSQL("PRAGMA foreign_keys = ON")\n            v10.execSQL(\n                """\n                INSERT INTO recordings (\n                    id, sourceType, sourceRemoteIdentity, sourceDeviceAddress,\n                    originalFilename, displayName, recordedAtLocalIso,\n                    deviceReportedDurationMs, mediaDurationMs, downloadedAtMs,\n                    createdAtMs, updatedAtMs, state\n                ) VALUES (\n                    'rec-qa4-r1', 'LOCAL_IMPORT', NULL, NULL,\n                    'qa4-r1.wav', 'QA4-R1', NULL,\n                    1000, 1000, NULL, 1, 1, 'ACTIVE'\n                )\n                """.trimIndent(),\n            )\n            v10.execSQL(\n                """\n                INSERT INTO recording_candidate_attention (\n                    recordingId, dismissedTranscriptionCandidateId,\n                    dismissedAiSummaryCandidateId, updatedAtMs\n                ) VALUES ('rec-qa4-r1', 'tx-candidate', 'summary-candidate', 10)\n                """.trimIndent(),\n            )\n        } finally {\n            v10.close()\n        }\n\n        val migrated =\n            helper.runMigrationsAndValidate(\n                TEST_DB_V11,\n                11,\n                true,\n                MIGRATION_10_11,\n            )\n\n        try {\n            migrated.query(\n                """\n                SELECT dismissedTranscriptionCandidateId, dismissedAiSummaryCandidateId,\n                       dismissedStaleSummaryFingerprint\n                FROM recording_candidate_attention\n                WHERE recordingId = 'rec-qa4-r1'\n                """.trimIndent(),\n            ).use { cursor ->\n                assertTrue(cursor.moveToFirst())\n                assertEquals("tx-candidate", cursor.getString(0))\n                assertEquals("summary-candidate", cursor.getString(1))\n                assertTrue(cursor.isNull(2))\n            }\n\n            migrated.execSQL(\n                """\n                UPDATE recording_candidate_attention\n                SET dismissedStaleSummaryFingerprint = 'summary|tx|revision'\n                WHERE recordingId = 'rec-qa4-r1'\n                """.trimIndent(),\n            )\n            migrated.query(\n                "SELECT dismissedStaleSummaryFingerprint FROM recording_candidate_attention WHERE recordingId = 'rec-qa4-r1'",\n            ).use { cursor ->\n                assertTrue(cursor.moveToFirst())\n                assertEquals("summary|tx|revision", cursor.getString(0))\n            }\n        } finally {\n            migrated.close()\n        }\n    }\n\n    private companion object {\n''',
    )
    replace_once(
        migration_test,
        '        const val TEST_DB = "stage13b5-qa4-migration-test.db"\n',
        '        const val TEST_DB = "stage13b5-qa4-migration-test.db"\n        const val TEST_DB_V11 = "stage13b5-qa4-r1-migration-test.db"\n',
    )

print("QA4-R1 source patch applied")
