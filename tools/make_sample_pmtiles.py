#!/usr/bin/env python3
# Copyright (C) 2026 the Opennav authors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Build a synthetic Terrain-RGB .pmtiles archive for the Phase 0 spike.

Litto3D needs a SHOM account and a few gigabytes of download, which is a poor thing to
stand between a contributor and their first run of the app. This script fabricates a
small piece of seabed off Brest with the three features the spike has to prove it
renders correctly:

  * a dredged channel deep enough to stay blue at any state of the tide,
  * a shoal that dries, so the red band appears and disappears as the slider moves,
  * a hole with no survey data, so the violet band and the conservative sentinel are
    exercised on a real GPU rather than only in unit tests.

The output is byte-compatible with what ``build_bathymetry.py`` produces from real
Litto3D tiles, so the app cannot tell them apart -- which is exactly why the archive is
labelled as synthetic in its metadata and in its filename.

    ./tools/make_sample_pmtiles.py --out sample-brest.pmtiles

NOT FOR NAVIGATION. The numbers in here are invented.
"""

from __future__ import annotations

import argparse
import array
import math
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import png  # noqa: E402
import terrain_rgb  # noqa: E402
import tiles  # noqa: E402
from pmtiles import COMPRESSION_NONE, TILETYPE_PNG, write_pmtiles  # noqa: E402

# A patch of the Rade de Brest and the Goulet. Small enough to build in a few seconds.
BOUNDS = (-4.62, 48.29, -4.38, 48.40)
MIN_ZOOM = 10
MAX_ZOOM = 14

# Feature placement, in degrees.
_CHANNEL_LAT = 48.335
_SHOAL = (-4.505, 48.352)
_HOLE = (-4.455, 48.318)


def seabed_elevation_m(lon: float, lat: float) -> float:
    """Invented seabed, in metres positive up from chart datum."""
    # A hole where the lidar found nothing. Checked first: no data beats any model.
    if _distance_deg(lon, lat, *_HOLE) < 0.012:
        return terrain_rgb.NO_DATA_ELEVATION_M

    # Land to the north, sloping up away from the shore.
    shore_lat = 48.386 + 0.004 * math.sin((lon + 4.5) * 210.0)
    if lat > shore_lat:
        return 2.0 + 260.0 * (lat - shore_lat)

    # A channel running east-west, deepest on its axis.
    across = abs(lat - _CHANNEL_LAT) / 0.030
    channel = -34.0 * math.exp(-across * across)

    # General shelving from the shore out to the channel.
    shelf = -6.0 - 40.0 * (shore_lat - lat)

    elevation = min(channel, shelf)

    # A shoal that dries about 1.4 m above chart datum.
    d = _distance_deg(lon, lat, *_SHOAL) / 0.010
    elevation += 12.0 * math.exp(-d * d)

    # Ripples, so the colour bands have something to bite on.
    elevation += 0.45 * math.sin(lon * 900.0) * math.cos(lat * 1100.0)
    return elevation


def _distance_deg(lon_a: float, lat_a: float, lon_b: float, lat_b: float) -> float:
    # Good enough at this latitude and this scale; nothing safety-critical depends on it.
    dx = (lon_a - lon_b) * math.cos(math.radians(lat_a))
    dy = lat_a - lat_b
    return math.hypot(dx, dy)


class _Level:
    """A zoom level held as one flat grid of elevations in global pixel space."""

    __slots__ = ("zoom", "ox", "oy", "w", "h", "cells")

    def __init__(self, zoom: int, x0: int, y0: int, x1: int, y1: int, tile_size: int):
        self.zoom = zoom
        self.ox = x0 * tile_size
        self.oy = y0 * tile_size
        self.w = (x1 - x0 + 1) * tile_size
        self.h = (y1 - y0 + 1) * tile_size
        self.cells = array.array("f", [terrain_rgb.NO_DATA_ELEVATION_M]) * (self.w * self.h)


def _sample_level(zoom: int, tile_size: int) -> _Level:
    x0, y0, x1, y1 = tiles.tile_range(BOUNDS, zoom, tile_size)
    level = _Level(zoom, x0, y0, x1, y1, tile_size)
    min_lon, min_lat, max_lon, max_lat = BOUNDS
    for row in range(level.h):
        lat = tiles.pixel_y_to_lat(level.oy + row + 0.5, zoom, tile_size)
        base = row * level.w
        inside_lat = min_lat <= lat <= max_lat
        for col in range(level.w):
            if not inside_lat:
                continue
            lon = tiles.pixel_x_to_lon(level.ox + col + 0.5, zoom, tile_size)
            if not (min_lon <= lon <= max_lon):
                continue
            level.cells[base + col] = seabed_elevation_m(lon, lat)
    return level


def _downsample(fine: _Level, tile_size: int) -> _Level:
    """Build the next coarser level, keeping the SHALLOWEST of each 2x2 block.

    Shallowest means the *greatest* elevation, because elevations are positive up. This
    is the single most misread line in the whole pipeline: "minimum depth" and "minimum
    elevation" are opposites, and picking the wrong one silently makes every overview
    zoom optimistic. See docs/DATA.md.

    A block is only marked as unsurveyed when every one of its children is. Propagating
    the sentinel from a single child would turn low zooms into a wall of violet, since
    data holes are everywhere; the cost is that holes are only drawn faithfully at the
    native zoom, which is why the app warns when it is zoomed out.
    """
    zoom = fine.zoom - 1
    x0, y0, x1, y1 = tiles.tile_range(BOUNDS, zoom, tile_size)
    coarse = _Level(zoom, x0, y0, x1, y1, tile_size)
    for row in range(coarse.h):
        gy = coarse.oy + row
        for col in range(coarse.w):
            gx = coarse.ox + col
            best = None
            for dy in (0, 1):
                fy = 2 * gy + dy - fine.oy
                if not (0 <= fy < fine.h):
                    continue
                for dx in (0, 1):
                    fx = 2 * gx + dx - fine.ox
                    if not (0 <= fx < fine.w):
                        continue
                    v = fine.cells[fy * fine.w + fx]
                    if terrain_rgb.is_no_data(v):
                        continue
                    if best is None or v > best:
                        best = v
            coarse.cells[row * coarse.w + col] = (
                best if best is not None else terrain_rgb.NO_DATA_ELEVATION_M
            )
    return coarse


def _encode_level(level: _Level, tile_size: int) -> dict[tuple[int, int, int], bytes]:
    out: dict[tuple[int, int, int], bytes] = {}
    cache: dict[int, tuple[int, int, int]] = {}
    for ty in range(level.h // tile_size):
        for tx in range(level.w // tile_size):
            buf = bytearray(tile_size * tile_size * 3)
            i = 0
            for row in range(tile_size):
                base = (ty * tile_size + row) * level.w + tx * tile_size
                for col in range(tile_size):
                    v = level.cells[base + col]
                    key = int(v * 100.0)
                    rgb = cache.get(key)
                    if rgb is None:
                        rgb = terrain_rgb.encode(v)
                        cache[key] = rgb
                    buf[i] = rgb[0]
                    buf[i + 1] = rgb[1]
                    buf[i + 2] = rgb[2]
                    i += 3
            out[(level.zoom, level.ox // tile_size + tx, level.oy // tile_size + ty)] = (
                png.encode_rgb(tile_size, tile_size, bytes(buf))
            )
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", default="sample-brest-synthetic.pmtiles")
    parser.add_argument(
        "--bounds", type=float, nargs=4,
        metavar=("MIN_LON", "MIN_LAT", "MAX_LON", "MAX_LAT"),
        help="fabriquer le fond ailleurs qu'en rade de Brest -- pratique pour poser un "
             "fond factice sous un vrai balisage en attendant les donnees Litto3D. "
             "L'archive reste marquee synthetique et l'app affiche un bandeau rouge.",
    )
    parser.add_argument("--tile-size", type=int, default=tiles.TILE_SIZE)
    parser.add_argument("--min-zoom", type=int, default=MIN_ZOOM)
    parser.add_argument("--max-zoom", type=int, default=MAX_ZOOM)
    args = parser.parse_args()

    if args.bounds:
        global BOUNDS
        BOUNDS = tuple(args.bounds)
        print(f"fond synthetique sur {BOUNDS} -- NE PAS NAVIGUER AVEC", flush=True)

    started = time.monotonic()
    print(f"sampling z{args.max_zoom} ...", flush=True)
    level = _sample_level(args.max_zoom, args.tile_size)

    all_tiles: dict[tuple[int, int, int], bytes] = {}
    all_tiles.update(_encode_level(level, args.tile_size))
    for zoom in range(args.max_zoom - 1, args.min_zoom - 1, -1):
        print(f"downsampling to z{zoom} ...", flush=True)
        level = _downsample(level, args.tile_size)
        all_tiles.update(_encode_level(level, args.tile_size))

    write_pmtiles(
        args.out,
        all_tiles,
        bounds=BOUNDS,
        center=((BOUNDS[0] + BOUNDS[2]) / 2, _CHANNEL_LAT),
        center_zoom=13,
        tile_type=TILETYPE_PNG,
        tile_compression=COMPRESSION_NONE,
        metadata={
            "name": "Opennav sample (SYNTHETIC, not for navigation)",
            "format": "png",
            "encoding": "mapbox",
            "type": "baselayer",
            "synthetic": True,
            "vertical_datum": "chart datum (zéro hydrographique), metres positive up",
            "no_data_elevation_m": terrain_rgb.NO_DATA_ELEVATION_M,
            "attribution": "Synthetic test data. Contains no SHOM or IGN material.",
            "description": (
                "Invented bathymetry generated by tools/make_sample_pmtiles.py so that "
                "the Phase 0 spike can be run without a SHOM account. NOT FOR NAVIGATION."
            ),
        },
    )

    size = os.path.getsize(args.out)
    print(
        f"wrote {args.out}: {len(all_tiles)} tiles, {size / 1024 / 1024:.2f} MiB, "
        f"{size / len(all_tiles):.0f} bytes/tile, in {time.monotonic() - started:.1f} s"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
