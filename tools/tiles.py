# Copyright (C) 2026 the Opennav authors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Web Mercator (EPSG:3857) tile arithmetic."""

from __future__ import annotations

import math

TILE_SIZE = 256

#: Ground resolution at zoom 0, in metres per pixel, on the equator.
EQUATOR_RESOLUTION_M = 156543.033928041


def lon_to_pixel_x(lon: float, zoom: int, tile_size: int = TILE_SIZE) -> float:
    return (lon + 180.0) / 360.0 * (1 << zoom) * tile_size


def lat_to_pixel_y(lat: float, zoom: int, tile_size: int = TILE_SIZE) -> float:
    s = math.sin(math.radians(lat))
    s = min(max(s, -0.9999), 0.9999)
    y = 0.5 - math.log((1 + s) / (1 - s)) / (4 * math.pi)
    return y * (1 << zoom) * tile_size


def pixel_x_to_lon(px: float, zoom: int, tile_size: int = TILE_SIZE) -> float:
    return px / ((1 << zoom) * tile_size) * 360.0 - 180.0


def pixel_y_to_lat(py: float, zoom: int, tile_size: int = TILE_SIZE) -> float:
    n = math.pi - 2.0 * math.pi * py / ((1 << zoom) * tile_size)
    return math.degrees(math.atan(0.5 * (math.exp(n) - math.exp(-n))))


def ground_resolution_m(lat: float, zoom: int, tile_size: int = TILE_SIZE) -> float:
    """Metres per pixel at a latitude and zoom."""
    return EQUATOR_RESOLUTION_M * math.cos(math.radians(lat)) / (1 << zoom) * (TILE_SIZE / tile_size)


def tile_range(
    bounds: tuple[float, float, float, float], zoom: int, tile_size: int = TILE_SIZE
) -> tuple[int, int, int, int]:
    """Inclusive (x_min, y_min, x_max, y_max) tile range covering ``bounds``."""
    min_lon, min_lat, max_lon, max_lat = bounds
    x0 = int(lon_to_pixel_x(min_lon, zoom, tile_size) // tile_size)
    x1 = int(lon_to_pixel_x(max_lon, zoom, tile_size) // tile_size)
    # Pixel Y grows southwards, so max_lat gives the smaller index.
    y0 = int(lat_to_pixel_y(max_lat, zoom, tile_size) // tile_size)
    y1 = int(lat_to_pixel_y(min_lat, zoom, tile_size) // tile_size)
    limit = (1 << zoom) - 1
    return (
        max(0, min(x0, limit)),
        max(0, min(y0, limit)),
        max(0, min(x1, limit)),
        max(0, min(y1, limit)),
    )


def tile_count(
    bounds: tuple[float, float, float, float], zoom: int, tile_size: int = TILE_SIZE
) -> int:
    x0, y0, x1, y1 = tile_range(bounds, zoom, tile_size)
    return (x1 - x0 + 1) * (y1 - y0 + 1)
