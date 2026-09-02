package com.wangxiuwen.coursebox.core.lan

import android.content.Context
import android.provider.Settings

/** Stable per-install/device identity; unlike Build.MODEL it distinguishes two identical phones. */
fun courseboxFingerprint(context: Context): String {
    val androidId = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ANDROID_ID,
    ).orEmpty()
    return "coursebox-" + (context.packageName + androidId).hashCode().toUInt().toString(16)
}
