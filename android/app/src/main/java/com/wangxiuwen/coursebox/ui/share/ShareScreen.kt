package com.wangxiuwen.coursebox.ui.share

import android.content.ClipData
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.navigation.NavHostController
import com.wangxiuwen.coursebox.core.CourseLibrary
import com.wangxiuwen.coursebox.core.CoursePackageRecord
import com.wangxiuwen.coursebox.core.courseShareFiles
import com.wangxiuwen.coursebox.core.lan.CourseShareClient
import com.wangxiuwen.coursebox.core.lan.DeviceType
import com.wangxiuwen.coursebox.core.lan.InfoDto
import com.wangxiuwen.coursebox.core.lan.LocalSend
import com.wangxiuwen.coursebox.core.lan.LocalSendDiscovery
import com.wangxiuwen.coursebox.ui.theme.AccentBlue
import kotlinx.coroutines.launch

private val PaperBg = Color(0xFFF5F4F1)
private val InkSoft = Color(0xFF6B6B66)

/** A peer the discovery loop has surfaced. Keyed by fingerprint so two
 *  channels (UDP + mDNS) reporting the same device collapse to one row. */
private data class Peer(
    val host: String,
    val port: Int,
    val info: InfoDto,
)

/**
 * Sends one or more course packages to another coursebox / LocalSend receiver
 * discovered automatically via UDP multicast + mDNS — no manual IP entry.
 *
 * Lifecycle: a [LocalSendDiscovery] is started on Composable enter and
 * stopped on dispose. Discovered peers stream into a stateMap keyed by
 * fingerprint, rendered as a list. Tapping a peer kicks off
 * prepare-upload + per-file streaming via [CourseShareClient].
 */
