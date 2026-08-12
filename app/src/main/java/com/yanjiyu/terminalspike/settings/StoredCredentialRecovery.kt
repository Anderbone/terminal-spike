package com.yanjiyu.terminalspike.settings

import java.security.GeneralSecurityException

enum class StoredCredentialKind {
    SSH_PASSWORD,
    SSH_PRIVATE_KEY,
}

/** A read never creates replacement key material for ciphertext it could not decrypt. */
class StoredCredentialKeyUnavailableException(
    val credentialKind: StoredCredentialKind,
) : GeneralSecurityException("The device key for $credentialKind is unavailable.")
