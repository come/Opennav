#!/usr/bin/env python3
# Copyright (C) 2026 the Opennav authors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Build a synthetic Terrain-RGB .pmtiles archive for the Phase 0 spike.

Litto3D needs a SHOM account and a few gigabytes of download, which is a poor thing to
stand between a contributor and their first run of the app. This script fabricates a
small piece of seabed with the three features the spike has to prove it renders
correctly:

  * a dredged channel deep enough to stay blue at any state of the tide,
  * a shoal that dries, so the red band appears and disappears as the slider moves,
  * a hole with no survey data, so the violet band and the conservative sentinel are
    exercised on a real GPU rather than only in unit tests.

The output is byte-compatible with what ``build_bathymetry.py`` produces from real
Litto3D tiles, so the app cannot tell them apart -- which is exactly why the archive is
labelled as synthetic in its metadata and in its filename.

    ./tools/make_sample_pmtiles.py --out sample-brest.pmtiles

The three features are placed as *fractions of the requested window*, not at fixed
coordinates, so ``--bounds`` moves the whole invented seabed rather than leaving it
behind in the Rade de Brest. That is the difference between a demo and a flat blue
rectangle, and it is why the app ships one of these under the Baie de Quiberon.

Two properties are deliberate, and both point the same way -- towards being unmistakable:

  * the shapes stay geometric. A straight channel, a circular shoal and a circular hole
    do not look like a survey at any zoom, whatever coastline they are laid over.
  * the depths stay in metres. Widening the window scales the features, never the
    soundings, so a demo of the Golfe du Morbihan is as deep as a demo of the Goulet.

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

#: A patch of the Rade de Brest and the Goulet. Small enough to build in a few seconds.
DEFAULT_BOUNDS = (-4.62, 48.29, -4.38, 48.40)
MIN_ZOOM = 10
MAX_ZOOM = 14

# --- feature placement, as fractions of the window ---------------------------------
#
# 0 is the south/west edge, 1 the north/east edge. Chosen so that the channel, the shoal
# and the hole are all comfortably inside any window, and none of them sits on top of
# another.
_SHORE = 0.87           # water below this, land above it
_CHANNEL = 0.41         # axis of the dredged channel
_SHOAL = (0.48, 0.62)   # lon, lat
_HOLE = (0.69, 0.26)

# Feature sizes, as fractions of the window's latitude span.
_CHANNEL_HALF_WIDTH = 0.27
_SHOAL_RADIUS = 0.09
_HOLE_RADIUS = 0.11
_SHORE_WOBBLE = 0.036   # amplitude
_SHORE_WAVES = 8.0      # over the longitude span

# --- depths, in metres, which do NOT scale with the window --------------------------
_CHANNEL_DEPTH_M = -34.0
_SHELF_NEAR_M = -6.0     # just off the beach
_SHELF_FAR_M = -11.0     # at the offshore edge of the window
_SHOAL_PEAK_M = 1.4      # dries: the reason the red band has anything to bite on
_SHORE_RISE_M = 2.0
_INLAND_SLOPE_M = 24.0   # over the window's latitude span
_RIPPLE_M = 0.45
_RIPPLE_WAVES = (34.0, 19.0)


class Seabed:
    """An invented seabed, sized and positioned to fill one geographic window."""

    def __init__(self, bounds: tuple[float, float, float, float]):
        self.bounds = bounds
        min_lon, min_lat, max_lon, max_lat = bounds
        self._lon0, self._lat0 = min_lon, min_lat
        self._span_lon = max_lon - min_lon
        self._span_lat = max_lat - min_lat
        if self._span_lon <= 0 or self._span_lat <= 0:
            raise ValueError(f"emprise vide ou inversee : {bounds}")

        # Everything below works in window fractions, so nothing has to be re-derived
        # per sample. Longitude is squeezed by the latitude cosine first, so a circular
        # feature stays circular on the ground instead of turning into an ellipse.
        self._squeeze = math.cos(math.radians((min_lat + max_lat) / 2.0))
        self._aspect = self._span_lon * self._squeeze / self._span_lat

    def elevation_m(self, lon: float, lat: float) -> float:
        """Elevation in metres, positive up from chart datum."""
        u = (lon - self._lon0) / self._span_lon
        v = (lat - self._lat0) / self._span_lat

        # A hole where the lidar found nothing. Checked first: no data beats any model.
        if self._distance(u, v, *_HOLE) < _HOLE_RADIUS:
            return terrain_rgb.NO_DATA_ELEVATION_M

        # Land to the north, sloping up away from the shore.
        shore = _SHORE + _SHORE_WOBBLE * math.sin(u * _SHORE_WAVES * 2.0 * math.pi)
        if v > shore:
            return _SHORE_RISE_M + _INLAND_SLOPE_M * (v - shore)

        # A channel running east-west, deepest on its axis, cut into a shelf that
        # deepens away from the beach. The deeper of the two: the channel is dredged
        # *into* the shelf, it is not laid on top of it.
        across = (v - _CHANNEL) / _CHANNEL_HALF_WIDTH
        channel = _CHANNEL_DEPTH_M * math.exp(-across * across)
        shelf = _SHELF_NEAR_M + (_SHELF_FAR_M - _SHELF_NEAR_M) * (shore - v) / max(shore, 1e-6)
        elevation = min(channel, shelf)

        # A shoal that dries. Written as a pull towards a target height rather than as a
        # bump of so many metres, so that its summit is _SHOAL_PEAK_M whatever the seabed
        # underneath happens to be. A fixed bump would leave it 12 m under water once it
        # landed over the channel, and the drying band would quietly never appear.
        d = self._distance(u, v, *_SHOAL) / _SHOAL_RADIUS
        pull = math.exp(-d * d)
        elevation += (_SHOAL_PEAK_M - elevation) * pull

        # Ripples, so the colour bands have something to bite on.
        elevation += _RIPPLE_M * (
            math.sin(u * _RIPPLE_WAVES[0] * 2.0 * math.pi)
            * math.cos(v * _RIPPLE_WAVES[1] * 2.0 * math.pi)
        )
        return elevation

    def _distance(self, u: float, v: float, cu: float, cv: float) -> float:
        """Distance in window fractions, corrected so circles stay round."""
        return math.hypot((u - cu) * self._aspect, v - cv)


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


