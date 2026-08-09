#!/usr/bin/env python3
# Copyright (C) 2026 the Opennav authors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Turn an OpenStreetMap extract into the base map under the buoyage.

Runs once, on a workstation, like the rest of the pipeline.

    ./tools/build_basemap.py \\
        --input bretagne-latest.osm.pbf \\
        --out bretagne-base.pmtiles \\
        --clip-bounds -5.30 47.00 -1.00 48.95

What "OpenSeaMap" looks like on the web is two things stacked: an ordinary OSM base map,
and the seamark overlay on top. Opennav had only ever built the second, which is why the
buoys floated on a blank background. This builds the first, offline, from the same
extract.

Six layers, chosen for what a coastal navigator needs rather than for what OSM has:

  * ``coastline``  -- the line itself, from ``natural=coastline``
  * ``land``       -- islands: a closed coastline way *is* the island's outline, so
                      Houat, Hoedic, Belle-Ile and every islet in the Golfe du Morbihan
                      come out as fillable polygons for free
  * ``water``      -- inland water, so an estuary does not read as land
  * ``structure``  -- piers, breakwaters, groynes: the things you round at three knots
  * ``harbour``    -- marinas and harbour basins
  * ``place``      -- settlements and named islands, carrying their name

**The mainland is not filled.** In OSM the mainland coastline is a set of open ways that
close only at the scale of a continent; turning them into land polygons inside a
bounding box means reassembling rings against the box edge, and getting that wrong draws
land where there is sea. On a navigation chart that is the one error that must not be
made quietly, so the mainland is drawn as a coastline stroke with land left unfilled.
Islands are safe because their rings are already closed in the data.

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
from mvt import LineFeature, PointFeature, PolygonFeature, encode_tile, project  # noqa: E402
from pmtiles import COMPRESSION_GZIP, write_pmtiles  # noqa: E402

try:
    import osmium
except ImportError as exc:  # pragma: no cover - exercised only on a bare machine
    raise SystemExit(
        f"missing dependency ({exc}); install with: pip install -r tools/requirements-dev.txt"
    ) from exc

#: PMTiles header value for Mapbox Vector Tiles.
TILETYPE_MVT = 1

LAYER_LAND = "land"
LAYER_WATER = "water"
LAYER_HARBOUR = "harbour"
LAYER_COASTLINE = "coastline"
LAYER_STRUCTURE = "structure"
LAYER_PLACE = "place"

#: Layers in draw order. The app styles them by name, so this list is a contract.
LAYER_ORDER = (
    LAYER_LAND, LAYER_WATER, LAYER_HARBOUR, LAYER_COASTLINE, LAYER_STRUCTURE, LAYER_PLACE,
)

#: Water bodies that are actually bodies of water.
#:
#: `natural=bay` and `natural=strait` are deliberately absent. They are names for a piece
#: of open sea, and OSM closes them with an arbitrary straight line across the mouth --
#: so mapping them produced a fill covering the Golfe du Morbihan whose southern edge was
#: a ruled line across navigable water. Nothing about that edge exists.
WATER_TAGS = {
    ("natural", "water"),
    ("landuse", "reservoir"),
    ("landuse", "basin"),
    ("waterway", "riverbank"),
}
HARBOUR_TAGS = {
    ("leisure", "marina"),
    ("landuse", "harbour"),
    ("harbour", "yes"),
}
STRUCTURE_VALUES = {"pier", "breakwater", "groyne", "dyke", "quay"}

#: Which settlements survive to which zoom. A base map that shows every hamlet at z8 is
#: a base map nobody can read, and most of its bytes are names nobody asked for.
PLACE_MIN_ZOOM = {
    "city": 6, "town": 9, "island": 9, "village": 11, "suburb": 12,
    "islet": 13, "hamlet": 13, "locality": 13,
}

#: Longest run of points kept in one feature before it is cut. A single coastline way
#: can carry tens of thousands of points and span a third of Brittany; left whole, it is
#: a candidate for every tile in that span and the tiler spends its life clipping it.
MAX_POINTS_PER_CHUNK = 250

#: A feature smaller than this, in tile units, is not drawn at that zoom. At extent 4096
#: over a 256 px tile there are 16 units to the pixel, so this is half a pixel: below
#: what a screen can show, and at low zoom it is most of the file.
MIN_FEATURE_UNITS = 8.0


def _bbox(points: list[tuple[float, float]]) -> tuple[float, float, float, float]:
    lons = [p[0] for p in points]
    lats = [p[1] for p in points]
    return min(lons), min(lats), max(lons), max(lats)


def _intersects(a: tuple[float, float, float, float],
                b: tuple[float, float, float, float]) -> bool:
    return not (a[2] < b[0] or a[0] > b[2] or a[3] < b[1] or a[1] > b[3])


