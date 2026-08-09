# Copyright (C) 2026 the Opennav authors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Terrain-RGB encoding, kept byte-for-byte compatible with :core:depth.

This module is the *only* place in the pipeline allowed to turn an elevation into
pixels. Every constant here has a twin in
``core/depth/src/main/kotlin/org/opennav/core/depth/TerrainRgb.kt``; the two are
cross-checked by ``tools/test_tools.py`` and by the Kotlin unit tests. If you change one,
change the other.

Conventions (see docs/DATA.md):

* Elevations are metres, **positive up**, referenced to the chart datum
  (*zéro hydrographique*).
* Quantisation rounds the seabed **up**, never down.
* Absent survey data is written as :data:`NO_DATA_ELEVATION_M`, a value *above* every
  real elevation, so that any consumer which does not understand the sentinel fails
  towards "dry land" rather than towards "deep water".
"""

from __future__ import annotations

import math

BASE_M = -10000.0
INTERVAL_M = 0.1
MAX_ENCODABLE_M = BASE_M + 0xFFFFFF * INTERVAL_M

NO_DATA_ELEVATION_M = 9000.0
NO_DATA_THRESHOLD_M = 8000.0

# Mirrors QUANTISATION_EPSILON in TerrainRgb.kt.
_QUANTISATION_EPSILON = 1e-9


def encode(elevation_m: float) -> tuple[int, int, int]:
    """Encode one elevation to an (R, G, B) triple, rounding the seabed up."""
    if elevation_m != elevation_m:  # NaN
        raise ValueError("cannot encode NaN elevation; use NO_DATA_ELEVATION_M")
    clamped = min(max(elevation_m, BASE_M), MAX_ENCODABLE_M)
    steps = math.ceil((clamped - BASE_M) / INTERVAL_M - _QUANTISATION_EPSILON)
    steps = min(max(steps, 0), 0xFFFFFF)
    return (steps >> 16) & 0xFF, (steps >> 8) & 0xFF, steps & 0xFF


def decode(r: int, g: int, b: int) -> float:
    """Decode an (R, G, B) triple back to metres."""
    return BASE_M + ((r << 16) | (g << 8) | b) * INTERVAL_M


def is_no_data(elevation_m: float) -> bool:
    return elevation_m != elevation_m or elevation_m >= NO_DATA_THRESHOLD_M


NO_DATA_RGB = encode(NO_DATA_ELEVATION_M)
