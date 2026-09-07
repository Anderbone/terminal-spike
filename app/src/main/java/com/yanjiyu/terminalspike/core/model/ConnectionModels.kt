package com.yanjiyu.terminalspike.core.model

/** Inclusive UDP port range requested from a separately installed Mosh transport. */
data class MoshPortRange(
    val first: Int,
    val last: Int,
) {
    init {
        requirePort(first, "first Mosh port")
        requirePort(last, "last Mosh port")
        require(last >= first) { "Mosh port range must be ascending." }
        require(last - first + 1 <= ModelLimits.MAX_MOSH_PORT_RANGE_SIZE) {
            "Mosh port range is too large."
        }
    }
}

/** Non-secret connection configuration. Secret material is resolved through [credentialId]. */
data class HostProfile(
    val id: String,
    val displayName: String,
    val hostname: String,
    val port: Int,
    val username: String,
    val protocol: ConnectionProtocol,
    /** Null is a visible missing-reference state after a credential has been deliberately removed. */
    val credentialId: String?,
    val terminalProfileId: String? = null,
    val keyboardProfileId: String? = null,
    val isFavorite: Boolean = false,
    val group: String? = null,
    val tag: String? = null,
    val startupCommand: String? = null,
    /** Null inherits the global value, zero disables it, otherwise this is an interval in seconds. */
    val keepaliveIntervalSeconds: Int? = null,
    /** Null inherits the global reconnect policy. */
    val reconnectPolicy: ReconnectPolicy? = null,
    val moshPort: Int? = null,
    val moshPortRange: MoshPortRange? = null,
    val moshServerCommand: String? = null,
    /** Null uses the documented bootstrap default without persisting it as an override. */
    val moshLocale: String? = null,
    val moshFallbackPolicy: MoshFallbackPolicy = MoshFallbackPolicy.NEVER,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    init {
        requireCanonicalUuid(id, "host profile ID")
        requirePlainText(displayName, "display name", ModelLimits.MAX_DISPLAY_NAME_LENGTH)
        requireHost(hostname, "hostname")
        requirePort(port, "SSH port")
        requireUsername(username)
        credentialId?.let { requireCanonicalUuid(it, "credential ID") }
        terminalProfileId?.let { requireCanonicalUuid(it, "terminal profile ID") }
        keyboardProfileId?.let { requireCanonicalUuid(it, "keyboard profile ID") }
        requireOptionalPlainText(group, "group", ModelLimits.MAX_GROUP_LENGTH)
        requireOptionalPlainText(tag, "tag", ModelLimits.MAX_TAG_LENGTH)
        requireOptionalCommand(startupCommand, "startup command", ModelLimits.MAX_COMMAND_LENGTH)
        keepaliveIntervalSeconds?.let { seconds ->
            require(seconds == 0 || seconds in ModelLimits.MIN_KEEPALIVE_SECONDS..ModelLimits.MAX_KEEPALIVE_SECONDS) {
                "Keepalive must be zero or between ${ModelLimits.MIN_KEEPALIVE_SECONDS} and " +
                    "${ModelLimits.MAX_KEEPALIVE_SECONDS} seconds."
            }
        }
        moshPort?.let { requirePort(it, "Mosh port") }
        require(moshPort == null || moshPortRange == null) { "Select either a Mosh port or range, not both." }
        requireOptionalCommand(
            moshServerCommand,
            "Mosh server command",
            ModelLimits.MAX_MOSH_SERVER_COMMAND_LENGTH,
        )
        moshLocale?.let(::requireMoshLocale)
        if (protocol == ConnectionProtocol.SSH) {
            require(
                moshPort == null &&
                    moshPortRange == null &&
                    moshServerCommand == null &&
                    moshLocale == null &&
                    moshFallbackPolicy == MoshFallbackPolicy.NEVER,
            ) {
                "SSH profiles cannot contain Mosh-only settings."
            }
        }
        requireEpochMillis(createdAtEpochMillis, "created timestamp")
        requireEpochMillis(updatedAtEpochMillis, "updated timestamp")
        requireTimestampOrder(createdAtEpochMillis, updatedAtEpochMillis, "updated timestamp")
    }
}

/** Metadata for one reusable SSH authentication choice. No plaintext secret belongs here. */
data class SshCredential(
    val id: String,
    val displayName: String,
    val authentication: SshAuthentication,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    val kind: SshCredentialKind
        get() = authentication.kind

    init {
        requireCanonicalUuid(id, "SSH credential ID")
        requirePlainText(displayName, "credential display name", ModelLimits.MAX_DISPLAY_NAME_LENGTH)
        requireEpochMillis(createdAtEpochMillis, "created timestamp")
        requireEpochMillis(updatedAtEpochMillis, "updated timestamp")
        requireTimestampOrder(createdAtEpochMillis, updatedAtEpochMillis, "updated timestamp")
    }
}

