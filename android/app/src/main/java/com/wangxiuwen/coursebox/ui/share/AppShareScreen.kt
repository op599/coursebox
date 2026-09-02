package com.wangxiuwen.coursebox.ui.share

import android.content.Intent
import android.content.ClipData
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.navigation.NavHostController
import com.wangxiuwen.coursebox.BuildConfig
import com.wangxiuwen.coursebox.core.AppShareServer
import com.wangxiuwen.coursebox.core.LanImportServer
import com.wangxiuwen.coursebox.ui.theme.AccentBlue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Share the currently installed APK without internet access. */
@Composable
fun AppShareScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val server = remember { AppShareServer(ctx.applicationContext) }
    var serverError by remember { mutableStateOf<String?>(null) }
    var url by remember { mutableStateOf<String?>(null) }
    var qr by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var preparingShare by remember { mutableStateOf(false) }

    DisposableEffect(server) {
        runCatching {
            server.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        }.onFailure { serverError = it.message ?: "未知错误" }
        onDispose { runCatching { server.stop() } }
    }

    // The address can change when the user enables a hotspot while this page
    // is open, so refresh it without requiring the screen to be reopened.
    LaunchedEffect(server) {
        while (true) {
            val next = AppShareServer.localIpv4()?.let { "http://$it:${AppShareServer.PORT}${server.pagePath}" }
            if (next != url) {
                url = next
                qr = next?.let { LanImportServer.qrBitmap(it, 720) }
            }
            delay(2_000)
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFFF5F4F1)).statusBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 18.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.Black)
                }
                Spacer(Modifier.width(4.dp))
                Text("分享课程盒子", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
            }

            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.White,
                    shape = RoundedCornerShape(14.dp),
                    shadowElevation = 1.dp,
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            "让对方连接同一 Wi-Fi，或连接本机热点，然后用浏览器扫码下载。",
                            color = Color(0xFF6B6B66),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        qr?.let { bitmap ->
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "APK 下载二维码",
                                modifier = Modifier.size(230.dp).clip(RoundedCornerShape(8.dp)),
                            )
                        }
                        Text(
                            when {
                                serverError != null -> "服务启动失败：$serverError"
                                url == null -> "未检测到局域网，请连接 Wi-Fi 或开启热点"
                                else -> url.orEmpty()
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (serverError != null) Color(0xFFC93B3B) else Color(0xFF6B6B66),
                        )
                        Text(
                            "当前分享版本 v${BuildConfig.VERSION_NAME} · 页面关闭后下载服务自动停止",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF6B6B66),
                        )
                    }
                }

                Button(
                    enabled = !preparingShare,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                    onClick = {
                        preparingShare = true
                        scope.launch {
                            runCatching {
                                val apk = withContext(Dispatchers.IO) {
                                    val src = File(ctx.applicationInfo.sourceDir)
                                    val dst = File(ctx.cacheDir, "coursebox-android-v${BuildConfig.VERSION_NAME}.apk")
                                    if (!dst.isFile || dst.length() != src.length()) src.copyTo(dst, overwrite = true)
                                    dst
                                }
                                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = AppShareServer.APK_MIME
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    clipData = ClipData.newRawUri("课程盒子 APK", uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                ctx.startActivity(Intent.createChooser(send, "通过蓝牙或互传分享课程盒子"))
                            }
                            preparingShare = false
                        }
                    },
                ) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (preparingShare) "正在准备 APK…" else "用蓝牙 / 互传分享 APK")
                }

                Text(
                    "提示：二维码方案不需要接收方预装任何应用；系统分享可调用蓝牙、小米互传或其他附近分享工具。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF6B6B66),
                )
            }
        }
    }
}
