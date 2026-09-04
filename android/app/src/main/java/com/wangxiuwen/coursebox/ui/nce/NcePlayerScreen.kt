package com.wangxiuwen.coursebox.ui.nce

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.wangxiuwen.coursebox.CourseboxApp
import com.wangxiuwen.coursebox.core.CourseLibrary
import com.wangxiuwen.coursebox.ui.SlimSlider
import com.wangxiuwen.coursebox.ui.fmtTime
import com.wangxiuwen.coursebox.ui.theme.CourseTone
import com.wangxiuwen.coursebox.ui.theme.toneFor
import kotlinx.coroutines.launch

private val ScreenBlack = Color(0xFF000000)
private val OnDark = Color.White
private val OnDarkDim = Color(0xCCFFFFFF)
private val OnDarkFaint = Color(0x66FFFFFF)
// Player chrome sits on a deep-blue gradient; the global AccentBlue
// would disappear against tone.gradMid, so use a warm amber accent here
// for high-contrast highlights (tab underline, word pron, etc).
private val PlayerAccent = Color(0xFFFBBF24)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NcePlayerScreen(
    library: CourseLibrary,
    courseId: String,
    lessonId: String,
    nav: NavHostController,
) {
    val scope = rememberCoroutineScope()
    val vm = remember { CourseboxApp.playerVm }

    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(courseId, lessonId) {
        scope.launch {
            val lessons = loadNceLessons(library, courseId)
            vm.load(courseId, lessons, library, lessonId)
            ready = true
        }
    }

    // Tone derives from the owning course so the player chrome matches
    // the library card's hue — purple 900句 stays purple in the player,
    // red NCE-3 stays red, etc.
    val pkg = remember(courseId) { library.packageById(courseId) }
    val tone = toneFor(pkg?.type ?: "nce", pkg?.id ?: courseId)
    val lesson = vm.current ?: vm.playlist.firstOrNull()
    val bgGradient = if (vm.showBack)
        Brush.verticalGradient(listOf(tone.gradMid, tone.gradEnd, ScreenBlack))
    else
        Brush.verticalGradient(listOf(tone.gradMid, ScreenBlack, ScreenBlack))

    Box(modifier = Modifier.fillMaxSize().background(bgGradient)) {
        if (!ready) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = OnDark)
            }
            return@Box
        }
        if (lesson == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("课程数据缺失，无法播放", color = OnDarkDim)
            }
            return@Box
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            // Chrome
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "收起", tint = OnDark)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "NEW CONCEPT ENGLISH · BOOK 02",
                    color = OnDarkDim,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.size(40.dp))
            }

            if (!vm.showBack) {
                PlayerFront(vm, lesson, tone)
            } else {
                PlayerBackLyrics(vm, lesson, tone)
            }
        }
    }
}

