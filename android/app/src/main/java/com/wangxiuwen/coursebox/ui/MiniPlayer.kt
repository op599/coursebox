@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.wangxiuwen.coursebox.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.wangxiuwen.coursebox.CourseboxApp
import com.wangxiuwen.coursebox.ui.theme.toneFor

/**
 * Floating mini player — 599player-style capsule.
 *
 *  - **single tap** → expand to full player
 *  - **double tap** → toggle play / pause
 *  - **long press** → quick-action sheet (favourite / next / dismiss)
 *  - **drag**        → reposition; offset is persisted on the VM, so
 *                       the bar stays put across tab switches and
 *                       cold-loaded composables.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MiniPlayer(
    nav: NavHostController,
    modifier: Modifier = Modifier,
) {
    val vm = CourseboxApp.playerVm
    val lesson = vm.current
    val pkg = vm.currentPackageId
    val density = LocalDensity.current
    var showSheet by remember { mutableStateOf(false) }
    var lyricsExpanded by remember { mutableStateOf(false) }

    AnimatedVisibility(
        visible = lesson != null && pkg != null,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier,
    ) {
        if (lesson == null || pkg == null) return@AnimatedVisibility
        val tone = toneFor("nce")
        val duration = vm.durationMs.coerceAtLeast(1L)
        val progress = (vm.positionMs.toFloat() / duration).coerceIn(0f, 1f)

        // Screen dimensions used to clamp the drag offset so the capsule
        // can't be dragged past the bottom comfort zone (~80dp from the
        // device's bottom edge) or off-screen sideways.
        val cfg = androidx.compose.ui.platform.LocalConfiguration.current
        val screenWidthDp = cfg.screenWidthDp.toFloat()
        val screenHeightDp = cfg.screenHeightDp.toFloat()
        // Capsule width = screenWidth - 24dp (12dp horizontal padding on
        // each side). Half of that is how far the centre can shift before
        // an edge clips; leave 12dp breathing margin.
        val maxDragXDp = (screenWidthDp - 24f) / 2f - 12f
        // Y axis grows downward. The capsule rests at BottomCenter with a
        // 26dp gap to the bottom (RootScreen padding). Cap positive dy at
        // +20dp so a small dip is still allowed but the bar can't be
        // dragged past the device's bottom edge or into the gesture bar.
        val maxDragYDp = 20f
        // Negative dy pulls it up. Allow it to be parked near the top of
        // the screen, leaving ~200dp of safe area for status bar + app
        // chrome above.
        val minDragYDp = -(screenHeightDp - 200f).coerceAtLeast(0f)

        Column(
            modifier = Modifier
                .offset(vm.miniDragX.dp, vm.miniDragY.dp)
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Inline expand card — tap the capsule to toggle.
            //  - Video track  → small live video preview (SurfaceView in a
            //    black rounded shell, capped at 200dp tall so the popup
            //    doesn't dominate the screen).
            //  - Audio track  → scrollable lyrics overlay showing the entire
            //    lesson transcript. Active line is timestamp-driven when the
            //    package was forced-aligned (NceLine.startMs >= 0); otherwise
            //    we fall back to a position-ratio approximation. Auto-scroll
            //    keeps the active line in the upper third of the viewport,
            //    but pauses while the user is dragging so the reader can roam
            //    freely; it resumes on the next line change.
            if (lyricsExpanded) {
                if (vm.hasVideo) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(vm.videoAspect.coerceAtLeast(0.6f))
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color.Black),
                        ) {
                            androidx.compose.ui.viewinterop.AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { c ->
                                    android.view.SurfaceView(c).also { sv ->
                                        vm.attachVideoSurfaceView(sv)
                                    }
                                },
                            )
                            DisposableEffect(Unit) {
                                onDispose { /* surface auto-released with SurfaceView */ }
                            }
                        }
                    }
                } else {
                    LyricsOverlay(
                        vm = vm,
                        lesson = lesson,
                        positionMs = vm.positionMs,
                        durationMs = vm.durationMs,
                    )
                }
                Spacer(Modifier.height(6.dp))
            }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .shadow(
                    elevation = 12.dp,
                    shape = RoundedCornerShape(28.dp),
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.30f),
                    spotColor = Color.Black.copy(alpha = 0.30f),
                )
                .clip(RoundedCornerShape(28.dp))
                .background(Color.White)
                .pointerInput(maxDragXDp, maxDragYDp, minDragYDp) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        val dx = with(density) { drag.x.toDp().value }
                        val dy = with(density) { drag.y.toDp().value }
                        // Clamp so the capsule can't be flung off-screen
                        // sideways or dragged below the bottom comfort
                        // zone. Bottom limit (+20dp) is intentionally
                        // tight — user feedback was that pulling it too
                        // low makes it awkward to recover.
                        vm.miniDragX = (vm.miniDragX + dx)
                            .coerceIn(-maxDragXDp, maxDragXDp)
                        vm.miniDragY = (vm.miniDragY + dy)
                            .coerceIn(minDragYDp, maxDragYDp)
                    }
                }
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { lyricsExpanded = !lyricsExpanded },
                    onDoubleClick = {
                        nav.navigate("nce/${pkg}/player/${lesson.id}") {
                            launchSingleTop = true
                        }
                    },
                    onLongClick = { showSheet = true },
                ),
        ) {
            // Progress is rendered as a thin ring around the play/pause
            // button on the right of the capsule (see below). The top
            // hairline was removed — it read like a stray top border and
            // clashed with the 28dp rounded capsule shape.

            Row(
                modifier = Modifier.fillMaxSize().padding(start = 8.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(tone.gradient),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        lesson.lesson.toString().padStart(2, '0'),
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        lesson.titleEn.ifBlank { lesson.numberLabel },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (lesson.titleCn.isNotBlank()) {
                        Text(
                            lesson.titleCn,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF6B7280),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Default.SkipPrevious,
                    contentDescription = "上一句",
                    tint = Color(0xFF3B3B3B),
                    modifier = Modifier
                        .size(28.dp)
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { vm.playPreviousSentence() },
                        ),
                )
                Spacer(Modifier.width(6.dp))
                if (vm.isBuffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    // Play / pause button with a thin circular progress
                    // ring tracing the outer edge. The ring replaces the
                    // old top-edge hairline so the capsule no longer looks
                    // like it has a stray top border.
                    Box(
                        modifier = Modifier.size(40.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        // Track (full circle, faint).
                        CircularProgressIndicator(
                            progress = { 1f },
                            modifier = Modifier.fillMaxSize(),
                            color = Color(0x14000000),
                            strokeWidth = 2.dp,
                            trackColor = Color.Transparent,
                            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                        )
                        // Active arc.
                        CircularProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxSize(),
                            color = tone.gradMid,
                            strokeWidth = 2.dp,
                            trackColor = Color.Transparent,
                            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                        )
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(tone.gradMid)
                                .combinedClickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { vm.togglePlayPause() },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (vm.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (vm.isPlaying) "暂停" else "播放",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Default.SkipNext,
                    contentDescription = "下一句",
                    tint = Color(0xFF3B3B3B),
                    modifier = Modifier
                        .size(28.dp)
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { vm.playNextSentence() },
                        ),
                )
            }
        }
        }
    }

    // Long-press quick actions.
    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            containerColor = Color.White,
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                QuickAction(
                    icon = Icons.Default.SkipPrevious,
                    label = "上一课",
                    onClick = { vm.playPrev(); showSheet = false },
                )
                QuickAction(
                    icon = Icons.Default.SkipNext,
                    label = "下一课",
                    onClick = { vm.playNext(); showSheet = false },
                )
                QuickAction(
                    icon = Icons.Default.Close,
                    label = "关闭播放",
                    onClick = { vm.stopAndClear(); showSheet = false },
                )
            }
        }
    }
}

