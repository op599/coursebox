package com.wangxiuwen.coursebox.ui.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.wangxiuwen.coursebox.ui.theme.AccentBlue

private val PaperBg = Color(0xFFF5F4F1)
private val InkSoft = Color(0xFF6B6B66)

/** Per-file row tracked by the LAN screen so the user sees every upload's
 *  outcome instead of just the last one. */
/**
 * Full-screen replacement for the old AlertDialog-based LAN importer.
 * Two-pane in landscape (QR + meta on the left, results list on the right);
 * single-column in portrait. The dialog version overflowed past the
 * screen in landscape and got cropped — this version uses the whole
 * window so any number of upload rows fits.
 */
@Composable
fun LanImportScreen(receiver: NearbyReceiveHost, nav: NavHostController) {
    val cfg = LocalConfiguration.current
    val isLandscape = cfg.screenWidthDp > cfg.screenHeightDp

    val serverStatus by receiver.status
    val url by receiver.url
    val qr by receiver.qr
    val rows = receiver.rows

    DisposableEffect(Unit) {
        receiver.setManualImportOpen(true)
        onDispose {
            receiver.setManualImportOpen(false)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PaperBg)
            .statusBarsPadding(),
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
                Text("局域网导入", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = Color.Black)
            }

            if (isLandscape) {
                Row(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        Column { QrSection(qr, url, serverStatus) }
                    }
                    Spacer(Modifier.width(12.dp))
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        ResultsList(rows)
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    QrSection(qr, url, serverStatus)
                    ResultsList(rows, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun QrSection(qr: android.graphics.Bitmap?, url: String?, serverStatus: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.White,
        shape = RoundedCornerShape(14.dp),
        shadowElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "电脑/平板浏览器打开下面的地址, 选 .cx 课程包上传 (支持多选).",
                color = InkSoft,
                style = MaterialTheme.typography.bodySmall,
            )
            qr?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "二维码",
                    modifier = Modifier
                        .size(220.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
            }
            Text(
                url ?: "—",
                color = Color.Black,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(serverStatus, style = MaterialTheme.typography.bodySmall, color = InkSoft)
        }
    }
}

@Composable
private fun ResultsList(rows: List<NearbyReceiveHost.FileRow>, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color.White,
        shape = RoundedCornerShape(14.dp),
        shadowElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                "上传结果 (${rows.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color.Black,
            )
            Spacer(Modifier.height(8.dp))
            if (rows.isEmpty()) {
                Text(
                    "暂无上传 — 用浏览器打开上面的地址开始.",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSoft,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(rows.size) { i ->
                        val r = rows[i]
                        val accent = when (r.state) {
                            "done" -> Color(0xFF0A7A3F)
                            "error" -> Color(0xFFC93B3B)
                            else -> InkSoft
                        }
                        val marker = when (r.state) {
                            "done" -> "✓"
                            "error" -> "✗"
                            else -> "•"
                        }
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(marker, color = accent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    r.name,
                                    color = Color.Black,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Text(
                                r.message,
                                color = accent,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
