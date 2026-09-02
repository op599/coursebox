package com.wangxiuwen.coursebox.core

import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.HttpURLConnection
import java.net.URL

class AppShareServerTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun servesDownloadPageAndCompleteApk() {
        val apkBytes = byteArrayOf(0x50, 0x4b, 0x03, 0x04) + ByteArray(128 * 1024) { (it % 251).toByte() }
        val apk = temp.newFile("base.apk").apply { writeBytes(apkBytes) }
        val server = AppShareServer.forTest(apk, "9.8.7")
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        try {
            val base = "http://127.0.0.1:${AppShareServer.PORT}"
            val page = URL(base + server.pagePath).readText()
            assertTrue(page.contains("v9.8.7"))
            assertTrue(page.contains(server.downloadPath))

            val connection = URL(base + server.downloadPath).openConnection() as HttpURLConnection
            assertEquals(200, connection.responseCode)
            assertEquals(AppShareServer.APK_MIME, connection.contentType)
            assertTrue(connection.inputStream.use { it.readBytes() }.contentEquals(apkBytes))
            connection.disconnect()
        } finally {
            server.stop()
        }
    }
}
