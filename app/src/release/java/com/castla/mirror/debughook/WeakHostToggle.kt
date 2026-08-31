package com.castla.mirror.debughook

import androidx.compose.runtime.Composable

/**
 * Release build twin of the debug WeakHostToggle. The weak-host synthesis experiment is
 * debug-only (its VpnService must never ship in release), so this renders nothing and
 * lets SettingsScreen call the same symbol in every build type.
 */
@Composable
fun WeakHostToggle() {
    // no-op in release
}
