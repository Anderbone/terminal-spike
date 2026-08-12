/*
 * Copyright 2026 Terminal Spike contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.yanjiyu.terminalspike.mosh.api

import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable

/** Main-app ends of the reliable terminal byte pipes returned by the extension. */
public class MoshSessionHandle(
    public val modelVersion: Int,
    public val sessionId: String,
    public val terminalInputWrite: ParcelFileDescriptor,
    public val terminalOutputRead: ParcelFileDescriptor,
    public val initialState: Int,
    public val negotiatedCapabilityFlags: Long,
) : Parcelable {
    init {
        MoshContractValidation.requireModelVersion(modelVersion)
        MoshContractValidation.requireSessionId(sessionId)
        MoshContractValidation.requireState(initialState)
        require(initialState == MoshSessionState.CONNECTING || initialState == MoshSessionState.CONNECTED) {
            "A new session handle must initially be connecting or connected"
        }
        MoshContractValidation.requireNegotiatedCapabilities(negotiatedCapabilityFlags)
    }

    private constructor(parcel: Parcel) : this(parcel.readSizedParcelableBody {
        Wire(
            modelVersion = readInt("modelVersion"),
            sessionId = readString("sessionId"),
            terminalInputWrite = readParcelable(
                "terminalInputWrite",
                ParcelFileDescriptor::class.java.classLoader,
            ),
            terminalOutputRead = readParcelable(
                "terminalOutputRead",
                ParcelFileDescriptor::class.java.classLoader,
            ),
            initialState = readInt("initialState"),
            negotiatedCapabilityFlags = readLong("negotiatedCapabilityFlags"),
        )
    })

    private constructor(wire: Wire) : this(
        wire.modelVersion,
        wire.sessionId,
        wire.terminalInputWrite,
        wire.terminalOutputRead,
        wire.initialState,
        wire.negotiatedCapabilityFlags,
    )

    override fun writeToParcel(destination: Parcel, flags: Int) {
        destination.writeSizedParcelableBody {
            writeInt(modelVersion)
            writeString(sessionId)
            writeParcelable(terminalInputWrite, flags)
            writeParcelable(terminalOutputRead, flags)
            writeInt(initialState)
            writeLong(negotiatedCapabilityFlags)
        }
    }

    override fun describeContents(): Int = Parcelable.CONTENTS_FILE_DESCRIPTOR

    private data class Wire(
        val modelVersion: Int,
        val sessionId: String,
        val terminalInputWrite: ParcelFileDescriptor,
        val terminalOutputRead: ParcelFileDescriptor,
        val initialState: Int,
        val negotiatedCapabilityFlags: Long,
    )

    public companion object CREATOR : Parcelable.Creator<MoshSessionHandle> {
        override fun createFromParcel(source: Parcel): MoshSessionHandle = MoshSessionHandle(source)

        override fun newArray(size: Int): Array<MoshSessionHandle?> = arrayOfNulls(size)
    }
}
