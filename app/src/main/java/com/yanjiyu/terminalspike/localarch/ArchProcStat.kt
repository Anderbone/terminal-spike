package com.yanjiyu.terminalspike.localarch

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Supplies btime when Android denies /proc/stat. Per-process /proc entries stay real. */
internal class ArchProcStat(
    private val bootIdentity: () -> String?,
    private val bootTimeSeconds: () -> Long,
    private val kernelStat: File = File("/proc/stat"),
) {
    fun prepare(directory: File): File? = synchronized(lock) {
        if (runCatching { kernelStat.inputStream().use { it.read() >= 0 } }.getOrDefault(false)) {
            return null
        }
        val boot = bootIdentity() ?: return null
        require(boot.matches(Regex("[a-zA-Z0-9-]{1,64}"))) { "Invalid Android boot identity" }
        Files.createDirectories(directory.toPath())
        val file = File(directory, "proc-stat-$boot")
        // Never recalculate an existing boot's value: rounding or a wall-clock correction
        // between shells must not change ps lstart and invalidate a live daemon's PID record.
        if (Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.length() <= 256) {
            val text = file.readText()
            if (text.startsWith(HEADER) && text.removePrefix(HEADER).matches(Regex("btime [1-9][0-9]*\n")) &&
                text.removePrefix(HEADER + "btime ").trim().toLongOrNull() != null) {
                return file
            }
        }
        val seconds = bootTimeSeconds()
        require(seconds > 0) { "Invalid Android boot time" }
        val pending = Files.createTempFile(directory.toPath(), "proc-stat-", ".pending")
        try {
            FileOutputStream(pending.toFile()).use { output ->
                output.write("${HEADER}btime $seconds\n".toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(pending, file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(pending)
        }
        file
    }

    companion object {
        private val lock = Any()
        // libproc2 validates the aggregate CPU header even when ps requests only btime.
        // Zero placeholders let it parse the file; they are not host CPU measurements.
        private const val HEADER = "cpu  0 0 0 0 0 0 0 0 0 0\n" +
            "# Local Arch boot time; CPU counters above are unavailable zero placeholders.\n"

        fun forAndroid(context: Context): ArchProcStat = ArchProcStat(
            bootIdentity = {
                // BOOT_COUNT is also available when an OEM restricts the kernel boot UUID.
                runCatching {
                    File("/proc/sys/kernel/random/boot_id").readText().trim().also {
                        require(it.matches(Regex("[a-zA-Z0-9-]{1,64}")))
                    }
                }.getOrElse {
                    runCatching { "boot-${Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)}" }
                        .getOrNull()
                }
            },
            bootTimeSeconds = {
                val elapsedBefore = SystemClock.elapsedRealtime()
                val wall = System.currentTimeMillis()
                val elapsedAfter = SystemClock.elapsedRealtime()
                (wall - (elapsedBefore + (elapsedAfter - elapsedBefore) / 2)) / 1000
            },
        )
    }
}
