package com.wangxiuwen.coursebox.ui.nce

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import android.view.SurfaceView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class RepeatModeChoice { OFF, ALL, ONE }

/**
 * Player view-model for the NCE reader. Wraps a single [ExoPlayer]
 * driven by a playlist of [NceLesson]; exposes Compose state directly
 * (mutableState fields) — Compose collects these without LiveData glue.
 */
class NcePlayerVm(context: Context) : ViewModel() {
    private val appCtx = context.applicationContext

    /** All lessons currently loaded (the playlist). */
    var playlist: List<NceLesson> by mutableStateOf(emptyList())
        private set

    /** sha→file path lookups, computed once when [playlist] is set. */
    private var resolvedPaths: List<String?> = emptyList()

    var currentIndex by mutableStateOf(0)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var isBuffering by mutableStateOf(false)
        private set
    var repeatChoice: RepeatModeChoice by mutableStateOf(RepeatModeChoice.ALL)
        private set
    var showBack: Boolean by mutableStateOf(false)
        private set

    var speechSegments: List<SpeechSegment> by mutableStateOf(emptyList())
        private set
    var sentenceAnalysisState: SentenceAnalysisState by mutableStateOf(SentenceAnalysisState.IDLE)
        private set

    /** Which course package the current playlist belongs to; lets the
     * mini player navigate back to the right player screen. */
    var currentPackageId: String? by mutableStateOf(null)
        private set

    /** Mini player drag offset in dp — persisted in-memory across screens. */
    var miniDragX: Float by mutableStateOf(0f)
    var miniDragY: Float by mutableStateOf(0f)

    /** True once the player has emitted a non-zero video size for the
     *  current item. Cover gradient is swapped for a SurfaceView when set. */
    var hasVideo: Boolean by mutableStateOf(false)
        private set
    /** Width / height of the current video stream — drives surface aspect. */
    var videoAspect: Float by mutableStateOf(16f / 9f)
        private set

    val current: NceLesson? get() = playlist.getOrNull(currentIndex)

