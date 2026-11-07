package com.yanjiyu.terminalspike.localarch.control

import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal const val CONTROL_MAX_REQUEST_BYTES = 64 * 1024
internal const val CONTROL_MAX_RESPONSE_BYTES = 1024 * 1024

internal class ControlFailure(val code: String, message: String) : IllegalArgumentException(message)

/** One UTF-8 JSON frame per connection. EOF before newline is an incomplete request. */
internal fun readControlFrame(input: InputStream): String {
    val source = input.buffered()
    val bytes = ByteArrayOutputStream()
    while (bytes.size() <= CONTROL_MAX_REQUEST_BYTES) {
        when (val next = source.read()) {
            -1 -> throw ControlFailure("INVALID_REQUEST", "Request must end with a newline.")
            10 -> return bytes.toByteArray().toString(Charsets.UTF_8)
            else -> bytes.write(next)
        }
    }
    throw ControlFailure("REQUEST_TOO_LARGE", "Request exceeds 64 KiB.")
}

internal fun parseControlRequest(frame: String): Pair<String, JsonObject> {
    val request = try {
        Json.parseToJsonElement(frame).jsonObject
    } catch (_: IllegalArgumentException) {
        throw ControlFailure("INVALID_REQUEST", "Expected a JSON object.")
    }
    request.requireFields("api", "method", "params")
    require(request.long("api") == 1L) { "Unsupported API version." }
    val params = request["params"] ?: JsonObject(emptyMap())
    require(params is JsonObject) { "params must be an object." }
    return request.string("method") to params
}

internal fun controlError(code: String, message: String) = buildJsonObject {
    put("api", 1)
    put("ok", false)
    put("error", buildJsonObject { put("code", code); put("message", message) })
}

internal fun JsonObject.requireFields(vararg fields: String) {
    require(keys.all { it in fields }) { "Unknown field. Use spikectl --help." }
}

internal fun JsonObject.string(key: String): String {
    val value = get(key) as? JsonPrimitive
    require(value != null && value.isString) { "$key must be a string." }
    return value.content
}

internal fun JsonObject.long(key: String): Long {
    val value = get(key) as? JsonPrimitive
    require(value != null && !value.isString && value.longOrNull != null) { "$key must be an integer." }
    return requireNotNull(value.longOrNull)
}

internal fun JsonObject.int(key: String): Int {
    val number = long(key)
    require(number in Int.MIN_VALUE..Int.MAX_VALUE) { "$key is out of range." }
    return number.toInt()
}

internal fun JsonObject.boolean(key: String): Boolean {
    val value = get(key) as? JsonPrimitive
    require(value != null && !value.isString && value.booleanOrNull != null) { "$key must be a boolean." }
    return requireNotNull(value.booleanOrNull)
}

internal fun JsonObject.float(key: String): Float {
    val value = get(key) as? JsonPrimitive
    require(value != null && !value.isString) { "$key must be a number." }
    val number = value.floatOrNull
    require(number != null && number.isFinite()) { "$key must be a finite number." }
    return number
}

internal fun JsonObject.requireRevision(current: Long) {
    if (long("revision") != current) {
        throw ControlFailure("CONFLICT", "Configuration changed. Read it again before editing.")
    }
}

internal fun nextControlRevision(current: Long): Long = maxOf(System.currentTimeMillis(), Math.addExact(current, 1))
