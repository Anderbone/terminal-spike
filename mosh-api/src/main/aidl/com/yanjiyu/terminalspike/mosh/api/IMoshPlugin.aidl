/*
 * Copyright 2026 Terminal Spike contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.yanjiyu.terminalspike.mosh.api;

import com.yanjiyu.terminalspike.mosh.api.IMoshCallback;
import com.yanjiyu.terminalspike.mosh.api.MoshCapabilities;
import com.yanjiyu.terminalspike.mosh.api.MoshNetworkHint;
import com.yanjiyu.terminalspike.mosh.api.MoshSessionHandle;
import com.yanjiyu.terminalspike.mosh.api.MoshSessionRequest;

/** Version 1 control plane for the separately installed Mosh extension. */
interface IMoshPlugin {
    int getApiVersion();
    MoshCapabilities getCapabilities();
    MoshSessionHandle startSession(in MoshSessionRequest request);
    void resizeSession(String sessionId, int columns, int rows);
    void updateNetworkHint(String sessionId, in MoshNetworkHint hint);
    void stopSession(String sessionId, int reason);
    void registerCallback(IMoshCallback callback);
    void unregisterCallback(IMoshCallback callback);
}
