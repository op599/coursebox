package com.wangxiuwen.coursebox.core

import java.io.File

/** Resolve selected library courses to unique, readable backing .cx files. */
fun courseShareFiles(
    packages: List<CoursePackageRecord>,
    selectedCourseIds: Set<String>,
): List<File> = packages.asSequence()
    .filter { it.id in selectedCourseIds }
    .flatMap { it.cxPaths.asSequence() }
    .map(::File)
    .filter { it.isFile }
    .distinctBy { it.absolutePath }
    .toList()
