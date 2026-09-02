package com.wangxiuwen.coursebox.core

import android.content.Context
import com.wangxiuwen.coursebox.BuildConfig
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

/**
 * Short-lived HTTP server that exposes the APK currently installed on this
 * device. The server only lives while AppShareScreen is visible and requires
 * an unguessable token in the URL.
 */
class AppShareServer private constructor(
    private val apk: File,
    private val versionName: String,
) : NanoHTTPD(PORT) {
    constructor(ctx: Context) : this(File(ctx.applicationInfo.sourceDir), BuildConfig.VERSION_NAME)

    private val token = randomToken()

    val pagePath: String = "/$token"
    val downloadPath: String = "/$token/coursebox.apk"

    override fun serve(session: IHTTPSession): Response = when {
        session.method == Method.GET && session.uri == pagePath -> page()
        (session.method == Method.GET || session.method == Method.HEAD) &&
            session.uri == downloadPath -> download(session.method == Method.HEAD)
        else -> newFixedLengthResponse(
            Response.Status.NOT_FOUND,
            MIME_PLAINTEXT,
            "Not found",
        )
    }

    private fun page(): Response {
        val name = apkName()
        val html = """
            <!doctype html><html lang="zh-CN"><head>
            <meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>下载课程盒子</title>
            <style>
            body{font-family:system-ui,-apple-system,sans-serif;background:#f5f4f1;color:#111;margin:0;padding:28px}
            main{max-width:520px;margin:10vh auto;background:white;border-radius:20px;padding:28px;box-shadow:0 2px 18px #0001}
            h1{margin:0 0 10px}p{color:#666;line-height:1.6}.button{display:block;text-align:center;background:#1769e0;color:white;text-decoration:none;padding:15px;border-radius:12px;font-weight:700;margin:24px 0 12px}
            small{color:#777}
            </style></head><body><main>
            <h1>课程盒子</h1>
            <p>来自附近设备的离线分享。版本 v$versionName。</p>
            <a class="button" href="$downloadPath" download="$name">下载 Android APK</a>
            <small>下载完成后打开 APK 安装；系统可能要求允许浏览器安装未知应用。</small>
            </main></body></html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html).apply {
            addHeader("Cache-Control", "no-store")
        }
    }

    private fun download(headOnly: Boolean): Response {
        if (!apk.isFile) {
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                "APK unavailable",
            )
        }
        val response = if (headOnly) {
            newFixedLengthResponse(
                Response.Status.OK,
                APK_MIME,
                "",
            )
        } else {
            newFixedLengthResponse(
                Response.Status.OK,
                APK_MIME,
                apk.inputStream(),
                apk.length(),
            )
        }
        response.addHeader("Content-Disposition", "attachment; filename=\"${apkName()}\"")
        response.addHeader("Cache-Control", "no-store")
        if (headOnly) response.addHeader("Content-Length", apk.length().toString())
        return response
    }

    private fun apkName() = "coursebox-android-v$versionName.apk"

    companion object {
        const val PORT = 38724
        const val APK_MIME = "application/vnd.android.package-archive"

        internal fun forTest(apk: File, versionName: String) = AppShareServer(apk, versionName)

        /** Prefer Wi-Fi/hotspot interfaces over mobile-data interfaces. */
        fun localIpv4(): String? = runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
            val candidates = interfaces.flatMap { nic ->
                nic.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                    .map { nic.name.lowercase() to it }
            }
            candidates
                .sortedBy { (name, _) ->
                    if (name.startsWith("wlan") || name.startsWith("ap") ||
                        name.startsWith("swlan") || name.startsWith("eth")) 0 else 1
                }
                .firstOrNull { (name, address) ->
                    name.startsWith("wlan") || name.startsWith("ap") ||
                        name.startsWith("swlan") || name.startsWith("eth") ||
                        address.isSiteLocalAddress
                }
                ?.second?.hostAddress
        }.getOrNull()

        private fun randomToken(): String {
            val bytes = ByteArray(12).also { SecureRandom().nextBytes(it) }
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
