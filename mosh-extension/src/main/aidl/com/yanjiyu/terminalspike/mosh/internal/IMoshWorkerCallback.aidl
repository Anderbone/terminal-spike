/*
 * Copyright 2026 Terminal Spike contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.yanjiyu.terminalspike.mosh.internal;

/** Low-frequency state callback from an isolated worker process to the extension broker. */
oneway interface IMoshWorkerCallback {
    void onSessionEvent(
        String sessionId,
        int state,
        int disconnectReason,
        int errorCode,
        String redactedDetail,
        long connectivityGeneration
    );
}
