package com.yanjiyu.terminalspike.connection

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpATTRS
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class SftpFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedAtEpochSeconds: Long,
)

internal interface SftpDownloadDestination {
    fun createDirectory(relativePath: String)
    fun openFile(relativePath: String): SftpPendingDownload
    fun complete()
    fun abort()
}

internal interface SftpPendingDownload {
    val stream: OutputStream
    fun commit()
    fun abort()
}

internal data class SftpUploadEntry(
    val relativePath: String,
    val isDirectory: Boolean,
    val open: (() -> InputStream)? = null,
)

internal interface SftpUploadSource {
    val rootName: String
    suspend fun consume(consumer: suspend (SftpUploadEntry) -> Unit)
}

internal interface SftpSession : AutoCloseable {
    suspend fun connect(
        config: SshConnectionConfig,
        onHostIdentityPrompt: (HostIdentityPrompt) -> Unit,
        onKeyboardInteractiveChallenge: (KeyboardInteractiveChallenge) -> Unit,
    ): String

    fun list(path: String): List<SftpFile>
    fun createDirectory(parent: String, name: String)
    fun rename(path: String, newName: String)
    suspend fun delete(path: String)
    fun move(path: String, destinationDirectory: String)
    suspend fun copy(path: String, destinationDirectory: String)
    fun upload(parent: String, name: String, source: InputStream)
    fun download(path: String, destination: OutputStream)
    suspend fun downloadRecursively(path: String, destination: SftpDownloadDestination)
    suspend fun uploadRecursively(parent: String, source: SftpUploadSource)
    fun answerHostIdentityPrompt(token: Long, decision: HostIdentityDecision)
    fun answerKeyboardInteractive(token: Long, responses: List<CharArray>): Boolean
    fun cancelKeyboardInteractive(token: Long): Boolean
}

