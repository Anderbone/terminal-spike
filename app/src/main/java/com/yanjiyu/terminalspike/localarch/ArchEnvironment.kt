package com.yanjiyu.terminalspike.localarch

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

internal enum class ArchInstallPhase { IDLE, CHECKING, DOWNLOADING, EXTRACTING, VALIDATING, INSTALLING_TOOLS, REMOVING }

internal data class ArchEnvironmentState(
    val supported: Boolean,
    val checked: Boolean = false,
    val installed: Boolean = false,
    val starterToolsInstalled: Boolean = false,
    val version: String? = null,
    val storageBytes: Long? = null,
    val phase: ArchInstallPhase = ArchInstallPhase.IDLE,
    val downloadedBytes: Long = 0,
    val error: String? = null,
) {
    val busy: Boolean get() = phase != ArchInstallPhase.IDLE
}

/** Disk/installation owner. Application-owned callers keep operations alive across UI recreation. */
internal class ArchEnvironment(
    context: Context,
    private val directory: File = File(context.applicationContext.filesDir, "local-arch"),
    private val temporaryDirectory: File = File(context.applicationContext.filesDir, "arch-tmp"),
) {
    private val appContext = context.applicationContext
    private val store by lazy { ArchEnvironmentStore(directory) }
    private val bootstrap = ArchBootstrap(File(appContext.applicationInfo.nativeLibraryDir))
    private val operationMutex = Mutex()
    private val mutableState = MutableStateFlow(ArchEnvironmentState(
        supported = Process.is64Bit() && "arm64-v8a" in Build.SUPPORTED_ABIS,
    ))
    val state = mutableState.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        if (!operationMutex.tryLock()) return@withContext
        try {
            publishDiskState()
        } catch (_: IOException) {
            mutableState.update { it.copy(checked = true, error = "Could not read Local Arch storage") }
        } finally {
            operationMutex.unlock()
        }
    }

    /** A reinstall must have explicit destructive confirmation from the settings UI. */
    suspend fun install(replaceExisting: Boolean = false): ArchEnvironmentStore.Installation = withContext(Dispatchers.IO) {
        check(state.value.supported) { "Local Arch Linux requires an ARM64 Android device" }
        // A Settings storage refresh may already own this lock. Queue the user
        // operation behind it; the repository prevents concurrent destructive actions.
        operationMutex.lock()
        mutableState.update { it.copy(phase = ArchInstallPhase.CHECKING, error = null) }
        try {
            val coroutine = currentCoroutineContext()
            val checkCancelled = { coroutine.ensureActive() }
            val existing = store.installed()
            if (existing != null && !replaceExisting) {
                store.beginOperation().use {
                    bootstrap.refreshDns(existing.rootfs, networkDns())
                    installStarterTools(existing.rootfs, checkCancelled)
                }
                return@withContext existing
            }
            bootstrap.validateRuntime()
            store.beginOperation().use { operation ->
                operation.prepare()
                // Allow for archive, extracted tree, filesystem overhead and keyring initialization.
                val needed = ArchRootfsManifest.DOWNLOAD_BYTES - store.downloadFile.length() +
                    ArchRootfsManifest.MAX_EXPANDED_BYTES + 2L * 1024 * 1024 * 1024
                if (directory.usableSpace < needed) throw IOException("Free at least 4.3 GiB of internal storage, then retry")
                mutableState.update { it.copy(phase = ArchInstallPhase.DOWNLOADING) }
                val archive = ArchRootfsDownloader().download(store.downloadFile, checkCancelled) { received ->
                    // Metadata only. This flow is unrelated to terminal rows or renderer frames.
                    val old = state.value.downloadedBytes
                    if (received == ArchRootfsManifest.DOWNLOAD_BYTES || received < old || received - old >= 512 * 1024) {
                        mutableState.update { it.copy(downloadedBytes = received) }
                    }
                }
                mutableState.update { it.copy(phase = ArchInstallPhase.EXTRACTING) }
                ArchRootfsExtractor().extract(archive, operation.rootfs, checkCancelled)
                checkCancelled()
                mutableState.update { it.copy(phase = ArchInstallPhase.VALIDATING) }
                bootstrap.configure(operation.rootfs, networkDns())
                val installed = operation.activate { rootfs ->
                    bootstrap.validate(rootfs, temporaryDirectory, checkCancelled)
                    installStarterTools(rootfs, checkCancelled)
                }
                // Activation has succeeded. Cleanup failure must not invalidate the user's installation.
                runCatching { operation.cleanupInactive() }
                runCatching { Files.deleteIfExists(archive.toPath()) }
                installed
            }
        } catch (cancelled: CancellationException) {
            mutableState.update { it.copy(error = "Installation interrupted. Retry to resume; an existing environment is retained.") }
            throw cancelled
        } catch (failure: Exception) {
            android.util.Log.w("LocalArch", "Installation failed during ${state.value.phase}", failure)
            val message = when (failure) {
                is IllegalStateException, is IllegalArgumentException -> failure.message
                is IOException -> if (failure.message?.startsWith("Free at least") == true) failure.message else null
                else -> null
            } ?: "Arch ${state.value.phase.name.lowercase()} failed. Check the network and internal storage, then retry."
            mutableState.update { it.copy(error = message) }
            throw failure
        } finally {
            runCatching { publishDiskState() }
            mutableState.update { it.copy(phase = ArchInstallPhase.IDLE) }
            operationMutex.unlock()
        }
    }

    private fun installStarterTools(rootfs: File, checkCancelled: () -> Unit) {
        mutableState.update { it.copy(phase = ArchInstallPhase.INSTALLING_TOOLS) }
        val script = appContext.assets.open("local-arch/starter-tools.sh").bufferedReader().use { it.readText() }
        val output = bootstrap.runGuest(rootfs, temporaryDirectory,
            listOf("/bin/bash", "--noprofile", "--norc", "-c", script), 30 * 60_000L,
            checkCancelled = checkCancelled)
        check(output.contains("LOCAL_ARCH_STARTER_TOOLS_OK")) { "Starter tool validation did not finish" }
        // Outside the guest: package/profile paths may legitimately be guest symlinks.
        java.io.FileOutputStream(File(rootfs.parentFile, "starter-tools.version")).use {
            it.write("1\n".toByteArray())
            it.fd.sync()
        }
    }

    suspend fun reset() = withContext(Dispatchers.IO) {
        // A Settings storage refresh may already own this lock. Queue the user
        // operation behind it; the repository prevents concurrent destructive actions.
        operationMutex.lock()
        mutableState.update { it.copy(phase = ArchInstallPhase.REMOVING, error = null) }
        try {
            store.beginOperation().use { it.reset() }
            ArchEnvironmentStore.deleteTree(temporaryDirectory.toPath())
        } catch (failure: Exception) {
            mutableState.update { it.copy(error = if (failure is IllegalStateException) failure.message else
                "Could not remove all Arch files. Close local shells and retry reset.") }
            throw failure
        } finally {
            runCatching { publishDiskState() }
            mutableState.update { it.copy(phase = ArchInstallPhase.IDLE) }
            operationMutex.unlock()
        }
    }

    suspend fun <T> withShell(block: suspend (ShellLease) -> T): T = withContext(Dispatchers.IO) {
        check(state.value.supported) { "Local Arch Linux requires an ARM64 Android device" }
        bootstrap.validateRuntime()
        val lease = store.acquire()
        try {
            // Offline launch remains supported. User-modified DNS is left intact.
            runCatching { bootstrap.refreshDns(lease.installation.rootfs, networkDns()) }
            block(ShellLease(lease, bootstrap.command(lease.installation.rootfs, temporaryDirectory)))
        } finally {
            lease.close()
        }
    }

    internal class ShellLease(val lease: ArchEnvironmentStore.Lease, val command: ProotCommand) : java.io.Closeable {
        override fun close() = lease.close()
    }

    private fun networkDns(): List<String> {
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
        val network = connectivity.activeNetwork ?: return emptyList()
        return connectivity.getLinkProperties(network)?.dnsServers.orEmpty()
            .mapNotNull { it.hostAddress?.substringBefore('%') }.distinct().take(16)
    }

    private fun publishDiskState() {
        val installed = store.installed()
        val bytes = runCatching {
            ArchEnvironmentStore.storageBytes(directory.toPath()) +
                ArchEnvironmentStore.storageBytes(temporaryDirectory.toPath())
        }.getOrNull()
        mutableState.update { it.copy(checked = true, installed = installed != null,
            version = installed?.version, storageBytes = bytes,
            starterToolsInstalled = installed?.let {
                val marker = File(it.rootfs.parentFile, "starter-tools.version")
                marker.isFile && marker.length() == 2L && marker.readText() == "1\n"
            } ?: false) }
    }
}
