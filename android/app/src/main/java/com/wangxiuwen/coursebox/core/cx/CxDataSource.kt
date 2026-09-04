package com.wangxiuwen.coursebox.core.cx

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.File

/**
 * ExoPlayer/Media3 DataSource that reads a slice of a `.cx` archive
 * directly — no extraction, no copy. Pairs with [CxArchive] to look up
 * the entry's byte range, then opens a sliding RandomAccessFile-backed
 * reader.
 *
 * URI scheme:
 *   `cx:///<percent-encoded-cx-path>/<percent-encoded-entry-name>`
 *
 * Example:
 *   cx:///%2Fdata%2Fuser%2F0%2F.../packages%2Fnce1.cx/objects%2Fabc.mp4
 *
 * The double-percent-encode is deliberate so the path separator inside
 * the entry name doesn't get confused with the URI path separator.
 *
 * Factory: see [CxDataSource.Factory] — register with ExoPlayer via
 *   `MediaSource.Factory(DefaultMediaSourceFactory(ctx).setDataSourceFactory(...))`
 * but for our case we just hand the URI to ExoPlayer and provide the
 * factory via the player's setMediaSourceFactory.
 *
 * Why a custom scheme instead of file://offset: ExoPlayer's default
 * FileDataSource opens the path and ignores the DataSpec.position
 * relative to a "virtual" entry — it just seeks N bytes into the file.
 * We need (file = .cx, virtual offset relative to entry data), so a
 * custom URI carrying both is cleaner than abusing fragment fields.
 */
class CxDataSource : BaseDataSource(/* isNetwork = */ false) {

    private var openedReader: CxArchive.Reader? = null
    private var openedUri: Uri? = null
    private var bytesRemaining: Long = 0

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val uri = dataSpec.uri
        val (cxPath, entryName) = parseUri(uri)
        val archive = CxArchive.open(File(cxPath))
        val entry = archive.entryByName(entryName)
            ?: throw java.io.IOException("entry not found in .cx: $entryName")

        val reader = archive.openReader(entry)
        if (dataSpec.position > 0) {
            reader.seek(dataSpec.position)
        }
        openedReader = reader
        openedUri = uri

        val remainingInEntry = entry.size - dataSpec.position
        bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            remainingInEntry
        } else {
            minOf(dataSpec.length, remainingInEntry)
        }
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val reader = openedReader ?: throw java.io.IOException("read before open")
        val toRead = minOf(length.toLong(), bytesRemaining).toInt()
        val n = reader.read(buffer, offset, toRead)
        if (n < 0) return C.RESULT_END_OF_INPUT
        bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    override fun close() {
        try {
            openedReader?.close()
        } finally {
            openedReader = null
            openedUri = null
            bytesRemaining = 0
            transferEnded()
        }
    }

    override fun getUri(): Uri? = openedUri

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = CxDataSource()
    }

    companion object {
        const val SCHEME = "cx"

        /** Build a `cx:///<file>/<entry>` URI for ExoPlayer. */
        fun makeUri(cxFile: File, entryName: String): Uri {
            val encPath = Uri.encode(cxFile.absolutePath, /* allow = */ null)
            val encEntry = Uri.encode(entryName, /* allow = */ null)
            // 3 leading slashes: scheme://authority/path. Authority empty.
            return Uri.parse("$SCHEME:///$encPath/$encEntry")
        }

        fun resolveEntry(uri: Uri): Pair<File, CxArchive.Entry> {
            val (cxPath, entryName) = parseUri(uri)
            val file = File(cxPath)
            val entry = CxArchive.open(file).entryByName(entryName)
                ?: throw java.io.IOException("entry not found in .cx: $entryName")
            return file to entry
        }

        private fun parseUri(uri: Uri): Pair<String, String> {
            require(uri.scheme == SCHEME) { "not a $SCHEME URI: $uri" }
            // After scheme://, path looks like "/<encPath>/<encEntry>"
            // but encPath itself contains percent-encoded slashes — Uri
            // decodes them so we instead split on the LAST slash between
            // the two known-encoded components by using getEncodedPath
            // and decoding each half.
            val raw = uri.encodedPath ?: error("no path in $uri")
            // raw starts with "/", drop it.
            val body = raw.trimStart('/')
            val split = body.indexOf('/')
            require(split > 0) { "malformed $SCHEME URI: $uri" }
            val encPath = body.substring(0, split)
            val encEntry = body.substring(split + 1)
            return Uri.decode(encPath) to Uri.decode(encEntry)
        }
    }
}
