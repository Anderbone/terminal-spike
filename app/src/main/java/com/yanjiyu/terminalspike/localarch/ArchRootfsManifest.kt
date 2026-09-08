package com.yanjiyu.terminalspike.localarch

/** A reviewed immutable bootstrap. Updating the APK never replaces an installed user filesystem. */
internal object ArchRootfsManifest {
    const val VERSION = "archlinux-aarch64-pd-v4.37.0"
    const val RUNTIME_VERSION = "proot-5.1.107.81/pty-1/shmem-2"
    const val URL = "https://github.com/gwitko/conduit-rootfs/releases/download/rootfs-pd-v4.37.0/archlinux-aarch64-pd-v4.37.0.tar.xz"
    const val SHA256 = "718151cc4adad701223c689a7e4690cb7710b7b16e9b23617b671856ff04d563"
    const val DOWNLOAD_BYTES = 176_379_228L
    const val ARCHIVE_PREFIX = "archlinux-aarch64"
    const val MAX_EXPANDED_BYTES = 2L * 1024 * 1024 * 1024
    const val MAX_ENTRIES = 100_000
}
