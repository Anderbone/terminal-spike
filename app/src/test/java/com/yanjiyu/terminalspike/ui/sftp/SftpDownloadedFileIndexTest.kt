package com.yanjiyu.terminalspike.ui.sftp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SftpDownloadedFileIndexTest {
    @Test fun completedDownloadsSurviveIndexRecreationWithoutRemoteAccess() {
        val storage = mutableMapOf<String, String>()
        fun index() = SftpDownloadedFileIndex(storage::get, { key, item ->
            if (item == null) storage.remove(key) else storage[key] = item
        }, { it == "local-file" })
        index().remember("remote-file", "local-file")
        assertEquals("local-file", index().find("remote-file"))
    }

    @Test fun deletedDownloadsAreForgotten() {
        val storage = mutableMapOf("remote-file" to "deleted")
        val index = SftpDownloadedFileIndex(storage::get, { key, _ -> storage.remove(key) }, { false })
        assertNull(index.find("remote-file"))
        assertNull(storage["remote-file"])
    }

    @Test fun remoteIdentitySeparatesHostsPortsUsersAndFullPaths() {
        val endpoint = listOf("host", "22", "user")
        val key = SftpDownloadedFileIndex.key(endpoint, "/first/report.txt")
        for (other in listOf(listOf("other", "22", "user"), listOf("host", "2222", "user"),
            listOf("host", "22", "other"))) {
            assertNotEquals(key, SftpDownloadedFileIndex.key(other, "/first/report.txt"))
        }
        assertNotEquals(key, SftpDownloadedFileIndex.key(endpoint, "/second/report.txt"))
    }
}
