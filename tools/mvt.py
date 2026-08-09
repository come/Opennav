# Copyright (C) 2026 the Opennav authors
# SPDX-License-Identifier: GPL-3.0-or-later
"""A minimal Mapbox Vector Tile (MVT v2) writer, protobuf hand-rolled.

Only what the seamark layer needs: point features with string/number/bool
properties. No lines, no polygons, no protobuf library.

That sounds like showing off, but the alternative is worse: the usual chain is
`tippecanoe`, a C++ program with no wheels, which would put a compiler between a
contributor and their first buoy. The MVT schema this needs is four messages wide, and
having it in-tree means `build_seamarks.py` runs anywhere Python does -- the same reason
`pmtiles.py` exists.

Spec: https://github.com/mapbox/vector-tile-spec/blob/master/2.1/README.md
"""

from __future__ import annotations

import io
import math
from dataclasses import dataclass, field

#: Tile-internal coordinate range. 4096 is the near-universal convention.
DEFAULT_EXTENT = 4096

GEOM_POINT = 1

_CMD_MOVE_TO = 1


@dataclass
class PointFeature:
    """One seamark. [lon]/[lat] in WGS84 degrees; [properties] become MVT tags."""

    lon: float
    lat: float
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


# --- MVT messages ------------------------------------------------------------


def _encode_value(value) -> bytes:
    """Tile.Layer.Value. Only the types a seamark tag can carry."""
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


def _encode_feature(
    feature: PointFeature,
    x: int,
    y: int,
    keys: list[str],
    key_index: dict[str, int],
    values: list,
    value_index: dict,
    extent: int,
) -> bytes:
    tags: list[int] = []
    for key, value in feature.properties.items():
        if value is None:
            continue
        if key not in key_index:
            key_index[key] = len(keys)
            keys.append(key)
        marker = (type(value).__name__, value)
        if marker not in value_index:
            value_index[marker] = len(values)
            values.append(value)
        tags.append(key_index[key])
        tags.append(value_index[marker])

    px, py = x, y
    geometry = [
        (_CMD_MOVE_TO | (1 << 3)),
        _zigzag(px),
        _zigzag(py),
    ]

    out = b""
    if feature.id is not None:
        out += _varint_field(1, feature.id)
    if tags:
        out += _packed(2, tags)
    out += _varint_field(3, GEOM_POINT)
    out += _packed(4, geometry)
    return _length_delimited(2, out)


def encode_tile(
    layers: dict[str, list[PointFeature]],
    zoom: int,
    tile_x: int,
    tile_y: int,
    extent: int = DEFAULT_EXTENT,
    tile_size: int = 256,
) -> bytes | None:
    """Encodes one tile, or None when no feature falls inside it.

    Features are placed by projecting to Web Mercator pixel space and taking the offset
    within the tile, scaled to `extent`. Anything outside is dropped: a point either
    belongs to this tile or to its neighbour.
    """
    body = b""
    used = False

    for name, features in layers.items():
        keys: list[str] = []
        key_index: dict[str, int] = {}
        values: list = []
        value_index: dict = {}
        encoded_features = b""

        for feature in features:
            local = _to_tile_coords(feature, zoom, tile_x, tile_y, extent, tile_size)
            if local is None:
                continue
            fx, fy = local
            encoded_features += _encode_feature(
                feature, fx, fy, keys, key_index, values, value_index, extent
            )

        if not encoded_features:
            continue
        used = True

        layer = _varint_field(15, 2)                       # version
        layer += _length_delimited(1, name.encode("utf-8"))  # name
        layer += encoded_features
        for key in keys:
            layer += _length_delimited(3, key.encode("utf-8"))
        for value in values:
            layer += _encode_value(value)
        layer += _varint_field(5, extent)
        body += _length_delimited(3, layer)

    return body if used else None


def _to_tile_coords(
    feature: PointFeature, zoom: int, tile_x: int, tile_y: int, extent: int, tile_size: int
) -> tuple[int, int] | None:
    scale = (1 << zoom) * tile_size
    world_x = (feature.lon + 180.0) / 360.0 * scale
    sin_lat = math.sin(math.radians(feature.lat))
    sin_lat = min(max(sin_lat, -0.9999), 0.9999)
    world_y = (0.5 - math.log((1 + sin_lat) / (1 - sin_lat)) / (4 * math.pi)) * scale

    local_x = (world_x - tile_x * tile_size) / tile_size * extent
    local_y = (world_y - tile_y * tile_size) / tile_size * extent
    if not (0 <= local_x < extent and 0 <= local_y < extent):
        return None
    return int(local_x), int(local_y)