def _chunk(points: list[tuple[float, float]]) -> list[list[tuple[float, float]]]:
    """Split a long way, repeating the join point so the line stays continuous."""
    if len(points) <= MAX_POINTS_PER_CHUNK:
        return [points]
    out = []
    start = 0
    while start < len(points) - 1:
        end = min(start + MAX_POINTS_PER_CHUNK, len(points))
        out.append(points[start:end])
        start = end - 1
    return out


def _way_points(way) -> list[tuple[float, float]] | None:
    """Way geometry, or None if the extract is missing any of its nodes."""
    points = []
    for node in way.nodes:
        if not node.location.valid():
            return None
        points.append((node.location.lon, node.location.lat))
    return points if len(points) >= 2 else None


def _ring_points(ring) -> list[tuple[float, float]] | None:
    points = []
    for node in ring:
        if not node.location.valid():
            return None
        points.append((node.location.lon, node.location.lat))
    return points if len(points) >= 3 else None


def _name_properties(tags) -> dict:
    out = {}
    for key in ("name", "seamark:name"):
        value = tags.get(key)
        if value:
            out["name"] = value
            break
    return out


class Reader:
    """One pass over the extract, sorting what it finds into the six layers."""

    def __init__(self, clip: tuple[float, float, float, float] | None):
        self.clip = clip
        self.layers: dict[str, list] = {name: [] for name in LAYER_ORDER}
        self.skipped_incomplete = 0

    def _keep(self, points: list[tuple[float, float]]) -> bool:
        return self.clip is None or _intersects(_bbox(points), self.clip)

    def read(self, path: str) -> None:
        # with_areas() assembles multipolygon relations as well as closed ways, which is
        # what makes an estuary with islands in it come out as one polygon with holes
        # rather than as a lake covering the islands.
        for obj in osmium.FileProcessor(path).with_areas():
            if isinstance(obj, osmium.osm.Area):
                self._area(obj)
            elif isinstance(obj, osmium.osm.Way):
                self._way(obj)
            elif isinstance(obj, osmium.osm.Node):
                self._node(obj)

    def _way(self, way) -> None:
        tags = way.tags
        if tags.get("natural") == "coastline":
            points = _way_points(way)
            if points is None:
                self.skipped_incomplete += 1
                return
            if not self._keep(points):
                return
            if points[0] == points[-1] and len(points) >= 4:
                # A closed coastline way is an island, and its ring is already closed in
                # the data -- no reassembly, no chance of inverting land and sea.
                self.layers[LAYER_LAND].append(
                    PolygonFeature(rings=[points], properties=_name_properties(tags))
                )
            else:
                for chunk in _chunk(points):
                    self.layers[LAYER_COASTLINE].append(LineFeature(points=chunk))
            return

        if tags.get("man_made") in STRUCTURE_VALUES:
            points = _way_points(way)
            if points is None or not self._keep(points):
                return
            properties = {"kind": tags.get("man_made")}
            properties.update(_name_properties(tags))
            for chunk in _chunk(points):
                self.layers[LAYER_STRUCTURE].append(
                    LineFeature(points=chunk, properties=properties)
                )

    def _area(self, area) -> None:
        tags = area.tags
        pairs = {(tag.k, tag.v) for tag in tags}
        matched = pairs & WATER_TAGS
        if matched:
            layer = LAYER_WATER
        else:
            matched = pairs & HARBOUR_TAGS
            if not matched:
                return
            layer = LAYER_HARBOUR
        # Carried through so the app can style or drop a category without a rebuild being
        # the only remedy. The bay fill had to wait for one of those.
        kind = sorted(matched)[0][1]

        for outer in area.outer_rings():
            exterior = _ring_points(outer)
            if exterior is None or not self._keep(exterior):
                continue
            rings = [exterior]
            for inner in area.inner_rings(outer):
                hole = _ring_points(inner)
                if hole is not None:
                    rings.append(hole)
            properties = {"kind": kind}
            properties.update(_name_properties(tags))
            self.layers[layer].append(PolygonFeature(rings=rings, properties=properties))

    def _node(self, node) -> None:
        kind = node.tags.get("place")
        if kind not in PLACE_MIN_ZOOM:
            return
        name = node.tags.get("name")
        if not name:
            return
        location = node.location
        if not location.valid():
            return
        if self.clip and not (
            self.clip[0] <= location.lon <= self.clip[2]
            and self.clip[1] <= location.lat <= self.clip[3]
        ):
            return
        self.layers[LAYER_PLACE].append(
            PointFeature(
                lon=location.lon, lat=location.lat,
                properties={"kind": kind, "name": name},
            )
        )


def _feature_bbox(feature) -> tuple[float, float, float, float]:
    if isinstance(feature, PointFeature):
        return (feature.lon, feature.lat, feature.lon, feature.lat)
    if isinstance(feature, LineFeature):
        return _bbox(feature.points)
    return _bbox(feature.rings[0])


def _too_small(box: tuple[float, float, float, float], zoom: int, tile_size: int) -> bool:
    x0, y0 = project(box[0], box[3], zoom, tile_size)
    x1, y1 = project(box[2], box[1], zoom, tile_size)
    units = max(abs(x1 - x0), abs(y1 - y0)) / tile_size * 4096
    return units < MIN_FEATURE_UNITS


