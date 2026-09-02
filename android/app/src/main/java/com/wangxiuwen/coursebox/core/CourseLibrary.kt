package com.wangxiuwen.coursebox.core

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

class ImportResult(
    val packages: List<CoursePackageRecord>,
    val addedObjects: Int,
)

/**
 * App-wide course library. Holds imported packages, materialises objects
 * to a content-addressed store under `filesDir/coursebox_library/`, and
 * persists state to `library_index.json`.
 *
 * Drives Compose recomposition through [stateFlow] (a [mutableStateOf]
 * because that's the lightest way to bridge to Compose without ViewModel
 * indirection — there's only ever one CourseLibrary per process).
 */
class CourseLibrary private constructor(
    private val root: File,
    initial: CourseLibraryState,
) {
    val stateFlow = mutableStateOf(initial)
    val state: CourseLibraryState get() = stateFlow.value

    /**
     * Serialises every state-mutating import so two LAN uploads that land
     * back-to-back can't race on the read-modify-write of `state.packages`.
     * Without this, a concurrent pair would each read the same packages
     * snapshot, build their newRecords independently, and the second
     * `stateFlow.value = ...` would clobber the first — making it look like
     * only one of N batch uploads "took".
     */
    private val importMutex = Mutex()

    val objectsDir: File get() = File(root, "objects")
    val packagesDir: File get() = File(root, "packages")

    private val indexFile: File get() = File(root, INDEX_NAME)

    fun packageById(id: String): CoursePackageRecord? =
        state.packages.firstOrNull { it.id == id }

    fun packagesOfType(types: Collection<String>): List<CoursePackageRecord> {
        if (types.isEmpty()) return state.packages
        val set = types.toSet()
        return state.packages.filter { it.type in set }
    }

    /**
     * Every resource_index entry is now a `cx://` URI pointing into a .cx
     * archive. The lookup just confirms the owning package's backing
     * file(s) still exist — no filesystem-path branch, no legacy extracted
     * objects/ to walk.
     */
    fun resolveHash(hash: String): String? {
        val norm = if (hash.startsWith("sha256:")) hash else "sha256:$hash"
        for (pkg in state.packages) {
            val path = pkg.resourceIndex[norm] ?: continue
            if (pkg.cxPaths.any { File(it).exists() }) return path
        }
        return null
    }

    fun resolveLogicalPath(logical: String): String? {
        val norm = normalizeLogicalPath(logical)
        for (pkg in state.packages) {
            val path = pkg.logicalPathIndex[norm] ?: continue
            if (pkg.cxPaths.any { File(it).exists() }) return path
        }
        return null
    }

    fun resolve(hash: String? = null, logicalPath: String? = null): String? {
        if (!hash.isNullOrBlank()) resolveHash(hash)?.let { return it }
        if (!logicalPath.isNullOrBlank()) return resolveLogicalPath(logicalPath)
        return null
    }

    suspend fun loadLessons(courseId: String): List<Map<String, kotlinx.serialization.json.JsonElement>> =
        withContext(Dispatchers.IO) {
            val pkg = packageById(courseId) ?: return@withContext emptyList()
            val file = File(pkg.lessonsManifestPath)
            if (!file.exists()) return@withContext emptyList()
            runCatching {
                val arr = json.parseToJsonElement(file.readText())
                (arr as? kotlinx.serialization.json.JsonArray)?.map {
                    (it as kotlinx.serialization.json.JsonObject).toMap()
                } ?: emptyList()
            }.getOrDefault(emptyList())
        }

    suspend fun toggleLearning(courseId: String) {
        val current = state.learning.toMutableSet()
        if (!current.add(courseId)) current.remove(courseId)
        stateFlow.value = state.copy(learning = current)
        persist()
    }

    fun isLearning(courseId: String): Boolean = courseId in state.learning

    /**
     * Drop a package from the library and delete the .cx files / staged
     * lessons.json that backed it. Bytes on disk go away; the library
     * state's resource_index entries for that id disappear automatically
     * because the record is removed.
     */
    suspend fun removePackage(courseId: String) {
        val pkg = state.packages.firstOrNull { it.id == courseId } ?: return
        // Forget pinned / learning state for this id too.
        stateFlow.value = state.copy(
            packages = state.packages.filter { it.id != courseId },
            pinned = state.pinned.filter { it != courseId },
            learning = state.learning - courseId,
        )
        persist()
        withContext(Dispatchers.IO) {
            pkg.cxPaths.forEach { runCatching { File(it).delete() } }
            runCatching { File(pkg.lessonsManifestPath).delete() }
        }
    }

    suspend fun togglePinned(courseId: String) {
        val current = state.pinned.toMutableList()
        if (current.remove(courseId)) {
            stateFlow.value = state.copy(pinned = current)
        } else {
            current.add(0, courseId)
            stateFlow.value = state.copy(pinned = current)
        }
        persist()
    }

    fun isPinned(courseId: String): Boolean = courseId in state.pinned

    /** Persist a user-defined card order. Pinned courses remain grouped at
     *  the front, but can also be reordered relative to other pinned cards. */
    suspend fun movePackage(movingId: String, targetId: String) {
        if (movingId == targetId) return
        val current = state.packages.toMutableList()
        val from = current.indexOfFirst { it.id == movingId }
        val target = current.indexOfFirst { it.id == targetId }
        if (from < 0 || target < 0) return
        val moving = current.removeAt(from)
        current.add(target.coerceAtMost(current.size), moving)

        val pinned = state.pinned.toMutableList()
        if (movingId in pinned && targetId in pinned) {
            val pinnedTarget = pinned.indexOf(targetId)
            pinned.remove(movingId)
            pinned.add(pinnedTarget.coerceAtMost(pinned.size), movingId)
        }
        stateFlow.value = state.copy(packages = current, pinned = pinned)
        persist()
    }

    /** Read a logical-path resource as text. Returns null if not imported. */
    suspend fun loadString(logicalPath: String): String? = withContext(Dispatchers.IO) {
        val p = resolveLogicalPath(logicalPath) ?: return@withContext null
        runCatching { File(p).readText() }.getOrNull()
    }

    suspend fun importZip(ctx: Context, uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        // Stream the SAF input to a temp file we can ZipFile-seek on.
        val tmp = File.createTempFile("import-", ".zip", ctx.cacheDir)
        try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            } ?: error("无法打开 zip: $uri")
            importLocalFile(tmp, sourceLabel = uri.toString())
        } finally {
            tmp.delete()
        }
    }

    suspend fun importLocalFile(zip: File, sourceLabel: String = zip.absolutePath): ImportResult =
        withContext(Dispatchers.IO) { importMutex.withLock { importLocked(zip, sourceLabel) } }

    /**
     * No-extract import: copy the .cx (any zip with STORED entries works,
     * regardless of the extension) under the library root, then index
     * the central directory so every object's byte offset is known.
     * Playback uses [com.wangxiuwen.coursebox.core.cx.CxDataSource] to
     * read slices directly from the .cx file — never expands objects to
     * disk, so a 3.7 GB pack takes ~3.7 GB instead of ~7.4 GB.
     */
    private suspend fun importLocked(zip: File, sourceLabel: String): ImportResult {
        require(zip.exists()) { "课程包不存在: $zip" }

        // 1. Read manifest.json if present. Single .cx and .cx.part0 both
        //    have one; .cx.part1..N do not. Absence means "this is a
        //    continuation part; attach it to whichever course expected it".
        val manifestStr = ZipFile(zip).use { zf ->
            zf.getEntry("manifest.json")
                ?.let { me -> zf.getInputStream(me).use { it.readBytes().toString(Charsets.UTF_8) } }
        }
        if (manifestStr == null) {
            return importContinuationPart(zip, sourceLabel)
        }

        val manifest: CoursePackageManifest = json.decodeFromString(manifestStr)
        require(manifest.format == "parrot-course-package" && manifest.version == 1) {
            "课程包格式不兼容: ${manifest.format}@v${manifest.version}"
        }

        packagesDir.mkdirs()

        // 2. Move the .cx into the library so its byte ranges stay valid
        //    across app restarts and the temp file from LAN-import can be
        //    deleted by the caller. Filename keyed by the manifest digest
        //    so the same content lands in one file.
        val cxDigest = sha256(manifestStr.toByteArray())
        val cxFile = File(packagesDir, "cx_$cxDigest.cx")
        if (!cxFile.exists() || cxFile.length() != zip.length()) {
            zip.copyTo(cxFile, overwrite = true)
        }

        // 3. Index every zip entry's data offset + size in THIS part. For
        //    a single-part .cx this covers every resource; for .cx.part0
        //    this covers only the subset that part0 carries — the rest of
        //    the resources get picked up as later partN imports arrive.
        val archive = com.wangxiuwen.coursebox.core.cx.CxArchive.open(cxFile)
        val resourcesInThisPart = manifest.resources.filter { archive.entryByName(it.path) != null }
        val resourceIndex = buildCxResourceIndex(archive, cxFile, resourcesInThisPart)
        val logicalIndex = buildCxLogicalIndex(archive, cxFile, resourcesInThisPart)

        val lessonsPath = stageLessonsJson(archive, cxFile, manifest.courses.first().lessonsManifest)

        val now = Instant.now().toString()
        val incomingIds = manifest.courses.map { it.id }.toSet()
        val replacedPackages = state.packages.filter { it.id !in incomingIds }
        val newRecords = manifest.courses.map { c ->
            val perCourseLessonsPath = if (c.lessonsManifest == manifest.courses.first().lessonsManifest)
                lessonsPath else stageLessonsJson(archive, cxFile, c.lessonsManifest)
            CoursePackageRecord(
                id = c.id,
                title = c.title,
                description = c.description,
                type = c.type,
                metadata = c.metadata,
                lessonsManifestPath = perCourseLessonsPath,
                lessonIndex = c.lessonIndex,
                resourceIndex = resourceIndex,
                logicalPathIndex = logicalIndex,
                importedAt = now,
                source = "cx:$sourceLabel",
                cxPaths = listOf(cxFile.absolutePath),
                multipartParts = manifest.multipartParts,
            )
        }
        stateFlow.value = state.copy(packages = replacedPackages + newRecords)

        File(packagesDir, "manifest_$cxDigest.json").writeText(manifestStr)
        persist()

        return ImportResult(newRecords, resourceIndex.size)
    }

    /**
     * Handle a .cx.partN file (N > 0) that has no manifest of its own. We
     * pick the owning package by matching the part's filename against an
     * existing package's [CoursePackageRecord.multipartParts]; then index
     * every `objects/...` entry in the part and merge those into the
     * owning package's resource_index.
     */
    private suspend fun importContinuationPart(zip: File, sourceLabel: String): ImportResult {
        val filename = sourceLabel
            .substringAfterLast('/')
            .substringAfterLast(':')
        val owning = state.packages.firstOrNull { filename in it.multipartParts }
            ?: error("课程包 $filename 找不到对应主包 (先导入 .cx.part0)")

        packagesDir.mkdirs()
        val partFile = File(packagesDir, "cx_${owning.id}_$filename")
        if (!partFile.exists() || partFile.length() != zip.length()) {
            zip.copyTo(partFile, overwrite = true)
        }

        val archive = com.wangxiuwen.coursebox.core.cx.CxArchive.open(partFile)
        val newEntries = archive.allEntries()
            .filterKeys { it.startsWith("objects/") }
            // The on-disk entry name is `objects/<sha>.<ext>`. The sha is
            // the same we index by, so we don't need the original manifest.
            .mapNotNull { (path, _) ->
                val sha = path.removePrefix("objects/").substringBefore('.')
                if (sha.length != 64) return@mapNotNull null
                val key = "sha256:$sha"
                key to com.wangxiuwen.coursebox.core.cx.CxDataSource
                    .makeUri(partFile, path).toString()
            }
            .toMap()

        val merged = owning.copy(
            cxPaths = (owning.cxPaths + partFile.absolutePath).distinct(),
            resourceIndex = owning.resourceIndex + newEntries,
        )
        stateFlow.value = state.copy(
            packages = state.packages.map { if (it.id == owning.id) merged else it },
        )
        persist()
        return ImportResult(listOf(merged), newEntries.size)
    }

    private fun stageLessonsJson(
        archive: com.wangxiuwen.coursebox.core.cx.CxArchive,
        cxFile: File,
        entryPath: String,
    ): String {
        val entry = archive.entryByName(entryPath)
            ?: error("lessons_manifest missing in .cx: $entryPath")
        val staged = File(packagesDir, "lessons_${entryPath.substringAfterLast('/').substringBefore('.')}.json")
        if (!staged.exists() || staged.length() != entry.size) {
            staged.writeBytes(archive.readBytes(entry, 0, entry.size.toInt()))
        }
        return staged.absolutePath
    }

    private fun buildCxResourceIndex(
        archive: com.wangxiuwen.coursebox.core.cx.CxArchive,
        cxFile: File,
        resources: List<CourseResource>,
    ): Map<String, String> = resources.associate { r ->
        val key = if (r.hash.startsWith("sha256:")) r.hash else "sha256:${r.hash}"
        val entry = archive.entryByName(r.path)
            ?: error("resource not in .cx: ${r.path}")
        key to com.wangxiuwen.coursebox.core.cx.CxDataSource.makeUri(cxFile, entry.name).toString()
    }

    private fun buildCxLogicalIndex(
        archive: com.wangxiuwen.coursebox.core.cx.CxArchive,
        cxFile: File,
        resources: List<CourseResource>,
    ): Map<String, String> = resources.filter { it.origin.isNotBlank() }
        .associate { r ->
            val entry = archive.entryByName(r.path)
                ?: error("resource not in .cx: ${r.path}")
            normalizeLogicalPath(r.origin) to
                com.wangxiuwen.coursebox.core.cx.CxDataSource.makeUri(cxFile, entry.name).toString()
        }

    private suspend fun persist() = withContext(Dispatchers.IO) {
        root.mkdirs()
        indexFile.writeText(json.encodeToString(state))
    }

    companion object {
        private const val INDEX_NAME = "library_index.json"

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = false
        }

        @Volatile private var instance: CourseLibrary? = null

        suspend fun get(ctx: Context): CourseLibrary {
            instance?.let { return it }
            return withContext(Dispatchers.IO) {
                instance ?: build(ctx).also { instance = it }
            }
        }

        private fun build(ctx: Context): CourseLibrary {
            val root = File(ctx.filesDir, "coursebox_library").apply { mkdirs() }
            File(root, "objects").mkdirs()
            File(root, "packages").mkdirs()

            val indexFile = File(root, INDEX_NAME)
            val state = runCatching {
                if (indexFile.exists()) json.decodeFromString<CourseLibraryState>(indexFile.readText())
                else CourseLibraryState()
            }.getOrDefault(CourseLibraryState())
            return CourseLibrary(root, state)
        }

        private fun sha256(bytes: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-256")
            val hex = md.digest(bytes).joinToString("") { "%02x".format(it) }
            return hex
        }
    }
}

/** Hash an InputStream lazily — used by future packers/tests; unused at runtime. */
internal fun InputStream.sha256Hex(): String {
    val md = MessageDigest.getInstance("SHA-256")
    val buf = ByteArray(64 * 1024)
    while (true) {
        val n = read(buf)
        if (n <= 0) break
        md.update(buf, 0, n)
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

/** Quietly close any [ZipInputStream] holders. Kept here so test fixtures share it. */
internal fun ZipInputStream.drain() {
    val buf = ByteArray(8 * 1024)
    while (read(buf) > 0) { /* discard */ }
}
