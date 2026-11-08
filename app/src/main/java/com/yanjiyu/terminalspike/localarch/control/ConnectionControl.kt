package com.yanjiyu.terminalspike.localarch.control

import com.yanjiyu.terminalspike.core.model.ConnectionProtocol
import com.yanjiyu.terminalspike.core.model.HostProfile
import com.yanjiyu.terminalspike.core.model.MoshFallbackPolicy
import java.util.UUID
import kotlinx.serialization.json.JsonObject

internal val CONTROL_CONNECTION_FIELDS = arrayOf(
    "id", "revision", "name", "hostname", "port", "username", "protocol",
    "credentialId", "terminalProfileId", "keyboardProfileId", "isFavorite", "group", "tag",
)

/** Patch non-secret endpoint fields; never replace unrelated transport or startup settings. */
internal fun patchControlConnection(current: HostProfile?, p: JsonObject): HostProfile {
    p.requireFields(*CONTROL_CONNECTION_FIELDS)
    if (current != null) {
        p.requireRevision(current.updatedAtEpochMillis)
        require(p.keys.any { it !in setOf("id", "revision") })
    } else {
        require("id" !in p && "revision" !in p)
    }
    val now = current?.let { nextControlRevision(it.updatedAtEpochMillis) } ?: System.currentTimeMillis()
    val protocol = p["protocol"]?.let { ConnectionProtocol.fromWireCode(p.string("protocol")) }
        ?: current?.protocol ?: ConnectionProtocol.SSH
    fun text(key: String, fallback: String?) = if (key in p) p.string(key).ifEmpty { null } else fallback
    val base = current ?: HostProfile(
        id = UUID.randomUUID().toString(), displayName = p.string("name"),
        hostname = p.string("hostname"), username = p.string("username"),
        port = p["port"]?.let { p.int("port") } ?: 22, protocol = protocol, credentialId = null,
        createdAtEpochMillis = now, updatedAtEpochMillis = now,
    )
    return base.copy(
        displayName = p["name"]?.let { p.string("name") } ?: base.displayName,
        hostname = p["hostname"]?.let { p.string("hostname") } ?: base.hostname,
        username = p["username"]?.let { p.string("username") } ?: base.username,
        port = p["port"]?.let { p.int("port") } ?: base.port,
        protocol = protocol,
        terminalProfileId = text("terminalProfileId", base.terminalProfileId),
        keyboardProfileId = text("keyboardProfileId", base.keyboardProfileId),
        isFavorite = p["isFavorite"]?.let { p.boolean("isFavorite") } ?: base.isFavorite,
        group = text("group", base.group), tag = text("tag", base.tag),
        // Switching back to SSH must remove settings that only make sense for Mosh.
        moshPort = base.moshPort.takeIf { protocol == ConnectionProtocol.MOSH },
        moshPortRange = base.moshPortRange.takeIf { protocol == ConnectionProtocol.MOSH },
        moshServerCommand = base.moshServerCommand.takeIf { protocol == ConnectionProtocol.MOSH },
        moshLocale = base.moshLocale.takeIf { protocol == ConnectionProtocol.MOSH },
        moshFallbackPolicy = if (protocol == ConnectionProtocol.MOSH) base.moshFallbackPolicy else MoshFallbackPolicy.NEVER,
        updatedAtEpochMillis = now,
    )
}
