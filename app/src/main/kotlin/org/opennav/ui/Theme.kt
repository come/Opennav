/*
 * Copyright (C) 2026 the Opennav authors
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 */
package org.opennav.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Dark only, and deliberately so: a white chrome around a chart is unreadable on deck in
// sunlight and blinding at night. The proper red night palette is Phase 4; this is not
// it, it is just a dark theme.
private val OpennavColors = darkColorScheme(
    primary = Color(0xFF7EC8E3),
    onPrimary = Color(0xFF06232E),
    secondary = Color(0xFF9ECAE1),
    background = Color(0xFF101014),
    onBackground = Color(0xFFE6E6E6),
    surface = Color(0xFF181820),
    onSurface = Color(0xFFE6E6E6),
    surfaceVariant = Color(0xFF23232C),
    onSurfaceVariant = Color(0xFFC6C6CE),
    error = Color(0xFFD7191C),
)

@Composable
fun OpennavTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = OpennavColors, content = content)
}
