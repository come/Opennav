#!/usr/bin/env python3
# Copyright (C) 2026 the Opennav authors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Turn an OpenStreetMap extract into the seamark overlay Opennav renders.

Runs once, on a workstation, like the bathymetry pipeline.

    # 1. an extract covering your area, from https://download.geofabrik.de
    wget https://download.geofabrik.de/europe/france/bretagne-latest.osm.pbf

    # 2. the overlay
    ./tools/build_seamarks.py \\
        --input bretagne-latest.osm.pbf \\
        --out bretagne-seamarks.pmtiles \\
        --clip-bounds -3.32 47.28 -2.65 47.66

What it keeps: nodes carrying `seamark:type`, which is where OpenSeaMap puts buoys,
beacons, lights, wrecks and isolated dangers. Ways and relations are ignored -- a lateral
buoy is a point, and the coastline is a different problem with different failure modes.

What it deliberately does **not** do is decide what is safe. It carries the OSM tags
through to the tiles; the app maps them to symbols. That separation matters: OSM seamark
data is contributed, uneven, and sometimes wrong, and the honest place to say so is the
app's Sources screen, not a silent filter in a build script.

Requires `osmium` (see tools/requirements-dev.txt).
"""

from __future__ import annotations

import argparse
import gzip
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import tiles as tilemath  # noqa: E402
from mvt import PointFeature, encode_tile  # noqa: E402
from pmtiles import COMPRESSION_GZIP, write_pmtiles  # noqa: E402

try:
    import osmium
except ImportError as exc:  # pragma: no cover - exercised only on a bare machine
    raise SystemExit(
        f"missing dependency ({exc}); install with: pip install -r tools/requirements-dev.txt"
    ) from exc

#: PMTiles header value for Mapbox Vector Tiles.
TILETYPE_MVT = 1

LAYER_NAME = "seamarks"

#: Tags carried through to the tiles. Everything else is dropped: an overlay that ships
#: every OSM tag would be several times larger for symbols nobody draws.
KEPT_TAGS = (
    "seamark:type",
    "seamark:name",
    "name",
    "seamark:buoy_cardinal:category",
    "seamark:buoy_cardinal:colour",
    "seamark:buoy_lateral:category",
    "seamark:buoy_lateral:colour",
    "seamark:buoy_safe_water:colour",
    "seamark:buoy_special_purpose:colour",
    "seamark:beacon_cardinal:category",
    "seamark:beacon_lateral:category",
    "seamark:light:colour",
    "seamark:light:character",
    "seamark:light:period",
    "seamark:light:range",
    "seamark:topmark:shape",
    "seamark:wreck:category",
    "seamark:rock:water_level",
)

#: Short property names in the tiles. MVT stores the key string once per layer per tile,
#: but the app's style expressions read better without the prefix.
SHORT_NAMES = {
    "seamark:type": "type",
    "seamark:name": "name",
    "name": "name",
    "seamark:buoy_cardinal:category": "cardinal",
    "seamark:buoy_cardinal:colour": "colour",
    "seamark:buoy_lateral:category": "lateral",
    "seamark:buoy_lateral:colour": "colour",
    "seamark:buoy_safe_water:colour": "colour",
    "seamark:buoy_special_purpose:colour": "colour",
    "seamark:beacon_cardinal:category": "cardinal",
    "seamark:beacon_lateral:category": "lateral",
    "seamark:light:colour": "light_colour",
    "seamark:light:character": "light_character",
    "seamark:light:period": "light_period",
    "seamark:light:range": "light_range",
    "seamark:topmark:shape": "topmark",
    "seamark:wreck:category": "wreck",
    "seamark:rock:water_level": "water_level",
}


def read_seamarks(
    path: str, clip: tuple[float, float, float, float] | None
) -> list[PointFeature]:
    """Every `seamark:type` node in the extract, as MVT-ready points."""
    features: list[PointFeature] = []
    processor = osmium.FileProcessor(path).with_filter(
        osmium.filter.KeyFilter("seamark:type")
    )
    for obj in processor:
        location = getattr(obj, "location", None)
        if location is None or not location.valid():
            continue
        lon, lat = location.lon, location.lat
        if clip and not (clip[0] <= lon <= clip[2] and clip[1] <= lat <= clip[3]):
            continue

        properties: dict[str, str] = {}
        for tag in KEPT_TAGS:
            value = obj.tags.get(tag)
            if value:
                properties.setdefault(SHORT_NAMES[tag], value)
        features.append(PointFeature(lon=lon, lat=lat, properties=properties, id=obj.id))
    return features


def build(args: argparse.Namespace) -> int:
    started = time.monotonic()
    clip = tuple(args.clip_bounds) if args.clip_bounds else None

    print(f"reading {args.input} ...", flush=True)
    features = read_seamarks(args.input, clip)
    if not features:
        raise SystemExit(
            "no seamark node found. Check --clip-bounds, and that the extract really "
            "covers the area: an OSM extract cropped to land has no buoys in it."
        )

    kinds: dict[str, int] = {}
    for feature in features:
        kinds[feature.properties.get("type", "?")] = (
            kinds.get(feature.properties.get("type", "?"), 0) + 1
        )
    print(f"{len(features)} seamarks")
    for kind, count in sorted(kinds.items(), key=lambda kv: -kv[1])[:12]:
        print(f"  {count:6d}  {kind}")

    lons = [f.lon for f in features]
    lats = [f.lat for f in features]
    bounds = (min(lons), min(lats), max(lons), max(lats))

    out_tiles: dict[tuple[int, int, int], bytes] = {}
    for zoom in range(args.min_zoom, args.max_zoom + 1):
        x0, y0, x1, y1 = tilemath.tile_range(bounds, zoom, args.tile_size)
        written = 0
        for x in range(x0, x1 + 1):
            for y in range(y0, y1 + 1):
                blob = encode_tile(
                    {LAYER_NAME: features}, zoom, x, y, tile_size=args.tile_size
                )
                if blob is None:
                    continue
                out_tiles[(zoom, x, y)] = gzip.compress(blob, mtime=0)
                written += 1
        print(f"z{zoom}: {written} tiles", flush=True)

    write_pmtiles(
        args.out,
        out_tiles,
        bounds=bounds,
        tile_type=TILETYPE_MVT,
        tile_compression=COMPRESSION_GZIP,
        metadata={
            "name": args.area_name,
            "format": "pbf",
            "type": "overlay",
            "attribution": "© OpenSeaMap / OpenStreetMap contributors (ODbL)",
            "generated_by": "opennav tools/build_seamarks.py",
            "seamark_count": len(features),
            # MapLibre reads this to know the layer exists before a tile arrives.
            "vector_layers": [
                {
                    "id": LAYER_NAME,
                    "description": "OSM seamark:* nodes",
                    "minzoom": args.min_zoom,
                    "maxzoom": args.max_zoom,
                }
            ],
        },
    )

    size = os.path.getsize(args.out)
    print(
        f"wrote {args.out}: {len(out_tiles)} tiles, {size / 1024 / 1024:.2f} MiB, "
        f"in {time.monotonic() - started:.0f} s"
    )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--input", required=True, help="an .osm.pbf or .osm extract")
    parser.add_argument("--out", required=True)
    parser.add_argument("--area-name", default="Opennav seamarks")
    parser.add_argument("--min-zoom", type=int, default=10)
    parser.add_argument("--max-zoom", type=int, default=14)
    parser.add_argument("--tile-size", type=int, default=tilemath.TILE_SIZE)
    parser.add_argument(
        "--clip-bounds", type=float, nargs=4,
        metavar=("MIN_LON", "MIN_LAT", "MAX_LON", "MAX_LAT"),
    )
    return build(parser.parse_args())


if __name__ == "__main__":
    raise SystemExit(main())
