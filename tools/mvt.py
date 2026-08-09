# Copyright (C) 2026 the Opennav authors
# SPDX-License-Identifier: GPL-3.0-or-later
"""A minimal Mapbox Vector Tile (MVT v2) writer, protobuf hand-rolled.

Points, lines and polygons, with string/number/bool properties. No protobuf library.

That sounds like showing off, but the alternative is worse: the usual chain is
`tippecanoe`, a C++ program with no wheels, which would put a compiler between a
contributor and their first buoy. The MVT schema this needs is four messages wide, and
having it in-tree means the builders run anywhere Python does -- the same reason
`pmtiles.py` exists.

Geometry is clipped to the tile (with a small buffer, so a stroke that straddles the
edge is not cut in half) and simplified with Douglas-Peucker at a tolerance measured in
tile units, which is what keeps a coastline from putting the same ten thousand points
into every zoom level.

Spec: https://github.com/mapbox/vector-tile-spec/blob/master/2.1/README.md
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field

#: Tile-internal coordinate range. 4096 is the near-universal convention.
DEFAULT_EXTENT = 4096

#: How far outside the tile geometry is kept, in tile units. A line drawn 6 px wide at
#: the tile edge needs its geometry to continue past that edge, or the join with the
#: neighbouring tile shows as a notch.
DEFAULT_BUFFER = DEFAULT_EXTENT // 32

#: Douglas-Peucker tolerance, in tile units. At extent 4096 over a 256 px tile, 4 units
#: is a quarter of a pixel: below what a screen can show, above what noise costs.
DEFAULT_TOLERANCE = 4.0

GEOM_POINT = 1
GEOM_LINESTRING = 2
GEOM_POLYGON = 3

_CMD_MOVE_TO = 1
_CMD_LINE_TO = 2
_CMD_CLOSE_PATH = 7


@dataclass
class PointFeature:
    """One point. [lon]/[lat] in WGS84 degrees; [properties] become MVT tags."""

    lon: float
    lat: float
    properties: dict = field(default_factory=dict)
    id: int | None = None


@dataclass
class LineFeature:
    """One line. [points] is a list of (lon, lat) in WGS84 degrees."""

    points: list[tuple[float, float]]
    properties: dict = field(default_factory=dict)
    id: int | None = None


@dataclass
class PolygonFeature:
    """One polygon. [rings] is the exterior ring first, then any holes.

    Winding on input is not significant: it is fixed on the way out, per tile, because
    clipping can reverse it and because the renderer's fill rule depends on getting it
    right.
    """

    rings: list[list[tuple[float, float]]]
    properties: dict = field(default_factory=dict)
    id: int | None = None


# --- protobuf primitives -----------------------------------------------------


def _varint(value: int) -> bytes:
    if value < 0:
        raise ValueError("varints are unsigned here")
    out = bytearray()
    while True:
        chunk = value & 0x7F
        value >>= 7
        if value:
            out.append(chunk | 0x80)
        else:
            out.append(chunk)
            return bytes(out)


def _tag(field_number: int, wire_type: int) -> bytes:
    return _varint((field_number << 3) | wire_type)


def _length_delimited(field_number: int, payload: bytes) -> bytes:
    return _tag(field_number, 2) + _varint(len(payload)) + payload


def _varint_field(field_number: int, value: int) -> bytes:
    return _tag(field_number, 0) + _varint(value)


def _zigzag(value: int) -> int:
    return (value << 1) ^ (value >> 31)


def _packed(field_number: int, values: list[int]) -> bytes:
    body = b"".join(_varint(v) for v in values)
    return _length_delimited(field_number, body)


def _command(command: int, count: int) -> int:
    return (command & 0x7) | (count << 3)


# --- projection ---------------------------------------------------------------


def project(lon: float, lat: float, zoom: int, tile_size: int = 256) -> tuple[float, float]:
    """WGS84 to Web Mercator pixel coordinates at [zoom]."""
    scale = (1 << zoom) * tile_size
    x = (lon + 180.0) / 360.0 * scale
    sin_lat = math.sin(math.radians(lat))
    sin_lat = min(max(sin_lat, -0.9999), 0.9999)
    y = (0.5 - math.log((1 + sin_lat) / (1 - sin_lat)) / (4 * math.pi)) * scale
    return x, y


def _to_local(
    points: list[tuple[float, float]], zoom: int, tile_x: int, tile_y: int,
    extent: int, tile_size: int,
) -> list[tuple[float, float]]:
    scale = extent / tile_size
    ox, oy = tile_x * tile_size, tile_y * tile_size
    out = []
    for lon, lat in points:
        wx, wy = project(lon, lat, zoom, tile_size)
        out.append(((wx - ox) * scale, (wy - oy) * scale))
    return out


# --- clipping and simplification ----------------------------------------------


def clip_segment(
    a: tuple[float, float], b: tuple[float, float], lo: float, hi: float
) -> tuple[tuple[float, float], tuple[float, float]] | None:
    """Liang-Barsky against the square [lo, hi]. None when wholly outside."""
    t0, t1 = 0.0, 1.0
    dx, dy = b[0] - a[0], b[1] - a[1]
    # The four half-planes: left, right, top, bottom.
    for p, q in ((-dx, a[0] - lo), (dx, hi - a[0]), (-dy, a[1] - lo), (dy, hi - a[1])):
        if p == 0:
            if q < 0:
                return None
            continue
        t = q / p
        if p < 0:
            if t > t1:
                return None
            t0 = max(t0, t)
        else:
            if t < t0:
                return None
            t1 = min(t1, t)
    return (
        (a[0] + t0 * dx, a[1] + t0 * dy),
        (a[0] + t1 * dx, a[1] + t1 * dy),
    )


def clip_line(
    points: list[tuple[float, float]], lo: float, hi: float
) -> list[list[tuple[float, float]]]:
    """Clip a polyline, returning the pieces that survive.

    A line crossing the tile twice comes back as two pieces rather than one with a
    shortcut across the middle, which is the whole reason this is not just a filter.
    """
    parts: list[list[tuple[float, float]]] = []
    current: list[tuple[float, float]] = []
    for a, b in zip(points, points[1:]):
        clipped = clip_segment(a, b, lo, hi)
        if clipped is None:
            if len(current) >= 2:
                parts.append(current)
            current = []
            continue
        p, q = clipped
        if current and _same(current[-1], p):
            current.append(q)
        else:
            if len(current) >= 2:
                parts.append(current)
            current = [p, q]
    if len(current) >= 2:
        parts.append(current)
    return parts


def _same(a: tuple[float, float], b: tuple[float, float]) -> bool:
    return abs(a[0] - b[0]) < 1e-9 and abs(a[1] - b[1]) < 1e-9


def clip_ring(
    ring: list[tuple[float, float]], lo: float, hi: float
) -> list[tuple[float, float]]:
    """Sutherland-Hodgman against the square [lo, hi]. Empty when wholly outside.

    A concave ring can come back with zero-width slivers running along the clip edge.
    They are invisible in a fill and cost a few coordinates, which is a better trade
    than the general polygon clipper needed to avoid them.
    """
    output = list(ring)
    for axis, limit, keep_greater in (
        (0, lo, True), (0, hi, False), (1, lo, True), (1, hi, False)
    ):
        if not output:
            return []
        inp, output = output, []
        for index, current in enumerate(inp):
            previous = inp[index - 1]
            current_in = current[axis] >= limit if keep_greater else current[axis] <= limit
            previous_in = previous[axis] >= limit if keep_greater else previous[axis] <= limit
            if current_in:
                if not previous_in:
                    output.append(_intersect(previous, current, axis, limit))
                output.append(current)
            elif previous_in:
                output.append(_intersect(previous, current, axis, limit))
    return output


def _intersect(
    a: tuple[float, float], b: tuple[float, float], axis: int, limit: float
) -> tuple[float, float]:
    span = b[axis] - a[axis]
    t = 0.0 if span == 0 else (limit - a[axis]) / span
    return (a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1]))


def simplify(
    points: list[tuple[float, float]], tolerance: float
) -> list[tuple[float, float]]:
    """Douglas-Peucker, iterative so a long coastline cannot blow the stack."""
    if tolerance <= 0 or len(points) < 3:
        return points
    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    squared = tolerance * tolerance
    while stack:
        first, last = stack.pop()
        if last <= first + 1:
            continue
        worst, worst_index = -1.0, first
        for index in range(first + 1, last):
            distance = _squared_distance_to_segment(points[index], points[first], points[last])
            if distance > worst:
                worst, worst_index = distance, index
        if worst > squared:
            keep[worst_index] = True
            stack.append((first, worst_index))
            stack.append((worst_index, last))
    return [p for p, k in zip(points, keep) if k]


def _squared_distance_to_segment(
    p: tuple[float, float], a: tuple[float, float], b: tuple[float, float]
) -> float:
    dx, dy = b[0] - a[0], b[1] - a[1]
    if dx == 0 and dy == 0:
        return (p[0] - a[0]) ** 2 + (p[1] - a[1]) ** 2
    t = ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / (dx * dx + dy * dy)
    t = min(1.0, max(0.0, t))
    return (p[0] - a[0] - t * dx) ** 2 + (p[1] - a[1] - t * dy) ** 2


def signed_area(ring: list[tuple[float, float]]) -> float:
    """Surveyor's formula. Positive means clockwise on screen, y being down.

    That is the sign MVT v2 requires of an exterior ring, and the reason this function
    is not a detail: a ring wound the wrong way is drawn as a hole, so an island becomes
    a lake in the fill and nothing in the pipeline complains.
    """
    total = 0.0
    for index in range(len(ring)):
        x0, y0 = ring[index]
        x1, y1 = ring[(index + 1) % len(ring)]
        total += x0 * y1 - x1 * y0
    return total / 2.0


# --- MVT messages ------------------------------------------------------------


def _encode_value(value) -> bytes:
    """Tile.Layer.Value. Only the types an OSM tag can carry."""
    if isinstance(value, bool):
        return _length_delimited(4, _varint_field(7, 1 if value else 0))
    if isinstance(value, int):
        # sint_value, zigzagged: covers negative elevations and light periods alike.
        zig = (value << 1) ^ (value >> 63)
        return _length_delimited(4, _varint_field(6, zig))
    if isinstance(value, float):
        import struct

        return _length_delimited(4, _tag(3, 1) + struct.pack("<d", value))
    encoded = str(value).encode("utf-8")
    return _length_delimited(4, _length_delimited(1, encoded))


class _Dictionary:
    """The per-layer key and value tables MVT deduplicates tags through."""

    def __init__(self):
        self.keys: list[str] = []
        self.values: list = []
        self._key_index: dict[str, int] = {}
        self._value_index: dict = {}

    def tags(self, properties: dict) -> list[int]:
        out: list[int] = []
        for key, value in properties.items():
            if value is None:
                continue
            if key not in self._key_index:
                self._key_index[key] = len(self.keys)
                self.keys.append(key)
            marker = (type(value).__name__, value)
            if marker not in self._value_index:
                self._value_index[marker] = len(self.values)
                self.values.append(value)
            out.append(self._key_index[key])
            out.append(self._value_index[marker])
        return out


def _encode_geometry(
    parts: list[list[tuple[int, int]]], geometry_type: int
) -> list[int]:
    """Command stream for one feature, as integers ready to be packed."""
    out: list[int] = []
    cursor_x = cursor_y = 0
    for part in parts:
        if not part:
            continue
        first_x, first_y = part[0]
        out += [_command(_CMD_MOVE_TO, 1), _zigzag(first_x - cursor_x),
                _zigzag(first_y - cursor_y)]
        cursor_x, cursor_y = first_x, first_y

        # A closed ring repeats its first point; ClosePath carries that, so the repeat
        # must not also be written or the renderer sees a zero-length edge.
        rest = part[1:-1] if geometry_type == GEOM_POLYGON else part[1:]
        if not rest:
            continue
        out.append(_command(_CMD_LINE_TO, len(rest)))
        for x, y in rest:
            out += [_zigzag(x - cursor_x), _zigzag(y - cursor_y)]
            cursor_x, cursor_y = x, y
        if geometry_type == GEOM_POLYGON:
            out.append(_command(_CMD_CLOSE_PATH, 1))
    return out


def _encode_feature(
    parts: list[list[tuple[int, int]]],
    geometry_type: int,
    properties: dict,
    feature_id: int | None,
    dictionary: _Dictionary,
) -> bytes:
    geometry = _encode_geometry(parts, geometry_type)
    if not geometry:
        return b""
    tags = dictionary.tags(properties)
    out = b""
    if feature_id is not None:
        out += _varint_field(1, feature_id)
    if tags:
        out += _packed(2, tags)
    out += _varint_field(3, geometry_type)
    out += _packed(4, geometry)
    return _length_delimited(2, out)


def _round(points: list[tuple[float, float]]) -> list[tuple[int, int]]:
    """Round to the integer grid, dropping points that land on their predecessor."""
    out: list[tuple[int, int]] = []
    for x, y in points:
        point = (int(round(x)), int(round(y)))
        if not out or point != out[-1]:
            out.append(point)
    return out


def _tile_parts(
    feature,
    zoom: int,
    tile_x: int,
    tile_y: int,
    extent: int,
    tile_size: int,
    buffer: float,
    tolerance: float,
) -> tuple[list[list[tuple[int, int]]], int] | None:
    """Clip, simplify and quantise one feature for one tile. None when it misses."""
    lo, hi = -buffer, extent + buffer

    if isinstance(feature, PointFeature):
        (x, y), = _to_local([(feature.lon, feature.lat)], zoom, tile_x, tile_y,
                            extent, tile_size)
        # A point belongs to exactly one tile: no buffer, or every marker is drawn
        # twice at the seams.
        if not (0 <= x < extent and 0 <= y < extent):
            return None
        return [[(int(x), int(y))]], GEOM_POINT

    if isinstance(feature, LineFeature):
        local = _to_local(feature.points, zoom, tile_x, tile_y, extent, tile_size)
        parts = []
        for piece in clip_line(local, lo, hi):
            rounded = _round(simplify(piece, tolerance))
            if len(rounded) >= 2:
                parts.append(rounded)
        return (parts, GEOM_LINESTRING) if parts else None

    if isinstance(feature, PolygonFeature):
        parts = []
        for index, ring in enumerate(feature.rings):
            local = _to_local(ring, zoom, tile_x, tile_y, extent, tile_size)
            clipped = clip_ring(local, lo, hi)
            if len(clipped) < 3:
                if index == 0:
                    return None
                continue
            simplified = simplify(clipped + [clipped[0]], tolerance)
            rounded = _round(simplified)
            if len(rounded) < 4:
                if index == 0:
                    return None
                continue
            if rounded[0] != rounded[-1]:
                rounded.append(rounded[0])
            exterior = index == 0
            area = signed_area(rounded[:-1])
            if (area > 0) != exterior:
                rounded.reverse()
            parts.append(rounded)
        return (parts, GEOM_POLYGON) if parts else None

    raise TypeError(f"unsupported feature: {type(feature).__name__}")


def encode_tile(
    layers: dict[str, list],
    zoom: int,
    tile_x: int,
    tile_y: int,
    extent: int = DEFAULT_EXTENT,
    tile_size: int = 256,
    buffer: float = DEFAULT_BUFFER,
    tolerance: float = DEFAULT_TOLERANCE,
) -> bytes | None:
    """Encodes one tile, or None when nothing falls inside it."""
    body = b""
    used = False

    for name, features in layers.items():
        dictionary = _Dictionary()
        encoded_features = b""

        for feature in features:
            prepared = _tile_parts(
                feature, zoom, tile_x, tile_y, extent, tile_size, buffer, tolerance
            )
            if prepared is None:
                continue
            parts, geometry_type = prepared
            encoded_features += _encode_feature(
                parts, geometry_type, feature.properties, feature.id, dictionary
            )

        if not encoded_features:
            continue
        used = True

        layer = _varint_field(15, 2)                         # version
        layer += _length_delimited(1, name.encode("utf-8"))  # name
        layer += encoded_features
        for key in dictionary.keys:
            layer += _length_delimited(3, key.encode("utf-8"))
        for value in dictionary.values:
            layer += _encode_value(value)
        layer += _varint_field(5, extent)
        body += _length_delimited(3, layer)

    return body if used else None