/**
 * Scrollable inline lyrics overlay shown when the user single-taps the
 * mini-player capsule. Renders the entire lesson transcript in a
 * `LazyColumn` so long NCE 课文 (10-20+ lines) aren't truncated to a
 * peephole window.
 *
 * Active line selection:
 *  - if any line has a real `startMs` (forced-aligned package),
 *    pick the last line whose `startMs <= positionMs`;
 *  - otherwise fall back to a uniform-ratio approximation.
 *
 * Auto-scroll vs user scroll:
 *  - on every active-line change we `animateScrollToItem(idx, -200)` so
 *    the active row sits in the upper third of the viewport;
 *  - while the user is actively dragging the list (`isScrollInProgress`),
 *    auto-scroll is suppressed; it resumes on the *next* line change so
 *    the reader can scroll ahead/behind without being hijacked.
 */
@Composable
private fun LyricsOverlay(
    vm: com.wangxiuwen.coursebox.ui.nce.NcePlayerVm,
    lesson: com.wangxiuwen.coursebox.ui.nce.NceLesson,
    positionMs: Long,
    durationMs: Long,
) {
    val lines = lesson.lines
    val hasWords = lesson.sections.any { it.words.isNotEmpty() }
    val hasQuestion = lesson.question.isNotBlank()

    // Tab selection — reset to "lines" when lesson changes.
    var selectedTab by remember(lesson.id) { mutableStateOf("lines") }

    val curIdx = remember(lines, positionMs, durationMs) {
        if (lines.isEmpty()) -1
        else if (lines.any { it.startMs >= 0 }) {
            var idx = 0
            lines.forEachIndexed { i, line ->
                if (line.startMs in 0..positionMs) idx = i
            }
            idx.coerceIn(0, lines.lastIndex)
        } else if (durationMs > 0) {
            ((positionMs.toFloat() / durationMs) * lines.size).toInt()
                .coerceIn(0, lines.lastIndex)
        } else 0
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.45f)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.78f)),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Tab row across the top — same widget as the full player.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                com.wangxiuwen.coursebox.ui.nce.LyricsTab(
                    "课文",
                    selected = selectedTab == "lines",
                ) { selectedTab = "lines" }
                if (hasWords) {
                    com.wangxiuwen.coursebox.ui.nce.LyricsTab(
                        "单词",
                        selected = selectedTab == "words",
                    ) { selectedTab = "words" }
                }
                if (hasQuestion) {
                    com.wangxiuwen.coursebox.ui.nce.LyricsTab(
                        "问题",
                        selected = selectedTab == "question",
                    ) { selectedTab = "question" }
                }
            }

            // Content area fills the remaining space.
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (selectedTab) {
                    "words" -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            com.wangxiuwen.coursebox.ui.nce.LyricsWordsContent(lesson)
                        }
                    }
                    "question" -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            com.wangxiuwen.coursebox.ui.nce.LyricsQuestionContent(lesson)
                        }
                    }
                    else -> LinesTabContent(lines = lines, curIdx = curIdx)
                }
            }
        }
    }
}

