package com.yanjiyu.terminalspike.connection

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SftpRealEndToEndTest {
    @Test
    fun liveSshTransportUploadsAPastedImageThroughItsSideChannel() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("terminalSpikeRunRealSftp") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val knownHosts = context.cacheDir.resolve("real-image-paste-known-hosts").apply { delete() }
        val config = SshConnectionConfig(
            host = arguments.getString("terminalSpikeSftpHost") ?: "127.0.0.1",
            port = arguments.getString("terminalSpikeSftpPort")?.toIntOrNull() ?: 22_222,
            username = "terminal",
            authentication = SshAuthentication.Password(
                "terminal-spike-test-only".encodeToByteArray(),
            ),
        )
        val connection = JschSshConnection(KnownHostManager { knownHosts }, config)
        val connected = CompletableDeferred<Unit>()
        val connectJob = launch(Dispatchers.IO) {
            connection.connect(
                columns = 80,
                rows = 24,
                onBytes = {},
                onState = { state ->
                    when (state) {
                        is ConnectionState.AwaitingApproval -> {
                            val prompt = state.prompt as? HostIdentityPrompt
                            if (prompt != null) {
                                connection.answerHostIdentityPrompt(
                                    prompt.promptToken,
                                    HostIdentityDecision.TrustOnce,
                                )
                            }
                        }
                        ConnectionState.Connected -> connected.complete(Unit)
                        is ConnectionState.Failed -> connected.completeExceptionally(
                            AssertionError(state.message),
                        )
                        else -> Unit
                    }
                },
            )
        }
        val payload = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 1, 2, 3)
        try {
            withTimeout(20_000) { connected.await() }
            val uploadedPath = withContext(Dispatchers.IO) {
                connection.uploadPastedImage(
                    "${UUID.randomUUID()}.png",
                    ByteArrayInputStream(payload),
                )
            }
            assertTrue(uploadedPath.startsWith("/home/terminal/.cache/terminal-spike/pasted-images/"))

            val verifier = SftpClient(KnownHostManager { knownHosts })
            try {
                verifier.connect(
                    config = SshConnectionConfig(
                        host = config.host,
                        port = config.port,
                        username = config.username,
                        authentication = SshAuthentication.Password(
                            "terminal-spike-test-only".encodeToByteArray(),
                        ),
                    ),
                    onHostIdentityPrompt = {
                        verifier.answerHostIdentityPrompt(it.promptToken, HostIdentityDecision.TrustOnce)
                    },
                    onKeyboardInteractiveChallenge = { error("Unexpected challenge: $it") },
                )
                val output = ByteArrayOutputStream()
                verifier.download(uploadedPath, output)
                assertArrayEquals(payload, output.toByteArray())
                verifier.delete(uploadedPath)
            } finally {
                verifier.close()
            }
        } finally {
            connection.close()
            connectJob.join()
            knownHosts.delete()
        }
    }

    @Test
    fun createsTransfersCopiesMovesDownloadsAndDeletesAgainstOpenSsh() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("terminalSpikeRunRealSftp") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val knownHosts = context.cacheDir.resolve("real-sftp-known-hosts").apply { delete() }
        val client = SftpClient(KnownHostManager { knownHosts })
        val payload = "Terminal Spike SFTP end-to-end".encodeToByteArray()
        var promptReceived = false
        try {
            val home = client.connect(
                config = SshConnectionConfig(
                    host = arguments.getString("terminalSpikeSftpHost") ?: "127.0.0.1",
                    port = arguments.getString("terminalSpikeSftpPort")?.toIntOrNull() ?: 22_222,
                    username = "terminal",
                    authentication = SshAuthentication.Password(
                        "terminal-spike-test-only".encodeToByteArray(),
                    ),
                ),
                onHostIdentityPrompt = {
                    promptReceived = true
                    client.answerHostIdentityPrompt(it.promptToken, HostIdentityDecision.TrustOnce)
                },
                onKeyboardInteractiveChallenge = { error("Unexpected challenge: $it") },
            )
            assertTrue(promptReceived)
            val testFolder = "terminal-spike-sftp-${System.currentTimeMillis()}"
            client.createDirectory(home, testFolder)
            val folderPath = childPath(home, testFolder)
            client.upload(folderPath, "source.txt", ByteArrayInputStream(payload))
            client.copy(childPath(folderPath, "source.txt"), folderPath)
            assertTrue(client.list(folderPath).any { it.name == "source copy.txt" })

            client.createDirectory(folderPath, "moved")
            client.move(childPath(folderPath, "source copy.txt"), childPath(folderPath, "moved"))
            val movedPath = childPath(childPath(folderPath, "moved"), "source copy.txt")
            val output = ByteArrayOutputStream()
            client.download(movedPath, output)
            assertArrayEquals(payload, output.toByteArray())

            client.rename(movedPath, "renamed.txt")
            client.delete(folderPath)
            assertFalse(client.list(home).any { it.name == testFolder })
        } finally {
            client.close()
            knownHosts.delete()
        }
    }
}
