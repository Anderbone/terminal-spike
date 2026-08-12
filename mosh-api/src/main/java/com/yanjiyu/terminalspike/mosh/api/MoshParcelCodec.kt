/*
 * Copyright 2026 Terminal Spike contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.yanjiyu.terminalspike.mosh.api

import android.os.BadParcelableException
import android.os.Parcel
import android.os.Parcelable

internal inline fun Parcel.writeSizedParcelableBody(body: Parcel.() -> Unit) {
    val start = dataPosition()
    writeInt(0)
    body()
    val end = dataPosition()
    setDataPosition(start)
    writeInt(end - start)
    setDataPosition(end)
}

internal inline fun <T> Parcel.readSizedParcelableBody(body: SizedParcelReader.() -> T): T {
    val start = dataPosition()
    val size = readInt()
    if (size < Int.SIZE_BYTES || start > Int.MAX_VALUE - size) {
        throw BadParcelableException("Invalid size-prefixed Mosh parcelable")
    }
    val end = start + size
    if (end > dataSize()) {
        throw BadParcelableException("Mosh parcelable exceeds its containing Parcel")
    }
    val reader = SizedParcelReader(this, end)
    return try {
        reader.body()
    } catch (error: BadParcelableException) {
        throw error
    } catch (error: RuntimeException) {
        throw BadParcelableException("Invalid Mosh parcelable").also { it.initCause(error) }
    } finally {
        setDataPosition(end)
    }
}

internal class SizedParcelReader(
    private val parcel: Parcel,
    private val end: Int,
) {
    fun readInt(field: String): Int = readFixed(Int.SIZE_BYTES, field) { parcel.readInt() }

    fun readLong(field: String): Long = readFixed(Long.SIZE_BYTES, field) { parcel.readLong() }

    fun readBoolean(field: String): Boolean = when (val value = readInt(field)) {
        0 -> false
        1 -> true
        else -> throw BadParcelableException("$field is not a canonical boolean: $value")
    }

    fun readString(field: String): String {
        requireRemaining(field)
        val value = parcel.readString() ?: throw BadParcelableException("$field is null")
        requireWithinEnd(field)
        return value
    }

    fun readByteArray(field: String): ByteArray {
        requireRemaining(field)
        val value = parcel.createByteArray() ?: throw BadParcelableException("$field is null")
        requireWithinEnd(field)
        return value
    }

    @Suppress("DEPRECATION")
    fun <T : Parcelable> readParcelable(field: String, classLoader: ClassLoader?): T {
        requireRemaining(field)
        val value = parcel.readParcelable<T>(classLoader)
            ?: throw BadParcelableException("$field is null")
        requireWithinEnd(field)
        return value
    }

    private inline fun <T> readFixed(byteCount: Int, field: String, block: () -> T): T {
        if (end - parcel.dataPosition() < byteCount) {
            throw BadParcelableException("$field is missing")
        }
        return block()
    }

    private fun requireRemaining(field: String) {
        if (parcel.dataPosition() >= end) throw BadParcelableException("$field is missing")
    }

    private fun requireWithinEnd(field: String) {
        if (parcel.dataPosition() > end) throw BadParcelableException("$field exceeds parcelable bounds")
    }
}
