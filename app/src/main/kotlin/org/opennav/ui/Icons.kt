/*
 * Copyright (C) 2026 the Opennav authors
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 */
package org.opennav.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

// Hand-rolled rather than pulled from material-icons: the set needed here is five glyphs,
// two of which (a tide curve, a measured leg) do not exist in the Material set at all, and
// a half-custom set would look like an accident. Stroked, 24 dp, 2 dp lines throughout.

private const val VIEWPORT = 24f
private const val STROKE = 2f

private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = VIEWPORT,
        viewportHeight = VIEWPORT,
    ).apply(block).build()

private fun ImageVector.Builder.stroke(block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
    path(
        stroke = SolidColor(Color.White),
        strokeLineWidth = STROKE,
        strokeLineCap = StrokeCap.Round,
        pathBuilder = block,
    )

private fun ImageVector.Builder.fill(block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
    path(fill = SolidColor(Color.White), pathBuilder = block)

/** Settings. A gear reduced to a ring and four teeth, which reads fine at 24 dp. */
val GearIcon: ImageVector = icon("Gear") {
    stroke {
        // Ring.
        moveTo(12f, 8.2f)
        curveTo(14.1f, 8.2f, 15.8f, 9.9f, 15.8f, 12f)
        curveTo(15.8f, 14.1f, 14.1f, 15.8f, 12f, 15.8f)
        curveTo(9.9f, 15.8f, 8.2f, 14.1f, 8.2f, 12f)
        curveTo(8.2f, 9.9f, 9.9f, 8.2f, 12f, 8.2f)
        close()
        // Teeth.
        moveTo(12f, 2.6f); lineTo(12f, 5.4f)
        moveTo(12f, 18.6f); lineTo(12f, 21.4f)
        moveTo(2.6f, 12f); lineTo(5.4f, 12f)
        moveTo(18.6f, 12f); lineTo(21.4f, 12f)
        moveTo(5.3f, 5.3f); lineTo(7.3f, 7.3f)
        moveTo(16.7f, 16.7f); lineTo(18.7f, 18.7f)
        moveTo(18.7f, 5.3f); lineTo(16.7f, 7.3f)
        moveTo(7.3f, 16.7f); lineTo(5.3f, 18.7f)
    }
}

/** Recentre on the boat. */
val CrosshairIcon: ImageVector = icon("Crosshair") {
    stroke {
        moveTo(12f, 5.6f)
        curveTo(15.5f, 5.6f, 18.4f, 8.5f, 18.4f, 12f)
        curveTo(18.4f, 15.5f, 15.5f, 18.4f, 12f, 18.4f)
        curveTo(8.5f, 18.4f, 5.6f, 15.5f, 5.6f, 12f)
        curveTo(5.6f, 8.5f, 8.5f, 5.6f, 12f, 5.6f)
        close()
        moveTo(12f, 1.8f); lineTo(12f, 4.2f)
        moveTo(12f, 19.8f); lineTo(12f, 22.2f)
        moveTo(1.8f, 12f); lineTo(4.2f, 12f)
        moveTo(19.8f, 12f); lineTo(22.2f, 12f)
    }
    fill {
        moveTo(12f, 9.7f)
        curveTo(13.3f, 9.7f, 14.3f, 10.7f, 14.3f, 12f)
        curveTo(14.3f, 13.3f, 13.3f, 14.3f, 12f, 14.3f)
        curveTo(10.7f, 14.3f, 9.7f, 13.3f, 9.7f, 12f)
        curveTo(9.7f, 10.7f, 10.7f, 9.7f, 12f, 9.7f)
        close()
    }
}

/** Measure a leg: two marks and the line between them. */
val RulerIcon: ImageVector = icon("Ruler") {
    stroke {
        moveTo(5.5f, 18.5f)
        lineTo(18.5f, 5.5f)
    }
    fill {
        moveTo(5.5f, 15.9f)
        curveTo(6.9f, 15.9f, 8.1f, 17.1f, 8.1f, 18.5f)
        curveTo(8.1f, 19.9f, 6.9f, 21.1f, 5.5f, 21.1f)
        curveTo(4.1f, 21.1f, 2.9f, 19.9f, 2.9f, 18.5f)
        curveTo(2.9f, 17.1f, 4.1f, 15.9f, 5.5f, 15.9f)
        close()
        moveTo(18.5f, 2.9f)
        curveTo(19.9f, 2.9f, 21.1f, 4.1f, 21.1f, 5.5f)
        curveTo(21.1f, 6.9f, 19.9f, 8.1f, 18.5f, 8.1f)
        curveTo(17.1f, 8.1f, 15.9f, 6.9f, 15.9f, 5.5f)
        curveTo(15.9f, 4.1f, 17.1f, 2.9f, 18.5f, 2.9f)
        close()
    }
}

/** Tide: two swells and the level they sit on. */
val WaveIcon: ImageVector = icon("Wave") {
    stroke {
        moveTo(2.5f, 9f)
        curveTo(5f, 6.2f, 7.5f, 6.2f, 10f, 9f)
        curveTo(12.5f, 11.8f, 15f, 11.8f, 17.5f, 9f)
        curveTo(19.2f, 7.1f, 20.8f, 6.6f, 21.5f, 7.4f)
        moveTo(2.5f, 15.5f)
        curveTo(5f, 12.7f, 7.5f, 12.7f, 10f, 15.5f)
        curveTo(12.5f, 18.3f, 15f, 18.3f, 17.5f, 15.5f)
        curveTo(19.2f, 13.6f, 20.8f, 13.1f, 21.5f, 13.9f)
    }
}

/** Sources and legal notices. */
val InfoIcon: ImageVector = icon("Info") {
    stroke {
        moveTo(12f, 2.9f)
        curveTo(17f, 2.9f, 21.1f, 7f, 21.1f, 12f)
        curveTo(21.1f, 17f, 17f, 21.1f, 12f, 21.1f)
        curveTo(7f, 21.1f, 2.9f, 17f, 2.9f, 12f)
        curveTo(2.9f, 7f, 7f, 2.9f, 12f, 2.9f)
        close()
        moveTo(12f, 11f)
        lineTo(12f, 16.5f)
    }
    fill {
        moveTo(12f, 6.6f)
        curveTo(12.7f, 6.6f, 13.3f, 7.2f, 13.3f, 7.9f)
        curveTo(13.3f, 8.6f, 12.7f, 9.2f, 12f, 9.2f)
        curveTo(11.3f, 9.2f, 10.7f, 8.6f, 10.7f, 7.9f)
        curveTo(10.7f, 7.2f, 11.3f, 6.6f, 12f, 6.6f)
        close()
    }
}
