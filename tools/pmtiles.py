# Copyright (C) 2026 the Opennav authors
# SPDX-License-Identifier: GPL-3.0-or-later
"""A minimal, dependency-free PMTiles v3 writer.

Only what Phase 0 needs: build a single-file tile archive out of an in-memory
``{(z, x, y): bytes}`` map, with a root directory and, when the root would overflow the
16 KiB budget, one level of leaf directories.

This exists so that ``make_sample_pmtiles.py`` can produce a loadable archive on a
machine with nothing but CPython -- no GDAL, no Go toolchain, no SHOM account. The
production pipeline (``build_bathymetry.py``) shells out to the official ``pmtiles``
CLI instead, which handles clustering and deduplication better than this does.

Spec: https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md
"""

from __future__ import annotations

import gzip
import io
import json
import struct
from dataclasses import dataclass

HEADER_LEN = 127
MAGIC = b"PMTiles"
ROOT_DIR_MAX_BYTES = 16384

# Header enum values.
COMPRESSION_NONE = 1
COMPRESSION_GZIP = 2

TILETYPE_PNG = 2


def zxy_to_tile_id(z: int, x: int, y: int) -> int:
    """Position of a tile on the Hilbert curve, as PMTiles orders them."""
    if z < 0 or z > 26:
        raise ValueError(f"zoom out of range: {z}")
    n = 1 << z
    if not (0 <= x < n and 0 <= y < n):
        raise ValueError(f"tile {z}/{x}/{y} is outside the pyramid")

    # Tiles of all shallower zooms come first: sum of 4^k for k < z.
    acc = ((1 << (z * 2)) - 1) // 3

    d = 0
    tx, ty = x, y
    s = n >> 1
    while s > 0:
        rx = 1 if (tx & s) > 0 else 0
        ry = 1 if (ty & s) > 0 else 0
        d += s * s * ((3 * rx) ^ ry)
        # Rotate the quadrant.
        if ry == 0:
            if rx == 1:
                tx = s - 1 - tx
                ty = s - 1 - ty
            tx, ty = ty, tx
        s >>= 1
    return acc + d


def _write_varint(buf: io.BytesIO, value: int) -> None:
    if value < 0:
        raise ValueError("varints are unsigned")
    while True:
        chunk = value & 0x7F
        value >>= 7
        if value:
            buf.write(bytes((chunk | 0x80,)))
        else:
            buf.write(bytes((chunk,)))
            return


@dataclass
class _Entry:
    tile_id: int
    offset: int
    length: int
    run_length: int


def _serialize_directory(entries: list[_Entry]) -> bytes:
    buf = io.BytesIO()
    _write_varint(buf, len(entries))

    last_id = 0
    for e in entries:
        _write_varint(buf, e.tile_id - last_id)
        last_id = e.tile_id
    for e in entries:
        _write_varint(buf, e.run_length)
    for e in entries:
        _write_varint(buf, e.length)
    for i, e in enumerate(entries):
        if i > 0 and e.offset == entries[i - 1].offset + entries[i - 1].length:
            _write_varint(buf, 0)
        else:
            _write_varint(buf, e.offset + 1)
    return buf.getvalue()


def _to_e7(deg: float) -> int:
    return int(round(deg * 10_000_000))


