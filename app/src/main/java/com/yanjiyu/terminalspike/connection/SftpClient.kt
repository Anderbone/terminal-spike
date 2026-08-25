package com.yanjiyu.terminalspike.connection

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpATTRS
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

internal data class SftpFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedAtEpochSeconds: Long,
)

internal interface SftpDownloadDestination {
    fun createDirectory(relativePath: String)
    fun openFile(relativePath: String): OutputStream
}

internal data class SftpUploadEntry(
    val relativePath: String,
    val isDirectory: Boolean,
    val open: (() -> InputStream)? = null,
)

internal class SftpClient(
    knownHostManager: KnownHostManager,
) : AutoCloseable {
    private val sessionFactory = JschAuthenticatedSessionFactory(knownHostManager)
    private var lease: AuthenticatedJschSession? = null
    private var channel: ChannelSftp? = null

    suspend fun connect(
        config: SshConnectionConfig,
        onHostIdentityPrompt: (HostIdentityPrompt) -> Unit,
        onKeyboardInteractiveChallenge: (KeyboardInteractiveChallenge) -> Unit,
    ): String {
        close()
        val opened = sessionFactory.connect(
            config = config,
            onPrompt = onHostIdentityPrompt,
            onKeyboardInteractiveChallenge = onKeyboardInteractiveChallenge,
            onRepositoryReady = { repository ->
                activePromptAnswer = { token, decision -> repository.answerPrompt(token, decision) }
            },
            registerBeforeConnect = { candidate ->
                lease = candidate
                true
            },
        ) ?: error("SFTP connection was cancelled.")
        val openedChannel = opened.session.openChannel("sftp") as ChannelSftp
        try {
            openedChannel.connect(CHANNEL_TIMEOUT_MILLIS)
            channel = openedChannel
            return normalizeAbsolutePath(openedChannel.pwd())
        } catch (error: Throwable) {
            runCatching { openedChannel.disconnect() }
            close()
            throw error
        }
    }

    fun list(path: String): List<SftpFile> {
        val absolute = normalizeAbsolutePath(path)
        @Suppress("UNCHECKED_CAST")
        val entries = requireChannel().ls(absolute) as java.util.Vector<ChannelSftp.LsEntry>
        return entries.asSequence()
            .filterNot { it.filename == "." || it.filename == ".." }
            .map { entry -> entry.toFile(childPath(absolute, entry.filename)) }
            .sortedWith(compareByDescending<SftpFile>(SftpFile::isDirectory).thenBy {
                it.name.lowercase(Locale.ROOT)
            })
            .toList()
    }

    fun createDirectory(parent: String, name: String) {
        requireValidName(name)
        requireChannel().mkdir(childPath(normalizeAbsolutePath(parent), name))
    }

    fun rename(path: String, newName: String) {
        requireValidName(newName)
        val source = normalizeAbsolutePath(path)
        require(source != "/") { "The remote root cannot be renamed." }
        requireChannel().rename(source, childPath(parentPath(source), newName))
    }

    fun delete(path: String) {
        val target = normalizeAbsolutePath(path)
        require(target != "/") { "The remote root cannot be deleted." }
        deleteRecursively(target)
    }

    fun move(path: String, destinationDirectory: String) {
        val source = normalizeAbsolutePath(path)
        val destination = uniqueDestination(
            childPath(normalizeAbsolutePath(destinationDirectory), fileName(source)),
        )
        requireValidTransfer(source, destination)
        requireChannel().rename(source, destination)
    }

    fun copy(path: String, destinationDirectory: String) {
        val source = normalizeAbsolutePath(path)
        val destination = uniqueDestination(
            childPath(normalizeAbsolutePath(destinationDirectory), fileName(source)),
        )
        requireValidTransfer(source, destination)
        copyRecursively(source, destination)
    }

    fun upload(parent: String, name: String, source: InputStream) {
        requireValidName(name)
        val destination = uniqueDestination(childPath(normalizeAbsolutePath(parent), name))
        source.use { input -> requireChannel().put(input, destination) }
    }

    fun download(path: String, destination: OutputStream) {
        val target = normalizeAbsolutePath(path)
        require(!attributes(target).isDir) { "Choose a file to download." }
        destination.use { output -> requireChannel().get(target, output) }
    }

    fun downloadRecursively(path: String, destination: SftpDownloadDestination) {
        val target = normalizeAbsolutePath(path)
        val rootName = fileName(target)
        require(rootName.isNotBlank()) { "The remote root cannot be downloaded." }
        downloadRecursively(target, rootName, destination)
    }

    fun uploadRecursively(parent: String, rootName: String, entries: List<SftpUploadEntry>) {
        requireValidName(rootName)
        val destinationRoot = uniqueDestination(childPath(normalizeAbsolutePath(parent), rootName))
        require(entries.isNotEmpty()) { "The selected folder is empty or cannot be read." }
        require(entries.first().relativePath.isEmpty() && entries.first().isDirectory) {
            "The local folder transfer is invalid."
        }
        var rootCreated = false
        try {
            requireChannel().mkdir(destinationRoot)
            rootCreated = true
            entries.drop(1).forEach { entry ->
                val relativeSegments = requireValidRelativePath(entry.relativePath)
                val destination = relativeSegments.fold(destinationRoot, ::childPath)
                if (entry.isDirectory) {
                    requireChannel().mkdir(destination)
                } else {
                    val source = requireNotNull(entry.open) { "Cannot read ${entry.relativePath}." }
                    source().use { input -> requireChannel().put(input, destination) }
                }
            }
        } catch (error: Throwable) {
            if (rootCreated) runCatching { deleteRecursively(destinationRoot) }
            throw error
        }
    }

    fun answerHostIdentityPrompt(token: Long, decision: HostIdentityDecision) {
        // The repository lease owns the prompt, but the verifying repository is deliberately
        // hidden behind the factory. The manager routes by the token-specific repository callback.
        activePromptAnswer?.invoke(token, decision)
    }

    private var activePromptAnswer: ((Long, HostIdentityDecision) -> Unit)? = null

    fun answerKeyboardInteractive(token: Long, responses: List<CharArray>): Boolean =
        lease?.answerKeyboardInteractiveChallenge(token, responses) ?: false.also {
            responses.forEach { response -> response.fill('\u0000') }
        }

    fun cancelKeyboardInteractive(token: Long): Boolean =
        lease?.cancelKeyboardInteractiveChallenge(token) ?: false

    override fun close() {
        activePromptAnswer = null
        runCatching { channel?.disconnect() }
        channel = null
        lease?.close()
        lease = null
    }

    private fun requireChannel(): ChannelSftp = channel?.takeIf { it.isConnected }
        ?: error("SFTP is not connected.")

    private fun attributes(path: String): SftpATTRS = requireChannel().lstat(path)

    private fun deleteRecursively(path: String) {
        if (!attributes(path).isDir) {
            requireChannel().rm(path)
            return
        }
        list(path).forEach { deleteRecursively(it.path) }
        requireChannel().rmdir(path)
    }

    private fun copyRecursively(source: String, destination: String) {
        if (!attributes(source).isDir) {
            // One ChannelSftp cannot service a download and upload concurrently: put() waits for
            // bytes while get() needs the same protocol channel to receive them. A second SFTP
            // channel on the already authenticated SSH session provides bounded streaming without
            // buffering the whole remote file in app memory or invoking a remote shell command.
            val reader = requireNotNull(lease).session.openChannel("sftp") as ChannelSftp
            try {
                reader.connect(CHANNEL_TIMEOUT_MILLIS)
                reader.get(source).use { input -> requireChannel().put(input, destination) }
            } finally {
                runCatching { reader.disconnect() }
            }
            return
        }
        requireChannel().mkdir(destination)
        list(source).forEach { child ->
            copyRecursively(child.path, childPath(destination, child.name))
        }
    }

    private fun downloadRecursively(
        source: String,
        relativePath: String,
        destination: SftpDownloadDestination,
    ) {
        if (!attributes(source).isDir) {
            destination.openFile(relativePath).use { output -> requireChannel().get(source, output) }
            return
        }
        destination.createDirectory(relativePath)
        list(source).forEach { child ->
            downloadRecursively(child.path, "$relativePath/${child.name}", destination)
        }
    }

    private fun uniqueDestination(candidate: String): String {
        if (!exists(candidate)) return candidate
        val parent = parentPath(candidate)
        val original = fileName(candidate)
        val dot = original.lastIndexOf('.').takeIf { it > 0 } ?: original.length
        val base = original.substring(0, dot)
        val extension = original.substring(dot)
        var suffix = 1
        while (true) {
            val next = childPath(parent, "$base copy${if (suffix == 1) "" else " $suffix"}$extension")
            if (!exists(next)) return next
            suffix += 1
        }
    }

    private fun exists(path: String): Boolean = runCatching { attributes(path) }.isSuccess

    private companion object {
        const val CHANNEL_TIMEOUT_MILLIS = 15_000
    }
}

