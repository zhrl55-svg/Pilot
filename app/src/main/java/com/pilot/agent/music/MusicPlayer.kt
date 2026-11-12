package com.pilot.agent.music

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.pilot.agent.Dbg
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-process music player with a lightweight search-result queue.
 *
 * Media3 handles progressive HTTP streams and buffering more reliably than the
 * platform MediaPlayer on Huawei devices. The queue itself stays small because
 * Netease URLs are resolved only when a track is about to start.
 */
class MusicPlayer private constructor(private val context: Context) {

    enum class PlaybackMode { SEQUENCE, REPEAT_ALL, REPEAT_ONE }

    sealed interface Source {
        data class Remote(val url: String) : Source
        data class Local(val uri: Uri) : Source
    }

    data class Track(
        val title: String,
        val artist: String,
        val source: Source? = null,
        val resolver: (() -> Source?)? = null,
        val lyricProvider: (() -> List<NeteaseApi.LyricLine>)? = null,
    ) {
        val display: String get() = if (artist.isBlank()) title else "$title - $artist"
    }

    data class QueueEntry(
        val title: String,
        val artist: String,
    )

    private var player: ExoPlayer? = null
    private var currentTitle: String? = null
    private var currentArtist: String = ""
    private var lyrics: List<NeteaseApi.LyricLine> = emptyList()
    private var lyricsReady = false
    private val queue = mutableListOf<Track>()
    private var queueIndex = -1
    private val history = mutableListOf<Int>()
    private var onQueueDone: (() -> Unit)? = null
    private var onQueueError: ((String) -> Unit)? = null
    private var consecutiveErrors = 0

