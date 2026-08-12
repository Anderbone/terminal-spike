/*
 * Copyright 2026 Terminal Spike contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.yanjiyu.terminalspike.mosh.api

import android.os.Parcel
import android.os.Parcelable

/** A bounded state change. [redactedDetail] must never contain endpoint or credential data. */
public class MoshSessionEvent(
    public val modelVersion: Int,
    public val sessionId: String,
    public val state: Int,
    public val disconnectReason: Int,
    public val errorCode: Int,
    public val redactedDetail: String,
    public val connectivityGeneration: Long,
) : Parcelable {
    init {
        MoshContractValidation.requireModelVersion(modelVersion)
        MoshContractValidation.requireSessionId(sessionId)
        MoshContractValidation.requireState(state)
        MoshContractValidation.requireDisconnectReason(disconnectReason)
        MoshContractValidation.requireErrorCode(errorCode)
        MoshContractValidation.requireEventConsistency(state, disconnectReason, errorCode)
        MoshContractValidation.requireRedactedDetail(redactedDetail)
        require(connectivityGeneration >= 0L) { "connectivityGeneration must be non-negative" }
    }

    private constructor(parcel: Parcel) : this(parcel.readSizedParcelableBody {
        Wire(
            modelVersion = readInt("modelVersion"),
            sessionId = readString("sessionId"),
            state = readInt("state"),
            disconnectReason = readInt("disconnectReason"),
            errorCode = readInt("errorCode"),
            redactedDetail = readString("redactedDetail"),
            connectivityGeneration = readLong("connectivityGeneration"),
        )
    })

    private constructor(wire: Wire) : this(
        wire.modelVersion,
        wire.sessionId,
        wire.state,
        wire.disconnectReason,
        wire.errorCode,
        wire.redactedDetail,
        wire.connectivityGeneration,
    )

    override fun writeToParcel(destination: Parcel, flags: Int) {
        destination.writeSizedParcelableBody {
            writeInt(modelVersion)
            writeString(sessionId)
            writeInt(state)
            writeInt(disconnectReason)
            writeInt(errorCode)
            writeString(redactedDetail)
            writeLong(connectivityGeneration)
        }
    }

    override fun describeContents(): Int = 0

    private data class Wire(
        val modelVersion: Int,
        val sessionId: String,
        val state: Int,
        val disconnectReason: Int,
        val errorCode: Int,
        val redactedDetail: String,
        val connectivityGeneration: Long,
    )

    public companion object CREATOR : Parcelable.Creator<MoshSessionEvent> {
        override fun createFromParcel(source: Parcel): MoshSessionEvent = MoshSessionEvent(source)

        override fun newArray(size: Int): Array<MoshSessionEvent?> = arrayOfNulls(size)
    }
}