def bucket(
    features: list, zoom: int, tile_size: int, drop_small: bool
) -> dict[tuple[int, int], list]:
    """Which features can possibly appear in which tile.

    By bounding box, which is the point: testing every feature against every tile is
    quadratic, and a Brittany-wide extract makes that the difference between a coffee
    and an afternoon.
    """
    buckets: dict[tuple[int, int], list] = {}
    for feature in features:
        box = _feature_bbox(feature)
        if drop_small and _too_small(box, zoom, tile_size):
            continue
        x0, y0, x1, y1 = tilemath.tile_range(box, zoom, tile_size)
        for x in range(x0, x1 + 1):
            for y in range(y0, y1 + 1):
                buckets.setdefault((x, y), []).append(feature)
    return buckets


def build(args: argparse.Namespace) -> int:
    started = time.monotonic()
    clip = tuple(args.clip_bounds) if args.clip_bounds else None

    print(f"reading {args.input} ...", flush=True)
    reader = Reader(clip)
    reader.read(args.input)

    counts = {name: len(items) for name, items in reader.layers.items()}
    for name in LAYER_ORDER:
        print(f"  {counts[name]:7d}  {name}")
    if reader.skipped_incomplete:
        print(f"  {reader.skipped_incomplete:7d}  ways skipped (nodes outside the extract)")

    if not any(counts.values()):
        raise SystemExit(
            "nothing found. Check --clip-bounds, and that the extract really covers the "
            "area: a .osm.pbf cropped inland has no coastline in it."
        )
    if not counts[LAYER_COASTLINE] and not counts[LAYER_LAND]:
        raise SystemExit(
            "no coastline at all. That is almost certainly the wrong extract or the "
            "wrong bounds -- a base map with no coastline is not worth building."
        )

    boxes = [
        _feature_bbox(f) for items in reader.layers.values() for f in items
    ]
    bounds = (
        max(min(b[0] for b in boxes), clip[0] if clip else -180.0),
        max(min(b[1] for b in boxes), clip[1] if clip else -90.0),
        min(max(b[2] for b in boxes), clip[2] if clip else 180.0),
        min(max(b[3] for b in boxes), clip[3] if clip else 90.0),
    )

    out_tiles: dict[tuple[int, int, int], bytes] = {}
    for zoom in range(args.min_zoom, args.max_zoom + 1):
        per_layer = {}
        for name in LAYER_ORDER:
            features = reader.layers[name]
            if name == LAYER_PLACE:
                features = [
                    f for f in features
                    if PLACE_MIN_ZOOM.get(f.properties.get("kind"), 99) <= zoom
                ]
            per_layer[name] = bucket(
                features, zoom, args.tile_size, drop_small=(name != LAYER_PLACE)
            )

        coordinates = set()
        for buckets in per_layer.values():
            coordinates |= set(buckets)

        written = 0
        for x, y in sorted(coordinates):
            layers = {
                name: per_layer[name].get((x, y), [])
                for name in LAYER_ORDER
                if per_layer[name].get((x, y))
            }
            blob = encode_tile(layers, zoom, x, y, tile_size=args.tile_size)
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
            "type": "baselayer",
            "attribution": "© OpenStreetMap contributors (ODbL)",
            "generated_by": "opennav tools/build_basemap.py",
            "mainland_filled": False,
            "vector_layers": [
                {"id": name, "minzoom": args.min_zoom, "maxzoom": args.max_zoom,
                 "description": description}
                for name, description in (
                    (LAYER_LAND, "islands, from closed natural=coastline ways"),
                    (LAYER_WATER, "inland water"),
                    (LAYER_HARBOUR, "marinas and harbour basins"),
                    (LAYER_COASTLINE, "natural=coastline"),
                    (LAYER_STRUCTURE, "piers, breakwaters, groynes"),
                    (LAYER_PLACE, "settlements and named islands"),
                )
            ],
        },
    )

    size = os.path.getsize(args.out)
    print(
        f"wrote {args.out}: {len(out_tiles)} tiles, {size / 1024 / 1024:.2f} MiB, "
        f"{size / max(len(out_tiles), 1):.0f} bytes/tile, in {time.monotonic() - started:.0f} s"
    )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--input", required=True, help="an .osm.pbf or .osm extract")
    parser.add_argument("--out", required=True)
    parser.add_argument("--area-name", default="Opennav base map")
    parser.add_argument("--min-zoom", type=int, default=6)
    parser.add_argument("--max-zoom", type=int, default=14)
    parser.add_argument("--tile-size", type=int, default=tilemath.TILE_SIZE)
    parser.add_argument(
        "--clip-bounds", type=float, nargs=4,
        metavar=("MIN_LON", "MIN_LAT", "MAX_LON", "MAX_LAT"),
    )
    return build(parser.parse_args())


if __name__ == "__main__":
    raise SystemExit(main())