@Composable
private fun ColumnScope.PlayerFront(vm: NcePlayerVm, lesson: NceLesson, tone: CourseTone) {
    // In landscape, fillMaxWidth + aspectRatio blows the media box past the
    // screen height (a 16:9 video on a 2400x1080 phone wants 1332px tall),
    // wiping the controls. Bind height to a fraction of the screen instead
    // and let aspectRatio drive the width, centred horizontally.
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    val isLandscape = cfg.screenWidthDp > cfg.screenHeightDp

    if (vm.hasVideo) {
        // Video face: SurfaceView wrapped in a black rounded shell.
        // Bound to the ExoPlayer via VM; detached on disposal.
        val aspect = vm.videoAspect.coerceAtLeast(0.6f)
        val mediaModifier = if (isLandscape) {
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxHeight(0.42f)
                .aspectRatio(aspect)
                .align(Alignment.CenterHorizontally)
        } else {
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .aspectRatio(aspect)
        }
        Box(
            modifier = mediaModifier
                // Visible border so the black SurfaceView doesn't blend
                // into the page's ScreenBlack gradient stop (especially in
                // landscape, where the media box sits in the lower half
                // of the screen where the bg has faded to pure black).
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.18f),
                    shape = RoundedCornerShape(14.dp),
                )
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black),
        ) {
            androidx.compose.ui.viewinterop.AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    android.view.SurfaceView(c).also { sv -> vm.attachVideoSurfaceView(sv) }
                },
            )
            DisposableEffect(Unit) {
                onDispose { /* surface auto-released with SurfaceView */ }
            }
        }
    } else {
        val coverModifier = if (isLandscape) {
            Modifier
                .padding(horizontal = 32.dp)
                .fillMaxHeight(0.42f)
                .aspectRatio(1f)
                .align(Alignment.CenterHorizontally)
        } else {
            Modifier
                .padding(horizontal = 32.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
        }
        Box(
            modifier = coverModifier
                .clip(RoundedCornerShape(14.dp))
                .background(tone.gradient),
        ) {
            Text(
                text = "B2",
                color = Color.White.copy(alpha = 0.18f),
                fontSize = 220.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 0.dp, bottom = 0.dp)
                    .offset(x = 12.dp, y = 40.dp),
            )
            Column(modifier = Modifier.fillMaxSize().padding(22.dp)) {
                Text(
                    "LESSON",
                    color = OnDark.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    lesson.lesson.toString().padStart(2, '0'),
                    color = OnDark,
                    fontSize = 96.sp,
                    fontWeight = FontWeight.Black,
                )
            }
        }
    }

    Spacer(Modifier.height(if (isLandscape) 8.dp else 20.dp))
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                lesson.titleEn.ifBlank { lesson.numberLabel },
                color = OnDark,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (lesson.titleCn.isNotBlank()) {
                Text(
                    "${lesson.titleCn} · 第 ${lesson.lesson} 课",
                    color = OnDarkDim,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }

    Spacer(Modifier.height(if (isLandscape) 10.dp else 24.dp))
    SliderRow(vm)
    SentenceTransportRow(vm)
    Spacer(Modifier.height(2.dp))
    TransportRow(vm)
    Spacer(Modifier.weight(1f))

    // The 课文/单词 flip button shifts focus away from the video. In
    // landscape the screen is short and the controls already crowd the
    // bottom, so just drop it — the user can tap the back chevron to
    // rotate to portrait if they need the lyrics.
    if (!isLandscape) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0x33FFFFFF))
                    .clickable { vm.setFlip(true) }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
            Icon(
                Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                tint = OnDark,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("课文 / 单词", color = OnDark, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun SliderRow(vm: NcePlayerVm) {
    var dragging by remember { mutableStateOf(false) }
    var dragPos by remember { mutableLongStateOf(0L) }
    val effective = if (dragging) dragPos else vm.positionMs
    val duration = vm.durationMs.coerceAtLeast(0L)
    Column(modifier = Modifier.padding(horizontal = 32.dp)) {
        SlimSlider(
            value = effective.coerceAtMost(duration).toFloat(),
            valueRange = 0f..(if (duration > 0) duration.toFloat() else 1f),
            enabled = duration > 0,
            onValueChange = { dragging = true; dragPos = it.toLong() },
            onValueChangeFinished = { vm.seekTo(dragPos); dragging = false },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(fmtTime(effective), color = OnDarkFaint, style = MaterialTheme.typography.labelSmall)
            Text(fmtTime(duration), color = OnDarkFaint, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SentenceTransportRow(vm: NcePlayerVm) {
    var showSentenceList by rememberSaveable { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                modifier = Modifier.weight(1f),
                onClick = { showSentenceList = true },
                enabled = vm.speechSegments.isNotEmpty(),
                contentPadding = PaddingValues(horizontal = 2.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = OnDark),
            ) { Text("句子", style = MaterialTheme.typography.labelLarge) }
            TextButton(
                modifier = Modifier.weight(1f),
                onClick = vm::playPreviousSentence,
                contentPadding = PaddingValues(horizontal = 2.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = OnDark),
            ) { Text("上一句", style = MaterialTheme.typography.labelLarge) }
            TextButton(
                modifier = Modifier.weight(1f),
                onClick = vm::replayCurrentSentence,
                contentPadding = PaddingValues(horizontal = 2.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = PlayerAccent),
            ) { Text("重听", style = MaterialTheme.typography.labelLarge) }
            TextButton(
                modifier = Modifier.weight(1f),
                onClick = vm::playNextSentence,
                contentPadding = PaddingValues(horizontal = 2.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = OnDark),
            ) { Text("下一句", style = MaterialTheme.typography.labelLarge) }
            TextButton(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (vm.sentencePracticeMode == SentencePracticeMode.SHADOWING) PlayerAccent
                        else Color.Transparent,
                    ),
                onClick = vm::toggleShadowing,
                enabled = vm.speechSegments.isNotEmpty(),
                contentPadding = PaddingValues(horizontal = 2.dp),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = if (vm.sentencePracticeMode == SentencePracticeMode.SHADOWING) {
                        ScreenBlack
                    } else {
                        OnDark
                    },
                ),
            ) { Text("跟读", style = MaterialTheme.typography.labelLarge) }
        }
        Text(
            when {
                vm.sentencePracticeMode == SentencePracticeMode.REPEAT_ONE ->
                    "正在循环第 ${vm.activeSentenceIndex + 1} 句"
                vm.sentencePracticeMode == SentencePracticeMode.SHADOWING &&
                    vm.shadowingPhase == ShadowingPhase.SPEAKING ->
                    "第 ${vm.activeSentenceIndex + 1} 句：现在跟读，稍后自动播放下一句"
                vm.sentencePracticeMode == SentencePracticeMode.SHADOWING ->
                    "第 ${vm.activeSentenceIndex + 1} 句：听原音"
                vm.sentenceAnalysisState == SentenceAnalysisState.ANALYZING ->
                    "正在离线分析语音…按钮暂按 8 秒跳转"
                vm.sentenceAnalysisState == SentenceAnalysisState.READY ->
                    "已识别 ${vm.speechSegments.size} 个语音片段"
                vm.sentenceAnalysisState == SentenceAnalysisState.FAILED ->
                    "未识别到语音，按钮按 8 秒跳转"
                else -> ""
            },
            color = OnDarkFaint,
            style = MaterialTheme.typography.labelSmall,
        )
    }

    if (showSentenceList) {
        SentenceListSheet(vm = vm, onDismiss = { showSentenceList = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SentenceListSheet(vm: NcePlayerVm, onDismiss: () -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(vm.activeSentenceIndex) {
        if (vm.activeSentenceIndex in vm.speechSegments.indices) {
            listState.animateScrollToItem(vm.activeSentenceIndex)
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.82f)
                .padding(horizontal = 18.dp),
        ) {
            Text("句子列表", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "按实际录音中的停顿切分；点一句播放，点“循环”反复听这一句。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(
                    items = vm.speechSegments,
                    key = { index, segment -> "${index}_${segment.startMs}" },
                ) { index, segment ->
                    val active = index == vm.activeSentenceIndex
                    val repeating = active &&
                        vm.sentencePracticeMode == SentencePracticeMode.REPEAT_ONE
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (active) MaterialTheme.colorScheme.primaryContainer
                                else Color.Transparent,
                            )
                            .clickable { vm.playSentence(index) }
                            .padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "第 ${index + 1} 句",
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                            )
                            Text(
                                "${fmtTime(segment.startMs)} – ${fmtTime(segment.endMs)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { vm.toggleRepeatSentence(index) }) {
                            Text(if (repeating) "停止" else "循环")
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun TransportRow(vm: NcePlayerVm) {
    val canPrev = vm.currentIndex > 0
    val canNext = vm.currentIndex < vm.playlist.lastIndex
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { vm.playPrev() }, enabled = canPrev) {
            Icon(
                Icons.Default.SkipPrevious,
                contentDescription = "上一课",
                tint = if (canPrev) OnDark else OnDarkFaint,
                modifier = Modifier.size(48.dp),
            )
        }
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(OnDark)
                .clickable { vm.togglePlayPause() },
            contentAlignment = Alignment.Center,
        ) {
            if (vm.isBuffering) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = ScreenBlack,
                    strokeWidth = 2.5.dp,
                )
            } else {
                Icon(
                    if (vm.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (vm.isPlaying) "暂停" else "播放",
                    tint = ScreenBlack,
                    modifier = Modifier.size(34.dp),
                )
            }
        }
        IconButton(onClick = { vm.playNext() }, enabled = canNext) {
            Icon(
                Icons.Default.SkipNext,
                contentDescription = "下一课",
                tint = if (canNext) OnDark else OnDarkFaint,
                modifier = Modifier.size(48.dp),
            )
        }
    }
}

@Composable
private fun ColumnScope.PlayerBackLyrics(vm: NcePlayerVm, lesson: NceLesson, tone: CourseTone) {
    val hasWords = lesson.sections.any { it.words.isNotEmpty() }
    val hasQuestion = lesson.question.isNotBlank()
    var selectedTab by rememberSaveable(lesson.id) { mutableStateOf("lines") }

    Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            LyricsTab("课文", selected = selectedTab == "lines") { selectedTab = "lines" }
            if (hasWords) LyricsTab("单词", selected = selectedTab == "words") { selectedTab = "words" }
            if (hasQuestion) LyricsTab("问题", selected = selectedTab == "question") { selectedTab = "question" }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 12.dp),
        ) {
            when (selectedTab) {
                "words" -> LyricsWordsContent(lesson)
                "question" -> LyricsQuestionContent(lesson)
                else -> LyricsLinesContent(vm, lesson)
            }
            Spacer(Modifier.height(120.dp))
        }
    }

    Surface(
        color = Color(0x66000000),
        modifier = Modifier.fillMaxWidth().clickable { vm.setFlip(false) },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(tone.gradient),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    lesson.lesson.toString().padStart(2, '0'),
                    color = OnDark,
                    fontWeight = FontWeight.Black,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    lesson.titleEn.ifBlank { lesson.numberLabel },
                    color = OnDark,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${fmtTime(vm.positionMs)} / ${fmtTime(vm.durationMs)}",
                    color = OnDarkFaint,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            IconButton(onClick = { vm.togglePlayPause() }) {
                Icon(
                    if (vm.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = OnDark,
                    modifier = Modifier.size(28.dp),
                )
            }
            IconButton(onClick = { vm.setFlip(false) }) {
                Icon(Icons.Default.Close, contentDescription = "关闭课文", tint = OnDarkDim)
            }
        }
    }
}

@Composable
internal fun LyricsTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier.clickable(
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        ),
    ) {
        Text(
            label,
            color = if (selected) OnDark else OnDarkFaint,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
        if (selected) {
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .height(2.dp)
                    .width(24.dp)
                    .background(PlayerAccent),
            )
        }
    }
}

