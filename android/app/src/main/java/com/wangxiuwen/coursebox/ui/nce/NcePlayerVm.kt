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
enum class SentencePracticeMode { OFF, REPEAT_ONE, SHADOWING }
enum class ShadowingPhase { IDLE, LISTENING, SPEAKING }

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
    var activeSentenceIndex by mutableStateOf(-1)
        private set
    var sentencePracticeMode by mutableStateOf(SentencePracticeMode.OFF)
        private set
    var shadowingPhase by mutableStateOf(ShadowingPhase.IDLE)
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
        cancelSentencePractice()
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
    private var shadowingJob: Job? = null
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
                if (playing) {
                    startTicker()
                } else {
                    // A learner usually pauses because the current sentence
                    // needs attention. Capture the exact pause position so
                    // loop/shadowing starts from that sentence, rather than
                    // the ticker's value from up to 250 ms earlier.
                    positionMs = p.currentPosition.coerceAtLeast(0L)
                    if (sentencePracticeMode == SentencePracticeMode.OFF && speechSegments.isNotEmpty()) {
                        activeSentenceIndex = segmentAtOrBefore(positionMs)
                    }
                    stopTicker()
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    durationMs = p.duration.coerceAtLeast(0L)
                }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                cancelSentencePractice()
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
        if (shadowingPhase == ShadowingPhase.SPEAKING) {
            shadowingJob?.cancel()
            shadowingPhase = ShadowingPhase.LISTENING
            speechSegments.getOrNull(activeSentenceIndex)?.let { seekInternal(it.startMs) }
            player.play()
        } else if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
    }

    fun seekTo(ms: Long) {
        cancelSentencePractice()
        seekInternal(ms)
    }

    private fun seekInternal(ms: Long) {
        val target = ms.coerceAtLeast(0L)
        player.seekTo(target)
        positionMs = target
    }
    fun playNext() {
        cancelSentencePractice()
        if (player.hasNextMediaItem()) player.seekToNextMediaItem()
    }
    fun playPrev() {
        cancelSentencePractice()
        if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem()
    }

    /** Restart the current spoken chunk; a second press near its start moves
     * to the previous chunk. While VAD is still running, fall back to 8 s. */
    fun playPreviousSentence() {
        cancelSentencePractice()
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
        cancelSentencePractice()
        if (speechSegments.isEmpty()) {
            seekTo(player.currentPosition - FALLBACK_SEEK_MS)
        } else {
            seekTo(speechSegments[segmentAtOrBefore(player.currentPosition)].startMs)
        }
    }

    fun playNextSentence() {
        cancelSentencePractice()
        val next = speechSegments.firstOrNull { it.startMs > player.currentPosition + 250L }
        when {
            next != null -> seekTo(next.startMs)
            player.hasNextMediaItem() -> player.seekToNextMediaItem()
            else -> seekTo((player.currentPosition + FALLBACK_SEEK_MS).coerceAtMost(player.duration.coerceAtLeast(0L)))
        }
    }
    fun selectIndex(idx: Int) {
        if (idx in playlist.indices) {
            cancelSentencePractice()
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

    /** Play any VAD sentence selected from the sentence list. */
    fun playSentence(index: Int) {
        val segment = speechSegments.getOrNull(index) ?: return
        cancelSentencePractice()
        activeSentenceIndex = index
        seekInternal(segment.startMs)
        player.play()
    }

    /** Toggle an exact sentence loop. This deliberately overrides shadowing. */
    fun toggleRepeatSentence(index: Int) {
        val segment = speechSegments.getOrNull(index) ?: return
        if (sentencePracticeMode == SentencePracticeMode.REPEAT_ONE && activeSentenceIndex == index) {
            finishSentencePracticeAndContinue()
            return
        }
        shadowingJob?.cancel()
        sentencePracticeMode = SentencePracticeMode.REPEAT_ONE
        shadowingPhase = ShadowingPhase.IDLE
        activeSentenceIndex = index
        seekInternal(segment.startMs)
        player.play()
    }

    /** Listen to one sentence, pause for the learner to repeat it, then advance. */
    fun toggleShadowing() {
        val index = when {
            activeSentenceIndex in speechSegments.indices -> activeSentenceIndex
            speechSegments.isNotEmpty() -> segmentAtOrBefore(player.currentPosition)
            else -> return
        }
        setShadowingEnabled(sentencePracticeMode != SentencePracticeMode.SHADOWING, index)
    }

    /** Shadowing is a switch inside a fixed sentence drill. Turning it off
     * returns to looping the same sentence; only closing the sheet advances. */
    fun setShadowingEnabled(enabled: Boolean, index: Int) {
        val segment = speechSegments.getOrNull(index) ?: return
        if (enabled) {
            startShadowingAt(index)
        } else {
            shadowingJob?.cancel()
            shadowingJob = null
            sentencePracticeMode = SentencePracticeMode.REPEAT_ONE
            shadowingPhase = ShadowingPhase.IDLE
            activeSentenceIndex = index
            seekInternal(segment.startMs)
            player.play()
        }
    }

    /** Select a sentence from the sheet using the active practice mode.
     * With no explicit mode selected, sentence selection defaults to looping. */
    fun selectSentenceForPractice(index: Int) {
        when (sentencePracticeMode) {
            SentencePracticeMode.SHADOWING -> startShadowingAt(index)
            SentencePracticeMode.OFF,
            SentencePracticeMode.REPEAT_ONE,
            -> toggleRepeatSentence(index)
        }
    }

    private fun startShadowingAt(index: Int) {
        val segment = speechSegments.getOrNull(index) ?: return
        shadowingJob?.cancel()
        sentencePracticeMode = SentencePracticeMode.SHADOWING
        shadowingPhase = ShadowingPhase.LISTENING
        activeSentenceIndex = index
        seekInternal(segment.startMs)
        player.play()
    }

    fun cancelSentencePractice() {
        shadowingJob?.cancel()
        shadowingJob = null
        sentencePracticeMode = SentencePracticeMode.OFF
        shadowingPhase = ShadowingPhase.IDLE
    }

    /** Leave the temporary sentence drill and resume the lesson after it. */
    fun finishSentencePracticeAndContinue() {
        val completed = activeSentenceIndex
        cancelSentencePractice()
        val next = speechSegments.getOrNull(completed + 1)
        if (next != null) {
            activeSentenceIndex = completed + 1
            seekInternal(next.startMs)
        } else {
            // Let ExoPlayer finish the tail of the lesson. Its independent
            // whole-lesson repeat setting decides whether to loop or advance.
            speechSegments.getOrNull(completed)?.let { seekInternal(it.endMs) }
        }
        player.play()
    }

    /** Whole-lesson repeat is independent from the temporary sentence drill. */
    fun toggleLessonRepeat() {
        repeatChoice = if (repeatChoice == RepeatModeChoice.ONE) {
            RepeatModeChoice.ALL
        } else {
            RepeatModeChoice.ONE
        }
        player.repeatMode = if (repeatChoice == RepeatModeChoice.ONE) {
            Player.REPEAT_MODE_ONE
        } else {
            Player.REPEAT_MODE_ALL
        }
    }

    private fun startTicker() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (true) {
                positionMs = player.currentPosition.coerceAtLeast(0L)
                val d = player.duration
                if (d > 0) durationMs = d
                updateActiveSentenceAndPracticeBoundary()
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
        shadowingJob?.cancel()
        stopTicker()
        player.release()
        scope.cancel()
        super.onCleared()
    }

    private fun segmentAtOrBefore(positionMs: Long): Int = speechSegments
        .indexOfLast { it.startMs <= positionMs }
        .coerceAtLeast(0)

    private fun updateActiveSentenceAndPracticeBoundary() {
        if (speechSegments.isEmpty()) return
        if (sentencePracticeMode == SentencePracticeMode.OFF) {
            activeSentenceIndex = segmentAtOrBefore(positionMs)
            return
        }
        val segment = speechSegments.getOrNull(activeSentenceIndex) ?: return
        if (positionMs < segment.endMs - SENTENCE_END_TOLERANCE_MS) return
        when (sentencePracticeMode) {
            SentencePracticeMode.REPEAT_ONE -> {
                seekInternal(segment.startMs)
                player.play()
            }
            SentencePracticeMode.SHADOWING -> {
                if (shadowingPhase != ShadowingPhase.LISTENING) return
                shadowingPhase = ShadowingPhase.SPEAKING
                player.pause()
                val expectedLesson = currentIndex
                val pauseMs = ((segment.endMs - segment.startMs) * SHADOWING_PAUSE_MULTIPLIER)
                    .toLong()
                    .coerceIn(MIN_SHADOWING_PAUSE_MS, MAX_SHADOWING_PAUSE_MS)
                shadowingJob?.cancel()
                shadowingJob = scope.launch {
                    delay(pauseMs)
                    if (sentencePracticeMode != SentencePracticeMode.SHADOWING ||
                        currentIndex != expectedLesson
                    ) return@launch
                    // Keep drilling the same manually selected sentence.
                    // Advancing belongs exclusively to closing the sheet.
                    shadowingPhase = ShadowingPhase.LISTENING
                    seekInternal(speechSegments[activeSentenceIndex].startMs)
                    player.play()
                }
            }
            SentencePracticeMode.OFF -> Unit
        }
    }

    private fun analyzeCurrentSentenceBoundaries() {
        analysisJob?.cancel()
        cancelSentencePractice()
        speechSegments = emptyList()
        activeSentenceIndex = -1
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
                activeSentenceIndex = segmentAtOrBefore(player.currentPosition)
                SentenceAnalysisState.READY
            } else {
                SentenceAnalysisState.FAILED
            }
        }
    }

    companion object {
        private const val FALLBACK_SEEK_MS = 8_000L
        private const val RESTART_CURRENT_AFTER_MS = 1_500L
        private const val SENTENCE_END_TOLERANCE_MS = 60L
        private const val MIN_SHADOWING_PAUSE_MS = 2_000L
        private const val MAX_SHADOWING_PAUSE_MS = 8_000L
        private const val SHADOWING_PAUSE_MULTIPLIER = 1.2
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