internal fun normalizeAbsolutePath(path: String): String {
    val segments = ArrayDeque<String>()
    path.replace('\\', '/').split('/').forEach { segment ->
        when (segment) {
            "", "." -> Unit
            ".." -> if (segments.isNotEmpty()) segments.removeLast()
            else -> segments.addLast(segment)
        }
    }
    return "/" + segments.joinToString("/")
}

internal fun parentPath(path: String): String {
    val normalized = normalizeAbsolutePath(path)
    return normalized.substringBeforeLast('/', missingDelimiterValue = "").ifEmpty { "/" }
}

internal fun childPath(parent: String, name: String): String {
    requireValidName(name)
    val normalizedParent = normalizeAbsolutePath(parent)
    return if (normalizedParent == "/") "/$name" else "$normalizedParent/$name"
}

private fun fileName(path: String): String = normalizeAbsolutePath(path).substringAfterLast('/')

private fun requireValidName(name: String) {
    require(name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\u0000' !in name) {
        "Enter a valid file name."
    }
}

private fun requireValidRelativePath(path: String): List<String> {
    val segments = path.split('/')
    require(path.isNotBlank() && segments.none { it.isBlank() }) { "The local path is invalid." }
    segments.forEach(::requireValidName)
    return segments
}

private fun requireValidTransfer(source: String, destination: String) {
    require(source != "/") { "The remote root cannot be moved or copied." }
    require(source != destination) { "Source and destination are the same." }
    require(!destination.startsWith("$source/")) { "A folder cannot be placed inside itself." }
}

private fun ChannelSftp.LsEntry.toFile(path: String): SftpFile = SftpFile(
    name = filename,
    path = path,
    isDirectory = attrs.isDir,
    size = attrs.size,
    modifiedAtEpochSeconds = attrs.mTime.toLong(),
)
