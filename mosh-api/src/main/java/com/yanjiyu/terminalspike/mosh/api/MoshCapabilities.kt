/*
 * Copyright 2026 Terminal Spike contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.yanjiyu.terminalspike.mosh.api

import android.os.Parcel
import android.os.Parcelable

public class MoshCapabilities(
    public val modelVersion: Int,
    public val minimumApiVersion: Int,
    public val maximumApiVersion: Int,
    public val capabilityFlags: Long,
    public val maximumConcurrentSessions: Int,
) : Parcelable {
    init {
        MoshContractValidation.requireModelVersion(modelVersion)
        require(minimumApiVersion >= 1 && maximumApiVersion >= minimumApiVersion) {
            "Invalid supported API version range"
        }
        require(maximumConcurrentSessions in 1..MoshContractLimit.MAX_SESSIONS) {
            "maximumConcurrentSessions must be in 1..${MoshContractLimit.MAX_SESSIONS}"
        }
    }

    private constructor(parcel: Parcel) : this(parcel.readSizedParcelableBody {
        Wire(
            modelVersion = readInt("modelVersion"),
            minimumApiVersion = readInt("minimumApiVersion"),
            maximumApiVersion = readInt("maximumApiVersion"),
            capabilityFlags = readLong("capabilityFlags"),
            maximumConcurrentSessions = readInt("maximumConcurrentSessions"),
        )
    })

    private constructor(wire: Wire) : this(
        wire.modelVersion,
        wire.minimumApiVersion,
        wire.maximumApiVersion,
        wire.capabilityFlags,
        wire.maximumConcurrentSessions,
    )

    override fun writeToParcel(destination: Parcel, flags: Int) {
        destination.writeSizedParcelableBody {
            writeInt(modelVersion)
            writeInt(minimumApiVersion)
            writeInt(maximumApiVersion)
            writeLong(capabilityFlags)
            writeInt(maximumConcurrentSessions)
        }
    }

    override fun describeContents(): Int = 0

    private data class Wire(
        val modelVersion: Int,
        val minimumApiVersion: Int,
        val maximumApiVersion: Int,
        val capabilityFlags: Long,
        val maximumConcurrentSessions: Int,
    )

    public companion object CREATOR : Parcelable.Creator<MoshCapabilities> {
        override fun createFromParcel(source: Parcel): MoshCapabilities = MoshCapabilities(source)

        override fun newArray(size: Int): Array<MoshCapabilities?> = arrayOfNulls(size)
    }
}