@Composable
fun ShareScreen(library: CourseLibrary, initialCourseId: String?, nav: NavHostController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val packages = library.stateFlow.value.packages
    val selected = remember(initialCourseId) {
        mutableStateMapOf<String, Boolean>().apply {
            if (initialCourseId == null) {
                packages.forEach { put(it.id, true) }
            } else {
                put(initialCourseId, true)
            }
        }
    }

    val peers = remember { mutableStateMapOf<String, Peer>() }
    var sending by remember { mutableStateOf(false) }
    var sendingTo by remember { mutableStateOf<String?>(null) }
    var lastResult by remember { mutableStateOf<String?>(null) }
    val progressByFile = remember { mutableStateMapOf<String, Pair<Long, Long>>() }

    val selectedIds = selected.filterValues { it }.keys
    val selectedPackages = packages.filter { it.id in selectedIds }
    val cxFiles = courseShareFiles(packages, selectedIds)
    val totalBytes = cxFiles.sumOf { it.length() }

    // selfInfo must be stable per-session so the discovery loop's "skip own
    // fingerprint" check works.
    val selfInfo = remember {
        val fp = "share-" + (ctx.packageName + Build.MODEL).hashCode().toUInt().toString(16)
        InfoDto(
            alias = "课程盒子 · ${Build.MODEL ?: "Android"}",
            deviceModel = Build.MODEL,
            deviceType = DeviceType.Mobile,
            fingerprint = fp,
            port = LocalSend.PORT,
            protocol = "http",
            download = false,
        )
    }

    DisposableEffect(Unit) {
        val disc = LocalSendDiscovery(
            ctx = ctx,
            selfInfo = { selfInfo },
            onPeer = { host, port, info ->
                peers[info.fingerprint] = Peer(host, port, info)
            },
        )
        disc.start()
        onDispose { disc.stop() }
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
                Text("批量分享课程", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = Color.Black)
            }

            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Card(title = "选择课程 (${selectedPackages.size}/${packages.size})") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            enabled = !sending,
                            onClick = {
                                val selectAll = selectedPackages.size != packages.size
                                packages.forEach { selected[it.id] = selectAll }
                            },
                        ) {
                            Text(
                                if (selectedPackages.size == packages.size) "清空" else "全选",
                                color = AccentBlue,
                            )
                        }
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(packages, key = { it.id }) { course ->
                            CourseSelectionRow(
                                course = course,
                                checked = selected[course.id] == true,
                                enabled = !sending,
                                onToggle = { selected[course.id] = !(selected[course.id] ?: false) },
                            )
                        }
                    }
                    Text(
                        "共 ${cxFiles.size} 个去重文件 · ${fmtMb(totalBytes)} MB",
                        color = InkSoft,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    val multipartCount = selectedPackages.count { it.multipartParts.isNotEmpty() }
                    if (multipartCount > 0) {
                        Text(
                            "包含 $multipartCount 个多分片课程，所有分片会自动发送",
                            color = InkSoft,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                OutlinedButton(
                    enabled = !sending && cxFiles.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        runCatching {
                            val uris = ArrayList(cxFiles.map { file ->
                                FileProvider.getUriForFile(
                                    ctx,
                                    "${ctx.packageName}.fileprovider",
                                    file,
                                )
                            })
                            val send = Intent(
                                if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE,
                            ).apply {
                                type = "application/octet-stream"
                                if (uris.size == 1) {
                                    putExtra(Intent.EXTRA_STREAM, uris.first())
                                } else {
                                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                                }
                                clipData = ClipData.newUri(ctx.contentResolver, "课程包", uris.first()).apply {
                                    uris.drop(1).forEach { addItem(ClipData.Item(it)) }
                                }
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            ctx.startActivity(Intent.createChooser(send, "用互传或蓝牙分享课程包"))
                        }.onFailure { e ->
                            lastResult = "✗ 无法打开系统分享：${e.message}"
                        }
                    },
                ) {
                    Text("用系统互传 / 蓝牙分享 ${cxFiles.size} 个文件")
                }
                Text(
                    "大文件建议选小米互传或 Quick Share；普通蓝牙速度较慢。对方收到 .cx 后需在课程盒子中导入。",
                    color = InkSoft,
                    style = MaterialTheme.typography.bodySmall,
                )

                Card(title = "附近设备 (${peers.size})") {
                    if (peers.isEmpty()) {
                        Text(
                            "正在搜索同一 Wi-Fi 下的 coursebox / LocalSend 设备…",
                            color = InkSoft,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(peers.values.toList()) { p ->
                                PeerRow(
                                    peer = p,
                                    enabled = !sending && cxFiles.isNotEmpty(),
                                    isCurrent = sendingTo == p.info.fingerprint,
                                    onTap = {
                                        if (sending || cxFiles.isEmpty()) return@PeerRow
                                        sending = true
                                        sendingTo = p.info.fingerprint
                                        lastResult = null
                                        progressByFile.clear()
                                        val specs = cxFiles.map { CourseShareClient.FileSpec(source = it) }
                                        scope.launch {
                                            val r = CourseShareClient.sendFiles(
                                                ctx = ctx,
                                                host = p.host,
                                                port = p.port,
                                                files = specs,
                                            ) { id, sent, total ->
                                                progressByFile[id] = sent to total
                                            }
                                            lastResult = when (r) {
                                                is CourseShareClient.Result.Ok ->
                                                    "✓ 已向 ${p.info.alias} 发送 ${selectedPackages.size} 个课程（${r.sentFiles} 个文件）"
                                                is CourseShareClient.Result.Rejected -> "✗ ${p.info.alias} 拒绝: ${r.message}"
                                                is CourseShareClient.Result.IoError -> "✗ 网络错: ${r.cause.message}"
                                            }
                                            sending = false
                                            sendingTo = null
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                if (sending || progressByFile.isNotEmpty()) {
                    Card(title = "发送进度") {
                        val sent = progressByFile.values.sumOf { it.first }
                        val pct = if (totalBytes > 0) (sent * 100 / totalBytes).toInt() else 0
                        Text(
                            "$pct% · ${fmtMb(sent)} / ${fmtMb(totalBytes)} MB",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Black,
                        )
                        LinearProgressIndicator(
                            progress = { sent.toFloat() / totalBytes.coerceAtLeast(1L) },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            color = AccentBlue,
                        )
                    }
                }

                lastResult?.let {
                    Card(title = "结果") {
                        Text(
                            it,
                            color = if (it.startsWith("✓")) Color(0xFF0A7A3F) else Color(0xFFC93B3B),
                        )
                    }
                }

                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

@Composable
private fun CourseSelectionRow(
    course: CoursePackageRecord,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onToggle).padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            enabled = enabled,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(checkedColor = AccentBlue),
        )
        Spacer(Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                course.title,
                color = Color.Black,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "${course.lessonIndex.size} 课 · ${course.cxPaths.size} 文件",
                color = InkSoft,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun PeerRow(peer: Peer, enabled: Boolean, isCurrent: Boolean, onTap: () -> Unit) {
    val bg = if (isCurrent) AccentBlue.copy(alpha = 0.12f) else Color(0x08000000)
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onTap),
        shape = RoundedCornerShape(10.dp),
        color = bg,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                peer.info.alias.ifBlank { peer.host },
                color = Color.Black,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "${peer.host}:${peer.port} · ${peer.info.deviceModel ?: peer.info.deviceType.wire}" +
                    if (isCurrent) " · 传输中…" else "",
                color = InkSoft,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun Card(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.White,
        shape = RoundedCornerShape(14.dp),
        shadowElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.Black)
            content()
        }
    }
}

private fun fmtMb(b: Long): String = "%.1f".format(b / 1024.0 / 1024.0)