sealed interface SshAuthentication {
    val kind: SshCredentialKind

    /** A null reference means prompt every time; the password itself never enters this model. */
    data class Password(val secretReferenceId: String? = null) : SshAuthentication {
        override val kind: SshCredentialKind = SshCredentialKind.PASSWORD

        init {
            secretReferenceId?.let { requireCanonicalUuid(it, "password secret reference ID") }
        }
    }

    data class PrivateKey(
        val keyIdentityId: String,
        /** Present only when the user explicitly opts to save a passphrase. */
        val passphraseSecretReferenceId: String? = null,
    ) : SshAuthentication {
        override val kind: SshCredentialKind = SshCredentialKind.PRIVATE_KEY

        init {
            requireCanonicalUuid(keyIdentityId, "SSH key identity ID")
            passphraseSecretReferenceId?.let {
                requireCanonicalUuid(it, "passphrase secret reference ID")
            }
        }
    }

    /** A null reference means all keyboard-interactive responses remain session-only. */
    data class KeyboardInteractive(val reusableResponseSecretReferenceId: String? = null) : SshAuthentication {
        override val kind: SshCredentialKind = SshCredentialKind.KEYBOARD_INTERACTIVE

        init {
            reusableResponseSecretReferenceId?.let {
                requireCanonicalUuid(it, "keyboard-interactive secret reference ID")
            }
        }
    }
}

data class SshKeyIdentity(
    val id: String,
    val name: String,
    /** OpenSSH algorithm identifier. Kept open-ended for future compatible algorithms. */
    val algorithm: String,
    val publicKeyFingerprint: String,
    /** Null only for retained legacy metadata whose key payload cannot currently be recovered. */
    val publicKey: String?,
    /** Opaque reference to the encrypted private-key payload in CredentialStore. */
    val privateKeySecretReferenceId: String,
    val origin: SshKeyOrigin,
    val isPassphraseProtected: Boolean,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val comment: String? = null,
) {
    init {
        requireCanonicalUuid(id, "SSH key identity ID")
        requirePlainText(name, "SSH key name", ModelLimits.MAX_DISPLAY_NAME_LENGTH)
        requireIdentifier(algorithm, "SSH key algorithm", ModelLimits.MAX_ALGORITHM_LENGTH)
        requirePlainText(
            publicKeyFingerprint,
            "public-key fingerprint",
            ModelLimits.MAX_FINGERPRINT_LENGTH,
        )
        publicKey?.let { requirePlainText(it, "public key", ModelLimits.MAX_PUBLIC_KEY_LENGTH) }
        require(origin != SshKeyOrigin.GENERATED || publicKey != null) {
            "Generated SSH identities must retain their public key."
        }
        requireCanonicalUuid(privateKeySecretReferenceId, "private-key secret reference ID")
        requireEpochMillis(createdAtEpochMillis, "created timestamp")
        requireEpochMillis(updatedAtEpochMillis, "updated timestamp")
        requireTimestampOrder(createdAtEpochMillis, updatedAtEpochMillis, "updated timestamp")
        requireOptionalPlainText(comment, "SSH key comment", ModelLimits.MAX_COMMENT_LENGTH)
    }
}

data class KnownHost(
    val id: String,
    /** Canonical lowercase hostname/IP without an implicit port. */
    val host: String,
    val port: Int,
    /** OpenSSH host-key algorithm identifier. */
    val keyAlgorithm: String,
    val fingerprint: String,
    val publicHostKey: String,
    /** Both timestamps are null for imported legacy OpenSSH entries with unknown history. */
    val firstSeenAtEpochMillis: Long?,
    val lastSeenAtEpochMillis: Long?,
) {
    init {
        requireCanonicalUuid(id, "known-host ID")
        requireHost(host, "known host", canonical = true)
        requirePort(port, "known-host port")
        requireIdentifier(keyAlgorithm, "host-key algorithm", ModelLimits.MAX_ALGORITHM_LENGTH)
        requirePlainText(fingerprint, "host-key fingerprint", ModelLimits.MAX_FINGERPRINT_LENGTH)
        requirePlainText(publicHostKey, "public host key", ModelLimits.MAX_PUBLIC_KEY_LENGTH)
        require((firstSeenAtEpochMillis == null) == (lastSeenAtEpochMillis == null)) {
            "Known-host observation timestamps must both be known or both be unknown."
        }
        if (firstSeenAtEpochMillis != null && lastSeenAtEpochMillis != null) {
            requireEpochMillis(firstSeenAtEpochMillis, "first-seen timestamp")
            requireEpochMillis(lastSeenAtEpochMillis, "last-seen timestamp")
            requireTimestampOrder(firstSeenAtEpochMillis, lastSeenAtEpochMillis, "last-seen timestamp")
        }
    }
}