/**
 * 课文 tab content — preserves the original LazyColumn + auto-scroll
 * behaviour from the audio-overlay inline view.
 */
@Composable
private fun LinesTabContent(
    lines: List<com.wangxiuwen.coursebox.ui.nce.NceLine>,
    curIdx: Int,
) {
    if (curIdx < 0 || lines.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "♪ 暂无课文",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.6f),
            )
        }
        return
    }

    val listState = rememberLazyListState()
    // Pause auto-scroll while the user is dragging so we don't fight
    // them. Resume on the next line change (no explicit "snap back"
    // button — simpler UX, matches spec).
    val userScrolling by remember {
        derivedStateOf { listState.isScrollInProgress }
    }
    LaunchedEffect(curIdx, lines.size) {
        if (!userScrolling && curIdx in lines.indices) {
            // Negative offset shifts the item *up* by 200px from the
            // viewport top, so the active line lands in the upper
            // third rather than glued to the top edge.
            listState.animateScrollToItem(curIdx, scrollOffset = -200)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(lines.size) { i ->
            val line = lines[i]
            val text = line.en.ifBlank { line.cn }
            if (text.isBlank()) return@items
            val isCur = i == curIdx
            Column {
                Text(
                    text = text,
                    fontSize = 17.sp,
                    lineHeight = 22.sp,
                    fontWeight = if (isCur) FontWeight.Bold else FontWeight.Normal,
                    color = if (isCur) Color.White else Color.White.copy(alpha = 0.6f),
                )
                if (line.en.isNotBlank() && line.cn.isNotBlank()) {
                    Text(
                        text = line.cn,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = if (isCur) Color.White.copy(alpha = 0.85f)
                        else Color.White.copy(alpha = 0.45f),
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
