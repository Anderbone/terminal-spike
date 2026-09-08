package com.yanjiyu.terminalspike.localarch

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class ArchRootfsDownloaderTest {
    private val downloader = ArchRootfsDownloader()

    @Test fun ignoredRangeRestartsInsteadOfAppendingDuplicateBytes() {
        assertFalse(downloader.validateResponse(200, null, 4096))
    }

    @Test fun resumedResponseMustMatchOffsetEndAndPinnedTotal() {
        val total = ArchRootfsManifest.DOWNLOAD_BYTES
        assertTrue(downloader.validateResponse(206, "bytes 4096-${total - 1}/$total", 4096))
        listOf(null, "bytes 0-${total - 1}/$total", "bytes 4096-9000/$total",
            "bytes 4096-${total - 1}/*", "bytes 4096-${total - 1}/${total + 1}").forEach { range ->
            assertThrows(IOException::class.java) { downloader.validateResponse(206, range, 4096) }
        }
        assertThrows(IOException::class.java) { downloader.validateResponse(206, "bytes 0-${total - 1}/$total", 0) }
    }

    @Test fun errorBodiesAreNeverAcceptedAsArchives() {
        listOf(301, 403, 404, 416, 429, 500, 503).forEach { status ->
            assertThrows(IOException::class.java) { downloader.validateResponse(status, null, 4096) }
        }
    }
}
