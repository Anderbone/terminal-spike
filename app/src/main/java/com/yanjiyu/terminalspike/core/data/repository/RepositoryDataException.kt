package com.yanjiyu.terminalspike.core.data.repository

/** The persisted record kinds exposed through the non-secret repository boundary. */
enum class RepositoryRecordType {
    HOST_PROFILE,
    SSH_CREDENTIAL,
    SSH_KEY_IDENTITY,
    TERMINAL_PROFILE,
    CUSTOM_TERMINAL_THEME,
    KEYBOARD_PROFILE,
    KNOWN_HOST,
    SNIPPET,
    RECENT_SESSION,
}

/**
 * A database row exists but cannot be represented by the validated domain model.
 *
 * Repositories deliberately surface this error instead of substituting defaults or deleting the
 * row. The opaque [recordKey] lets recovery UI identify a record without echoing its contents.
 */
class CorruptStoredDataException(
    val recordType: RepositoryRecordType,
    val recordKey: String,
    cause: IllegalArgumentException,
) : IllegalStateException("Stored ${recordType.name.lowercase()} '$recordKey' is invalid.", cause)

/** A domain value failed the persistence-boundary validation performed before a write. */
class InvalidRepositoryInputException(
    val recordType: RepositoryRecordType,
    val recordKey: String,
    cause: IllegalArgumentException,
) : IllegalArgumentException("Invalid ${recordType.name.lowercase()} '$recordKey'.", cause)