    private val resolverExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "PilotMusicResolver").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val requestToken = AtomicInteger(0)

    @Volatile
    private var loading = false

    @Volatile
    var playbackMode: PlaybackMode = PlaybackMode.REPEAT_ALL
        private set

    @Volatile
    var shuffleEnabled: Boolean = false
        private set

    @Volatile
    var isPlaying: Boolean = false
        private set

    var paused: Boolean = false
        private set

    private var audioGate: AutoCloseable? = null

    fun play(
        url: String,
        title: String,
        onDone: () -> Unit = {},
        onError: (String) -> Unit = {},
    ): Boolean = playQueue(
        listOf(trackFromLegacyTitle(title, Source.Remote(url))),
        onDone = onDone,
        onError = onError,
    )

    fun play(
        uri: Uri,
        title: String,
        onDone: () -> Unit = {},
        onError: (String) -> Unit = {},
    ): Boolean = playQueue(
        listOf(trackFromLegacyTitle(title, Source.Local(uri))),
        onDone = onDone,
        onError = onError,
    )

    fun playQueue(
        tracks: List<Track>,
        startIndex: Int = 0,
        onDone: () -> Unit = {},
        onError: (String) -> Unit = {},
    ): Boolean {
        if (tracks.isEmpty()) return false
        releasePlayer()
        queue.clear()
        queue.addAll(tracks)
        queueIndex = startIndex.coerceIn(queue.indices)
        history.clear()
        onQueueDone = onDone
        onQueueError = onError
        consecutiveErrors = 0
        return startCurrent()
    }

    private fun startCurrent(): Boolean {
        releasePlayer()
        val track = queue.getOrNull(queueIndex) ?: return false
        currentTitle = track.title
        currentArtist = track.artist
        lyrics = emptyList()
        lyricsReady = false
        paused = false
        loading = true

        return runCatching {
            val httpFactory = DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Linux; Android 12) Pilot/0.1")
                .setDefaultRequestProperties(mapOf("Referer" to "https://music.163.com/"))
                .setAllowCrossProtocolRedirects(true)
            // HTTP 工厂负责网易云 CDN；DefaultDataSource 还要识别 content://、
            // file:// 和本地 asset，否则 ExoPlayer 会把 MediaStore URI 当 HTTP URL。
            val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
            val exo = ExoPlayer.Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                .build()
            player = exo
            exo.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                false,
            )
            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (player !== exo) return
                    when (playbackState) {
                        Player.STATE_READY -> onCurrentReady(exo)
                        Player.STATE_ENDED -> onCurrentEnded()
                    }
                }

                override fun onIsPlayingChanged(playing: Boolean) {
                    if (player !== exo) return
                    isPlaying = playing
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (player !== exo) return
                    handlePlaybackError(error)
                }
            })

            val token = requestToken.incrementAndGet()
            fun prepare(source: Source?) {
                if (player !== exo || requestToken.get() != token) return
                if (source == null) throw IllegalStateException("没有可用的播放地址")
                val uri = when (source) {
                    is Source.Remote -> Uri.parse(source.url)
                    is Source.Local -> source.uri
                }
                val item = MediaItem.Builder()
                    .setUri(uri)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(track.title)
                            .setArtist(track.artist)
                            .build(),
                    )
                    .build()
                exo.setMediaItem(item)
                exo.prepare()
                exo.playWhenReady = true
            }

            if (track.source != null) {
                prepare(track.source)
                resolveLyrics(track, exo, token)
            } else {
                val resolver = track.resolver
                    ?: throw IllegalStateException("播放队列缺少地址解析器")
                Dbg.i(TAG, "解析播放地址：${track.display}")
                resolverExecutor.execute {
                    val source = runCatching { resolver() }.getOrNull()
                    mainHandler.post {
                        if (player !== exo || requestToken.get() != token) return@post
                        runCatching { prepare(source) }
                            .onFailure { error ->
                                Dbg.w(TAG, "播放地址不可用：${track.display} ${error.message}")
                                handlePlaybackError(null, error.message ?: "播放地址不可用")
                            }
                    }
                }
                resolveLyrics(track, exo, token)
            }
            true
        }.getOrElse {
            Dbg.e(TAG, "播放启动失败：${it.message}")
            finishQueue("播放启动失败：${it.message}")
            false
        }
    }

    private fun resolveLyrics(track: Track, exo: ExoPlayer, token: Int) {
        val provider = track.lyricProvider
        if (provider == null) {
            mainHandler.post {
                if (player === exo && requestToken.get() == token) lyricsReady = true
            }
            return
        }
        resolverExecutor.execute {
            val lines = runCatching { provider() }
                .onFailure { Dbg.w(TAG, "歌词加载失败：${track.display} ${it.message}") }
                .getOrDefault(emptyList())
                .sortedBy { it.timeMs }
            mainHandler.post {
                if (player !== exo || requestToken.get() != token) return@post
                lyrics = lines
                lyricsReady = true
                Dbg.i(TAG, "歌词就绪：${track.display} ${lines.size} 行")
            }
        }
    }

    private fun onCurrentReady(exo: ExoPlayer) {
        loading = false
        consecutiveErrors = 0
        runCatching { exo.volume = 1f }
        audioGate = com.pilot.agent.voice.AudioGate.hold("music")
        com.pilot.agent.voice.AudioFocusManager.requestMusicFocus(context)
        com.pilot.agent.voice.AudioFocusManager.musicVolume = { volume ->
            runCatching { exo.volume = volume }
        }
        com.pilot.agent.voice.AudioFocusManager.musicStop = { stop() }
        com.pilot.agent.voice.AudioFocusManager.onMusicStarted()
        Dbg.i(TAG, "开始播放：${queue.getOrNull(queueIndex)?.display} (${queueIndex + 1}/${queue.size})")
    }

    private fun onCurrentEnded() {
        isPlaying = false
        releasePlayer()
        if (playbackMode == PlaybackMode.REPEAT_ONE) {
            startCurrent()
        } else if (!advance(automatic = true)) {
            finishQueue()
        }
    }

    private fun handlePlaybackError(error: PlaybackException?, fallback: String = "播放失败") {
        releasePlayer()
        consecutiveErrors++
        val detail = error?.errorCodeName ?: error?.message ?: fallback
        Dbg.w(TAG, "播放器错误：$detail")
        if (consecutiveErrors < queue.size && advance(automatic = true)) return
        finishQueue("播放失败（$detail）")
    }

    fun next(): Boolean {
        if (queue.isEmpty()) return false
        return advance(automatic = false)
    }

    fun playAt(index: Int): Boolean {
        if (index !in queue.indices) return false
        if (index == queueIndex) return true
        history.add(queueIndex)
        if (history.size > 100) history.removeAt(0)
        queueIndex = index
        return startCurrent()
    }

    fun previous(): Boolean {
        if (queue.isEmpty()) return false
        val target = when {
            shuffleEnabled && history.isNotEmpty() -> history.removeAt(history.lastIndex)
            queueIndex > 0 -> queueIndex - 1
            playbackMode != PlaybackMode.SEQUENCE -> queue.lastIndex
            else -> return false
        }
        history.add(queueIndex)
        queueIndex = target
        return startCurrent()
    }

    private fun advance(automatic: Boolean): Boolean {
        if (queue.isEmpty()) return false
        val target = if (shuffleEnabled && queue.size > 1) {
            generateSequence { queue.indices.random() }.first { it != queueIndex }
        } else {
            val next = queueIndex + 1
            when {
                next <= queue.lastIndex -> next
                playbackMode != PlaybackMode.SEQUENCE || !automatic -> 0
                else -> return false
            }
        }
        history.add(queueIndex)
        if (history.size > 100) history.removeAt(0)
        queueIndex = target
        return startCurrent()
    }

    fun toggleShuffle(): Boolean {
        shuffleEnabled = !shuffleEnabled
        Dbg.i(TAG, "随机播放 ${if (shuffleEnabled) "开启" else "关闭"}")
        return shuffleEnabled
    }

    fun cyclePlaybackMode(): PlaybackMode {
        playbackMode = when (playbackMode) {
            PlaybackMode.REPEAT_ALL -> PlaybackMode.REPEAT_ONE
            PlaybackMode.REPEAT_ONE -> PlaybackMode.SEQUENCE
            PlaybackMode.SEQUENCE -> PlaybackMode.REPEAT_ALL
        }
        Dbg.i(TAG, "播放模式 $playbackMode")
        return playbackMode
    }

    fun pause() {
        val exo = player ?: return
        if (paused) return
        exo.pause()
        isPlaying = false
        paused = true
        releaseGate()
        Dbg.i(TAG, "音乐已暂停：$currentTitle")
    }

    fun resume() {
        val exo = player ?: return
        if (!paused) return
        com.pilot.agent.voice.AudioFocusManager.requestMusicFocus(context)
        if (audioGate == null) audioGate = com.pilot.agent.voice.AudioGate.hold("music")
        exo.play()
        paused = false
        Dbg.i(TAG, "音乐已恢复：$currentTitle")
    }

    fun stop() {
        releasePlayer()
        queue.clear()
        queueIndex = -1
        history.clear()
        currentTitle = null
        currentArtist = ""
        lyrics = emptyList()
        lyricsReady = false
        onQueueDone = null
        onQueueError = null
    }

    private fun releasePlayer() {
        requestToken.incrementAndGet()
        val old = player
        player = null
        old?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        isPlaying = false
        paused = false
        loading = false
        releaseGate()
        com.pilot.agent.voice.AudioFocusManager.abandonMusicFocus()
        com.pilot.agent.voice.AudioFocusManager.musicVolume = null
        com.pilot.agent.voice.AudioFocusManager.musicStop = null
    }

    private fun releaseGate() {
        runCatching { audioGate?.close() }
        audioGate = null
    }

    private fun finishQueue(error: String? = null) {
        val done = onQueueDone
        val failed = onQueueError
        stop()
        if (error != null) failed?.invoke(error) else done?.invoke()
    }

    private fun trackFromLegacyTitle(title: String, source: Source): Track {
        val separator = title.lastIndexOf(" - ")
        return if (separator > 0) {
            Track(title.substring(0, separator).trim(), title.substring(separator + 3).trim(), source)
        } else {
            Track(title.trim(), "", source)
        }
    }

    fun nowPlaying(): String? = if (player != null && (loading || isPlaying || paused)) currentTitle else null

    fun currentArtist(): String = currentArtist

    fun currentLyric(): String? {
        if (!lyricsReady || lyrics.isEmpty()) return null
        val position = positionMs()
        var low = 0
        var high = lyrics.lastIndex
        var match = -1
        while (low <= high) {
            val mid = (low + high).ushr(1)
            if (lyrics[mid].timeMs <= position) {
                match = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return lyrics.getOrNull(match)?.text
    }

    fun lyricsReady(): Boolean = lyricsReady

    fun currentIndex(): Int = if (queueIndex >= 0) queueIndex + 1 else 0

    fun queueSize(): Int = queue.size

    fun queueSnapshot(): List<QueueEntry> = queue.map { QueueEntry(it.title, it.artist) }

    fun positionMs(): Long {
        val exo = player ?: return 0L
        return runCatching { exo.currentPosition.coerceAtLeast(0L) }.getOrDefault(0L)
    }

    fun durationMs(): Long {
        val exo = player ?: return 0L
        return runCatching {
            exo.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
        }.getOrDefault(0L)
    }

    companion object {
        private const val TAG = "PilotMusic"

        @Volatile
        private var instance: MusicPlayer? = null

        fun get(ctx: Context): MusicPlayer = instance ?: synchronized(this) {
            instance ?: MusicPlayer(ctx.applicationContext).also { instance = it }
        }
    }
}