def _sample_level(seabed: Seabed, zoom: int, tile_size: int) -> _Level:
    x0, y0, x1, y1 = tiles.tile_range(seabed.bounds, zoom, tile_size)
    level = _Level(zoom, x0, y0, x1, y1, tile_size)
    min_lon, min_lat, max_lon, max_lat = seabed.bounds
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
            level.cells[base + col] = seabed.elevation_m(lon, lat)
    return level


def _downsample(seabed: Seabed, fine: _Level, tile_size: int) -> _Level:
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
    x0, y0, x1, y1 = tiles.tile_range(seabed.bounds, zoom, tile_size)
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


def build(
    out_path: str,
    bounds: tuple[float, float, float, float],
    min_zoom: int = MIN_ZOOM,
    max_zoom: int = MAX_ZOOM,
    tile_size: int = tiles.TILE_SIZE,
    area_name: str | None = None,
    quiet: bool = False,
) -> str:
    """Write one synthetic archive. Returns the path, for callers that chain steps."""
    def say(message: str) -> None:
        if not quiet:
            print(message, flush=True)

    seabed = Seabed(bounds)
    started = time.monotonic()

    say(f"sampling z{max_zoom} ...")
    level = _sample_level(seabed, max_zoom, tile_size)

    all_tiles: dict[tuple[int, int, int], bytes] = {}
    all_tiles.update(_encode_level(level, tile_size))
    for zoom in range(max_zoom - 1, min_zoom - 1, -1):
        say(f"downsampling to z{zoom} ...")
        level = _downsample(seabed, level, tile_size)
        all_tiles.update(_encode_level(level, tile_size))

    where = f" ({area_name})" if area_name else ""
    write_pmtiles(
        out_path,
        all_tiles,
        bounds=bounds,
        center=((bounds[0] + bounds[2]) / 2, (bounds[1] + bounds[3]) / 2),
        center_zoom=min(13, max_zoom),
        tile_type=TILETYPE_PNG,
        tile_compression=COMPRESSION_NONE,
        metadata={
            "name": f"Opennav demo{where} (SYNTHETIC, not for navigation)",
            "format": "png",
            "encoding": "mapbox",
            "type": "baselayer",
            "synthetic": True,
            "vertical_datum": "chart datum (zéro hydrographique), metres positive up",
            "no_data_elevation_m": terrain_rgb.NO_DATA_ELEVATION_M,
            "attribution": "Synthetic test data. Contains no SHOM or IGN material.",
            "description": (
                "Invented bathymetry generated by tools/make_sample_pmtiles.py so that "
                "the app can be run without a SHOM account. The channel, the shoal and "
                "the data hole are geometric shapes placed by fractions of the window; "
                "they bear no relation to the seabed under them. NOT FOR NAVIGATION."
            ),
        },
    )

    size = os.path.getsize(out_path)
    say(
        f"wrote {out_path}: {len(all_tiles)} tiles, {size / 1024 / 1024:.2f} MiB, "
        f"{size / len(all_tiles):.0f} bytes/tile, in {time.monotonic() - started:.1f} s"
    )
    return out_path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", default="sample-brest-synthetic.pmtiles")
    parser.add_argument(
        "--bounds", type=float, nargs=4,
        metavar=("MIN_LON", "MIN_LAT", "MAX_LON", "MAX_LAT"),
        help="fabriquer le fond ailleurs qu'en rade de Brest -- pratique pour poser un "
             "fond factice sous un vrai balisage en attendant les donnees Litto3D. "
             "L'archive reste marquee synthetique et l'app affiche un bandeau rouge.",
    )
    parser.add_argument("--area-name", help="nom de la zone, ecrit dans les metadonnees")
    parser.add_argument("--tile-size", type=int, default=tiles.TILE_SIZE)
    parser.add_argument("--min-zoom", type=int, default=MIN_ZOOM)
    parser.add_argument("--max-zoom", type=int, default=MAX_ZOOM)
    args = parser.parse_args()

    bounds = tuple(args.bounds) if args.bounds else DEFAULT_BOUNDS
    if args.bounds:
        print(f"fond synthetique sur {bounds} -- NE PAS NAVIGUER AVEC", flush=True)

    build(
        args.out,
        bounds,
        min_zoom=args.min_zoom,
        max_zoom=args.max_zoom,
        tile_size=args.tile_size,
        area_name=args.area_name,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
