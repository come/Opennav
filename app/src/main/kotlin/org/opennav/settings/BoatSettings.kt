/*
 * Copyright (C) 2026 the Opennav authors
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 */
package org.opennav.settings

import android.content.Context
import androidx.core.content.edit
import org.opennav.core.depth.BoatProfile
import org.opennav.core.depth.DepthPalette

/**
 * The handful of preferences Phase 0 needs, in `SharedPreferences`.
 *
 * Room arrives with waypoints and tracks in Phase 3; a boat's draft is two doubles and
 * does not justify a database yet.
 */
class BoatSettings(context: Context) {

    private val prefs = context.getSharedPreferences("opennav", Context.MODE_PRIVATE)

    var profile: BoatProfile
        get() = BoatProfile(
            name = prefs.getString(KEY_NAME, DEFAULT_NAME) ?: DEFAULT_NAME,
            draftMeters = prefs.getFloat(KEY_DRAFT, DEFAULT_DRAFT_M).toDouble(),
            safetyMarginMeters = prefs.getFloat(
                KEY_MARGIN,
                BoatProfile.DEFAULT_SAFETY_MARGIN_METERS.toFloat(),
            ).toDouble(),
        )
        set(value) = prefs.edit {
            putString(KEY_NAME, value.name)
            putFloat(KEY_DRAFT, value.draftMeters.toFloat())
            putFloat(KEY_MARGIN, value.safetyMarginMeters.toFloat())
        }

    /** Clearance beyond which the safe-water blue stops darkening. Cosmetic. */
    var deepRangeMeters: Double
        get() = prefs.getFloat(
            KEY_DEEP_RANGE,
            DepthPalette.DEFAULT_DEEP_RANGE_METERS.toFloat(),
        ).toDouble()
        set(value) = prefs.edit { putFloat(KEY_DEEP_RANGE, value.toFloat()) }

    /** Frame-time overlay. On by default while the project is still a spike. */
    var showPerformanceHud: Boolean
        get() = prefs.getBoolean(KEY_HUD, true)
        set(value) = prefs.edit { putBoolean(KEY_HUD, value) }

    /**
     * Absolute path of the chart the user last imported or picked.
     *
     * Null falls back to "largest archive wins". Storing the choice matters as soon as
     * importing exists: a freshly imported harbour is smaller than the synthetic sample,
     * and would otherwise be silently ignored the moment it landed.
     */
    var selectedChartPath: String?
        get() = prefs.getString(KEY_CHART, null)
        set(value) = prefs.edit { putString(KEY_CHART, value) }

    var disclaimerAccepted: Boolean
        get() = prefs.getBoolean(KEY_DISCLAIMER, false)
        set(value) = prefs.edit { putBoolean(KEY_DISCLAIMER, value) }

    private companion object {
        const val KEY_NAME = "boat_name"
        const val KEY_DRAFT = "boat_draft_m"
        const val KEY_MARGIN = "boat_margin_m"
        const val KEY_DEEP_RANGE = "deep_range_m"
        const val KEY_HUD = "show_perf_hud"
        const val KEY_DISCLAIMER = "disclaimer_accepted"
        const val KEY_CHART = "selected_chart_path"

        const val DEFAULT_NAME = "Mon bateau"
        const val DEFAULT_DRAFT_M = 1.5f
    }
}
