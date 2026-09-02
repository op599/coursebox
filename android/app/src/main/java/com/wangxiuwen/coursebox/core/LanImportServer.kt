package com.wangxiuwen.coursebox.core

import android.content.Context
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import android.graphics.Bitmap
import android.graphics.Color
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val TAG = "LanImportServer"
private const val SERVER_PORT = 38723

/**
 * One-shot HTTP receiver for `.coursebox.zip` files. Hosts a minimal upload
 * page on the phone's LAN IP so any browser on the same Wi-Fi (or another
 * coursebox device) can push a zip in via multipart POST.
 *
 *  - GET /            → upload form (works in any browser)
 *  - POST /upload     → save multipart to cache, hand off to CourseLibrary
 *
 * The endpoint also accepts a LocalSend-style raw octet-stream PUT so a
 * desktop helper can integrate later without a form.
 */
class LanImportServer(
    private val ctx: Context,
    private val library: CourseLibrary,
    private val onProgress: (status: String) -> Unit,
    private val onEvent: (Event) -> Unit = {},
    private val allowLegacyRaw: () -> Boolean = { true },
    private val onShareRequest: ((IncomingShareRequest, (Boolean) -> Unit) -> Unit)? = null,
    private val enableLocalSendReceiver: Boolean = false,
) : NanoHTTPD(SERVER_PORT) {

    data class IncomingFile(val name: String, val size: Long)
    data class IncomingShareRequest(
        val id: String,
        val sender: String,
        val courseCount: Int,
        val files: List<IncomingFile>,
    ) {
        val totalBytes: Long get() = files.sumOf { it.size }
    }

    /**
     * Structured progress event surfaced for UI consumers. The old
     * `onProgress(String)` is still emitted so any caller that only wants
     * one-line status keeps working; new UIs build a per-file list from
     * [Event]s.
     */
    sealed class Event {
        data class Started(val filename: String) : Event()
        data class Done(val filename: String, val message: String) : Event()
        data class Failed(val filename: String, val message: String) : Event()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Asynchronous import state, keyed by sessionId. Browser polls
     * `GET /status?id=...` while we run the long-running import in the
     * background. Kept in memory only — server restarts mean any pending
     * imports were already started in the previous process.
     */
    private data class ImportState(
        val status: String,   // "pending" | "done" | "error"
        val message: String,  // human-readable progress / result / error
    )
    private val imports = java.util.concurrent.ConcurrentHashMap<String, ImportState>()
    private data class ApprovedSession(
        val files: ConcurrentHashMap<String, Long>,
        val expiresAt: Long,
    )
    private val approvedSessions = ConcurrentHashMap<String, ApprovedSession>()
    private val pendingApprovals = ConcurrentHashMap<String, CompletableFuture<Boolean>>()

    /**
     * LocalSend v2 receiver, runs on port 53317 in parallel. We lifecycle it
     * alongside this server so a desktop running LocalSend can push a zip
     * directly without the browser upload page.
     */
    private val localSend by lazy { LocalSendServer(ctx, library, onProgress) }

    override fun start(timeout: Int, daemon: Boolean) {
        // NanoHTTPD's default socket read timeout is 5000 ms — that aborts
        // any single read() that doesn't see new bytes for 5 s, which trips
        // immediately on a 3.7 GB upload over adb-forward / slow Wi-Fi.
        // 0 disables the timeout entirely; we want big uploads to actually
        // finish, and the connection is intra-LAN so an open socket is fine.
        super.start(0, daemon)
        if (enableLocalSendReceiver) {
            runCatching { localSend.start(0, daemon) }
                .onFailure { Log.w(TAG, "localsend start fail", it) }
        }
    }

    override fun serve(session: IHTTPSession): Response = when {
        session.method == Method.POST && session.uri == "/api/coursebox/prepare" -> handlePrepare(session)
        session.method == Method.GET && session.uri == "/" -> page()
        session.method == Method.POST && session.uri == "/upload" -> handleMultipart(session)
        session.method == Method.PUT && session.uri.startsWith("/raw") -> handleRaw(session)
        session.method == Method.GET && session.uri == "/status" -> handleStatus(session)
        session.method == Method.GET && session.uri == "/apk" -> serveApk()
        else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
    }

    private fun handlePrepare(session: IHTTPSession): Response {
        val callback = onShareRequest ?: return newFixedLengthResponse(
            Response.Status.NOT_FOUND, "text/plain", "unsupported",
        )
        return try {
            val body = HashMap<String, String>()
            session.parseBody(body)
            val obj = JSONObject(body["postData"].orEmpty())
            val arr = obj.optJSONArray("files")
                ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "missing files")
            val incoming = buildList {
                for (i in 0 until arr.length()) {
                    val f = arr.getJSONObject(i)
                    val name = f.optString("name").trim()
                    val size = f.optLong("size", -1)
                    if (name.isNotEmpty() && size >= 0) add(IncomingFile(name, size))
                }
            }
            if (incoming.isEmpty()) return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "text/plain", "empty files",
            )
            val request = IncomingShareRequest(
                id = UUID.randomUUID().toString(),
                sender = obj.optString("sender", "附近设备").take(80),
                courseCount = obj.optInt("courseCount", incoming.size).coerceAtLeast(1),
                files = incoming,
            )
            val decision = CompletableFuture<Boolean>()
            pendingApprovals[request.id] = decision
            callback(request) { accepted -> decision.complete(accepted) }
            val accepted = runCatching { decision.get(90, TimeUnit.SECONDS) }.getOrDefault(false)
            pendingApprovals.remove(request.id)
            if (!accepted) return newFixedLengthResponse(
                Response.Status.FORBIDDEN, "application/json", """{"accepted":false}""",
            )
            val token = UUID.randomUUID().toString()
            approvedSessions[token] = ApprovedSession(
                ConcurrentHashMap(incoming.associate { it.name to it.size }),
                System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(30),
            )
            newFixedLengthResponse(
                Response.Status.OK, "application/json",
                """{"accepted":true,"sessionId":"$token"}""",
            )
        } catch (e: Throwable) {
            Log.w(TAG, "prepare fail", e)
            newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", e.message ?: "bad request")
        }
    }

    private fun handleStatus(session: IHTTPSession): Response {
        val id = session.parameters["id"]?.firstOrNull().orEmpty()
        val st = imports[id]
            ?: return newFixedLengthResponse(
                Response.Status.NOT_FOUND, "application/json",
                """{"status":"unknown","message":"未知任务"}""",
            )
        val body = """{"status":"${st.status}","message":${jsonStr(st.message)}}"""
        return newFixedLengthResponse(Response.Status.OK, "application/json", body)
    }

    private fun jsonStr(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '\\', '"' -> { append('\\'); append(c) }
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }

    private fun serveApk(): Response {
        val apk = File(ctx.applicationInfo.sourceDir)
        if (!apk.exists()) {
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND, "text/plain", "apk not found",
            )
        }
        val filename = "coursebox-${com.wangxiuwen.coursebox.BuildConfig.VERSION_NAME}.apk"
        val resp = newFixedLengthResponse(
            Response.Status.OK,
            "application/vnd.android.package-archive",
            apk.inputStream(),
            apk.length(),
        )
        resp.addHeader("Content-Disposition", "attachment; filename=\"$filename\"")
        return resp
    }

    private fun page(): Response {
        val ver = com.wangxiuwen.coursebox.BuildConfig.VERSION_NAME
        val html = """
            <!doctype html>
            <html lang="zh">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
              <title>课程盒子 · 局域网共享</title>
              <style>
                *, *::before, *::after { box-sizing: border-box; }
                html, body { margin: 0; padding: 0; }
                body {
                  font-family: -apple-system, BlinkMacSystemFont, "Helvetica Neue", "PingFang SC", sans-serif;
                  background: #F5F4F1; color: #111;
                  padding: 20px 16px 40px;
                  -webkit-text-size-adjust: 100%;
                }
                .card {
                  max-width: 520px; margin: 0 auto;
                  background: #fff; border-radius: 16px;
                  padding: 22px 20px;
                  box-shadow: 0 1px 3px rgba(0,0,0,0.06);
                }
                .card + .card { margin-top: 16px; }
                h1 { margin: 0 0 6px; font-size: 20px; }
                h2 { margin: 0 0 6px; font-size: 17px; }
                .sub { color: #6B7280; margin: 0 0 16px; font-size: 13px; line-height: 1.5; }
                .drop {
                  display: block; width: 100%;
                  border: 2px dashed #C8C7C0; border-radius: 12px;
                  padding: 24px 16px; text-align: center;
                  color: #666; font-size: 14px;
                  cursor: pointer; transition: all .15s ease;
                  -webkit-tap-highlight-color: transparent;
                }
                .drop:active, .drop.hot { border-color: #007AFF; background: #EAF2FF; color: #007AFF; }
                .btn {
                  display: block; width: 100%;
                  background: #007AFF; color: #fff;
                  border: none; border-radius: 10px;
                  padding: 13px 20px; font-size: 15px; font-weight: 600;
                  cursor: pointer; text-decoration: none; text-align: center;
                  margin-top: 14px;
                  -webkit-tap-highlight-color: transparent;
                }
                .btn:disabled { opacity: .45; cursor: default; }
                .btn.ghost { background: #fff; color: #007AFF; border: 1px solid #007AFF; }
                .status { margin-top: 12px; font-size: 13px; color: #444; min-height: 18px; }
                .progress {
                  margin-top: 10px; height: 6px; width: 100%;
                  background: #ECEBE7; border-radius: 3px; overflow: hidden;
                  display: none;
                }
                .progress.on { display: block; }
                .progress > .bar {
                  height: 100%; width: 0%;
                  background: #007AFF; border-radius: 3px;
                  transition: width .15s linear;
                }
                #file { position: absolute; left: -9999px; opacity: 0; }
                .ver { color: #9CA3AF; font-size: 12px; margin-top: 8px; }
              </style>
            </head>
            <body>
              <div class="card">
                <h1>📦 导入课程</h1>
                <p class="sub">选一个 <code>.cx</code> 课程包上传到手机。</p>
                <input id="file" type="file" multiple accept=".cx">
                <label class="drop" id="drop" for="file">
                  <span id="dropText">点击选择或拖拽 .cx 课程包（可多选）</span>
                </label>
                <button class="btn" id="go" disabled>上传</button>
                <div class="progress" id="progress"><div class="bar" id="bar"></div></div>
                <div class="status" id="status"></div>
                <ul id="results" style="margin:10px 0 0;padding:0;list-style:none;font-size:13px;color:#444;"></ul>
              </div>

              <div class="card">
                <h2>📲 下载本机的 App</h2>
                <p class="sub">没有装课程盒子？点这里下载并安装本机当前版本（v$ver）。</p>
                <a class="btn" href="/apk" download>下载 APK</a>
                <p class="ver">需要打开"未知来源应用"安装权限。</p>
              </div>

              <script>
                (function() {
                  var drop = document.getElementById('drop');
                  var fileEl = document.getElementById('file');
                  var go = document.getElementById('go');
                  var status = document.getElementById('status');
                  var dropText = document.getElementById('dropText');
                  var results = document.getElementById('results');
                  var queue = [];

                  function pickList(files) {
                    if (!files || !files.length) return;
                    queue = Array.prototype.slice.call(files);
                    var totalMB = queue.reduce(function(a, f) { return a + f.size; }, 0) / 1024 / 1024;
                    dropText.textContent = queue.length === 1
                      ? queue[0].name + ' · ' + (queue[0].size / 1024 / 1024).toFixed(1) + ' MB'
                      : queue.length + ' 个文件 · 共 ' + totalMB.toFixed(1) + ' MB';
                    go.disabled = false;
                  }

                  fileEl.addEventListener('change', function(e) { pickList(e.target.files); });
                  ['dragenter','dragover'].forEach(function(t) {
                    drop.addEventListener(t, function(e) { e.preventDefault(); drop.classList.add('hot'); });
                  });
                  ['dragleave','drop'].forEach(function(t) {
                    drop.addEventListener(t, function(e) { e.preventDefault(); drop.classList.remove('hot'); });
                  });
                  drop.addEventListener('drop', function(e) {
                    e.preventDefault();
                    drop.classList.remove('hot');
                    if (e.dataTransfer && e.dataTransfer.files.length) pickList(e.dataTransfer.files);
                  });

                  var progress = document.getElementById('progress');
                  var bar = document.getElementById('bar');
                  function fmtMB(b) { return (b / 1024 / 1024).toFixed(1) + ' MB'; }
                  function addResult(text, ok) {
                    var li = document.createElement('li');
                    li.style.padding = '6px 0';
                    li.style.borderTop = '1px solid #ECEBE7';
                    li.style.color = ok ? '#0a7' : '#c33';
                    li.textContent = (ok ? '✓ ' : '✗ ') + text;
                    results.appendChild(li);
                  }

                  function uploadOne(file, onDone) {
                    bar.style.width = '0%';
                    var label = file.name + ' · ' + fmtMB(file.size);
                    status.textContent = label + ' · 上传中… 0%';
                    var xhr = new XMLHttpRequest();
                    var startedAt = Date.now();
                    xhr.upload.addEventListener('progress', function(e) {
                      if (!e.lengthComputable) return;
                      var pct = (e.loaded / e.total) * 100;
                      bar.style.width = pct.toFixed(1) + '%';
                      var elapsed = (Date.now() - startedAt) / 1000;
                      var speed = elapsed > 0.5 ? ' · ' + fmtMB(e.loaded / elapsed) + '/s' : '';
                      status.textContent = label + ' · ' + pct.toFixed(0) + '% (' +
                        fmtMB(e.loaded) + ' / ' + fmtMB(e.total) + ')' + speed;
                    });
                    xhr.upload.addEventListener('load', function() {
                      bar.style.width = '100%';
                      status.textContent = label + ' · 上传完成，等待导入…';
                    });
                    xhr.onerror = function() {
                      addResult(file.name + '：上传失败（网络错误）', false);
                      onDone();
                    };
                    xhr.onload = function() {
                      if (xhr.status < 200 || xhr.status >= 300) {
                        addResult(file.name + '：上传失败 ' + (xhr.responseText || ('HTTP ' + xhr.status)), false);
                        onDone();
                        return;
                      }
                      var resp; try { resp = JSON.parse(xhr.responseText); } catch (_) { resp = {}; }
                      if (!resp.id) {
                        addResult(file.name + '：未拿到任务 ID', false);
                        onDone();
                        return;
                      }
                      var importStart = Date.now();
                      function poll() {
                        var px = new XMLHttpRequest();
                        px.onerror = function() { setTimeout(poll, 2000); };
                        px.onload = function() {
                          var s; try { s = JSON.parse(px.responseText); } catch (_) { s = {}; }
                          var elapsed = Math.round((Date.now() - importStart) / 1000);
                          if (s.status === 'done') {
                            addResult(file.name + '：' + (s.message || '已导入'), true);
                            onDone();
                          } else if (s.status === 'error') {
                            addResult(file.name + '：' + (s.message || '导入失败'), false);
                            onDone();
                          } else {
                            status.textContent = label + ' · 导入中… 已用 ' + elapsed + 's · ' +
                              (s.message || '解析中');
                            setTimeout(poll, 1500);
                          }
                        };
                        px.open('GET', '/status?id=' + encodeURIComponent(resp.id));
                        px.send();
                      }
                      poll();
                    };
                    xhr.open('PUT', '/raw?name=' + encodeURIComponent(file.name));
                    xhr.setRequestHeader('Content-Type', 'application/octet-stream');
                    xhr.send(file);
                  }

                  go.addEventListener('click', function() {
                    if (!queue.length) return;
                    go.disabled = true;
                    progress.classList.add('on');
                    var total = queue.length;
                    var pending = queue.slice();
                    function next() {
                      if (!pending.length) {
                        status.textContent = '全部完成（' + total + ' 个）';
                        setTimeout(function() { progress.classList.remove('on'); }, 800);
                        queue = [];
                        dropText.textContent = '点击选择或拖拽 .cx 课程包（可多选）';
                        // Reset the file input + button so the user can pick
                        // another batch without reloading the page.
                        fileEl.value = '';
                        go.disabled = true;
                        return;
                      }
                      var f = pending.shift();
                      var idx = total - pending.length;
                      status.textContent = '[' + idx + '/' + total + '] ' + f.name + ' · 准备中…';
                      uploadOne(f, next);
                    }
                    next();
                  });
                })();
              </script>
            </body>
            </html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun handleMultipart(session: IHTTPSession): Response {
        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val tmpPath = files["file"] ?: return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "text/plain", "missing file part",
            )
            val originalName = session.parameters["file"]?.firstOrNull() ?: "import.zip"
            val id = beginImport(File(tmpPath), originalName)
            newFixedLengthResponse(Response.Status.OK, "application/json", """{"id":"$id"}""")
        } catch (e: Throwable) {
            Log.w(TAG, "multipart fail", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.message ?: "error")
        }
    }

    private fun handleRaw(session: IHTTPSession): Response {
        return try {
            val name = session.parameters["name"]?.firstOrNull()
                ?: "lan-import-${System.currentTimeMillis()}.zip"
            // NanoHTTPD's `inputStream` is the raw socket stream — it is NOT
            // capped at Content-Length, so `copyTo` would read forever and
            // hang the worker. We must read exactly Content-Length bytes.
            val total = session.headers["content-length"]?.toLongOrNull()
                ?: return newFixedLengthResponse(
                    Response.Status.LENGTH_REQUIRED, "text/plain",
                    "missing Content-Length",
                )
            val shareToken = session.parameters["sessionId"]?.firstOrNull()
            val approved = shareToken?.let { token ->
                val entry = approvedSessions[token]
                if (entry == null || entry.expiresAt < System.currentTimeMillis()) {
                    approvedSessions.remove(token)
                    false
                } else entry.files[name] == total
            } == true
            if (!approved && !allowLegacyRaw()) {
                return newFixedLengthResponse(
                    Response.Status.FORBIDDEN, "text/plain", "请先在接收端确认",
                )
            }
            // UUID instead of currentTimeMillis: two browser PUTs landing in
            // the same millisecond would collide on the timestamp filename,
            // and the second import would race on a half-written zip.
            val out = File(
                ctx.cacheDir,
                "lan-import-${java.util.UUID.randomUUID().toString().take(12)}.zip",
            )
            val buf = ByteArray(64 * 1024)
            var remaining = total
            val input = session.inputStream
            out.outputStream().use { sink ->
                while (remaining > 0) {
                    val toRead = if (remaining < buf.size) remaining.toInt() else buf.size
                    val n = input.read(buf, 0, toRead)
                    if (n < 0) break
                    sink.write(buf, 0, n)
                    remaining -= n
                }
            }
            if (remaining > 0) {
                Log.w(TAG, "raw short read: $remaining bytes missing of $total")
            }
            val id = beginImport(out, name)
            if (approved) {
                val token = requireNotNull(shareToken)
                approvedSessions[token]?.files?.remove(name)
                if (approvedSessions[token]?.files?.isEmpty() == true) approvedSessions.remove(token)
            }
            newFixedLengthResponse(Response.Status.OK, "application/json", """{"id":"$id"}""")
        } catch (e: Throwable) {
            Log.w(TAG, "raw fail", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.message ?: "error")
        }
    }

    /**
     * Start an import in the background and return a session id. The HTTP
     * response goes out immediately (so the browser doesn't time out while
     * we're parsing a multi-GB zip); the page then polls GET /status?id=...
     * to learn when the import finishes (or fails).
     */
    private fun beginImport(src: File, label: String): String {
        val id = java.util.UUID.randomUUID().toString().take(12)
        imports[id] = ImportState("pending", "已接收 $label，正在导入到课程库…")
        onProgress("收到 $label，正在导入…")
        onEvent(Event.Started(label))
        scope.launch {
            try {
                val res = library.importLocalFile(src, sourceLabel = "lan://$label")
                val titles = res.packages.joinToString { it.title }
                val msg = "已导入：$titles（${res.addedObjects} 个对象）"
                imports[id] = ImportState("done", msg)
                onProgress("✓ $msg")
                onEvent(Event.Done(label, msg))
            } catch (e: Throwable) {
                Log.w(TAG, "ingest fail", e)
                imports[id] = ImportState("error", e.message ?: "未知错误")
                onProgress("导入失败：${e.message}")
                onEvent(Event.Failed(label, e.message ?: "未知错误"))
            } finally {
                runCatching { src.delete() }
            }
        }
        return id
    }

    override fun stop() {
        pendingApprovals.values.forEach { it.complete(false) }
        pendingApprovals.clear()
        approvedSessions.clear()
        super.stop()
        if (enableLocalSendReceiver) runCatching { localSend.stop() }
        runCatching { scope.coroutineContext.cancel() }
    }

    companion object {
        /** First non-loopback IPv4 of any "up" interface — typically wlan0. */
        fun localIpv4(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                ?.hostAddress
        }.getOrNull()

        fun url(): String? = localIpv4()?.let { "http://$it:$SERVER_PORT" }

        /** Render a `text` payload (the upload URL) as a square QR Bitmap. */
        fun qrBitmap(text: String, sizePx: Int = 512): Bitmap {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1,
            )
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val w = matrix.width
            val h = matrix.height
            val pixels = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                pixels[y * w + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
            return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, w, 0, 0, w, h)
            }
        }
    }
}

// Bridges runCatching cancel call above to the Job underlying the scope.
private fun kotlin.coroutines.CoroutineContext.cancel() {
    this[kotlinx.coroutines.Job]?.cancel()
}
