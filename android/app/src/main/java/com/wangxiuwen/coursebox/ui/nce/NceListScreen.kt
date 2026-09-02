package com.wangxiuwen.coursebox.ui.nce

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.wangxiuwen.coursebox.core.CourseLibrary
import com.wangxiuwen.coursebox.ui.theme.AccentBlue
import com.wangxiuwen.coursebox.ui.theme.toneFor
import kotlinx.coroutines.launch

private val PaperBg = Color(0xFFF5F4F1)
private val InkSoft = Color(0xFF6B6B66)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NceListScreen(
    library: CourseLibrary,
    courseId: String,
    nav: NavHostController,
) {
    val scope = rememberCoroutineScope()
    val pkg = remember(courseId) { library.packageById(courseId) }
    var lessons by remember { mutableStateOf<List<NceLesson>>(emptyList()) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedBook by rememberSaveable { mutableStateOf<Int?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(courseId) {
        scope.launch {
            lessons = loadNceLessons(library, courseId)
            loading = false
        }
    }

    val books = remember(lessons) { lessons.map { it.book }.distinct().sorted() }
    val currentBook = selectedBook ?: books.firstOrNull()
    val visible = remember(lessons, query, currentBook) {
        val needle = query.trim().lowercase()
        lessons.asSequence()
            .filter { it.book == currentBook }
            .filter {
                needle.isBlank() ||
                    it.titleEn.lowercase().contains(needle) ||
                    it.titleCn.contains(needle) ||
                    it.question.lowercase().contains(needle) ||
                    it.lines.any { line ->
                        line.en.lowercase().contains(needle) || line.cn.contains(needle)
                    } ||
                    it.sections.any { section ->
                        section.title.lowercase().contains(needle) ||
                            section.text.any { text -> text.lowercase().contains(needle) } ||
                            section.words.any { word ->
                                word.word.lowercase().contains(needle) ||
                                    word.definition.contains(needle) ||
                                    word.pos.lowercase().contains(needle)
                            }
                    } ||
                    it.numberLabel.contains(needle)
            }
            .toList()
    }
    // Tone derives from the parent course so a 英语900句 lesson list stays
    // purple, NCE-第三册 stays red, etc — same palette the library card
    // picked. Falls back to "nce" if the package vanished mid-render.
    val tone = toneFor(pkg?.type ?: "nce", pkg?.id ?: courseId)

    Box(modifier = Modifier.fillMaxSize().background(PaperBg).statusBarsPadding()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar — minimal, no M3 chrome
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 18.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = Color.Black,
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    pkg?.title ?: "新概念英语",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    softWrap = true,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Search field — flat white pill
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                color = Color.White,
                shape = RoundedCornerShape(14.dp),
                shadowElevation = 1.dp,
            ) {
                BasicSearchField(query, onChange = { query = it })
            }

            // Book filter — red-underline pill row.
            if (books.isNotEmpty()) {
                val scrollState = rememberScrollState()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(scrollState)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    books.forEach { book ->
                        BookPill(
                            label = bookLabel(book),
                            selected = book == currentBook,
                            onClick = { selectedBook = book },
                        )
                    }
                }
            }

            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                visible.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("没有可显示的课程", color = InkSoft)
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp).let {
                        PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 130.dp)
                    },
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(visible, key = { it.id }) { lesson ->
                        LessonRow(lesson, tone) {
                            nav.navigate("nce/${courseId}/player/${lesson.id}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BasicSearchField(value: String, onChange: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, contentDescription = null, tint = InkSoft, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            modifier = Modifier.weight(1f),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.Black),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(
                        "搜索课次 / 英文 / 中文",
                        color = InkSoft,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                inner()
            },
        )
    }
}

@Composable
private fun BookPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            color = if (selected) Color.Black else InkSoft,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            style = MaterialTheme.typography.titleMedium,
        )
        if (selected) {
            Spacer(Modifier.height(3.dp))
            Box(
                modifier = Modifier
                    .height(3.dp)
                    .width(18.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(AccentBlue),
            )
        }
    }
}

@Composable
private fun LessonRow(
    lesson: NceLesson,
    tone: com.wangxiuwen.coursebox.ui.theme.CourseTone,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = Color.White,
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Plain mini block: centred lesson number on the parent
            // course's tone gradient. (Playing-card chrome moved up to
            // the library tab; in-course rows stay calm.)
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(tone.gradient),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    lesson.lesson.toString().padStart(2, '0'),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    lesson.titleEn.ifBlank { lesson.numberLabel },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    softWrap = true,
                    overflow = TextOverflow.Ellipsis,
                )
                if (lesson.titleCn.isNotBlank()) {
                    Text(
                        lesson.titleCn,
                        style = MaterialTheme.typography.bodySmall,
                        color = InkSoft,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun bookLabel(book: Int): String = when (book) {
    900 -> "英语 900"
    in 1..4 -> "第 $book 册"
    else -> "其他"
}
