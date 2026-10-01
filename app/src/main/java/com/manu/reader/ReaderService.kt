package com.manu.reader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.edit
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import androidx.media.session.MediaButtonReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReaderService : Service() {

    data class State(
        val book: Book? = null,
        val position: Int = 0,
        val playing: Boolean = false,
        val loading: Boolean = false,
        val error: String? = null,
        val engineName: String? = null,
    )

    inner class LocalBinder : Binder() {
        val service: ReaderService get() = this@ReaderService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private lateinit var session: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private lateinit var focusRequest: AudioFocusRequestCompat
    private lateinit var wakeLock: PowerManager.WakeLock

    private var tts: TextToSpeech? = null
    private var enginePackage: String? = null
    private var ttsReady = false
    private var playWhenReady = false
    private var resumeOnFocusGain = false
    private var isForeground = false
    private var noisyRegistered = false

    // Incremented on every flush so callbacks from stale utterances are ignored.
    private var generation = 0
    private var lastQueued = -1

    private val prefs by lazy { getSharedPreferences("reader", MODE_PRIVATE) }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ManuReader:tts")
            .apply { setReferenceCounted(false) }

        focusRequest = AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributesCompat.Builder()
                    .setUsage(AudioAttributesCompat.USAGE_MEDIA)
                    .setContentType(AudioAttributesCompat.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        resumeOnFocusGain = false
                        pause()
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (_state.value.playing) {
                        resumeOnFocusGain = true
                        pause(abandonFocus = false)
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnFocusGain) {
                        resumeOnFocusGain = false
                        play()
                    }
                }
            }
            .build()

        createNotificationChannel()
        session = MediaSessionCompat(this, "ManuReader").apply {
            setSessionActivity(contentIntent())
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = play()
                override fun onPause() = pause()
                override fun onStop() = stop()
                override fun onSkipToNext() = nextChapter()
                override fun onSkipToPrevious() = previousChapter()
                override fun onFastForward() = seekTo(_state.value.position + 1)
                override fun onRewind() = seekTo(_state.value.position - 1)
                override fun onSeekTo(pos: Long) = seekToChapterTime(pos)
                override fun onCustomAction(action: String, extras: android.os.Bundle?) {
                    when (action) {
                        CUSTOM_PREV_PARAGRAPH -> onRewind()
                        CUSTOM_NEXT_PARAGRAPH -> onFastForward()
                        CUSTOM_NEXT_CHAPTER -> nextChapter()
                    }
                }

                // SKIP_TO_NEXT isn't advertised (see updateSession), so handle the key ourselves.
                override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
                    val event = IntentCompat.getParcelableExtra(mediaButtonEvent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    if (event?.keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) {
                        if (event.action == KeyEvent.ACTION_DOWN) nextChapter()
                        return true
                    }
                    return super.onMediaButtonEvent(mediaButtonEvent)
                }
            })
        }
        initTts()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == Intent.ACTION_MEDIA_BUTTON) {
            // Started via startForegroundService by MediaButtonReceiver: must go foreground right away.
            startForegroundCompat()
            if (_state.value.book == null) {
                val last = prefs.getString(KEY_LAST_BOOK, null)
                if (last != null) loadBook(Uri.parse(last), autoPlay = true) else stop()
            } else {
                MediaButtonReceiver.handleIntent(session, intent)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        pause()
        tts?.shutdown()
        session.release()
        scope.cancel()
        super.onDestroy()
    }

    fun initTts() {
        val engine = TtsEngines.selected(this) ?: return
        if (tts != null && enginePackage == engine.packageName) return
        pause()
        tts?.shutdown()
        ttsReady = false
        enginePackage = engine.packageName
        _state.update { it.copy(engineName = engine.label, error = null) }
        tts = TextToSpeech(this, { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                tts?.setOnUtteranceProgressListener(utteranceListener)
                if (playWhenReady) play()
            } else {
                _state.update { it.copy(error = getString(R.string.error_engine_start, engine.label)) }
            }
        }, engine.packageName)
    }

    fun loadBook(uri: Uri, autoPlay: Boolean = false) {
        pause()
        _state.update { it.copy(loading = true, error = null) }
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { EpubParser.parse(this@ReaderService, uri) } }
            result.onSuccess { book ->
                prefs.edit { putString(KEY_LAST_BOOK, uri.toString()) }
                val saved = prefs.getInt(positionKey(uri), 0).coerceIn(0, book.paragraphs.lastIndex)
                _state.update { State(book = book, position = saved, engineName = it.engineName) }
                currentUri = uri
                updateSession()
                if (autoPlay) play()
            }.onFailure { e ->
                Log.e(TAG, "Failed to load EPUB", e)
                _state.update { it.copy(loading = false, error = getString(R.string.error_open_book, e.message.orEmpty())) }
                if (autoPlay) stop()
            }
        }
    }

    fun lastBookUri(): Uri? = prefs.getString(KEY_LAST_BOOK, null)?.let(Uri::parse)

    private var currentUri: Uri? = null

    fun play() {
        val book = _state.value.book ?: return
        if (!ttsReady) {
            playWhenReady = true
            initTts()
            return
        }
        playWhenReady = false
        if (AudioManagerCompat.requestAudioFocus(audioManager, focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return

        startForegroundCompat()
        session.isActive = true
        wakeLock.acquire(WAKE_LOCK_TIMEOUT)
        if (!noisyRegistered) {
            ContextCompat.registerReceiver(
                this, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            noisyRegistered = true
        }

        val start = if (_state.value.position >= book.paragraphs.size) 0 else _state.value.position
        generation++
        lastQueued = start - 1
        enqueue(start, TextToSpeech.QUEUE_FLUSH)
        enqueue(start + 1)
        _state.update { it.copy(position = start, playing = true) }
        updateSession()
    }

    fun pause(abandonFocus: Boolean = true) {
        playWhenReady = false
        if (!_state.value.playing) return
        generation++
        tts?.stop()
        if (wakeLock.isHeld) wakeLock.release()
        if (noisyRegistered) {
            unregisterReceiver(noisyReceiver)
            noisyRegistered = false
        }
        if (abandonFocus) AudioManagerCompat.abandonAudioFocusRequest(audioManager, focusRequest)
        _state.update { it.copy(playing = false) }
        savePosition()
        updateSession()
    }

    fun togglePlayPause() = if (_state.value.playing) pause() else play()

    fun stop() {
        pause()
        session.isActive = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        isForeground = false
        stopSelf()
    }

    fun seekTo(index: Int) {
        val book = _state.value.book ?: return
        val target = index.coerceIn(0, book.paragraphs.lastIndex)
        if (_state.value.playing) {
            _state.update { it.copy(position = target) }
            play()
        } else {
            _state.update { it.copy(position = target) }
            savePosition()
            updateSession()
        }
    }

    fun nextChapter() {
        val book = _state.value.book ?: return
        val next = book.chapterIndexOf(_state.value.position) + 1
        if (next < book.chapters.size) seekTo(book.chapters[next].start)
    }

    fun previousChapter() {
        val book = _state.value.book ?: return
        val position = _state.value.position
        val current = book.chapterIndexOf(position)
        // Like music players: go to the start of this chapter first, then to the previous one.
        val target = if (position > book.chapters[current].start + 1 || current == 0) {
            book.chapters[current].start
        } else {
            book.chapters[current - 1].start
        }
        seekTo(target)
    }

    private fun enqueue(index: Int, mode: Int = TextToSpeech.QUEUE_ADD) {
        val book = _state.value.book ?: return
        if (index > book.paragraphs.lastIndex || index <= lastQueued) return
        tts?.speak(book.paragraphs[index], mode, null, "$generation:$index")
        lastQueued = index
    }

    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {
            scope.launch { onUtteranceStart(utteranceId) }
        }

        override fun onDone(utteranceId: String) {
            scope.launch { onUtteranceDone(utteranceId) }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) {
            Log.w(TAG, "TTS error on $utteranceId")
            scope.launch { onUtteranceDone(utteranceId) }
        }

        override fun onError(utteranceId: String, errorCode: Int) {
            Log.w(TAG, "TTS error $errorCode on $utteranceId")
            scope.launch { onUtteranceDone(utteranceId) }
        }
    }

    private fun parseId(id: String): Int? {
        val (gen, index) = id.split(':').map { it.toIntOrNull() ?: return null }
        return if (gen == generation && _state.value.playing) index else null
    }

    private fun onUtteranceStart(id: String) {
        val index = parseId(id) ?: return
        wakeLock.acquire(WAKE_LOCK_TIMEOUT)
        val chapterChanged = _state.value.book?.let { it.chapterIndexOf(index) != it.chapterIndexOf(_state.value.position) } ?: false
        _state.update { it.copy(position = index) }
        enqueue(index + 1)
        savePosition()
        if (chapterChanged) updateSession() else updatePlaybackState()
    }

    private fun onUtteranceDone(id: String) {
        val index = parseId(id) ?: return
        val book = _state.value.book ?: return
        if (index >= book.paragraphs.lastIndex) {
            pause()
            _state.update { it.copy(position = book.paragraphs.size) }
        } else {
            // Keeps the queue going if an utterance failed before onStart of the next one.
            enqueue(index + 1)
        }
    }

    private fun savePosition() {
        val uri = currentUri ?: return
        prefs.edit { putInt(positionKey(uri), _state.value.position) }
    }

    private fun positionKey(uri: Uri) = "pos_$uri"

    // Duration and position are estimated from text length so the lock screen can show a chapter progress bar.
    private fun chapterTimes(): Pair<Long, Long>? {
        val book = _state.value.book ?: return null
        val position = _state.value.position.coerceAtMost(book.paragraphs.lastIndex)
        val chapter = book.chapterIndexOf(position)
        val start = book.charOffsets[book.chapters[chapter].start]
        val duration = (book.charOffsets[book.chapterEnd(chapter)] - start) * MS_PER_CHAR
        val elapsed = (book.charOffsets[position] - start) * MS_PER_CHAR
        return duration to elapsed
    }

    private fun seekToChapterTime(ms: Long) {
        val book = _state.value.book ?: return
        val chapter = book.chapterIndexOf(_state.value.position.coerceAtMost(book.paragraphs.lastIndex))
        val first = book.chapters[chapter].start
        val target = book.charOffsets[first] + ms / MS_PER_CHAR
        var index = first
        while (index + 1 < book.chapterEnd(chapter) && book.charOffsets[index + 1] <= target) index++
        seekTo(index)
    }

    private fun updateSession() {
        val s = _state.value
        val book = s.book
        val chapter = book?.chapters?.getOrNull(book.chapterIndexOf(s.position))?.title
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, chapter ?: getString(R.string.app_name))
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, book?.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, book?.title)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, chapterTimes()?.first ?: -1L)
                .build()
        )
        updatePlaybackState()
        if (isForeground) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun updatePlaybackState() {
        val s = _state.value
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SEEK_TO or
                        PlaybackStateCompat.ACTION_FAST_FORWARD or PlaybackStateCompat.ACTION_REWIND
                )
                // Android 13+ lock screen fills the missing "next" slot with the first custom action,
                // which yields the layout: prev chapter, prev paragraph, next paragraph, next chapter.
                .addCustomAction(
                    PlaybackStateCompat.CustomAction.Builder(
                        CUSTOM_PREV_PARAGRAPH, getString(R.string.previous_paragraph), R.drawable.ic_fast_rewind,
                    ).build()
                )
                .addCustomAction(
                    PlaybackStateCompat.CustomAction.Builder(
                        CUSTOM_NEXT_PARAGRAPH, getString(R.string.next_paragraph), R.drawable.ic_fast_forward,
                    ).build()
                )
                .addCustomAction(
                    PlaybackStateCompat.CustomAction.Builder(
                        CUSTOM_NEXT_CHAPTER, getString(R.string.next_chapter), R.drawable.ic_skip_next,
                    ).build()
                )
                .setState(
                    if (s.playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    chapterTimes()?.second ?: PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                    1f,
                )
                .build()
        )
    }

    private fun startForegroundCompat() {
        if (!isForeground) {
            // Makes the service "started" so it outlives the activity's binding.
            ContextCompat.startForegroundService(this, Intent(this, ReaderService::class.java))
        }
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        isForeground = true
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun buildNotification(): Notification {
        val s = _state.value
        val book = s.book
        val chapter = book?.chapters?.getOrNull(book.chapterIndexOf(s.position))?.title
        fun action(icon: Int, title: Int, action: Long) = NotificationCompat.Action(
            icon, getString(title), MediaButtonReceiver.buildMediaButtonPendingIntent(this, action),
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(book?.title ?: getString(R.string.app_name))
            .setContentText(chapter)
            .setContentIntent(contentIntent())
            .setDeleteIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_STOP))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setOngoing(s.playing)
            .addAction(action(R.drawable.ic_skip_previous, R.string.previous_chapter, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS))
            .addAction(action(R.drawable.ic_fast_rewind, R.string.previous_paragraph, PlaybackStateCompat.ACTION_REWIND))
            .addAction(
                if (s.playing) action(R.drawable.ic_pause, R.string.pause, PlaybackStateCompat.ACTION_PAUSE)
                else action(R.drawable.ic_play, R.string.play, PlaybackStateCompat.ACTION_PLAY)
            )
            .addAction(action(R.drawable.ic_fast_forward, R.string.next_paragraph, PlaybackStateCompat.ACTION_FAST_FORWARD))
            .addAction(action(R.drawable.ic_skip_next, R.string.next_chapter, PlaybackStateCompat.ACTION_SKIP_TO_NEXT))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(1, 2, 3)
            )
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "ReaderService"
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1
        private const val KEY_LAST_BOOK = "last_book"
        private const val WAKE_LOCK_TIMEOUT = 10 * 60 * 1000L
        private const val CUSTOM_PREV_PARAGRAPH = "prev_paragraph"
        private const val CUSTOM_NEXT_PARAGRAPH = "next_paragraph"
        private const val CUSTOM_NEXT_CHAPTER = "next_chapter"
        // Roughly 15 characters per second at normal speech rate.
        private const val MS_PER_CHAR = 65L
    }
}
