/*
 * Copyright 2026 Terminal Spike contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.yanjiyu.terminalspike.mosh.api;

import com.yanjiyu.terminalspike.mosh.api.MoshSessionEvent;

/** Bounded, low-frequency state events. Terminal bytes never travel through this interface. */
oneway interface IMoshCallback {
    void onSessionEvent(in MoshSessionEvent event);
}
