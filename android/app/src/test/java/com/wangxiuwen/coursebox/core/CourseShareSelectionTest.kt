package com.wangxiuwen.coursebox.core

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CourseShareSelectionTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun deduplicatesSharedBackingFilesAndSkipsMissingFiles() {
        val shared = temp.newFile("shared.cx")
        val other = temp.newFile("other.cx")
        val missing = temp.root.resolve("missing.cx")
        val packages = listOf(
            record("a", listOf(shared.absolutePath, missing.absolutePath)),
            record("b", listOf(shared.absolutePath)),
            record("c", listOf(other.absolutePath)),
        )

        assertEquals(
            listOf(shared.absolutePath, other.absolutePath),
            courseShareFiles(packages, setOf("a", "b", "c")).map { it.absolutePath },
        )
    }

    private fun record(id: String, paths: List<String>) = CoursePackageRecord(
        id = id,
        title = id,
        lessonsManifestPath = "",
        cxPaths = paths,
    )
}
