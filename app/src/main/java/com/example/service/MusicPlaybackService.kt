package com.example.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.local.DownloadedSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class PlaybackState(
    val isPlaying: Boolean = false,
    val currentSong: DownloadedSong? = null,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val queue: List<DownloadedSong> = emptyList(),
    val currentIndex: Int = -1,
    val isWebAudioBackgroundActive: Boolean = false
)

class MusicPlaybackService : Service() {

    private val binder = LocalBinder()
    private var mediaPlayer: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var progressJob: Job? = null

    inner class LocalBinder : Binder() {
        fun getService(): MusicPlaybackService = this@MusicPlaybackService
    }

    companion object {
        const val CHANNEL_ID = "lancebuddy_music_playback_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_PLAY_QUEUE = "com.example.action.PLAY_QUEUE"
        const val ACTION_PLAY_SONG = "com.example.action.PLAY_SONG"
        const val ACTION_PLAY_PAUSE = "com.example.action.PLAY_PAUSE"
        const val ACTION_NEXT = "com.example.action.NEXT"
        const val ACTION_PREV = "com.example.action.PREV"
        const val ACTION_STOP = "com.example.action.STOP"
        const val ACTION_SEEK = "com.example.action.SEEK"
        const val ACTION_START_WEB_BACKGROUND = "com.example.action.START_WEB_BACKGROUND"
        const val ACTION_STOP_WEB_BACKGROUND = "com.example.action.STOP_WEB_BACKGROUND"

        const val EXTRA_SONG_INDEX = "extra_song_index"
        const val EXTRA_SEEK_POS = "extra_seek_pos"

        private val _playbackState = MutableStateFlow(PlaybackState())
        val playbackState = _playbackState.asStateFlow()

        // Helper functions to send intents to service
        fun playSong(context: Context, song: DownloadedSong, queue: List<DownloadedSong>) {
            val index = queue.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
            _playbackState.value = _playbackState.value.copy(
                queue = queue,
                currentIndex = index,
                currentSong = song
            )
            val intent = Intent(context, MusicPlaybackService::class.java).apply {
                action = ACTION_PLAY_SONG
                putExtra(EXTRA_SONG_INDEX, index)
            }
            startServiceCompat(context, intent)
        }

        fun togglePlayPause(context: Context) {
            val intent = Intent(context, MusicPlaybackService::class.java).apply {
                action = ACTION_PLAY_PAUSE
            }
            startServiceCompat(context, intent)
        }

        fun skipNext(context: Context) {
            val intent = Intent(context, MusicPlaybackService::class.java).apply {
                action = ACTION_NEXT
            }
            startServiceCompat(context, intent)
        }

        fun skipPrev(context: Context) {
            val intent = Intent(context, MusicPlaybackService::class.java).apply {
                action = ACTION_PREV
            }
            startServiceCompat(context, intent)
        }

        fun seekTo(context: Context, posMs: Long) {
            val intent = Intent(context, MusicPlaybackService::class.java).apply {
                action = ACTION_SEEK
                putExtra(EXTRA_SEEK_POS, posMs)
            }
            startServiceCompat(context, intent)
        }

        fun startWebBackground(context: Context) {
            val intent = Intent(context, MusicPlaybackService::class.java).apply {
                action = ACTION_START_WEB_BACKGROUND
            }
            startServiceCompat(context, intent)
        }

        fun stopWebBackground(context: Context) {
            val intent = Intent(context, MusicPlaybackService::class.java).apply {
                action = ACTION_STOP_WEB_BACKGROUND
            }
            startServiceCompat(context, intent)
        }

        private fun startServiceCompat(context: Context, intent: Intent) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {}
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initLocks()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    @SuppressLint("WakelockTimeout")
    private fun initLocks() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "LanceBuddyMusic:PlaybackWakeLock"
        )
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifiLock = wifiManager?.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "LanceBuddyMusic:WifiLock"
        )
    }

    private fun acquireLocks() {
        try {
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire()
            }
            if (wifiLock?.isHeld != true) {
                wifiLock?.acquire()
            }
        } catch (_: Exception) {}
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_SONG -> {
                val index = intent.getIntExtra(EXTRA_SONG_INDEX, _playbackState.value.currentIndex)
                playSongAtIndex(index)
            }
            ACTION_PLAY_PAUSE -> {
                togglePlayback()
            }
            ACTION_NEXT -> {
                playNext()
            }
            ACTION_PREV -> {
                playPrev()
            }
            ACTION_SEEK -> {
                val pos = intent.getLongExtra(EXTRA_SEEK_POS, 0L)
                seekToPosition(pos)
            }
            ACTION_START_WEB_BACKGROUND -> {
                startWebAudioBackgroundMode()
            }
            ACTION_STOP_WEB_BACKGROUND -> {
                stopWebAudioBackgroundMode()
            }
            ACTION_STOP -> {
                stopPlayback()
            }
        }
        return START_STICKY
    }

    private fun playSongAtIndex(index: Int) {
        val state = _playbackState.value
        val queue = state.queue
        if (queue.isEmpty() || index !in queue.indices) return

        val song = queue[index]
        val file = File(song.localFilePath)
        if (!file.exists()) return

        stopMediaPlayer()
        requestAudioFocus()
        acquireLocks()

        try {
            mediaPlayer = MediaPlayer().apply {
                setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(song.localFilePath)
                prepare()
                start()

                setOnCompletionListener {
                    playNext()
                }

                setOnErrorListener { _, _, _ ->
                    _playbackState.value = _playbackState.value.copy(isPlaying = false)
                    updateNotification(false)
                    true
                }
            }

            val duration = mediaPlayer?.duration?.toLong() ?: song.durationMs
            _playbackState.value = _playbackState.value.copy(
                isPlaying = true,
                currentSong = song,
                currentIndex = index,
                currentPositionMs = 0L,
                durationMs = duration,
                isWebAudioBackgroundActive = false
            )

            startProgressTracker()
            startForeground(NOTIFICATION_ID, buildNotification(song.title, song.artist, isPlaying = true))
        } catch (e: Exception) {
            _playbackState.value = _playbackState.value.copy(isPlaying = false)
        }
    }

    private fun togglePlayback() {
        val mp = mediaPlayer
        if (mp != null) {
            if (mp.isPlaying) {
                mp.pause()
                _playbackState.value = _playbackState.value.copy(isPlaying = false)
                updateNotification(false)
            } else {
                requestAudioFocus()
                acquireLocks()
                mp.start()
                _playbackState.value = _playbackState.value.copy(isPlaying = true)
                startProgressTracker()
                updateNotification(true)
            }
        }
    }

    private fun playNext() {
        val state = _playbackState.value
        if (state.queue.isEmpty()) return
        val nextIndex = (state.currentIndex + 1) % state.queue.size
        playSongAtIndex(nextIndex)
    }

    private fun playPrev() {
        val state = _playbackState.value
        if (state.queue.isEmpty()) return
        val prevIndex = if (state.currentIndex - 1 < 0) state.queue.size - 1 else state.currentIndex - 1
        playSongAtIndex(prevIndex)
    }

    private fun seekToPosition(posMs: Long) {
        mediaPlayer?.let { mp ->
            mp.seekTo(posMs.toInt())
            _playbackState.value = _playbackState.value.copy(currentPositionMs = posMs)
        }
    }

    private fun startWebAudioBackgroundMode() {
        acquireLocks()
        _playbackState.value = _playbackState.value.copy(
            isWebAudioBackgroundActive = true
        )
        startForeground(
            NOTIFICATION_ID,
            buildNotification("LanceBuddy Music", "Streaming Audio (Background Active)", isPlaying = true)
        )
    }

    private fun stopWebAudioBackgroundMode() {
        if (_playbackState.value.isWebAudioBackgroundActive && mediaPlayer == null) {
            releaseLocks()
            _playbackState.value = _playbackState.value.copy(isWebAudioBackgroundActive = false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startProgressTracker() {
        progressJob?.cancel()
        progressJob = serviceScope.launch {
            while (isActive && mediaPlayer?.isPlaying == true) {
                mediaPlayer?.let { mp ->
                    val pos = mp.currentPosition.toLong()
                    val dur = mp.duration.toLong()
                    _playbackState.value = _playbackState.value.copy(
                        currentPositionMs = pos,
                        durationMs = dur
                    )
                }
                delay(500)
            }
        }
    }

    private fun stopMediaPlayer() {
        progressJob?.cancel()
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
    }

    private fun stopPlayback() {
        stopMediaPlayer()
        releaseLocks()
        abandonAudioFocus()
        _playbackState.value = _playbackState.value.copy(
            isPlaying = false,
            currentPositionMs = 0L
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                            if (mediaPlayer?.isPlaying == true) {
                                mediaPlayer?.pause()
                                _playbackState.value = _playbackState.value.copy(isPlaying = false)
                                updateNotification(false)
                            }
                        }
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            if (mediaPlayer != null && !mediaPlayer!!.isPlaying) {
                                mediaPlayer?.start()
                                _playbackState.value = _playbackState.value.copy(isPlaying = true)
                                updateNotification(true)
                            }
                        }
                    }
                }
                .build()
            audioFocusRequest = request
            audioManager?.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(
                { focusChange ->
                    if (focusChange == AudioManager.AUDIOFOCUS_LOSS && mediaPlayer?.isPlaying == true) {
                        mediaPlayer?.pause()
                        _playbackState.value = _playbackState.value.copy(isPlaying = false)
                        updateNotification(false)
                    }
                },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "LanceBuddy Music Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Controls and information for active background music playback"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, artist: String, isPlaying: Boolean): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val playPauseActionIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, MusicPlaybackService::class.java).apply { action = ACTION_PLAY_PAUSE },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val nextActionIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, MusicPlaybackService::class.java).apply { action = ACTION_NEXT },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val prevActionIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, MusicPlaybackService::class.java).apply { action = ACTION_PREV },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopActionIntent = PendingIntent.getService(
            this,
            4,
            Intent(this, MusicPlaybackService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseTitle = if (isPlaying) "Pause" else "Play"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(artist)
            .setSmallIcon(R.drawable.app_logo_vector)
            .setContentIntent(openAppIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(isPlaying)
            .addAction(android.R.drawable.ic_media_previous, "Previous", prevActionIntent)
            .addAction(playPauseIcon, playPauseTitle, playPauseActionIntent)
            .addAction(android.R.drawable.ic_media_next, "Next", nextActionIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopActionIntent)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$title - $artist"))
            .build()
    }

    private fun updateNotification(isPlaying: Boolean) {
        val state = _playbackState.value
        val title = state.currentSong?.title ?: "LanceBuddy Music"
        val artist = state.currentSong?.artist ?: "Downloaded Playlist"
        val notification = buildNotification(title, artist, isPlaying)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        stopMediaPlayer()
        releaseLocks()
        abandonAudioFocus()
        super.onDestroy()
    }
}
