package com.wangxiuwen.coursebox.ui.library

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.wangxiuwen.coursebox.core.CourseLibrary
import com.wangxiuwen.coursebox.core.LanImportServer
import com.wangxiuwen.coursebox.core.lan.CourseShareClient
import com.wangxiuwen.coursebox.core.lan.DeviceType
import com.wangxiuwen.coursebox.core.lan.InfoDto
import com.wangxiuwen.coursebox.core.lan.LocalSendDiscovery
import com.wangxiuwen.coursebox.core.lan.courseboxFingerprint
import fi.iki.elonen.NanoHTTPD
import java.util.concurrent.atomic.AtomicBoolean

/** App 前台期间唯一的课程接收入口。 */
class NearbyReceiveHost(
    context: Context,
    private val library: CourseLibrary,
) {
    data class FileRow(val name: String, val state: String, val message: String)

    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val manualImportOpen = AtomicBoolean(false)
    private var server: LanImportServer? = null
    private var discovery: LocalSendDiscovery? = null
    private var responder: ((Boolean) -> Unit)? = null

    val status = mutableStateOf("接收服务未启动")
    val url = mutableStateOf<String?>(null)
    val qr = mutableStateOf<android.graphics.Bitmap?>(null)
    val rows = mutableStateListOf<FileRow>()
    val pending = mutableStateOf<LanImportServer.IncomingShareRequest?>(null)

    @Synchronized
    fun start() {
        if (server != null) return
        val s = LanImportServer(
            ctx = ctx,
            library = library,
            onProgress = { post { status.value = it } },
            onEvent = { event ->
                post {
                    when (event) {
                        is LanImportServer.Event.Started -> {
                            rows.removeAll { it.name == event.filename }
                            rows.add(0, FileRow(event.filename, "pending", "接收完成，正在导入…"))
                        }
                        is LanImportServer.Event.Done -> updateRow(event.filename, "done", event.message)
                        is LanImportServer.Event.Failed -> updateRow(event.filename, "error", event.message)
                    }
                }
            },
            allowLegacyRaw = { manualImportOpen.get() },
            onShareRequest = { request, reply ->
                post {
                    if (pending.value != null) {
                        reply(false)
                    } else {
                        responder = reply
                        pending.value = request
                    }
                }
            },
        )
        try {
            s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = s
            val u = LanImportServer.url()
            url.value = u
            qr.value = u?.let { LanImportServer.qrBitmap(it, 720) }
            status.value = if (u == null) "未检测到局域网" else "已开启附近接收"
        } catch (e: Throwable) {
            status.value = "接收服务启动失败：${e.message}"
            runCatching { s.stop() }
            return
        }

        val info = InfoDto(
            alias = "课程盒子 · ${Build.MODEL ?: "Android"}",
            deviceModel = Build.MODEL,
            deviceType = DeviceType.Mobile,
            fingerprint = courseboxFingerprint(ctx),
            port = CourseShareClient.LAN_PORT,
            protocol = "http",
            download = false,
        )
        discovery = LocalSendDiscovery(ctx, { info }) { _, _, _ -> }.also {
            runCatching { it.start() }
        }
    }

    @Synchronized
    fun stop() {
        responder?.invoke(false)
        responder = null
        pending.value = null
        runCatching { discovery?.stop() }
        runCatching { server?.stop() }
        discovery = null
        server = null
        status.value = "接收服务未启动"
    }

    fun setManualImportOpen(open: Boolean) {
        manualImportOpen.set(open)
    }

    fun respond(requestId: String, accepted: Boolean) {
        if (pending.value?.id != requestId) return
        val callback = responder
        responder = null
        pending.value = null
        callback?.invoke(accepted)
        status.value = if (accepted) "已同意，等待对方传输…" else "已拒绝本次传输"
    }

    private fun updateRow(name: String, state: String, message: String) {
        val index = rows.indexOfFirst { it.name == name }
        if (index >= 0) rows[index] = FileRow(name, state, message)
        else rows.add(0, FileRow(name, state, message))
    }

    private fun post(block: () -> Unit) {
        main.post(block)
    }
}