def write_pmtiles(
    path: str,
    tiles: dict[tuple[int, int, int], bytes],
    *,
    bounds: tuple[float, float, float, float],
    center: tuple[float, float] | None = None,
    center_zoom: int | None = None,
    metadata: dict | None = None,
    tile_type: int = TILETYPE_PNG,
    tile_compression: int = COMPRESSION_NONE,
) -> None:
    """Write ``tiles`` to ``path``.

    ``bounds`` is (min_lon, min_lat, max_lon, max_lat) in degrees. Tiles are stored
    verbatim; PNG is already deflated, so ``tile_compression`` stays ``COMPRESSION_NONE``.
    """
    if not tiles:
        raise ValueError("refusing to write an empty archive")

    zooms = sorted({z for z, _, _ in tiles})
    min_zoom, max_zoom = zooms[0], zooms[-1]

    # Deduplicate identical tiles (large uniform no-data areas collapse nicely).
    ordered = sorted(tiles.items(), key=lambda kv: zxy_to_tile_id(*kv[0]))
    blob = io.BytesIO()
    offsets: dict[bytes, tuple[int, int]] = {}
    entries: list[_Entry] = []
    addressed = 0
    for (z, x, y), data in ordered:
        addressed += 1
        placement = offsets.get(data)
        if placement is None:
            placement = (blob.tell(), len(data))
            offsets[data] = placement
            blob.write(data)
        tile_id = zxy_to_tile_id(z, x, y)
        # Run-length encode consecutive tile ids pointing at the same blob.
        if (
            entries
            and entries[-1].offset == placement[0]
            and entries[-1].length == placement[1]
            and entries[-1].tile_id + entries[-1].run_length == tile_id
        ):
            entries[-1].run_length += 1
        else:
            entries.append(_Entry(tile_id, placement[0], placement[1], 1))

    tile_data = blob.getvalue()

    metadata_bytes = gzip.compress(
        json.dumps(metadata or {}, ensure_ascii=False).encode("utf-8"),
        mtime=0,
    )

    root_entries, leaf_bytes = _split_directories(entries)
    root_bytes = gzip.compress(_serialize_directory(root_entries), mtime=0)
    if len(root_bytes) > ROOT_DIR_MAX_BYTES:
        raise ValueError(
            f"root directory is {len(root_bytes)} bytes, over the {ROOT_DIR_MAX_BYTES} "
            "byte budget; this writer only implements one level of leaves"
        )

    root_offset = HEADER_LEN
    metadata_offset = root_offset + len(root_bytes)
    leaf_offset = metadata_offset + len(metadata_bytes)
    tile_offset = leaf_offset + len(leaf_bytes)

    min_lon, min_lat, max_lon, max_lat = bounds
    c_lon, c_lat = center if center else ((min_lon + max_lon) / 2, (min_lat + max_lat) / 2)
    c_zoom = center_zoom if center_zoom is not None else max_zoom

    header = bytearray(HEADER_LEN)
    header[0:7] = MAGIC
    header[7] = 3
    struct.pack_into("<QQQQQQQQ", header, 8,
                     root_offset, len(root_bytes),
                     metadata_offset, len(metadata_bytes),
                     leaf_offset, len(leaf_bytes),
                     tile_offset, len(tile_data))
    struct.pack_into("<QQQ", header, 72, addressed, sum(e.run_length for e in entries),
                     len(offsets))
    header[96] = 1  # clustered: entries are written in tile-id order
    header[97] = COMPRESSION_GZIP  # internal (directory + metadata) compression
    header[98] = tile_compression
    header[99] = tile_type
    header[100] = min_zoom
    header[101] = max_zoom
    struct.pack_into("<iiii", header, 102,
                     _to_e7(min_lon), _to_e7(min_lat), _to_e7(max_lon), _to_e7(max_lat))
    header[118] = c_zoom
    struct.pack_into("<ii", header, 119, _to_e7(c_lon), _to_e7(c_lat))

    with open(path, "wb") as fh:
        fh.write(bytes(header))
        fh.write(root_bytes)
        fh.write(metadata_bytes)
        fh.write(leaf_bytes)
        fh.write(tile_data)


