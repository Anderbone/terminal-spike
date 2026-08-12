/*
 * Copyright 2026 Terminal Spike contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.yanjiyu.terminalspike.mosh.api

import android.os.Parcel
import android.os.Parcelable

public class MoshNetworkHint(
    public val modelVersion: Int,
    public val connectivityGeneration: Long,
    public val addressFamily: Int,
    public val isMetered: Boolean,
) : Parcelable {
    init {
        MoshContractValidation.requireModelVersion(modelVersion)
        MoshContractValidation.requireNetworkHint(addressFamily, connectivityGeneration)
    }

    private constructor(parcel: Parcel) : this(parcel.readSizedParcelableBody {
        Wire(
            modelVersion = readInt("modelVersion"),
            connectivityGeneration = readLong("connectivityGeneration"),
            addressFamily = readInt("addressFamily"),
            isMetered = readBoolean("isMetered"),
        )
    })

    private constructor(wire: Wire) : this(
        wire.modelVersion,
        wire.connectivityGeneration,
        wire.addressFamily,
        wire.isMetered,
    )

    override fun writeToParcel(destination: Parcel, flags: Int) {
        destination.writeSizedParcelableBody {
            writeInt(modelVersion)
            writeLong(connectivityGeneration)
            writeInt(addressFamily)
            writeInt(if (isMetered) 1 else 0)
        }
    }

    override fun describeContents(): Int = 0

    private data class Wire(
        val modelVersion: Int,
        val connectivityGeneration: Long,
        val addressFamily: Int,
        val isMetered: Boolean,
    )

    public companion object CREATOR : Parcelable.Creator<MoshNetworkHint> {
        override fun createFromParcel(source: Parcel): MoshNetworkHint = MoshNetworkHint(source)

        override fun newArray(size: Int): Array<MoshNetworkHint?> = arrayOfNulls(size)
    }
}