internal class SftpClient(
    knownHostManager: KnownHostManager,
    private val traversalLimits: SftpTraversalLimits = SftpTraversalLimits(),
) : SftpSession {
    private val sessionFactory = JschAuthenticatedSessionFactory(knownHostManager)
    private var lease: AuthenticatedJschSession? = null
    private var channel: ChannelSftp? = null

    override suspend fun connect(
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

    override fun list(path: String): List<SftpFile> {
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

    override fun createDirectory(parent: String, name: String) {
        requireValidName(name)
        requireChannel().mkdir(childPath(normalizeAbsolutePath(parent), name))
    }

    override fun rename(path: String, newName: String) {
        requireValidName(newName)
        val source = normalizeAbsolutePath(path)
        require(source != "/") { "The remote root cannot be renamed." }
        requireChannel().rename(source, childPath(parentPath(source), newName))
    }

    override suspend fun delete(path: String) {
        val target = normalizeAbsolutePath(path)
        require(target != "/") { "The remote root cannot be deleted." }
        deleteTree(target, traversalLimits)
    }

    override fun move(path: String, destinationDirectory: String) {
        val source = normalizeAbsolutePath(path)
        val destination = uniqueDestination(
            childPath(normalizeAbsolutePath(destinationDirectory), fileName(source)),
        )
        requireValidTransfer(source, destination)
        requireChannel().rename(source, destination)
    }

    override suspend fun copy(path: String, destinationDirectory: String) {
        val source = normalizeAbsolutePath(path)
        val destination = uniqueDestination(
            childPath(normalizeAbsolutePath(destinationDirectory), fileName(source)),
        )
        requireValidTransfer(source, destination)
        val sourceAttributes = attributes(source)
        if (!sourceAttributes.isDir) {
            copyFileAtomically(source, destination)
            return
        }
        val temporaryRoot = uniqueTemporarySibling(destination)
        atomicRemoteDirectoryWrite(
            temporaryPath = temporaryRoot,
            finalPath = destination,
            createTemporary = { requireChannel().mkdir(it) },
            populate = {
                val root = RemoteCopyNode(source, temporaryRoot, isDirectory = true)
                walkSftpTree(
                    root = root.toTreeNode(),
                    limits = traversalLimits,
                    children = { node ->
                        list(node.source).map { child ->
                            RemoteCopyNode(
                                source = child.path,
                                destination = childPath(node.destination, child.name),
                                isDirectory = child.isDirectory,
                            ).toTreeNode()
                        }
                    },
                    onEnter = { node, depth ->
                        currentCoroutineContext().ensureActive()
                        when {
                            depth == 0 -> Unit
                            node.isDirectory -> requireChannel().mkdir(node.destination)
                            else -> copyFile(node.source, node.destination)
                        }
                    },
                )
            },
            rename = { from, to -> requireChannel().rename(from, to) },
            cleanupTemporary = ::cleanupOwnedTree,
        )
    }

    override fun upload(parent: String, name: String, source: InputStream) {
        requireValidName(name)
        val destination = uniqueDestination(childPath(normalizeAbsolutePath(parent), name))
        val temporary = uniqueTemporarySibling(destination)
        atomicRemoteFileWrite(
            temporaryPath = temporary,
            finalPath = destination,
            write = { path -> source.uploadTo(path) { input, target ->
                requireChannel().put(input, target)
            } },
            rename = { from, to -> requireChannel().rename(from, to) },
            removeTemporary = { requireChannel().rm(it) },
        )
    }

    override fun download(path: String, destination: OutputStream) {
        val target = normalizeAbsolutePath(path)
        require(!attributes(target).isDir) { "Choose a file to download." }
        destination.use { output -> requireChannel().get(target, output) }
    }

    override suspend fun downloadRecursively(path: String, destination: SftpDownloadDestination) {
        val target = normalizeAbsolutePath(path)
        val rootName = fileName(target)
        require(rootName.isNotBlank()) { "The remote root cannot be downloaded." }
        val rootAttributes = attributes(target)
        val root = RemoteDownloadNode(target, rootName, rootAttributes.isDir)
        withDownloadDestination(destination) {
            walkSftpTree(
                root = root.toTreeNode(),
                limits = traversalLimits,
                children = { node ->
                    list(node.source).map { child ->
                        RemoteDownloadNode(
                            source = child.path,
                            relativePath = "${node.relativePath}/${child.name}",
                            isDirectory = child.isDirectory,
                        ).toTreeNode()
                    }
                },
                onEnter = { node, _ ->
                    currentCoroutineContext().ensureActive()
                    if (node.isDirectory) {
                        destination.createDirectory(node.relativePath)
                    } else {
                        val pending = destination.openFile(node.relativePath)
                        writePendingDownload(pending) { output ->
                            requireChannel().get(node.source, output)
                        }
                    }
                },
            )
        }
    }

    override suspend fun uploadRecursively(
        parent: String,
        source: SftpUploadSource,
    ) {
        requireValidName(source.rootName)
        val destinationRoot = uniqueDestination(
            childPath(normalizeAbsolutePath(parent), source.rootName),
        )
        val temporaryRoot = uniqueTemporarySibling(destinationRoot)
        var entryCount = 1
        atomicRemoteDirectoryWrite(
            temporaryPath = temporaryRoot,
            finalPath = destinationRoot,
            createTemporary = { requireChannel().mkdir(it) },
            populate = {
                source.consume { entry ->
                    currentCoroutineContext().ensureActive()
                    val relativeSegments = requireValidRelativePath(entry.relativePath)
                    require(relativeSegments.size <= traversalLimits.maxDepth) {
                        "The selected folder is nested too deeply."
                    }
                    entryCount += 1
                    require(entryCount <= traversalLimits.maxEntries) {
                        "The selected folder contains too many items."
                    }
                    val destination = relativeSegments.fold(temporaryRoot, ::childPath)
                    if (entry.isDirectory) {
                        requireChannel().mkdir(destination)
                    } else {
                        val open = requireNotNull(entry.open) {
                            "Cannot read ${entry.relativePath}."
                        }
                        open().use { input -> requireChannel().put(input, destination) }
                    }
                }
            },
            rename = { from, to -> requireChannel().rename(from, to) },
            cleanupTemporary = ::cleanupOwnedTree,
        )
    }

    override fun answerHostIdentityPrompt(token: Long, decision: HostIdentityDecision) {
        // The repository lease owns the prompt, but the verifying repository is deliberately
        // hidden behind the factory. The manager routes by the token-specific repository callback.
        activePromptAnswer?.invoke(token, decision)
    }

    private var activePromptAnswer: ((Long, HostIdentityDecision) -> Unit)? = null

    override fun answerKeyboardInteractive(token: Long, responses: List<CharArray>): Boolean =
        lease?.answerKeyboardInteractiveChallenge(token, responses) ?: false.also {
            responses.forEach { response -> response.fill('\u0000') }
        }

    override fun cancelKeyboardInteractive(token: Long): Boolean =
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

    private suspend fun deleteTree(path: String, limits: SftpTraversalLimits) {
        val rootAttributes = attributes(path)
        val root = RemotePathNode(path, rootAttributes.isDir)
        walkSftpTree(
            root = root.toTreeNode(),
            limits = limits,
            children = { node ->
                list(node.path).map { child ->
                    RemotePathNode(child.path, child.isDirectory).toTreeNode()
                }
            },
            onEnter = { node, _ ->
                if (!node.isDirectory) requireChannel().rm(node.path)
            },
            onLeaveDirectory = { node, _ -> requireChannel().rmdir(node.path) },
        )
    }

    private fun copyFileAtomically(source: String, destination: String) {
        val temporary = uniqueTemporarySibling(destination)
        atomicRemoteFileWrite(
            temporaryPath = temporary,
            finalPath = destination,
            write = { copyFile(source, it) },
            rename = { from, to -> requireChannel().rename(from, to) },
            removeTemporary = { requireChannel().rm(it) },
        )
    }

    private fun copyFile(source: String, destination: String) {
        // One ChannelSftp cannot service a download and upload concurrently: put() waits for bytes
        // while get() needs the same protocol channel. A second channel streams without buffering.
        val reader = requireNotNull(lease).session.openChannel("sftp") as ChannelSftp
        try {
            reader.connect(CHANNEL_TIMEOUT_MILLIS)
            reader.get(source).use { input -> requireChannel().put(input, destination) }
        } finally {
            runCatching { reader.disconnect() }
        }
    }

    private suspend fun cleanupOwnedTree(path: String) {
        withContext(NonCancellable) {
            try {
                deleteTree(path, traversalLimits)
            } catch (_: Throwable) {
                // Preserve the original failure. Cleanup targets only this operation's random
                // temporary sibling, never a selected or pre-existing final name.
            }
        }
    }

    private fun uniqueTemporarySibling(finalPath: String): String {
        val parent = parentPath(finalPath)
        repeat(MAX_TEMP_NAME_ATTEMPTS) {
            val candidate = childPath(parent, ".terminal-spike-${UUID.randomUUID()}.part")
            if (!exists(candidate)) return candidate
        }
        error("Cannot allocate a temporary remote transfer name.")
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
        const val MAX_TEMP_NAME_ATTEMPTS = 8
    }
}

private data class RemotePathNode(val path: String, val isDirectory: Boolean) {
    fun toTreeNode() = SftpTreeNode(
        value = this,
        isDirectory = isDirectory,
        directoryIdentity = normalizeAbsolutePath(path).takeIf { isDirectory },
    )
}

private data class RemoteCopyNode(
    val source: String,
    val destination: String,
    val isDirectory: Boolean,
) {
    fun toTreeNode() = SftpTreeNode(
        value = this,
        isDirectory = isDirectory,
        directoryIdentity = normalizeAbsolutePath(source).takeIf { isDirectory },
    )
}

private data class RemoteDownloadNode(
    val source: String,
    val relativePath: String,
    val isDirectory: Boolean,
) {
    fun toTreeNode() = SftpTreeNode(
        value = this,
        isDirectory = isDirectory,
        directoryIdentity = normalizeAbsolutePath(source).takeIf { isDirectory },
    )
}

internal fun normalizeAbsolutePath(path: String): String {
    val segments = ArrayDeque<String>()
    path.split('/').forEach { segment ->
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

internal fun fileName(path: String): String = normalizeAbsolutePath(path).substringAfterLast('/')

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
