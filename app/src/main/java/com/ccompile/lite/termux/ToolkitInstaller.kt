package com.ccompile.lite.termux

import android.content.Context
import java.io.File

class ToolkitInstaller(private val context: Context) {

    private val prefixDir: File get() = File(context.filesDir, "usr")
    private val sdkDir: File get() = File(context.filesDir, "home/android-sdk")

    // Kotlin API ini hanya dipakai UI untuk cek status (Installed/Not Installed)
    val isDownloading: Boolean = false
    val statusMessage: String = ""
    val downloadProgress: Int = -1

    fun isInstalled(): Boolean {
        // Cek binary gradle & java (hasil instalasi apt-get) dan android platform cache
        return File(prefixDir, "bin/gradle").exists() &&
               File(prefixDir, "bin/java").exists() &&
               File(sdkDir, "platforms").exists()
    }

    fun uninstall() {
        sdkDir.deleteRecursively()
        File(context.filesDir, "home/.gradle").deleteRecursively()
        File(context.filesDir, "home/workspace").deleteRecursively()
    }

    fun getInstalledSize(): Long {
        if (!sdkDir.exists()) return 0
        return sdkDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}