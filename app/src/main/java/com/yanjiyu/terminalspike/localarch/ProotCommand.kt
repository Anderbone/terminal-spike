package com.yanjiyu.terminalspike.localarch

import java.io.File

/** Arch-only launch contract. Arguments are never interpolated into host shell text. */
internal data class ProotCommand(
    val executable: String,
    val arguments: List<String>,
    val environment: Map<String, String>,
    val workingDirectory: String,
) {
    fun start(columns: Int, rows: Int): NativePty = NativePty.start(
        executable, arguments, environment, workingDirectory, columns, rows,
    )

    companion object {
        fun archShell(
            nativeLibraryDirectory: File,
            rootfs: File,
            temporaryDirectory: File,
            guestCommand: List<String> = listOf("/bin/bash", "--login"),
        ): ProotCommand {
            listOf(nativeLibraryDirectory, rootfs, temporaryDirectory).forEach { path ->
                require(path.isAbsolute && path.path.none { it == ':' || it == '\u0000' })
            }
            require(guestCommand.isNotEmpty() && guestCommand.first().startsWith('/'))
            require(guestCommand.all { '\u0000' !in it })
            // PRoot redirects long guest Unix socket names into PROOT_TMP_DIR.
            // Leave space for its generated filename within Linux's 108-byte sun_path.
            require(temporaryDirectory.path.toByteArray(Charsets.UTF_8).size <= 80) {
                "PRoot temporary directory is too long for Unix sockets"
            }
            val bindings = listOf(
                "/dev", "/proc", "/sys", "/dev/pts", "/dev/urandom:/dev/random",
                "/proc/self/fd:/dev/fd", "/proc/self/fd/0:/dev/stdin",
                "/proc/self/fd/1:/dev/stdout", "/proc/self/fd/2:/dev/stderr",
            )
            return ProotCommand(
                executable = File(nativeLibraryDirectory, "libproot.so").path,
                arguments = listOf("--kill-on-exit", "--link2symlink", "--sysvipc", "-0", "-r", rootfs.path) +
                    bindings.flatMap { listOf("-b", it) } +
                    listOf(
                        "--cwd=/root", "-k", "5.4.0", "/usr/bin/env", "-i",
                        "HOME=/root", "USER=root", "LOGNAME=root", "TERM=xterm-256color",
                        "LANG=C.UTF-8", "PATH=/usr/local/sbin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin",
                    ) + guestCommand,
                environment = mapOf(
                    "PROOT_LOADER" to File(nativeLibraryDirectory, "libproot_loader.so").path,
                    "PROOT_TMP_DIR" to temporaryDirectory.path,
                    "LD_LIBRARY_PATH" to nativeLibraryDirectory.path,
                ),
                workingDirectory = rootfs.path,
            )
        }
    }
}