class PMTilesReader:
    """Reads back what :func:`write_pmtiles` produced.

    Deliberately independent of the writer's internals -- it parses the file from the
    header outwards -- so that ``test_tools.py`` round-tripping through it is a real
    check on the format rather than a check that the writer agrees with itself.
    """

    def __init__(self, path: str):
        self.path = path
        with open(path, "rb") as fh:
            self._blob = fh.read()
        if self._blob[:7] != MAGIC:
            raise ValueError("not a PMTiles archive")
        if self._blob[7] != 3:
            raise ValueError(f"unsupported PMTiles version {self._blob[7]}")
        (
            self.root_offset, self.root_length,
            self.metadata_offset, self.metadata_length,
            self.leaf_offset, self.leaf_length,
            self.tile_offset, self.tile_length,
        ) = struct.unpack_from("<QQQQQQQQ", self._blob, 8)
        self.addressed_tiles, self.tile_entries, self.tile_contents = struct.unpack_from(
            "<QQQ", self._blob, 72
        )
        self.clustered = bool(self._blob[96])
        self.internal_compression = self._blob[97]
        self.tile_compression = self._blob[98]
        self.tile_type = self._blob[99]
        self.min_zoom = self._blob[100]
        self.max_zoom = self._blob[101]
        e7 = struct.unpack_from("<iiii", self._blob, 102)
        self.bounds = tuple(v / 10_000_000 for v in e7)

    @property
    def metadata(self) -> dict:
        raw = self._blob[self.metadata_offset:self.metadata_offset + self.metadata_length]
        if self.internal_compression == COMPRESSION_GZIP:
            raw = gzip.decompress(raw)
        return json.loads(raw.decode("utf-8"))

    def get(self, z: int, x: int, y: int) -> bytes | None:
        tile_id = zxy_to_tile_id(z, x, y)
        entries = self._directory(self.root_offset, self.root_length)
        for _ in range(4):  # bounded, matching the native reader's depth limit
            entry = _find_entry(entries, tile_id)
            if entry is None:
                return None
            if entry.run_length == 0:
                entries = self._directory(self.leaf_offset + entry.offset, entry.length)
                continue
            start = self.tile_offset + entry.offset
            return self._blob[start:start + entry.length]
        raise ValueError("maximum directory depth exceeded")

    def _directory(self, offset: int, length: int) -> list[_Entry]:
        raw = self._blob[offset:offset + length]
        if self.internal_compression == COMPRESSION_GZIP:
            raw = gzip.decompress(raw)
        return _deserialize_directory(raw)


def _read_varint(buf: memoryview, pos: int) -> tuple[int, int]:
    value = 0
    shift = 0
    while True:
        byte = buf[pos]
        pos += 1
        value |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return value, pos
        shift += 7


def _deserialize_directory(raw: bytes) -> list[_Entry]:
    buf = memoryview(raw)
    count, pos = _read_varint(buf, 0)
    ids = []
    last = 0
    for _ in range(count):
        delta, pos = _read_varint(buf, pos)
        last += delta
        ids.append(last)
    runs = []
    for _ in range(count):
        v, pos = _read_varint(buf, pos)
        runs.append(v)
    lengths = []
    for _ in range(count):
        v, pos = _read_varint(buf, pos)
        lengths.append(v)
    offsets = []
    for i in range(count):
        v, pos = _read_varint(buf, pos)
        if v == 0 and i > 0:
            offsets.append(offsets[i - 1] + lengths[i - 1])
        else:
            offsets.append(v - 1)
    return [_Entry(ids[i], offsets[i], lengths[i], runs[i]) for i in range(count)]


def _find_entry(entries: list[_Entry], tile_id: int) -> _Entry | None:
    lo, hi = 0, len(entries) - 1
    found = None
    while lo <= hi:
        mid = (lo + hi) // 2
        if entries[mid].tile_id <= tile_id:
            found = entries[mid]
            lo = mid + 1
        else:
            hi = mid - 1
    if found is None:
        return None
    if found.run_length == 0:
        return found  # leaf pointer
    if tile_id < found.tile_id + found.run_length:
        return found
    return None


def _split_directories(entries: list[_Entry]) -> tuple[list[_Entry], bytes]:
    """Return (root entries, packed leaf bytes).

    Tries a root-only archive first, which is what every Phase 0 sample ends up as.
    """
    root_only = gzip.compress(_serialize_directory(entries), mtime=0)
    if len(root_only) <= ROOT_DIR_MAX_BYTES:
        return entries, b""

    # Chunk into leaves until the root fits. Doubling the leaf size converges quickly.
    leaf_size = 4096
    while leaf_size <= 1 << 20:
        leaves = io.BytesIO()
        root: list[_Entry] = []
        for i in range(0, len(entries), leaf_size):
            chunk = entries[i:i + leaf_size]
            packed = gzip.compress(_serialize_directory(chunk), mtime=0)
            root.append(_Entry(chunk[0].tile_id, leaves.tell(), len(packed), 0))
            leaves.write(packed)
        if len(gzip.compress(_serialize_directory(root), mtime=0)) <= ROOT_DIR_MAX_BYTES:
            return root, leaves.getvalue()
        leaf_size *= 2
    raise ValueError("archive too large for a single level of leaf directories")
