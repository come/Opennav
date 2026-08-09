#!/usr/bin/env python3
# Copyright (C) 2026 the Opennav authors
# SPDX-License-Identifier: GPL-3.0-or-later
"""How big would the bathymetry for all of Brittany be?

This is the second half of the Phase 0 risk check, and the half that does not need a
device. The plan budgets under 400 MB for the whole region; if that is wrong, the
architecture changes, so it is worth answering before any UI exists.

The tile *count* is exact geometry and is computed here. The bytes *per tile* depends on
how noisy real Litto3D bathymetry is once quantised to 10 cm, which no amount of
arithmetic will tell you -- so the script reports the break-even figure instead:

    "you have N KB per tile of budget"

Measure one real département with build_bathymetry.py, divide, and compare. Until then
the honest answer is a break-even number, not a prediction.

    ./tools/estimate_volume.py
    ./tools/estimate_volume.py --bytes-per-tile 62000     # after a real measurement
    ./tools/estimate_volume.py --mode bbox                # whole-rectangle upper bound
"""

from __future__ import annotations

import argparse
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import tiles  # noqa: E402

# Brittany, generously. Only used by --mode bbox, which is an upper bound: most of this
# rectangle is land or open sea that Litto3D does not cover.
BRITTANY_BOUNDS = (-5.20, 47.20, -1.00, 48.95)

# Coastline of the Bretagne region including islands, at roughly 1:100 000. The figure
# is scale-dependent by nature (that is the coastline paradox), which is why the width
# below is the parameter to argue about, not this.
BRITTANY_COASTLINE_KM = 2730.0

# Litto3D is a land-sea interface product: a strip either side of the shore, not a
# full-shelf survey. 2 km is a defensible average for the Breton coast; pass --band-km
# to see what a different assumption does.
DEFAULT_BAND_KM = 2.0

DEFAULT_BUDGET_MB = 400.0
REPRESENTATIVE_LATITUDE = 48.4


def tiles_in_bbox(min_zoom: int, max_zoom: int, tile_size: int) -> dict[int, int]:
    return {
        z: tiles.tile_count(BRITTANY_BOUNDS, z, tile_size)
        for z in range(min_zoom, max_zoom + 1)
    }


def tiles_in_coastal_band(
    min_zoom: int, max_zoom: int, tile_size: int, coastline_km: float, band_km: float
) -> dict[int, int]:
    """Tiles needed to cover a strip of given width along the coastline.

    At the native zoom the strip is `coastline * width` of ground divided by the ground
    area of one tile. Coarser zooms cover the same strip with tiles four times larger in
    area, but the strip is long and thin, so below the zoom where a tile is wider than
    the band the count stops falling by four and starts falling by two: it is a line, not
    an area. Both regimes are modelled.
    """
    area_km2 = coastline_km * band_km
    counts = {}
    for z in range(min_zoom, max_zoom + 1):
        tile_km = tiles.ground_resolution_m(REPRESENTATIVE_LATITUDE, z, tile_size) * tile_size / 1000.0
        if tile_km <= band_km:
            counts[z] = math.ceil(area_km2 / (tile_km * tile_km))
        else:
            # One row of tiles following the coast.
            counts[z] = math.ceil(coastline_km / tile_km)
    return counts


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--mode", choices=("band", "bbox"), default="band")
    parser.add_argument("--min-zoom", type=int, default=0)
    parser.add_argument("--max-zoom", type=int, default=14)
    parser.add_argument("--tile-size", type=int, default=tiles.TILE_SIZE)
    parser.add_argument("--coastline-km", type=float, default=BRITTANY_COASTLINE_KM)
    parser.add_argument("--band-km", type=float, default=DEFAULT_BAND_KM)
    parser.add_argument("--budget-mb", type=float, default=DEFAULT_BUDGET_MB)
    parser.add_argument("--bytes-per-tile", type=float, default=None,
                        help="measured average; prints a real total instead of a "
                             "break-even figure")
    args = parser.parse_args()

    if args.mode == "bbox":
        counts = tiles_in_bbox(args.min_zoom, args.max_zoom, args.tile_size)
        headline = "whole bounding box (upper bound: includes land and open sea)"
    else:
        counts = tiles_in_coastal_band(
            args.min_zoom, args.max_zoom, args.tile_size,
            args.coastline_km, args.band_km,
        )
        headline = (
            f"{args.coastline_km:.0f} km of coastline x {args.band_km:.1f} km of "
            "Litto3D coverage"
        )

    total = sum(counts.values())
    native = counts[args.max_zoom]

    print(f"Brittany bathymetry, {headline}")
    print(f"tile size {args.tile_size} px, zooms {args.min_zoom}-{args.max_zoom}\n")
    print(f"{'zoom':>5} {'m/px':>8} {'tiles':>12}")
    for z in sorted(counts):
        resolution = tiles.ground_resolution_m(REPRESENTATIVE_LATITUDE, z, args.tile_size)
        print(f"{z:>5} {resolution:>8.1f} {counts[z]:>12,}")
    print(f"{'':>5} {'':>8} {'-' * 12}")
    print(f"{'':>5} {'total':>8} {total:>12,}")
    print(f"      native zoom is {native:,} tiles "
          f"({100.0 * native / total:.0f} % of the archive)\n")

    budget_bytes = args.budget_mb * 1024 * 1024
    if args.bytes_per_tile:
        size_mb = total * args.bytes_per_tile / 1024 / 1024
        verdict = "fits" if size_mb <= args.budget_mb else "DOES NOT FIT"
        print(f"at {args.bytes_per_tile / 1024:.1f} KiB/tile: "
              f"{size_mb:.0f} MB -- {verdict} in the {args.budget_mb:.0f} MB budget")
    else:
        print(f"break-even: {budget_bytes / total / 1024:.1f} KiB per tile to stay "
              f"inside {args.budget_mb:.0f} MB")
        print("\nTo turn this into an answer, build one real département and divide:")
        print("  ./tools/build_bathymetry.py --input '~/litto3d/finistere/*.asc' \\")
        print("      --out finistere.pmtiles --datum-shift 3.64")
        print("  ./tools/estimate_volume.py --bytes-per-tile "
              "$(( $(stat -c%s finistere.pmtiles) / TILE_COUNT ))")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
