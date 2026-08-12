/*
 * Copyright 2026 Terminal Spike contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.yanjiyu.terminalspike.mosh.internal;

import android.os.ParcelFileDescriptor;
import com.yanjiyu.terminalspike.mosh.internal.IMoshWorkerCallback;

/** Private same-APK control plane for one process-isolated native Mosh client. */
interface IMoshWorker {
    oneway void startSession(
        String sessionId,
        in byte[] serverAddress,
        int addressFamily,
        int udpPort,
        in ParcelFileDescriptor moshKeyRead,
        in ParcelFileDescriptor terminalInputRead,
        in ParcelFileDescriptor terminalOutputWrite,
        int initialColumns,
        int initialRows,
        String locale,
        long optionFlags,
        in IMoshWorkerCallback callback
    );
    oneway void resizeSession(String sessionId, int columns, int rows);
    oneway void updateNetworkHint(String sessionId, long connectivityGeneration);
    oneway void stopSession(String sessionId, int reason);
}
