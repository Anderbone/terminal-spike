/*
 * Android terminal-display initialization for Mosh 1.4.0.
 * Copyright 2026 Terminal Spike contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Modified 2026-08-09: Android has no terminfo database in the application
 * sandbox. The extension writes conservative VT200/xterm-compatible escape
 * sequences into its terminal-output pipe instead of consulting ncurses.
 */
#include "terminaldisplay.h"

using namespace Terminal;

Display::Display( bool /* use_environment */ )
  : has_ech( true ),
    has_bce( true ),
    has_title( true ),
    smcup( NULL ),
    rmcup( NULL )
{}
