package io.github.ioannes78.voica

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState as PlatformPlaybackState
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.ioannes78.voica.audio.CanonicalPcmProfile
import io.github.ioannes78.voica.audio.PlaybackSnapshot
import io.github.ioannes78.voica.audio.PlaybackState as VoicaPlaybackState
import io.github.ioannes78.voica.database.RecordingLibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

/**
 * Stage 13B mediaPlayback foreground owner for the existing Voica playback runtime.
 *
 * The service deliberately does not create a second player. Both the Compose UI and the system
 * MediaSession control AppContainer.playbackRuntime, preserving the canonical-sample timeline.
 */
class PlaybackForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var playbackController: io.github.ioannes78.voica.playback.AndroidPlaybackController
    private lateinit var recordingRepository: RecordingLibraryRepository
    private lateinit var mediaSession: MediaSession
    private lateinit var notificationManager: NotificationManager
    private var observing = false
    private var lastMetadataKey: String? = null
    private var currentRecordingName: String = FALLBACK_RECORDING_NAME

    override fun onCreate() {
        super.onCreate()
        val application = application as VoicaApplication
        playbackController = application.container.playbackRuntime
        recordingRepository = application.container.recordingLibraryRepository
        notificationManager = getSystemService(NotificationManager::class.java)
        createChannel()
        mediaSession =
            MediaSession(this, SESSION_TAG).apply {
                setCallback(
                    object : MediaSession.Callback() {
                        override fun onPlay() {
                            promote(playbackController.snapshot.value)
                            serviceScope.launch { playbackController.play() }
                        }

                        override fun onPause() {
                            serviceScope.launch { playbackController.pause() }
                        }

                        override fun onStop() {
                            serviceScope.launch {
                                playbackController.unload()
                                stopForeground(STOP_FOREGROUND_REMOVE)
                                stopSelf()
                            }
                        }

                        override fun onSeekTo(pos: Long) {
                            val sample =
                                pos.coerceAtLeast(0L) *
                                    CanonicalPcmProfile.SAMPLE_RATE_HZ /
                                    1_000L
                            serviceScope.launch { playbackController.seekToSample(sample) }
                        }

                        override fun onRewind() {
                            seekRelative(-SEEK_SECONDS)
                        }

                        override fun onFastForward() {
                            seekRelative(SEEK_SECONDS)
                        }
                    },
                )
                isActive = true
            }
        observeRecordingNames()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val snapshot = playbackController.snapshot.value
        promote(snapshot)
        startObserving()

        when (intent?.action) {
            ACTION_PLAY -> serviceScope.launch { playbackController.play() }
            ACTION_PAUSE -> serviceScope.launch { playbackController.pause() }
            ACTION_REWIND -> seekRelative(-SEEK_SECONDS)
            ACTION_FORWARD -> seekRelative(SEEK_SECONDS)
            ACTION_STOP ->
                serviceScope.launch {
                    playbackController.unload()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            ACTION_ENSURE,
            null,
            -> Unit
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        mediaSession.isActive = false
        mediaSession.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun observeRecordingNames() {
        serviceScope.launch {
            recordingRepository.recordings.collect { recordings ->
                val snapshot = playbackController.snapshot.value
                val recordingId = snapshot.recordingId
                val item = recordings.firstOrNull { it.id == recordingId }
                val resolved =
                    item?.displayName?.trim()?.takeIf { it.isNotEmpty() }
                        ?: item?.originalFilename?.trim()?.takeIf { it.isNotEmpty() }
                        ?: FALLBACK_RECORDING_NAME
                if (resolved != currentRecordingName) {
                    currentRecordingName = resolved
                    lastMetadataKey = null
                    if (recordingId != null) publishSnapshot(snapshot)
                }
            }
        }
    }

    private fun startObserving() {
        if (observing) return
        observing = true
        serviceScope.launch {
            playbackController.snapshot
                .distinctUntilChangedBy { snapshot ->
                    NotificationKey(
                        recordingId = snapshot.recordingId,
                        state = snapshot.state,
                        durationUs = snapshot.durationUs,
                        speed = snapshot.speed,
                        seekGeneration = snapshot.seekGeneration,
                        errorCode = snapshot.error?.code?.name,
                    )
                }
                .collect(::publishSnapshot)
        }
    }

    private fun publishSnapshot(snapshot: PlaybackSnapshot) {
        if (snapshot.recordingId == null) {
            currentRecordingName = FALLBACK_RECORDING_NAME
        }
        updateMediaSession(snapshot)
        val notification = buildNotification(snapshot)

        when (snapshot.state) {
            VoicaPlaybackState.PLAYING,
            VoicaPlaybackState.SEEKING,
            -> promote(snapshot, notification)

            VoicaPlaybackState.READY,
            VoicaPlaybackState.PAUSED,
            VoicaPlaybackState.COMPLETED,
            -> {
                notificationManager.notify(NOTIFICATION_ID, notification)
                stopForeground(STOP_FOREGROUND_DETACH)
            }

            VoicaPlaybackState.IDLE,
            VoicaPlaybackState.ERROR,
            VoicaPlaybackState.RELEASED,
            -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                notificationManager.cancel(NOTIFICATION_ID)
                stopSelf()
            }

            VoicaPlaybackState.PREPARING -> promote(snapshot, notification)
        }
    }

    private fun promote(
        snapshot: PlaybackSnapshot,
        notification: Notification = buildNotification(snapshot),
    ) {
        updateMediaSession(snapshot)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
    }

    private fun updateMediaSession(snapshot: PlaybackSnapshot) {
        val metadataKey =
            snapshot.recordingId.orEmpty() + "|" + snapshot.durationUs + "|" + currentRecordingName
        if (metadataKey != lastMetadataKey) {
            lastMetadataKey = metadataKey
            mediaSession.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, currentRecordingName)
                    .putLong(
                        MediaMetadata.METADATA_KEY_DURATION,
                        snapshot.durationUs.coerceAtLeast(0L) / 1_000L,
                    )
                    .build(),
            )
        }

        val platformState =
            when (snapshot.state) {
                VoicaPlaybackState.PLAYING -> PlatformPlaybackState.STATE_PLAYING
                VoicaPlaybackState.PAUSED,
                VoicaPlaybackState.READY,
                -> PlatformPlaybackState.STATE_PAUSED
                VoicaPlaybackState.SEEKING,
                VoicaPlaybackState.PREPARING,
                -> PlatformPlaybackState.STATE_BUFFERING
                VoicaPlaybackState.COMPLETED -> PlatformPlaybackState.STATE_STOPPED
                VoicaPlaybackState.ERROR -> PlatformPlaybackState.STATE_ERROR
                VoicaPlaybackState.IDLE,
                VoicaPlaybackState.RELEASED,
                -> PlatformPlaybackState.STATE_NONE
            }
        val actions =
            PlatformPlaybackState.ACTION_PLAY or
                PlatformPlaybackState.ACTION_PAUSE or
                PlatformPlaybackState.ACTION_STOP or
                PlatformPlaybackState.ACTION_SEEK_TO or
                PlatformPlaybackState.ACTION_REWIND or
                PlatformPlaybackState.ACTION_FAST_FORWARD
        val builder =
            PlatformPlaybackState.Builder()
                .setActions(actions)
                .setState(
                    platformState,
                    snapshot.positionUs.coerceAtLeast(0L) / 1_000L,
                    if (snapshot.state == VoicaPlaybackState.PLAYING) snapshot.speed else 0f,
                    SystemClock.elapsedRealtime(),
                )
        snapshot.error?.let { error ->
            builder.setErrorMessage(error.code.name)
        }
        mediaSession.setPlaybackState(builder.build())
    }

    private fun buildNotification(snapshot: PlaybackSnapshot): Notification {
        val playing = snapshot.state == VoicaPlaybackState.PLAYING
        val contentIntent =
            PendingIntent.getActivity(
                this,
                REQUEST_CONTENT,
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val rewind = notificationAction(ACTION_REWIND, android.R.drawable.ic_media_rew, "后退 10 秒", REQUEST_REWIND)
        val toggle =
            if (playing) {
                notificationAction(ACTION_PAUSE, android.R.drawable.ic_media_pause, "暂停", REQUEST_TOGGLE)
            } else {
                notificationAction(ACTION_PLAY, android.R.drawable.ic_media_play, "播放", REQUEST_TOGGLE)
            }
        val forward = notificationAction(ACTION_FORWARD, android.R.drawable.ic_media_ff, "前进 10 秒", REQUEST_FORWARD)
        val speed = formatSpeed(snapshot.speed)
        val text =
            when (snapshot.state) {
                VoicaPlaybackState.PLAYING -> "正在播放 · $speed"
                VoicaPlaybackState.PAUSED -> "已暂停 · $speed"
                VoicaPlaybackState.SEEKING -> "正在跳转…"
                VoicaPlaybackState.PREPARING -> "正在准备播放…"
                VoicaPlaybackState.COMPLETED -> "播放完成"
                VoicaPlaybackState.READY -> "准备播放 · $speed"
                VoicaPlaybackState.ERROR -> "播放失败"
                else -> "录音播放"
            }

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(if (playing) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause)
            .setContentTitle(currentRecordingName)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(rewind)
            .addAction(toggle)
            .addAction(forward)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )
            .build()
    }

    private fun notificationAction(
        action: String,
        icon: Int,
        title: String,
        requestCode: Int,
    ): Notification.Action {
        val pending =
            PendingIntent.getForegroundService(
                this,
                requestCode,
                Intent(this, PlaybackForegroundService::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return Notification.Action.Builder(icon, title, pending).build()
    }

    private fun seekRelative(seconds: Long) {
        val snapshot = playbackController.snapshot.value
        val delta = seconds * CanonicalPcmProfile.SAMPLE_RATE_HZ
        val target =
            (snapshot.positionSampleIndex + delta)
                .coerceIn(0L, snapshot.durationSampleCount.coerceAtLeast(0L))
        serviceScope.launch { playbackController.seekToSample(target) }
    }

    private fun formatSpeed(speed: Float): String =
        when (speed) {
            0.5f -> "0.5x"
            0.75f -> "0.75x"
            1f -> "1.0x"
            1.5f -> "1.5x"
            2f -> "2.0x"
            else -> String.format("%.2gx", speed)
        }

    private fun createChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "录音播放",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Voica 后台和锁屏录音播放控制"
                setShowBadge(false)
            },
        )
    }

    private data class NotificationKey(
        val recordingId: String?,
        val state: VoicaPlaybackState,
        val durationUs: Long,
        val speed: Float,
        val seekGeneration: Long,
        val errorCode: String?,
    )

    companion object {
        private const val CHANNEL_ID = "voica-playback"
        private const val NOTIFICATION_ID = 13021
        private const val SESSION_TAG = "VoicaPlayback"
        private const val SEEK_SECONDS = 10L
        private const val FALLBACK_RECORDING_NAME = "录音"

        private const val ACTION_ENSURE = "io.github.ioannes78.voica.playback.ENSURE"
        private const val ACTION_PLAY = "io.github.ioannes78.voica.playback.PLAY"
        private const val ACTION_PAUSE = "io.github.ioannes78.voica.playback.PAUSE"
        private const val ACTION_REWIND = "io.github.ioannes78.voica.playback.REWIND_10"
        private const val ACTION_FORWARD = "io.github.ioannes78.voica.playback.FORWARD_10"
        private const val ACTION_STOP = "io.github.ioannes78.voica.playback.STOP"

        private const val REQUEST_CONTENT = 13020
        private const val REQUEST_REWIND = 13021
        private const val REQUEST_TOGGLE = 13022
        private const val REQUEST_FORWARD = 13023

        fun ensureStarted(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, PlaybackForegroundService::class.java)
                    .setAction(ACTION_ENSURE),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PlaybackForegroundService::class.java))
        }
    }
}
