package com.ccompile.lite.termux

import android.content.Context
import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class BootstrapInstaller(private val context: Context) {

    private companion object {
        const val BOOTSTRAP_MARKER = "fork-relocatable-v4"
    }

    val prefixDir: File get() = File(context.filesDir, "usr")
    val homeDir: File get() = File(context.filesDir, "home")

    private val markerFile: File get() = File(prefixDir, ".bootstrap_complete")

    fun isInstalled(): Boolean {
        if (!File(prefixDir, "bin/bash").exists()) return false

        val criticalApplets = listOf("chmod", "mkdir", "cp", "ls", "rm")
        val appletsOk = criticalApplets.all { name ->
            val f = File(prefixDir, "bin/$name")
            f.exists() || isSymlink(f)
        }
        if (!appletsOk) return false

        val libDir = File(prefixDir, "lib")
        if (!libDir.exists()) return false

        // Reinstall older installs created before runtime path relocation.
        if (!markerFile.exists() || markerFile.readText().trim() != BOOTSTRAP_MARKER) return false
        val startupFiles = listOf(
            File(prefixDir, "etc/profile"),
            File(prefixDir, "etc/bash.bashrc"),
            File(prefixDir, "bin/pkg"),
            File(prefixDir, "bin/termux-setup-package-manager")
        )
        if (startupFiles.any { containsLegacyRuntimePath(it) }) return false

        return true
    }

    fun installFromAssets(onProgress: ((Int) -> Unit)? = null) {
        val assetPath = "termux-bootstrap/bootstrap-aarch64.zip"
        val assetManager = context.assets

        prefixDir.deleteRecursively()
        homeDir.deleteRecursively()
        prefixDir.mkdirs()
        homeDir.mkdirs()
        File(prefixDir, "tmp").mkdirs()

        val symlinks = mutableListOf<Pair<String, String>>()

        assetManager.open(assetPath).use { input ->
            extractZipStream(ZipInputStream(input.buffered()), symlinks, onProgress)
        }

        val failCount = linkSymlinks(symlinks)
        if (failCount > 0 && !isInstalled()) {
            throw java.io.IOException("Bootstrap incomplete: $failCount symlink(s) failed to create")
        }

        try {
            val out = File(prefixDir, "bin/termux-build")
            context.assets.open("apkbuilder/build.sh").use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            out.setExecutable(true, false)
        } catch (_: Exception) {}

        try {
            markerFile.writeText(BOOTSTRAP_MARKER)
        } catch (e: Exception) {
            android.util.Log.w("BootstrapInstaller", "Failed to write bootstrap marker: ${e.message}")
        }

        onProgress?.invoke(100)
    }

    private fun extractZipStream(
        zis: ZipInputStream,
        symlinksOut: MutableList<Pair<String, String>>,
        onProgress: ((Int) -> Unit)?
    ) {
        var entry: ZipEntry?
        var count = 0
        while (true) {
            entry = zis.nextEntry ?: break
            val name = entry.name

            if (name == "SYMLINKS.txt") {
                val text = patchRuntimeText(String(zis.readBytes(), Charsets.UTF_8))
                text.lineSequence().forEach { line ->
                    if (line.isBlank()) return@forEach
                    val parts = when {
                        line.contains("\t") -> line.split("\t")
                        line.contains("←") -> line.split("←")
                        line.contains(" -> ") -> line.split(" -> ")
                        else -> listOf(line)
                    }
                    if (parts.size == 2) symlinksOut.add(parts[0].trim() to parts[1].trim())
                }
                zis.closeEntry()
                continue
            }

            val outFile = File(prefixDir, name)
            if (entry.isDirectory) {
                outFile.mkdirs()
            } else {
                outFile.parentFile?.mkdirs()
                val fos = FileOutputStream(outFile)
                try {
                    zis.copyTo(fos)
                    fos.flush()
                    fos.fd.sync()
                } catch (e: java.io.SyncFailedException) {
                } finally {
                    fos.close()
                }
                patchTextFileIfNeeded(outFile)
                outFile.setExecutable(true, false)
            }
            zis.closeEntry()
            count++
            if (count % 50 == 0) onProgress?.invoke(-1)
        }
    }

    private fun linkSymlinks(symlinks: List<Pair<String, String>>): Int {
        var failCount = 0
        symlinks.forEach { (target, linkName) ->
            try {
                val linkFile = File(prefixDir, linkName)
                linkFile.parentFile?.mkdirs()
                if (linkFile.exists() || isSymlink(linkFile)) linkFile.delete()
                Os.symlink(patchRuntimeText(target), linkFile.absolutePath)
            } catch (e: Exception) {
                failCount++
                android.util.Log.w("BootstrapInstaller", "Failed to symlink $linkName -> $target: ${e.message}")
            }
        }
        return failCount
    }

    private fun patchRuntimeText(value: String): String {
        val oldPrefix = "/data/data/com.termux/files/usr"
        val oldHome = "/data/data/com.termux/files/home"
        val oldCache = "/data/data/com.termux/cache"
        val oldUserCache = "/data/user/0/com.termux/cache"
        return value
            .replace("@FORK_PREFIX@", prefixDir.absolutePath)
            .replace("@FORK_HOME@", homeDir.absolutePath)
            .replace("@FORK_CACHE@", context.cacheDir.absolutePath)
            .replace("@FORK_DATA@", context.dataDir.absolutePath)
            .replace(oldPrefix, prefixDir.absolutePath)
            .replace(oldHome, homeDir.absolutePath)
            .replace(oldCache, context.cacheDir.absolutePath)
            .replace(oldUserCache, context.cacheDir.absolutePath)
            .replace("/data/data/com.termux", context.dataDir.absolutePath)
            .replace("/data/user/0/com.termux", context.dataDir.absolutePath)
            .replace("@FORK_PACKAGE@", context.packageName)
    }

    private fun containsLegacyRuntimePath(file: File): Boolean =
        try {
            if (!file.isFile) false
            else {
                val text = file.readText(Charsets.UTF_8)
                text.contains("/data/data/com.termux") || text.contains("/data/user/0/com.termux")
            }
        } catch (_: Exception) {
            true
        }

    private fun patchTextFileIfNeeded(file: File) {
        try {
            val bytes = file.readBytes()
            if (bytes.any { it.toInt() == 0 }) return
            val text = bytes.toString(Charsets.UTF_8)
            val patched = patchRuntimeText(text)
            if (patched != text) file.writeText(patched, Charsets.UTF_8)
        } catch (_: Exception) {}
    }

    private fun isSymlink(file: File): Boolean =
        try {
            file.exists() && Os.lstat(file.absolutePath).let { true }
        } catch (_: Exception) {
            false
        }

    fun buildEnv(): Array<String> {
        val ldPreload = File(prefixDir, "lib/libtermux-exec-ld-preload.so")
        val envList = mutableListOf(
            "HOME=${homeDir.absolutePath}",
            "PREFIX=${prefixDir.absolutePath}",
            "TERMUX_PREFIX=${prefixDir.absolutePath}",
            "TERMUX__PREFIX=${prefixDir.absolutePath}",
            "PATH=${prefixDir.absolutePath}/bin:${prefixDir.absolutePath}/bin/applets",
            "LD_LIBRARY_PATH=${prefixDir.absolutePath}/lib",
            "TMPDIR=${prefixDir.absolutePath}/tmp",
            "SSL_CERT_FILE=${prefixDir.absolutePath}/etc/tls/cert.pem",
            "CURL_CA_BUNDLE=${prefixDir.absolutePath}/etc/tls/cert.pem",
            "SSL_CERT_DIR=${prefixDir.absolutePath}/etc/tls",
            "APT_CONFIG=${prefixDir.absolutePath}/etc/apt/fork-apt.conf",
            "TERMUX_PKG_NO_MIRROR_SELECT=1",
            "LANG=en_US.UTF-8",
            "TERM=xterm-256color"
        )
        if (ldPreload.exists()) {
            envList.add("LD_PRELOAD=${ldPreload.absolutePath}")
        }
        return envList.toTypedArray()
    }

    fun ensureReady() {
        if (!isInstalled()) {
            installFromAssets()
            Thread.sleep(150)
        }
        File(prefixDir, "tmp").mkdirs()
    }

    fun ensureReadyAsync(onDone: (Boolean) -> Unit) {
        Thread {
            val ok = try {
                if (!isInstalled()) {
                    installFromAssets()
                    Thread.sleep(150)
                }
                File(prefixDir, "tmp").mkdirs()
                true
            } catch (e: Exception) {
                android.util.Log.e("BootstrapInstaller", "ensureReadyAsync failed: ${e.message}", e)
                false
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post { onDone(ok) }
        }.start()
    }
}