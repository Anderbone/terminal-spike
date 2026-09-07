# Copyright 2026 Terminal Spike contributors
# SPDX-License-Identifier: GPL-3.0-or-later

# JNI entry points and the native listener are resolved by their stable binary names.
-keep class com.yanjiyu.terminalspike.mosh.MoshNativeBridge { *; }
-keep class com.yanjiyu.terminalspike.mosh.MoshNativeListener { *; }

# The main app binds this exact documented component name.
-keep class com.yanjiyu.terminalspike.mosh.MoshExtensionService { *; }
