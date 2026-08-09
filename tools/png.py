# Copyright (C) 2026 the Opennav authors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Just enough PNG to write 8-bit RGB tiles without pulling in Pillow."""

from __future__ import annotations

import struct
import zlib


def _chunk(tag: bytes, payload: bytes) -> bytes:
    return (
        struct.pack(">I", len(payload))
        + tag
        + payload
        + struct.pack(">I", zlib.crc32(tag + payload) & 0xFFFFFFFF)
    )


def encode_rgb(width: int, height: int, pixels: bytes, *, level: int = 9) -> bytes:
    """Encode raw RGB bytes (``width * height * 3``) as a PNG.

    Uses filter type 1 (Sub) on every row. Terrain-RGB tiles are dominated by the blue
    channel stepping by one unit between neighbouring cells, so Sub turns most of a tile
    into a run of small values and compresses far better than no filtering. Anything
    fancier belongs in the production pipeline, where GDAL does the work.
    """
    if len(pixels) != width * height * 3:
        raise ValueError(
            f"expected {width * height * 3} bytes of RGB, got {len(pixels)}"
        )

    stride = width * 3
    raw = bytearray()
    zeros = b"\x00\x00\x00"
    for row in range(height):
        start = row * stride
        line = pixels[start:start + stride]
        raw.append(1)  # Sub filter
        # Sub predicts each byte from the same channel of the pixel to its left; the
        # leftmost pixel predicts from zero, which `zeros + line[:-3]` supplies.
        raw += bytes((a - b) & 0xFF for a, b in zip(line, zeros + line[:-3]))

    return (
        b"\x89PNG\r\n\x1a\n"
        + _chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
        + _chunk(b"IDAT", zlib.compress(bytes(raw), level))
        + _chunk(b"IEND", b"")
    )
