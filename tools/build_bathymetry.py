#!/usr/bin/env python3
# Copyright (C) 2026 the Opennav authors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Turn Litto3D tiles into a Terrain-RGB .pmtiles archive for Opennav.

Runs once, on a workstation. Never on the phone.

    ./tools/build_bathymetry.py \\
        --input ~/litto3d/finistere/*.asc \\
        --out finistere.pmtiles \\
        --datum-shift 3.64 \\
        --area-name "Finistère" \\
        --survey "Litto3D Bretagne 2018-2021"

What it does, and why each step is the way it is:

1.  **Reprojects RGF93 / Lambert-93 (EPSG:2154) to Web Mercator (EPSG:3857).** Done
    lazily, one tile window at a time, so a whole département never has to fit in RAM.

2.  **Resamples with ``max``, not ``average`` and not ``min``.** The plan calls for "the
    minimum *depth* of the cell". Depth is positive down and this pipeline works in
    elevation, which is positive up, so the shallowest sample in a cell is the one with
    the **greatest elevation**. Asking GDAL for ``min`` here -- the word the plan uses --
    would select the deepest sample in every cell and make the entire app optimistic.
    This is the single most dangerous line in the repository.

3.  **Rebuilds every zoom straight from the source** rather than halving the level above.
    Six rounds of "shallowest of four" applied to already-shallowest values drifts
    upwards; resampling z8 from the original samples does not.

4.  **Refuses to guess the vertical datum.** Litto3D altitudes are referenced to IGN 1969,
    the app's arithmetic is referenced to the chart datum (*zéro hydrographique*), and
    the offset between them is a few metres that varies along the coast. Getting it wrong
    is a systematic error on every depth in the app, so ``--datum-shift`` or
    ``--datum-grid`` is mandatory. See docs/DATA.md.

5.  **Writes holes as the no-data sentinel, never as an interpolated depth.** Bathymetric
    lidar stops at 10-20 m depending on turbidity; beyond that Litto3D simply has nothing,
    and the app must say so.

Requires ``rasterio`` (see tools/requirements.txt).
"""

from __future__ import annotations

import argparse
import glob
import math
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import png  # noqa: E402
import terrain_rgb  # noqa: E402
import tiles as tilemath  # noqa: E402
from pmtiles import COMPRESSION_NONE, TILETYPE_PNG, write_pmtiles  # noqa: E402

try:
    import numpy as np
    import rasterio
    from rasterio.enums import Resampling
    from rasterio.transform import Affine
    from rasterio.vrt import WarpedVRT
    from rasterio.windows import Window
except ImportError as exc:  # pragma: no cover - exercised only on a bare machine
    raise SystemExit(
        f"missing dependency ({exc}); install with: pip install -r tools/requirements.txt"
    ) from exc

#: Half-width of the Web Mercator plane, in metres.
MERCATOR_ORIGIN_M = 20037508.342789244

WEB_MERCATOR = "EPSG:3857"


def zoom_transform(zoom: int, tile_size: int) -> tuple[Affine, int]:
    """Affine transform and pixel width of the whole Web Mercator grid at ``zoom``."""
    side = tile_size * (1 << zoom)
    res = 2 * MERCATOR_ORIGIN_M / side
    return Affine(res, 0.0, -MERCATOR_ORIGIN_M, 0.0, -res, MERCATOR_ORIGIN_M), side


class SourceMosaic:
    """The input rasters, presented as one lazily reprojected surface per zoom."""

    def __init__(self, paths: list[str], src_nodata: float | None):
        if not paths:
            raise ValueError("no input rasters")
        self.paths = paths
        self.src_nodata = src_nodata
        self._datasets = [rasterio.open(p) for p in paths]
        for ds, path in zip(self._datasets, paths):
            if ds.crs is None:
                raise ValueError(f"{path} has no CRS; Litto3D tiles must declare EPSG:2154")
        self.bounds_3857 = self._union_bounds()

        # One WarpedVRT per (dataset, zoom), built on demand and kept. Building one per
        # tile made the reprojection setup dominate the run.
        self._vrts: dict[tuple[int, int], list] = {}

    def close(self) -> None:
        for vrts in self._vrts.values():
            for vrt in vrts:
                vrt.close()
        self._vrts.clear()
        for ds in self._datasets:
            ds.close()

    def _vrts_for(self, zoom: int, tile_size: int) -> list:
        cached = self._vrts.get((zoom, tile_size))
        if cached is not None:
            return cached
        transform, side = zoom_transform(zoom, tile_size)
        vrts = []
        for ds in self._datasets:
            nodata = self.src_nodata if self.src_nodata is not None else ds.nodata
            vrts.append(
                WarpedVRT(
                    ds,
                    crs=WEB_MERCATOR,
                    transform=transform,
                    width=side,
                    height=side,
                    # `max` on elevation == shallowest sample in the cell. See the module
                    # docstring; do not "fix" this to `min`.
                    resampling=Resampling.max,
                    src_nodata=nodata,
                    nodata=nodata,
                )
            )
        self._vrts[(zoom, tile_size)] = vrts
        return vrts

    def _union_bounds(self) -> tuple[float, float, float, float]:
        from rasterio.warp import transform_bounds

        xs, ys = [], []
        for ds in self._datasets:
            left, bottom, right, top = transform_bounds(ds.crs, WEB_MERCATOR, *ds.bounds)
            xs += [left, right]
            ys += [bottom, top]
        return min(xs), min(ys), max(xs), max(ys)

    def read_tile(
        self, zoom: int, x: int, y: int, tile_size: int
    ) -> "np.ndarray | None":
        """Shallowest elevation per pixel for one tile, or None if nothing overlaps.

        Returns a masked array: masked cells are the ones the survey does not cover.
        """
        _, side = zoom_transform(zoom, tile_size)
        col_off, row_off = x * tile_size, y * tile_size

        # A WarpedVRT refuses boundless reads, and the tiles at the edge of the archive
        # necessarily hang off the side of the source. Clip the window, read what exists,
        # and leave the rest masked -- masked meaning "not surveyed", which is exactly
        # what a tile that runs past the edge of the survey is.
        c0, r0 = max(0, col_off), max(0, row_off)
        c1, r1 = min(side, col_off + tile_size), min(side, row_off + tile_size)
        if c1 <= c0 or r1 <= r0:
            return None
        window = Window(c0, r0, c1 - c0, r1 - r0)

        stack = None
        for vrt in self._vrts_for(zoom, tile_size):
            chunk = vrt.read(1, window=window, masked=True)
            if np.ma.getmaskarray(chunk).all():
                continue
            data = np.ma.masked_all((tile_size, tile_size), dtype="float64")
            data[r0 - row_off:r1 - row_off, c0 - col_off:c1 - col_off] = chunk
            stack = data if stack is None else np.ma.maximum(stack, data)
        return stack


def _sample_datum_grid(grid_path: str, zoom: int, x: int, y: int, tile_size: int):
    """Chart-datum separation, resampled onto the same tile grid.

    Bilinear is right here: the separation surface is smooth by construction, and unlike
    the bathymetry it is not something a rock can hide inside.
    """
    transform, side = zoom_transform(zoom, tile_size)
    window = Window(x * tile_size, y * tile_size, tile_size, tile_size)
    with rasterio.open(grid_path) as ds:
        with WarpedVRT(
            ds, crs=WEB_MERCATOR, transform=transform, width=side, height=side,
            resampling=Resampling.bilinear,
        ) as vrt:
            return vrt.read(1, window=window, boundless=True, masked=True)


def encode_tile(elevations, tile_size: int) -> bytes:
    """Encode a masked elevation array as a Terrain-RGB PNG."""
    filled = np.ma.filled(
        elevations.astype("float64"), terrain_rgb.NO_DATA_ELEVATION_M
    )
    # Anything that came out of the survey at or above the sentinel threshold is either
    # nonsense or a nodata value that escaped the mask; either way it is not navigable.
    filled = np.where(
        np.isnan(filled) | (filled >= terrain_rgb.NO_DATA_THRESHOLD_M),
        terrain_rgb.NO_DATA_ELEVATION_M,
        filled,
    )
    clamped = np.clip(filled, terrain_rgb.BASE_M, terrain_rgb.MAX_ENCODABLE_M)
    # ceil, matching TerrainRgb.encode: quantisation raises the seabed, never lowers it.
    steps = np.ceil(
        (clamped - terrain_rgb.BASE_M) / terrain_rgb.INTERVAL_M - 1e-9
    ).astype("int64")
    np.clip(steps, 0, 0xFFFFFF, out=steps)

    rgb = np.empty((tile_size, tile_size, 3), dtype="uint8")
    rgb[..., 0] = (steps >> 16) & 0xFF
    rgb[..., 1] = (steps >> 8) & 0xFF
    rgb[..., 2] = steps & 0xFF
    return png.encode_rgb(tile_size, tile_size, rgb.tobytes())


def build(args: argparse.Namespace) -> int:
    paths: list[str] = []
    for pattern in args.input:
        expanded = sorted(glob.glob(pattern))
        paths += expanded if expanded else [pattern]
    missing = [p for p in paths if not os.path.exists(p)]
    if missing:
        raise SystemExit(f"input not found: {missing[0]}")

    mosaic = SourceMosaic(paths, args.src_nodata)
    try:
        left, bottom, right, top = mosaic.bounds_3857
        bounds_deg = (
            _x_to_lon(left), _y_to_lat(bottom), _x_to_lon(right), _y_to_lat(top),
        )
        if args.clip_bounds:
            bounds_deg = (
                max(bounds_deg[0], args.clip_bounds[0]),
                max(bounds_deg[1], args.clip_bounds[1]),
                min(bounds_deg[2], args.clip_bounds[2]),
                min(bounds_deg[3], args.clip_bounds[3]),
            )
        print(f"source covers {bounds_deg}", flush=True)

        out_tiles: dict[tuple[int, int, int], bytes] = {}
        started = time.monotonic()
        for zoom in range(args.min_zoom, args.max_zoom + 1):
            x0, y0, x1, y1 = tilemath.tile_range(bounds_deg, zoom, args.tile_size)
            written = 0
            for x in range(x0, x1 + 1):
                for y in range(y0, y1 + 1):
                    data = mosaic.read_tile(zoom, x, y, args.tile_size)
                    if data is None:
                        continue
                    data = _apply_datum(data, args, zoom, x, y)
                    out_tiles[(zoom, x, y)] = encode_tile(data, args.tile_size)
                    written += 1
            print(
                f"z{zoom}: {written} tiles "
                f"({tilemath.ground_resolution_m(bounds_deg[3], zoom, args.tile_size):.1f} m/px)",
                flush=True,
            )

        if not out_tiles:
            raise SystemExit("no tiles produced; check --input and --clip-bounds")

        write_pmtiles(
            args.out,
            out_tiles,
            bounds=bounds_deg,
            tile_type=TILETYPE_PNG,
            tile_compression=COMPRESSION_NONE,
            metadata={
                "name": args.area_name,
                "format": "png",
                "encoding": "mapbox",
                "type": "baselayer",
                "synthetic": False,
                "survey": args.survey,
                "vertical_datum": "chart datum (zéro hydrographique), metres positive up",
                "vertical_shift_applied_m": args.datum_shift,
                "vertical_shift_grid": args.datum_grid,
                "resampling": "max on elevation (= shallowest sample in the cell)",
                "no_data_elevation_m": terrain_rgb.NO_DATA_ELEVATION_M,
                "attribution": args.attribution,
                "generated_by": "opennav tools/build_bathymetry.py",
            },
        )
    finally:
        mosaic.close()

    size = os.path.getsize(args.out)
    print(
        f"wrote {args.out}: {len(out_tiles)} tiles, {size / 1024 / 1024:.1f} MiB, "
        f"{size / len(out_tiles):.0f} bytes/tile, in {time.monotonic() - started:.0f} s"
    )
    return 0


def _apply_datum(data, args: argparse.Namespace, zoom: int, x: int, y: int):
    if args.already_chart_datum:
        return data
    if args.datum_grid:
        separation = _sample_datum_grid(args.datum_grid, zoom, x, y, args.tile_size)
        return data + separation
    return data + args.datum_shift


def _x_to_lon(x: float) -> float:
    return x / MERCATOR_ORIGIN_M * 180.0


def _y_to_lat(y: float) -> float:
    return math.degrees(
        2 * math.atan(math.exp(y / MERCATOR_ORIGIN_M * math.pi)) - math.pi / 2
    )


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--input", nargs="+", required=True,
                        help="Litto3D rasters, or glob patterns")
    parser.add_argument("--out", required=True)
    parser.add_argument("--area-name", default="Opennav bathymetry")
    parser.add_argument("--survey", default="Litto3D Bretagne 2018-2021",
                        help="shown in the app next to the depths, so the user can see "
                             "how old the survey is")
    parser.add_argument(
        "--attribution",
        default="Shom - IGN, 2024. https://doi.org/10.17183/LITTO3D_BZH_2018_2021 "
                "(Licence Ouverte 2.0)",
    )
    parser.add_argument("--min-zoom", type=int, default=0)
    parser.add_argument("--max-zoom", type=int, default=14)
    parser.add_argument("--tile-size", type=int, default=tilemath.TILE_SIZE)
    parser.add_argument("--src-nodata", type=float, default=None,
                        help="override the nodata value declared by the inputs")
    parser.add_argument("--clip-bounds", type=float, nargs=4,
                        metavar=("MIN_LON", "MIN_LAT", "MAX_LON", "MAX_LAT"))

    datum = parser.add_mutually_exclusive_group(required=True)
    datum.add_argument(
        "--datum-shift", type=float,
        help="metres to ADD to source elevations to express them above the chart datum. "
             "For IGN 1969 altitudes this is the height of the IGN69 zero above the "
             "zéro hydrographique at the port of reference (about 3.64 m at Brest). A "
             "constant is an approximation: it is only defensible over a small area.",
    )
    datum.add_argument(
        "--datum-grid",
        help="raster of the IGN69-to-chart-datum separation, in metres, sampled and "
             "added per pixel. Preferred over --datum-shift for anything bigger than "
             "one harbour.",
    )
    datum.add_argument(
        "--already-chart-datum", action="store_true",
        help="the inputs are already referenced to the zéro hydrographique. Only pass "
             "this if you have checked the product's metadata, not because it seems "
             "likely.",
    )
    return build(parser.parse_args())


if __name__ == "__main__":
    raise SystemExit(main())
