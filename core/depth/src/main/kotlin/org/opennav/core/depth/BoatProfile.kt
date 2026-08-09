/*
 * Copyright (C) 2026 the Opennav authors
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 */
package org.opennav.core.depth

/**
 * The two numbers the mariner owns.
 *
 * @param name free-form label, so a user with two boats can tell the profiles apart.
 * @param draftMeters deepest point of the hull below the waterline, in metres.
 * @param safetyMarginMeters water the mariner wants to keep *under* the keel on top of
 *   the draft. Defaults to 1.0 m. This is not slack to be spent: everything the app
 *   colours as safe already has this margin subtracted.
 */
public data class BoatProfile(
    val name: String,
    val draftMeters: Double,
    val safetyMarginMeters: Double = DEFAULT_SAFETY_MARGIN_METERS,
) {
    init {
        require(draftMeters.isFinite() && draftMeters > 0.0) {
            "draft must be a positive number of metres, got $draftMeters"
        }
        require(safetyMarginMeters.isFinite() && safetyMarginMeters >= 0.0) {
            "safety margin must be zero or more metres, got $safetyMarginMeters"
        }
    }

    /** Total water column the boat needs before the app will call a cell safe. */
    public val requiredWaterMeters: Double get() = draftMeters + safetyMarginMeters

    public companion object {
        public const val DEFAULT_SAFETY_MARGIN_METERS: Double = 1.0
    }
}
