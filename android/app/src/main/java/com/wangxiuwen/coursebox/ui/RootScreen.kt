package com.wangxiuwen.coursebox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.wangxiuwen.coursebox.BuildConfig
import com.wangxiuwen.coursebox.core.CourseLibrary
import com.wangxiuwen.coursebox.core.UpdateAvailable
import com.wangxiuwen.coursebox.core.UpdateChecker
import com.wangxiuwen.coursebox.ui.chinese.ChineseLibraryScreen
import com.wangxiuwen.coursebox.ui.library.LibraryTab
import com.wangxiuwen.coursebox.ui.music.MusicFoundationScreen
import com.wangxiuwen.coursebox.ui.nce.NceListScreen
import com.wangxiuwen.coursebox.ui.nce.NcePlayerScreen
import com.wangxiuwen.coursebox.ui.tts.TtsScreen
import com.wangxiuwen.coursebox.ui.theme.AccentBlue
import kotlinx.coroutines.launch

private object Routes {
    const val HOME = "home"
    const val NCE_LIST = "nce/{courseId}"
    const val NCE_PLAYER = "nce/{courseId}/player/{lessonId}"
    const val CHINESE = "chinese/{courseId}"
    const val MUSIC = "music/{courseId}"
    const val TTS = "tts"
    const val LAN_IMPORT = "lan_import"
    const val APP_SHARE = "app_share"
    const val SHARE_BATCH = "share"
    const val SHARE = "share/{courseId}"
}

/**
 * Single-screen root: 资源库 is the home, all course types drill in from there.
 * Mini player floats on top of every non-player screen.
 *
 * Update flow lives here too:
 *   - LaunchedEffect checks GitHub Releases on launch
 *   - if newer, show "发现新版本" dialog (with 立即更新 / 稍后)
 *   - 立即更新 → close dialog immediately, kick off background download
 *   - while downloading, a thin LinearProgressIndicator sits under the
 *     status bar so the user can keep using the app and still know the
 *     update is making progress
 *   - on download done, show a small "立即安装" pill the user can tap to
 *     fire the OS install Intent (Android requires user confirm to install)
 *   - 稍后 in either dialog hides it for this session only; the bar tap
 *     is sticky so the user can still install later
 */