@Composable
internal fun LyricsLinesContent(vm: NcePlayerVm, lesson: NceLesson) {
    val lines = lesson.lines
    if (lines.isEmpty()) {
        Text("课文文本缺失", color = OnDarkFaint, style = MaterialTheme.typography.bodyMedium)
        return
    }
    // Prefer real per-line start_ms when the package was forced-aligned
    // (scripts/align_lessons.py). Otherwise fall back to a uniform-ratio
    // approximation against the audio duration. The aligned path picks the
    // *last* line whose startMs has been reached, so a line stays
    // highlighted until the next aligned line begins.
    val hasTimestamps = lines.any { it.startMs >= 0 }
    val approxCurrent = if (hasTimestamps) {
        val pos = vm.positionMs
        var idx = 0
        lines.forEachIndexed { i, line ->
            if (line.startMs in 0..pos) idx = i
        }
        idx.coerceIn(0, lines.lastIndex)
    } else if (vm.durationMs > 0) {
        ((vm.positionMs.toFloat() / vm.durationMs) * lines.size).toInt()
            .coerceIn(0, lines.lastIndex)
    } else 0
    lines.forEachIndexed { idx, line ->
        val isCurrent = idx == approxCurrent
        Column(modifier = Modifier.padding(vertical = if (isCurrent) 10.dp else 6.dp)) {
            Text(
                line.en,
                color = if (isCurrent) Color.White else OnDarkDim,
                style = if (isCurrent) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCurrent) FontWeight.ExtraBold else FontWeight.SemiBold,
                lineHeight = if (isCurrent) 32.sp else MaterialTheme.typography.bodyLarge.lineHeight,
            )
            if (line.cn.isNotBlank()) {
                Text(
                    line.cn,
                    color = if (isCurrent) Color.White.copy(alpha = 0.85f) else OnDarkDim,
                    style = if (isCurrent) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
internal fun LyricsWordsContent(lesson: NceLesson) {
    var rendered = false
    for (s in lesson.sections) {
        if (s.words.isEmpty()) continue
        rendered = true
        for (w in s.words) {
            Column(modifier = Modifier.padding(vertical = 5.dp)) {
                Text(
                    w.word,
                    color = OnDark,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Row {
                    if (w.pron.isNotBlank()) {
                        Text(
                            w.pron,
                            color = PlayerAccent.copy(alpha = 0.95f),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        listOf(w.pos, w.definition).filter { it.isNotBlank() }.joinToString(" "),
                        color = OnDarkDim,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
    if (!rendered) {
        Text("本课暂无单词表", color = OnDarkFaint, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun LyricsQuestionContent(lesson: NceLesson) {
    if (lesson.question.isBlank()) {
        Text("本课暂无问题", color = OnDarkFaint, style = MaterialTheme.typography.bodyMedium)
        return
    }
    Text(
        lesson.question,
        color = OnDark,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.SemiBold,
    )
}
