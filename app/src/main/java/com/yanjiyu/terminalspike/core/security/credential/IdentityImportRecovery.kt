package com.yanjiyu.terminalspike.core.security.credential

/** Device-local recovery metadata; it never contains key material, labels or document URIs. */
internal data class IdentityImportRecoveryState(
    val incomplete: Boolean = false,
    val needsReselection: Boolean = false,
)

internal interface IdentityImportRecoveryStore {
    fun read(): IdentityImportRecoveryState
    /** Returns only after persistence succeeds; failure must throw. */
    fun write(state: IdentityImportRecoveryState)
}

internal enum class IdentityImportRecoveryIssue {
    READ_MARKER, READ_GRANTS, RELEASE_GRANT, WRITE_MARKER,
}

/**
 * One owner per application process. Current persisted read grants belong only to key imports.
 * All live imports share the grant lifetime, so completing one cannot revoke another's grant.
 * Recovery releases old grants and requests reselection; it never resumes an import.
 */
internal class IdentityImportRecovery(
    private val store: IdentityImportRecoveryStore,
    private val persistedReadGrants: () -> Set<String>,
    private val releaseGrant: (String) -> Unit,
    private val onIssue: (IdentityImportRecoveryIssue) -> Unit = {},
) {
    @Volatile
    private var state = try {
        store.read()
    } catch (_: Exception) {
        onIssue(IdentityImportRecoveryIssue.READ_MARKER)
        IdentityImportRecoveryState(incomplete = true, needsReselection = true)
    }
    private val active = mutableSetOf<Any>()

    init {
        val grants = readGrants()
        if (state.incomplete || grants == null || grants.isNotEmpty()) {
            saveBestEffort(
                IdentityImportRecoveryState(
                    incomplete = grants == null || !releaseAll(grants),
                    needsReselection = true,
                ),
            )
        }
    }

    val needsReselection: Boolean get() = state.needsReselection

    /** Call on IO, before acquiring any grant or reading any key bytes. */
    @Synchronized
    fun begin(): AutoCloseable {
        val pending = state.copy(incomplete = true)
        try {
            store.write(pending)
        } catch (error: Exception) {
            onIssue(IdentityImportRecoveryIssue.WRITE_MARKER)
            throw error
        }
        state = pending
        val token = Any()
        active += token
        return AutoCloseable { finish(token) }
    }

    @Synchronized
    fun requireReselection() {
        saveBestEffort(state.copy(needsReselection = true))
    }

    @Synchronized
    fun acknowledgeReselection() {
        saveBestEffort(state.copy(needsReselection = false))
    }

    @Synchronized
    private fun finish(token: Any) {
        if (!active.remove(token) || active.isNotEmpty()) return
        val grants = readGrants()
        saveBestEffort(state.copy(incomplete = grants == null || !releaseAll(grants)))
    }

    private fun readGrants(): Set<String>? = try {
        persistedReadGrants().toSet()
    } catch (_: Exception) {
        onIssue(IdentityImportRecoveryIssue.READ_GRANTS)
        null
    }

    private fun releaseAll(grants: Set<String>): Boolean {
        var complete = true
        for (uri in grants) {
            try {
                releaseGrant(uri)
            } catch (_: Exception) {
                complete = false
                // Provider exceptions may contain private URIs; report only a fixed issue code.
                onIssue(IdentityImportRecoveryIssue.RELEASE_GRANT)
            }
        }
        return complete
    }

    private fun saveBestEffort(next: IdentityImportRecoveryState) {
        state = next
        try {
            store.write(next)
        } catch (_: Exception) {
            // A failed cleanup/ack write may repeat the prompt next process; it must not
            // replace an import result or turn a completion callback into an uncaught error.
            onIssue(IdentityImportRecoveryIssue.WRITE_MARKER)
        }
    }
}