@Composable
fun RootScreen(library: CourseLibrary) {
    val nav = rememberNavController()
    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val isOnPlayer = currentRoute?.startsWith("nce/") == true && currentRoute.contains("/player/")

    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // Lifecycle of the update prompt: check → ask → download → install.
    var update by remember { mutableStateOf<UpdateAvailable?>(null) }
    var promptDismissed by rememberSaveable { mutableStateOf(false) }
    var downloadStarted by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var downloadIndeterminate by remember { mutableStateOf(true) }
    var readyApk by remember { mutableStateOf<java.io.File?>(null) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    var installDismissed by rememberSaveable { mutableStateOf(false) }

    // Step 1: check on launch.
    LaunchedEffect(Unit) {
        update = UpdateChecker.check(BuildConfig.VERSION_NAME)
    }

    // Step 2: when the user confirms with 立即更新, run the download in the
    // background. The dialog has already closed by the time we get here, so
    // the user can keep navigating; only the slim top progress bar shows.
    LaunchedEffect(downloadStarted) {
        if (!downloadStarted) return@LaunchedEffect
        val u = update ?: return@LaunchedEffect
        runCatching {
            UpdateChecker.download(ctx, u.apkAsset) { bytes, total ->
                if (total > 0) {
                    downloadIndeterminate = false
                    downloadProgress = (bytes.toFloat() / total).coerceIn(0f, 1f)
                } else {
                    downloadIndeterminate = true
                }
            }
        }.onSuccess { apk ->
            downloadProgress = 1f
            readyApk = apk
        }.onFailure { e ->
            downloadError = e.message ?: "未知错误"
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(navController = nav, startDestination = Routes.HOME) {
            composable(Routes.HOME) { LibraryTab(library, nav) }
            composable(Routes.NCE_LIST) { entry ->
                val courseId = entry.arguments?.getString("courseId").orEmpty()
                NceListScreen(library, courseId, nav)
            }
            composable(Routes.NCE_PLAYER) { entry ->
                val courseId = entry.arguments?.getString("courseId").orEmpty()
                val lessonId = entry.arguments?.getString("lessonId").orEmpty()
                NcePlayerScreen(library, courseId, lessonId, nav)
            }
            composable(Routes.CHINESE) { entry ->
                val courseId = entry.arguments?.getString("courseId").orEmpty()
                ChineseLibraryScreen(library, courseId, nav)
            }
            composable(Routes.MUSIC) { entry ->
                val courseId = entry.arguments?.getString("courseId").orEmpty()
                MusicFoundationScreen(library, courseId, nav)
            }
            composable(Routes.TTS) { TtsScreen(nav) }
            composable(Routes.LAN_IMPORT) {
                com.wangxiuwen.coursebox.ui.library.LanImportScreen(library, nav)
            }
            composable(Routes.APP_SHARE) {
                com.wangxiuwen.coursebox.ui.share.AppShareScreen(nav)
            }
            composable(Routes.SHARE_BATCH) {
                com.wangxiuwen.coursebox.ui.share.ShareScreen(library, null, nav)
            }
            composable(Routes.SHARE) { entry ->
                val cid = entry.arguments?.getString("courseId").orEmpty()
                com.wangxiuwen.coursebox.ui.share.ShareScreen(library, cid, nav)
            }
        }

        // Top-of-screen thin progress bar — only shown while we have an
        // in-flight download. Sits under the status bar via statusBarsPadding
        // so it doesn't draw behind the system clock. Tapping it once the
        // download finishes fires the install Intent.
        if (downloadStarted && (readyApk == null || !installDismissed)) {
            UpdateProgressBar(
                progress = downloadProgress,
                indeterminate = downloadIndeterminate && readyApk == null,
                ready = readyApk != null,
                version = update?.latestVersion.orEmpty(),
                onTap = {
                    readyApk?.let { apk ->
                        runCatching { UpdateChecker.install(ctx, apk) }
                        installDismissed = true
                    }
                },
            )
        }

        if (!isOnPlayer) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                MiniPlayer(
                    nav = nav,
                    modifier = Modifier.padding(bottom = 26.dp),
                )
            }
        }

        // Initial "发现新版本" prompt — only the first time, only if download
        // hasn't been kicked off yet. Tapping 立即更新 closes the dialog and
        // hands off to the background download.
        val u = update
        if (u != null && !promptDismissed && !downloadStarted) {
            AlertDialog(
                onDismissRequest = { promptDismissed = true },
                containerColor = Color.White,
                title = { Text("发现新版本 v${u.latestVersion}") },
                text = {
                    Text(
                        "当前版本：v${u.currentVersion}\n\n" +
                            (u.release.body.take(280).ifBlank { "点击立即更新, 下载会在后台进行, 不影响使用。" }),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            downloadStarted = true
                            promptDismissed = true
                        },
                    ) {
                        Text("立即更新", color = AccentBlue)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { promptDismissed = true }) {
                        Text("稍后", color = Color(0xFF6B7280))
                    }
                },
            )
        }
    }
}

@Composable
private fun UpdateProgressBar(
    progress: Float,
    indeterminate: Boolean,
    ready: Boolean,
    version: String,
    onTap: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xCC0E0D0E))
                .let { if (ready) it.clickable(onClick = onTap) else it }
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (ready) "v$version 已下载, 点击安装" else "后台下载新版本 v$version",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.padding(horizontal = 8.dp))
                if (!ready && !indeterminate) {
                    Text(
                        "${(progress * 100).toInt()}%",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        // The thin animated bar sits just below the pill so a tap on the
        // pill is the actual install action while still telegraphing
        // progress visually.
        Box(modifier = Modifier.fillMaxWidth().padding(top = 38.dp)) {
            if (!ready) {
                if (indeterminate) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = AccentBlue,
                        trackColor = Color.Transparent,
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = AccentBlue,
                        trackColor = Color.Transparent,
                    )
                }
            }
        }
    }
}

@Composable
internal fun currentRoute(nav: androidx.navigation.NavHostController): String? =
    nav.currentBackStackEntryAsState().value?.destination?.route