    fun stopAndClear() {
        analysisJob?.cancel()
        player.stop()
        player.clearMediaItems()
        playlist = emptyList()
        currentPackageId = null
        positionMs = 0L
        durationMs = 0L
        speechSegments = emptyList()
        sentenceAnalysisState = SentenceAnalysisState.IDLE
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tickJob: Job? = null
    private var analysisJob: Job? = null
    private val voiceActivityAnalyzer = VoiceActivityAnalyzer(appCtx)

    private val player: ExoPlayer = ExoPlayer.Builder(appCtx)
        .setMediaSourceFactory(
            androidx.media3.exoplayer.source.DefaultMediaSourceFactory(appCtx)
                .setDataSourceFactory(
                    com.wangxiuwen.coursebox.core.cx.CxOrDefaultDataSourceFactory(appCtx),
                ),
        )
        .build().also { p ->
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (playing) startTicker() else stopTicker()
            }
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    durationMs = p.duration.coerceAtLeast(0L)
                }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentIndex = p.currentMediaItemIndex.coerceAtLeast(0)
                durationMs = p.duration.coerceAtLeast(0L)
                positionMs = 0L
                // Reseed from the manifest field so the surface swap happens
                // synchronously on lesson change, not on the first decoded
                // video frame. onVideoSizeChanged still refines the aspect.
                hasVideo = playlist.getOrNull(currentIndex)?.isVideo == true
                analyzeCurrentSentenceBoundaries()
            }
            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) {
                    hasVideo = true
                    videoAspect = size.width.toFloat() / size.height.toFloat()
                }
            }
        })
        p.setRepeatMode(Player.REPEAT_MODE_ALL)
    }

    /** Attach a SurfaceView so any video track in the current playlist
     *  renders into it. Must be paired with [detachVideoSurfaceView] when
     *  the view is destroyed. */
    fun attachVideoSurfaceView(view: SurfaceView) {
        player.setVideoSurfaceView(view)
    }

    fun detachVideoSurfaceView(view: SurfaceView) {
        player.clearVideoSurfaceView(view)
    }

    fun load(
        courseId: String,
        lessons: List<NceLesson>,
        library: com.wangxiuwen.coursebox.core.CourseLibrary,
        initialLessonId: String?,
    ) {
        // Re-entering the same session (mini player → full player) must
        // NOT reset playback position. The old guard used reference
        // equality on `lessons === playlist`, but loadNceLessons builds a
        // fresh List each call, so the check always failed and the
        // setMediaItems below restarted from 00:00. We now key the guard
        // on (courseId, lesson-id list, requested lesson) — same course,
        // same playlist contents, requested lesson is the one we're
        // already on → leave the player alone.
        val playlistIdsMatch = lessons.size == playlist.size &&
            lessons.zip(playlist).all { (a, b) -> a.id == b.id }
        val sameLessonRequested = initialLessonId == null ||
            initialLessonId.isBlank() ||
            initialLessonId == current?.id
        if (currentPackageId == courseId && playlistIdsMatch && sameLessonRequested) return

        currentPackageId = courseId
        playlist = lessons
        // resolveMediaPath picks the video object for video lessons (so
        // ExoPlayer renders into the SurfaceView) and falls back to the
        // audio object / logical path / remote URL for audio lessons.
        resolvedPaths = lessons.map { it.resolveMediaPath(library) }

        val items = lessons.mapIndexedNotNull { idx, _ ->
            resolvedPaths[idx]?.let { path -> MediaItem.fromUri(toUri(path)) }
        }
        if (items.isEmpty()) {
            player.stop()
            player.clearMediaItems()
            return
        }
        val startIndex = (lessons.indexOfFirst { it.id == initialLessonId }.takeIf { it >= 0 } ?: 0)
            .coerceIn(0, items.size - 1)
        player.setMediaItems(items, startIndex, 0L)
        currentIndex = startIndex
        // Seed the surface-vs-art toggle immediately so the player screen
        // shows the SurfaceView before the first video frame decodes.
        hasVideo = lessons.getOrNull(startIndex)?.isVideo == true
        player.prepare()
        analyzeCurrentSentenceBoundaries()
    }

    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun seekTo(ms: Long) {
        val target = ms.coerceAtLeast(0L)
        player.seekTo(target)
        positionMs = target
    }
    fun playNext() { if (player.hasNextMediaItem()) player.seekToNextMediaItem() }
    fun playPrev() { if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem() }

    /** Restart the current spoken chunk; a second press near its start moves
     * to the previous chunk. While VAD is still running, fall back to 8 s. */
    fun playPreviousSentence() {
        val segments = speechSegments
        if (segments.isEmpty()) {
            seekTo(player.currentPosition - FALLBACK_SEEK_MS)
            return
        }
        val position = player.currentPosition.coerceAtLeast(0L)
        val index = segmentAtOrBefore(position)
        val currentStart = segments[index].startMs
        val target = if (position - currentStart > RESTART_CURRENT_AFTER_MS) {
            currentStart
        } else {
            segments[(index - 1).coerceAtLeast(0)].startMs
        }
        seekTo(target)
    }

    fun replayCurrentSentence() {
        if (speechSegments.isEmpty()) {
            seekTo(player.currentPosition - FALLBACK_SEEK_MS)
        } else {
            seekTo(speechSegments[segmentAtOrBefore(player.currentPosition)].startMs)
        }
    }

    fun playNextSentence() {
        val next = speechSegments.firstOrNull { it.startMs > player.currentPosition + 250L }
        when {
            next != null -> seekTo(next.startMs)
            player.hasNextMediaItem() -> player.seekToNextMediaItem()
            else -> seekTo((player.currentPosition + FALLBACK_SEEK_MS).coerceAtMost(player.duration.coerceAtLeast(0L)))
        }
    }
    fun selectIndex(idx: Int) {
        if (idx in playlist.indices) {
            player.seekTo(idx, 0L)
            player.playWhenReady = true
        }
    }

    fun cycleRepeat() {
        repeatChoice = when (repeatChoice) {
            RepeatModeChoice.OFF -> RepeatModeChoice.ALL
            RepeatModeChoice.ALL -> RepeatModeChoice.ONE
            RepeatModeChoice.ONE -> RepeatModeChoice.OFF
        }
        player.setRepeatMode(
            when (repeatChoice) {
                RepeatModeChoice.OFF -> Player.REPEAT_MODE_OFF
                RepeatModeChoice.ALL -> Player.REPEAT_MODE_ALL
                RepeatModeChoice.ONE -> Player.REPEAT_MODE_ONE
            }
        )
    }

    fun toggleFlip() { showBack = !showBack }
    fun setFlip(v: Boolean) { showBack = v }

    private fun startTicker() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (true) {
                positionMs = player.currentPosition.coerceAtLeast(0L)
                val d = player.duration
                if (d > 0) durationMs = d
                delay(250)
            }
        }
    }

    private fun stopTicker() {
        tickJob?.cancel()
        tickJob = null
    }

    override fun onCleared() {
        analysisJob?.cancel()
        stopTicker()
        player.release()
        scope.cancel()
        super.onCleared()
    }

    private fun segmentAtOrBefore(positionMs: Long): Int = speechSegments
        .indexOfLast { it.startMs <= positionMs }
        .coerceAtLeast(0)

    private fun analyzeCurrentSentenceBoundaries() {
        analysisJob?.cancel()
        speechSegments = emptyList()
        val expectedIndex = currentIndex
        val mediaPath = resolvedPaths.getOrNull(expectedIndex)
        if (mediaPath.isNullOrBlank()) {
            sentenceAnalysisState = SentenceAnalysisState.FAILED
            return
        }
        sentenceAnalysisState = SentenceAnalysisState.ANALYZING
        analysisJob = scope.launch {
            val result = runCatching { voiceActivityAnalyzer.analyze(mediaPath) }
            if (currentIndex != expectedIndex) return@launch
            result.exceptionOrNull()?.let { Log.w("NcePlayerVm", "离线语音分析失败", it) }
            speechSegments = result.getOrDefault(emptyList())
            sentenceAnalysisState = if (speechSegments.isNotEmpty()) {
                SentenceAnalysisState.READY
            } else {
                SentenceAnalysisState.FAILED
            }
        }
    }

    companion object {
        private const val FALLBACK_SEEK_MS = 8_000L
        private const val RESTART_CURRENT_AFTER_MS = 1_500L
    }

    /**
     * Convert a resource string from CourseLibrary into a Uri ExoPlayer
     * can consume:
     *   - "cx:///..."   → custom no-extract scheme, parsed verbatim
     *   - "http(s)://"  → remote URL (legacy NCE-900 audio)
     *   - everything else → treat as an absolute filesystem path
     */
    private fun toUri(path: String): android.net.Uri = when {
        path.startsWith("cx:") -> android.net.Uri.parse(path)
        path.startsWith("http") -> android.net.Uri.parse(path)
        else -> android.net.Uri.fromFile(java.io.File(path))
    }
}

/** Convenience factory so callers can `viewModel { NcePlayerVm.factory(ctx) }`. */
class NcePlayerVmFactory(private val ctx: Context) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = NcePlayerVm(ctx) as T
}
