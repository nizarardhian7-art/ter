package com.ccompile.lite

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class ArchiveExtractorService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentJob: Job? = null
    @Volatile private var isCancelled = false

    sealed class ExtractEvent {
        data class Progress(val message: String, val percent: Int) : ExtractEvent()
        data class Finished(val success: Boolean, val message: String, val cancelled: Boolean) : ExtractEvent()
    }

    companion object {
        const val ACTION_CANCEL = "com.ccompile.lite.EXTRACT_CANCEL"

        const val NOTIF_ID = 1002
        const val NOTIF_RESULT_ID = 1004

        @Volatile
        var isRunning = false
            private set

        val eventFlow = MutableSharedFlow<ExtractEvent>(
            replay = 1,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

        fun isExtracting(): Boolean = isRunning
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            isCancelled = true
            currentJob?.cancel()
            return START_NOT_STICKY
        }

        if (isRunning) return START_NOT_STICKY
        isRunning = true
        isCancelled = false

        val sourcePath = intent?.getStringExtra("source_file") ?: run {
            isRunning = false
            return START_NOT_STICKY
        }
        val destDirPath = intent.getStringExtra("dest_dir") ?: run {
            isRunning = false
            return START_NOT_STICKY
        }
        val deleteSource = intent.getBooleanExtra("delete_source", false)
        val title = intent.getStringExtra("title") ?: "Extracting archive..."

        val sourceFile = File(sourcePath)
        val destDir = File(destDirPath)

        startForeground(NOTIF_ID, buildNotification("Preparing...", true, 0).build())

        currentJob = serviceScope.launch {
            try {
                if (!sourceFile.exists()) {
                    throw IOException("Source file not found")
                }
                if (!destDir.exists() && !destDir.mkdirs()) {
                    throw IOException("Cannot create destination directory")
                }

                emitProgress("Detecting format...", -1)

                val format = detectFormat(sourceFile)
                val totalSize = sourceFile.length()
                var extractedCount = 0

                when (format) {
                    "tar.gz", "tgz" -> {
                        emitProgress("Extracting tar.gz...", 0)
                        extractedCount = extractTarGz(sourceFile, destDir, totalSize)
                    }
                    "tar.xz" -> {
                        emitProgress("Extracting tar.xz...", 0)
                        extractedCount = extractTarXz(sourceFile, destDir, totalSize)
                    }
                    "zip" -> {
                        emitProgress("Extracting zip...", 0)
                        extractedCount = extractZip(sourceFile, destDir, totalSize)
                    }
                    "7z" -> {
                        emitProgress("Extracting 7z...", 0)
                        extractedCount = extract7z(sourceFile, destDir)
                    }
                    else -> throw IOException("Unsupported archive format")
                }

                if (isCancelled) {
                    emitFinished(false, "Extraction cancelled", cancelled = true)
                    return@launch
                }

                emitProgress("Extraction complete ($extractedCount files)", 100)
                if (deleteSource) {
                    sourceFile.delete()
                }
                emitFinished(true, "Extraction successful ($extractedCount files)", cancelled = false)
                showFinalNotification("Extraction Complete", "Extracted $extractedCount files to ${destDir.name}", true)

            } catch (e: Exception) {
                if (isCancelled) {
                    emitFinished(false, "Extraction cancelled", cancelled = true)
                    showFinalNotification("Extraction Cancelled", e.message ?: "", false)
                } else {
                    emitFinished(false, "Error: ${e.message}", cancelled = false)
                    showFinalNotification("Extraction Failed", e.message ?: "Unknown error", false)
                }
            } finally {
                isRunning = false
                stopForeground(true)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun emitProgress(msg: String, percent: Int) {
        eventFlow.tryEmit(ExtractEvent.Progress(msg, percent))
    }

    private fun emitFinished(success: Boolean, msg: String, cancelled: Boolean) {
        eventFlow.tryEmit(ExtractEvent.Finished(success, msg, cancelled))
    }

    private fun detectFormat(file: File): String {
        val name = file.name.lowercase()
        return file.inputStream().use { stream ->
            val magic = ByteArray(6)
            val read = stream.read(magic)
            when {
                read >= 2 && magic[0] == 0x1F.toByte() && magic[1] == 0x8B.toByte() -> "tar.gz"
                read >= 6 && magic[0] == 0xFD.toByte() && magic[1] == 0x37.toByte() &&
                        magic[2] == 0x7A.toByte() && magic[3] == 0x58.toByte() &&
                        magic[4] == 0x5A.toByte() && magic[5] == 0x00.toByte() -> "tar.xz"
                read >= 4 && magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte() &&
                        magic[2] == 0x03.toByte() && magic[3] == 0x04.toByte() -> "zip"
                read >= 6 && magic[0] == 0x37.toByte() && magic[1] == 0x7A.toByte() &&
                        magic[2] == 0xBC.toByte() && magic[3] == 0xAF.toByte() &&
                        magic[4] == 0x27.toByte() && magic[5] == 0x1C.toByte() -> "7z"
                name.endsWith(".tar.gz") || name.endsWith(".tgz") -> "tar.gz"
                name.endsWith(".tar.xz") -> "tar.xz"
                name.endsWith(".zip") -> "zip"
                name.endsWith(".7z") -> "7z"
                else -> "unknown"
            }
        }
    }

    private fun extractTarGz(sourceFile: File, destDir: File, totalSize: Long): Int {
        return extractTar(sourceFile, destDir, totalSize, gzip = true)
    }

    private fun extractTarXz(sourceFile: File, destDir: File, totalSize: Long): Int {
        return extractTar(sourceFile, destDir, totalSize, gzip = false)
    }

    private fun extractTar(sourceFile: File, destDir: File, totalSize: Long, gzip: Boolean): Int {
        var count = 0
        var lastPercent = -1
        sourceFile.inputStream().use { raw ->
            val counting = CountingInputStream(raw)
            val buffered = BufferedInputStream(counting)
            val decompressed = if (gzip) {
                GzipCompressorInputStream(buffered)
            } else {
                XZCompressorInputStream(buffered)
            }
            TarArchiveInputStream(decompressed).use { tar ->
                var entry = tar.nextTarEntry
                while (entry != null && !isCancelled) {
                    count++
                    val percent = if (totalSize > 0) {
                        ((counting.bytesRead * 100) / totalSize).toInt().coerceIn(0, 99)
                    } else -1
                    if (percent != lastPercent) {
                        lastPercent = percent
                        notifyFile(entry.name, entry.isDirectory, percent)
                    }

                    val outFile = File(destDir, entry.name)
                    val destCanonical = destDir.canonicalPath
                    val outCanonical = outFile.canonicalPath
                    if (outCanonical != destCanonical && !outCanonical.startsWith(destCanonical + File.separator)) {
                        throw IOException("Zip slip attack detected: ${entry.name}")
                    }

                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else if (entry.isSymbolicLink) {
                        outFile.parentFile?.mkdirs()
                        try {
                            android.system.Os.symlink(entry.linkName, outFile.absolutePath)
                        } catch (e: Exception) { /* ignore */ }
                    } else if (entry.isLink) {
                        outFile.parentFile?.mkdirs()
                        try {
                            val targetPath = File(destDir, entry.linkName).absolutePath
                            android.system.Os.link(targetPath, outFile.absolutePath)
                        } catch (e: Exception) { /* ignore */ }
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { out -> tar.copyTo(out) }
                        if (entry.mode and 0b001_000_000 != 0) {
                            outFile.setExecutable(true, false)
                        }
                        outFile.setReadable(true, false)
                        outFile.setWritable(true, false)
                    }
                    entry = tar.nextTarEntry
                }
            }
        }
        fixExecutables(destDir)
        return count
    }

    private fun extract7z(sourceFile: File, destDir: File): Int {
        var count = 0
        org.apache.commons.compress.archivers.sevenz.SevenZFile(sourceFile).use { sz ->
            var entry = sz.nextEntry
            while (entry != null && !isCancelled) {
                count++
                val outFile = File(destDir, entry.name)
                val destCanonical = destDir.canonicalPath
                val outCanonical = outFile.canonicalPath
                if (outCanonical != destCanonical && !outCanonical.startsWith(destCanonical + File.separator)) {
                    throw IOException("Zip slip attack detected: ${entry.name}")
                }

                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    sz.getInputStream(entry).use { input ->
                        FileOutputStream(outFile).use { out -> input.copyTo(out) }
                    }
                    outFile.setReadable(true, false)
                    outFile.setWritable(true, false)
                }
                notifyFile(entry.name, entry.isDirectory, -1)
                entry = sz.nextEntry
            }
        }
        fixExecutables(destDir)
        return count
    }

    private fun fixExecutables(root: File) {
        val skipExtensions = setOf(
            "h", "hpp", "c", "cc", "cpp", "cxx", "java", "kt", "py", "js", "json",
            "xml", "txt", "md", "gradle", "properties", "cmake", "mk", "gitignore",
            "html", "css", "png", "jpg", "jpeg", "gif", "svg", "ttf", "otf", "map",
            "yml", "yaml", "toml", "lock", "pdf", "license", "notice", "readme",
            "kts", "iml", "pro", "cfg", "ini", "bat", "cmd", "md5", "sha1", "sha256",
            "pom", "jar", "class", "dex", "ap_", "aab", "webp", "ico", "wav", "mp3"
        )

        val knownExecutables = setOf(
            "ndk-build", "gradlew", "cmake", "ninja", "make", "clang", "clang++",
            "gcc", "g++", "cc", "ld", "ld.lld", "ar", "nm", "objdump", "strip",
            "patchelf", "python3", "python", "bash", "sh", "git", "rsync", "curl",
            "wget", "zip", "unzip", "7z", "tar", "gzip", "xz", "bzip2", "dpkg",
            "apt-get", "apt", "aapt", "aapt2", "d8", "apksigner", "zipalign"
        )

        root.walkTopDown()
            .onEnter { dir -> dir.name != ".git" }
            .forEach { f ->
                if (!f.isFile) return@forEach
                if (isCancelled) return@forEach

                val n = f.name
                val ext = n.substringAfterLast('.', "").lowercase()

                if (ext in skipExtensions) return@forEach

                val inBinDir = f.parentFile?.name == "bin" ||
                    f.parentFile?.name == "sbin" ||
                    f.parentFile?.name == "libexec" ||
                    f.path.contains("${File.separator}bin${File.separator}") ||
                    f.path.contains("${File.separator}toolchains${File.separator}")

                val looksImportant = n in knownExecutables ||
                    n.endsWith(".sh") ||
                    n.endsWith(".so") ||
                    inBinDir

                if (looksImportant) {
                    f.setExecutable(true, false)
                    return@forEach
                }

                if (ext.isEmpty()) {
                    val isElf = try {
                        f.inputStream().use { s ->
                            val h = ByteArray(4)
                            val r = s.read(h)
                            r == 4 && h[0] == 0x7F.toByte() && h[1] == 0x45.toByte() &&
                                    h[2] == 0x4C.toByte() && h[3] == 0x46.toByte()
                        }
                    } catch (e: Exception) { false }
                    if (isElf) f.setExecutable(true, false)
                }
            }
    }

    private fun extractZip(sourceFile: File, destDir: File, totalSize: Long): Int {
        var count = 0
        var lastPercent = -1
        sourceFile.inputStream().use { raw ->
            val counting = CountingInputStream(raw)
            ZipInputStream(BufferedInputStream(counting)).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null && !isCancelled) {
                    count++
                    val percent = if (totalSize > 0) {
                        ((counting.bytesRead * 100) / totalSize).toInt().coerceIn(0, 99)
                    } else -1
                    if (percent != lastPercent) {
                        lastPercent = percent
                        notifyFile(entry.name, entry.isDirectory, percent)
                    }

                    val outFile = File(destDir, entry.name)
                    val destCanonical = destDir.canonicalPath
                    val outCanonical = outFile.canonicalPath
                    if (outCanonical != destCanonical && !outCanonical.startsWith(destCanonical + File.separator)) {
                        throw IOException("Zip slip attack detected: ${entry.name}")
                    }

                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { out -> zis.copyTo(out) }
                        outFile.setReadable(true, false)
                        outFile.setWritable(true, false)
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
        return count
    }

    private class CountingInputStream(stream: InputStream) : FilterInputStream(stream) {
        var bytesRead: Long = 0
            private set
        override fun read(): Int {
            val b = super.read()
            if (b != -1) bytesRead++
            return b
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) bytesRead += n
            return n
        }
    }

    private fun notifyFile(name: String, isDir: Boolean, percent: Int) {
        if (isDir) return
        val shortName = name.substringAfterLast('/').ifEmpty { name.trimEnd('/').substringAfterLast('/') }
        if (shortName.isEmpty()) return
        val display = if (shortName.length > 38) "...${shortName.takeLast(35)}" else shortName
        val msg = "→ $display"
        emitProgress(msg, percent)
        updateNotification(msg, percent < 0, percent)
    }

    private fun updateNotification(message: String, indeterminate: Boolean, percent: Int = -1) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIF_ID, buildNotification(message, indeterminate, percent).build())
    }

    private fun showFinalNotification(title: String, message: String, success: Boolean) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(this, MainActivity.CHANNEL_INSTALL)
            .setSmallIcon(if (success) R.drawable.ic_install_notif else R.drawable.ic_clear)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()
        manager.notify(NOTIF_RESULT_ID, notif)
    }

    private fun buildNotification(message: String, indeterminate: Boolean, percent: Int = 0): NotificationCompat.Builder {
        val cancelIntent = Intent(this, ArchiveExtractorService::class.java).apply {
            action = ACTION_CANCEL
        }
        val cancelPendingIntent = PendingIntent.getService(
            this, 0, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, MainActivity.CHANNEL_INSTALL)
            .setSmallIcon(R.drawable.ic_install_notif)
            .setContentTitle(if (percent in 0..99) "Extracting — $percent%" else "Extracting")
            .setContentText(message)
            .setProgress(100, percent.coerceIn(0, 100), indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPendingIntent)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        currentJob?.cancel()
        super.onDestroy()
    }
}