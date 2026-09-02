package com.wangxiuwen.coursebox.core.lan

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "CourseShareClient"

/**
 * Sender side for Coursebox-to-Coursebox transfer. It first asks the peer
 * for approval, then streams every selected package with the one-time
 * session token returned by the receiver.
 *
 * Discovery (LocalSend mDNS + UDP multicast) still announces the peer so
 * the sender can pick from a list instead of typing an IP; only the
 * transfer is bound to Wi-Fi so an active VPN cannot swallow LAN traffic.
 */
object CourseShareClient {
    /** Receiver's LanImportServer port. Hard-coded to match LanImportServer.SERVER_PORT. */
    const val LAN_PORT = 38723

    sealed interface Result {
        data class Ok(val sentFiles: Int) : Result
        data class Rejected(val httpCode: Int, val message: String) : Result
        data class IoError(val cause: Throwable) : Result
    }

    /**
     * Ping the peer's LanImportServer root path to confirm it's listening.
     * The HTML upload page sits at `GET /`; a 200 means we can push.
     */
    suspend fun probe(ctx: Context, host: String, port: Int = LAN_PORT): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val c = openWifiConnection(ctx, "http://$host:$port/").apply {
                requestMethod = "GET"
                connectTimeout = 3000
                readTimeout = 3000
            }
            try { c.responseCode in 200..299 } finally { runCatching { c.disconnect() } }
        }.onFailure { Log.w(TAG, "probe $host:$port: ${it.message}") }.getOrDefault(false)
    }

    /**
     * Send each [FileSpec] sequentially. Each PUT corresponds to one
     * lan-import session on the receiver — for a multi-part .cx the
     * receiver matches partN filenames against the package's
     * multipart_parts list and merges them under the same course id.
     */
    suspend fun sendFiles(
        ctx: Context,
        host: String,
        port: Int,
        files: List<FileSpec>,
        courseCount: Int = files.size,
        onProgress: (fileId: String, sent: Long, total: Long) -> Unit = { _, _, _ -> },
    ): Result = withContext(Dispatchers.IO) {
        if (files.isEmpty()) return@withContext Result.Ok(0)
        val prepared = prepare(ctx, host, port, files, courseCount)
        if (prepared is PrepareResult.Rejected) {
            return@withContext Result.Rejected(prepared.code, prepared.message)
        }
        if (prepared is PrepareResult.Failed) {
            return@withContext Result.IoError(prepared.cause)
        }
        val sessionId = (prepared as? PrepareResult.Accepted)?.sessionId
        var sent = 0
        for (f in files) {
            val tokenPart = sessionId?.let { "&sessionId=${URLEncoder.encode(it, "UTF-8")}" }.orEmpty()
            val url = "http://$host:$port/raw?name=${URLEncoder.encode(f.fileName, "UTF-8")}$tokenPart"
            val err = postBytes(ctx, url, f) { bytes -> onProgress(f.id, bytes, f.size) }
            if (err != null) return@withContext Result.IoError(err)
            sent += 1
        }
        Result.Ok(sent)
    }

    private sealed interface PrepareResult {
        data class Accepted(val sessionId: String) : PrepareResult
        data object Legacy : PrepareResult
        data class Rejected(val code: Int, val message: String) : PrepareResult
        data class Failed(val cause: Throwable) : PrepareResult
    }

    private fun prepare(
        ctx: Context,
        host: String,
        port: Int,
        files: List<FileSpec>,
        courseCount: Int,
    ): PrepareResult {
        val payload = JSONObject().apply {
            put("sender", "课程盒子 · ${Build.MODEL ?: "Android"}")
            put("courseCount", courseCount)
            put("files", JSONArray().apply {
                files.forEach { put(JSONObject().put("name", it.fileName).put("size", it.size)) }
            })
        }.toString().toByteArray(Charsets.UTF_8)
        return try {
            val c = openWifiConnection(ctx, "http://$host:$port/api/coursebox/prepare").apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 5000
                readTimeout = 95_000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setFixedLengthStreamingMode(payload.size)
            }
            try {
                c.outputStream.use { it.write(payload) }
                val code = c.responseCode
                if (code == 404) PrepareResult.Legacy
                else if (code in 200..299) {
                    val obj = JSONObject(c.inputStream.bufferedReader().readText())
                    val token = obj.optString("sessionId")
                    if (token.isEmpty()) PrepareResult.Rejected(code, "接收端未返回传输凭证")
                    else PrepareResult.Accepted(token)
                } else {
                    val msg = c.errorStream?.bufferedReader()?.readText()?.take(200)
                        ?: if (code == 403) "对方拒绝接收" else "HTTP $code"
                    PrepareResult.Rejected(code, msg)
                }
            } finally { c.disconnect() }
        } catch (e: Throwable) { PrepareResult.Failed(e) }
    }

    /**
     * One file in a send batch. The File must remain readable for the
     * duration of the send (we keep it as a path so we can stream from
     * disk without buffering everything in memory).
     */
    data class FileSpec(
        val id: String = UUID.randomUUID().toString(),
        val source: File,
        val fileName: String = source.name,
        val size: Long = source.length(),
    )

    private fun postBytes(
        ctx: Context,
        url: String,
        spec: FileSpec,
        onSent: (Long) -> Unit,
    ): Throwable? = runCatching {
        val c = openWifiConnection(ctx, url).apply {
            requestMethod = "PUT"
            doOutput = true
            connectTimeout = 5000
            // No read timeout — multi-GB transfers over Wi-Fi can take a while.
            readTimeout = 0
            setRequestProperty("Content-Type", "application/octet-stream")
            setRequestProperty("User-Agent", "coursebox-share")
        }
        try {
            setFixedLengthStreamingMode(c, spec.size)
            val buf = ByteArray(64 * 1024)
            var written = 0L
            spec.source.inputStream().use { input ->
                c.outputStream.use { sink ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        sink.write(buf, 0, n)
                        written += n
                        onSent(written)
                    }
                }
            }
            if (c.responseCode !in 200..299) {
                error("HTTP ${c.responseCode}: ${c.errorStream?.bufferedReader()?.readText()?.take(200)}")
            }
        } finally {
            runCatching { c.disconnect() }
        }
    }.exceptionOrNull()

    /**
     * A VPN can advertise itself as the default network while still allowing
     * UDP discovery on wlan0. Bind transfers to the real Wi-Fi network so the
     * peer does not become "visible but unreachable" on Clash and similar VPNs.
     */
    private fun openWifiConnection(ctx: Context, url: String): HttpURLConnection {
        val manager = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifi = manager.allNetworks.firstOrNull { network ->
            val caps = manager.getNetworkCapabilities(network) ?: return@firstOrNull false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        return ((wifi?.openConnection(URL(url)) ?: URL(url).openConnection()) as HttpURLConnection)
    }

    private fun setFixedLengthStreamingMode(c: HttpURLConnection, len: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            c.setFixedLengthStreamingMode(len)
        } else {
            c.setFixedLengthStreamingMode(len.toInt())
        }
    }

}
