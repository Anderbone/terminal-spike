package com.yanjiyu.terminalspike.connection

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64

internal data class KnownHost(
    val host: String,
    val algorithm: String,
    val key: ByteArray,
)

internal enum class FirstHostKeyTrustResult {
    STORED,
    ALREADY_TRUSTED,
    CHANGED,
}

internal class KnownHostStore(
    private val file: File,
) {
    private val entries = LinkedHashMap<Pair<String, String>, KnownHost>()

    init {
        load()
    }

    @Synchronized
    fun entries(host: String? = null, algorithm: String? = null): List<KnownHost> =
        entries.values.filter { entry ->
            (host == null || entry.host == host) &&
                (algorithm == null || entry.algorithm == algorithm)
        }

    @Synchronized
    fun trust(host: String, algorithm: String, key: ByteArray) {
        entries[host to algorithm] = KnownHost(host, algorithm, key.copyOf())
        persist()
    }

    @Synchronized
    fun trustFirstKeyIfHostUnchanged(
        host: String,
        algorithm: String,
        key: ByteArray,
    ): FirstHostKeyTrustResult {
        val knownForHost = entries.values.filter { entry -> entry.host == host }
        if (knownForHost.any { entry -> entry.algorithm == algorithm && entry.key.contentEquals(key) }) {
            return FirstHostKeyTrustResult.ALREADY_TRUSTED
        }
        if (knownForHost.isNotEmpty()) return FirstHostKeyTrustResult.CHANGED

        val entryKey = host to algorithm
        entries[entryKey] = KnownHost(host, algorithm, key.copyOf())
        return try {
            persist()
            FirstHostKeyTrustResult.STORED
        } catch (failure: Exception) {
            entries.remove(entryKey)
            throw failure
        }
    }

    /** Legacy-file compatibility only; Room is the authoritative endpoint-wide implementation. */
    @Synchronized
    fun replaceEndpoint(host: String, algorithm: String, key: ByteArray): Int {
        val previous = entries.mapValues { (_, entry) -> entry.copy(key = entry.key.copyOf()) }
        val removed = entries.values.count { entry -> entry.host == host }
        entries.entries.removeAll { (_, entry) -> entry.host == host }
        entries[host to algorithm] = KnownHost(host, algorithm, key.copyOf())
        return try {
            persist()
            removed
        } catch (failure: Exception) {
            entries.clear()
            entries.putAll(previous)
            throw failure
        }
    }

    /** Legacy-file compare-and-replace equivalent of the authoritative Room transaction. */
    @Synchronized
    fun replaceEndpointIfUnchanged(
        host: String,
        expectedKeys: List<KnownHost>,
        algorithm: String,
        key: ByteArray,
    ): Boolean {
        require(expectedKeys.isNotEmpty()) {
            "Changed-key replacement requires a non-empty expected trust snapshot."
        }
        require(expectedKeys.all { expected -> expected.host == host }) {
            "Expected known-host keys must belong to the replacement endpoint."
        }
        val current = entries.values.filter { entry -> entry.host == host }
        val unchanged = current.size == expectedKeys.size && current.all { trusted ->
            expectedKeys.any { expected ->
                expected.algorithm == trusted.algorithm && expected.key.contentEquals(trusted.key)
            }
        }
        if (!unchanged) return false
        replaceEndpoint(host, algorithm, key)
        return true
    }

    @Synchronized
    fun removeEndpoint(host: String): Int {
        val previous = entries.mapValues { (_, entry) -> entry.copy(key = entry.key.copyOf()) }
        val removed = entries.values.count { entry -> entry.host == host }
        if (removed == 0) return 0
        entries.entries.removeAll { (_, entry) -> entry.host == host }
        return try {
            persist()
            removed
        } catch (failure: Exception) {
            entries.clear()
            entries.putAll(previous)
            throw failure
        }
    }

    @Synchronized
    fun remove(host: String, algorithm: String? = null, key: ByteArray? = null) {
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            if (
                entry.host == host &&
                (algorithm == null || entry.algorithm == algorithm) &&
                (key == null || entry.key.contentEquals(key))
            ) {
                iterator.remove()
            }
        }
        persist()
    }

    private fun load() {
        if (!file.isFile) return
        file.useLines(StandardCharsets.UTF_8) { lines ->
            lines.forEach { line ->
                val parts = line.trim().split(WHITESPACE, limit = 3)
                if (parts.size != 3 || parts[0].isBlank() || parts[1].isBlank()) return@forEach
                val key = runCatching { Base64.getDecoder().decode(parts[2]) }.getOrNull() ?: return@forEach
                entries[parts[0] to parts[1]] = KnownHost(parts[0], parts[1], key)
            }
        }
    }

    private fun persist() {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        val contents = buildString {
            entries.values.forEach { entry ->
                append(entry.host)
                    .append(' ')
                    .append(entry.algorithm)
                    .append(' ')
                    .append(Base64.getEncoder().encodeToString(entry.key))
                    .append('\n')
            }
        }
        FileOutputStream(temporary).use { output ->
            output.write(contents.toByteArray(StandardCharsets.UTF_8))
            output.fd.sync()
        }
        try {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")
    }
}

internal object KnownHostStores {
    private val stores = HashMap<String, KnownHostStore>()

    @Synchronized
    fun forFile(file: File): KnownHostStore =
        stores.getOrPut(file.canonicalPath) { KnownHostStore(file) }
}
