/*
 * Copyright 2026 Terminal Spike contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.yanjiyu.terminalspike.mosh.api

import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable

/**
 * Version-1 start request. The one-shot key pipe contains exactly the ephemeral 22-character
 * Mosh session key. This model intentionally has no hostname, credential, or SSH key fields.
 */
public class MoshSessionRequest(
    public val modelVersion: Int,
    public val sessionId: String,
    serverAddress: ByteArray,
    public val addressFamily: Int,
    public val udpPort: Int,
    public val moshKeyRead: ParcelFileDescriptor,
    public val initialColumns: Int,
    public val initialRows: Int,
    public val locale: String,
    public val optionFlags: Long,
) : Parcelable {
    private val serverAddressBytes: ByteArray = serverAddress.copyOf()

    public val serverAddress: ByteArray
        get() = serverAddressBytes.copyOf()

    init {
        MoshContractValidation.requireModelVersion(modelVersion)
        MoshContractValidation.requireSessionId(sessionId)
        MoshContractValidation.requireAddress(addressFamily, serverAddressBytes)
        MoshContractValidation.requirePort(udpPort)
        MoshContractValidation.requireDimensions(initialColumns, initialRows)
        MoshContractValidation.requireLocale(locale)
        MoshContractValidation.requireOptionFlags(optionFlags)
    }

    private constructor(parcel: Parcel) : this(parcel.readSizedParcelableBody {
        Wire(
            modelVersion = readInt("modelVersion"),
            sessionId = readString("sessionId"),
            serverAddress = readByteArray("serverAddress"),
            addressFamily = readInt("addressFamily"),
            udpPort = readInt("udpPort"),
            moshKeyRead = readParcelable("moshKeyRead", ParcelFileDescriptor::class.java.classLoader),
            initialColumns = readInt("initialColumns"),
            initialRows = readInt("initialRows"),
            locale = readString("locale"),
            optionFlags = readLong("optionFlags"),
        )
    })

    private constructor(wire: Wire) : this(
        wire.modelVersion,
        wire.sessionId,
        wire.serverAddress,
        wire.addressFamily,
        wire.udpPort,
        wire.moshKeyRead,
        wire.initialColumns,
        wire.initialRows,
        wire.locale,
        wire.optionFlags,
    )

    override fun writeToParcel(destination: Parcel, flags: Int) {
        destination.writeSizedParcelableBody {
            writeInt(modelVersion)
            writeString(sessionId)
            writeByteArray(serverAddressBytes)
            writeInt(addressFamily)
            writeInt(udpPort)
            writeParcelable(moshKeyRead, flags)
            writeInt(initialColumns)
            writeInt(initialRows)
            writeString(locale)
            writeLong(optionFlags)
        }
    }

    override fun describeContents(): Int = Parcelable.CONTENTS_FILE_DESCRIPTOR

    private data class Wire(
        val modelVersion: Int,
        val sessionId: String,
        val serverAddress: ByteArray,
        val addressFamily: Int,
        val udpPort: Int,
        val moshKeyRead: ParcelFileDescriptor,
        val initialColumns: Int,
        val initialRows: Int,
        val locale: String,
        val optionFlags: Long,
    )

    public companion object CREATOR : Parcelable.Creator<MoshSessionRequest> {
        override fun createFromParcel(source: Parcel): MoshSessionRequest = MoshSessionRequest(source)

        override fun newArray(size: Int): Array<MoshSessionRequest?> = arrayOfNulls(size)
    }
}
