package com.ccompile.lite

import android.util.LruCache
import java.io.File

/**
 * Tipe project yang bisa dideteksi dari file penanda di dalam folder.
 */
enum class ProjectType(val label: String, val colorHex: Int) {
    GRADLE("Gradle", 0xFF02303A.toInt()),
    CMAKE("CMake", 0xFF064F8C.toInt()),
    NDK_BUILD("NDK", 0xFF3DDC84.toInt()),
    NODE("Node", 0xFF339933.toInt()),
    MAKE("Make", 0xFF427819.toInt()),
    PYTHON("Python", 0xFF3776AB.toInt())
}

/**
 * Mendeteksi tipe project dari file penanda di dalam sebuah direktori.
 * Return null jika folder bukan project yang dikenali.
 *
 * Hasil deteksi di-cache (LruCache) agar tidak ada I/O berulang saat scroll
 * RecyclerView. Sebelumnya ProjectDetector.detect() dipanggil untuk SETIAP
 * subfolder di MyAdapter → ratusan File.list() per render.
 */
object ProjectDetector {

    /** Wrapper untuk handle nullable di LruCache (LruCache.get() return null
     *  baik untuk key yang tidak ada maupun value null). */
    private data class CacheEntry(val type: ProjectType?)

    private val cache = LruCache<String, CacheEntry>(200)

    fun detect(dir: File): ProjectType? {
        if (!dir.isDirectory) return null

        val key = dir.absolutePath
        cache.get(key)?.let { return it.type }

        val result = detectInternal(dir)
        cache.put(key, CacheEntry(result))
        return result
    }

    /** Panggil ini jika ada perubahan filesystem (create/rename/delete) */
    fun invalidate(path: String) {
        cache.remove(path)
    }

    /** Bersihkan seluruh cache (misal saat user ganti root directory) */
    fun invalidateAll() {
        cache.evictAll()
    }

    private fun detectInternal(dir: File): ProjectType? {
        val names = try { dir.list()?.toSet() } catch (_: Exception) { null } ?: return null

        // 1. Periksa file langsung di root folder
        when {
            "build.gradle" in names || "build.gradle.kts" in names -> return ProjectType.GRADLE
            "CMakeLists.txt" in names -> return ProjectType.CMAKE
            "Android.mk" in names || "Application.mk" in names -> return ProjectType.NDK_BUILD
            "package.json" in names -> return ProjectType.NODE
            "Makefile" in names || "makefile" in names -> return ProjectType.MAKE
            "setup.py" in names || "requirements.txt" in names || "pyproject.toml" in names -> return ProjectType.PYTHON
        }

        // 2. Periksa apakah ini parent folder dari NDK (memiliki folder "jni")
        if ("jni" in names) {
            val jniDir = File(dir, "jni")
            if (File(jniDir, "Android.mk").exists() ||
                File(jniDir, "Application.mk").exists() ||
                File(jniDir, "CMakeLists.txt").exists()
            ) {
                return if (File(jniDir, "CMakeLists.txt").exists()) ProjectType.CMAKE
                else ProjectType.NDK_BUILD
            }
        }

        return null
    }
}