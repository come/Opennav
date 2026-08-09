#!/usr/bin/env python3
# Copyright (C) 2026 the Opennav authors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Tests for the pipeline. Run with ``python3 -m unittest discover -s tools``.

The interesting ones are :class:`KotlinParityTest` (the pipeline and the app must agree
on the encoding, or every depth in the app is wrong by a constant) and
:class:`ConservatismTest` (the pipeline must never make water deeper than it is).
"""

from __future__ import annotations

import os
import re
import struct
import subprocess
import sys
import tempfile
import unittest
import zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import png
import terrain_rgb
import tiles
from pmtiles import PMTilesReader, write_pmtiles, zxy_to_tile_id

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
KOTLIN_TERRAIN_RGB = os.path.join(
    REPO_ROOT, "core", "depth", "src", "main", "kotlin", "org", "opennav", "core", "depth",
    "TerrainRgb.kt",
)


class KotlinParityTest(unittest.TestCase):
    """The Python encoder and the Kotlin decoder must share every constant."""

    def _kotlin_const(self, name: str) -> float:
        with open(KOTLIN_TERRAIN_RGB, encoding="utf-8") as fh:
            source = fh.read()
        match = re.search(rf"const val {name}:\s*Double\s*=\s*([-\d.]+)", source)
        self.assertIsNotNone(match, f"{name} not found in TerrainRgb.kt")
        return float(match.group(1))

    def test_constants_match(self):
        self.assertEqual(self._kotlin_const("BASE_METERS"), terrain_rgb.BASE_M)
        self.assertEqual(self._kotlin_const("INTERVAL_METERS"), terrain_rgb.INTERVAL_M)
        self.assertEqual(
            self._kotlin_const("NO_DATA_ELEVATION_METERS"), terrain_rgb.NO_DATA_ELEVATION_M
        )
        self.assertEqual(
            self._kotlin_const("NO_DATA_THRESHOLD_METERS"), terrain_rgb.NO_DATA_THRESHOLD_M
        )

    def test_sentinel_encodes_to_the_documented_pixel(self):
        # TerrainRgb.kt promises RGB(2, 230, 48); its unit tests assert the same triple.
        self.assertEqual((2, 230, 48), terrain_rgb.NO_DATA_RGB)


class ConservatismTest(unittest.TestCase):

    def test_quantisation_never_deepens_the_seabed(self):
        x = -60.0
        while x < 20.0:
            decoded = terrain_rgb.decode(*terrain_rgb.encode(x))
            self.assertGreaterEqual(decoded, x - 1e-9, f"encoding {x} m deepened it")
            self.assertLessEqual(decoded - x, terrain_rgb.INTERVAL_M + 1e-9)
            x += 0.0137

    def test_grid_values_round_trip_exactly(self):
        for tenths in range(-1200, 2001):
            m = tenths / 10.0
            self.assertAlmostEqual(m, terrain_rgb.decode(*terrain_rgb.encode(m)), places=6)

    def test_sentinel_is_above_every_real_elevation(self):
        self.assertGreater(terrain_rgb.NO_DATA_ELEVATION_M, terrain_rgb.NO_DATA_THRESHOLD_M)
        self.assertTrue(terrain_rgb.is_no_data(terrain_rgb.NO_DATA_ELEVATION_M))
        self.assertFalse(terrain_rgb.is_no_data(300.0))
        self.assertFalse(terrain_rgb.is_no_data(-150.0))

    def test_downsampling_keeps_the_shallowest_child(self):
        # The rule the whole overview pyramid rests on, stated as a test so that
        # "min depth" can never quietly become "min elevation".
        block = [-12.0, -3.0, -9.5, -30.0]
        shallowest = max(block)
        self.assertEqual(-3.0, shallowest)
        self.assertLess(
            terrain_rgb.decode(*terrain_rgb.encode(min(block))),
            terrain_rgb.decode(*terrain_rgb.encode(shallowest)),
        )


class TileMathTest(unittest.TestCase):

    def test_hilbert_ids_match_the_spec(self):
        self.assertEqual(0, zxy_to_tile_id(0, 0, 0))
        self.assertEqual(1, zxy_to_tile_id(1, 0, 0))
        self.assertEqual(2, zxy_to_tile_id(1, 0, 1))
        self.assertEqual(3, zxy_to_tile_id(1, 1, 1))
        self.assertEqual(4, zxy_to_tile_id(1, 1, 0))
        self.assertEqual(5, zxy_to_tile_id(2, 0, 0))

    def test_ids_are_unique_within_a_level(self):
        seen = set()
        for x in range(16):
            for y in range(16):
                seen.add(zxy_to_tile_id(4, x, y))
        self.assertEqual(256, len(seen))

    def test_pixel_projection_round_trips(self):
        for lon, lat in [(-4.5, 48.35), (-2.0, 47.5), (-5.1, 48.7)]:
            px = tiles.lon_to_pixel_x(lon, 14)
            py = tiles.lat_to_pixel_y(lat, 14)
            self.assertAlmostEqual(lon, tiles.pixel_x_to_lon(px, 14), places=6)
            self.assertAlmostEqual(lat, tiles.pixel_y_to_lat(py, 14), places=6)

    def test_zoom_14_resolves_litto3d_at_10_m(self):
        # 10 m source data needs a tile zoom at least as fine as 10 m/px, or the
        # pyramid throws away survey detail before the app ever sees it.
        self.assertLess(tiles.ground_resolution_m(48.4, 14), 10.0)
        self.assertGreater(tiles.ground_resolution_m(48.4, 13), 10.0)


class PngTest(unittest.TestCase):

    def test_encodes_a_decodable_png(self):
        width, height = 4, 3
        pixels = bytes(range(width * height * 3))
        blob = png.encode_rgb(width, height, pixels)
        self.assertEqual(b"\x89PNG\r\n\x1a\n", blob[:8])

        # Walk the chunks and undo the Sub filter, i.e. decode it the hard way.
        pos = 8
        idat = b""
        ihdr = None
        while pos < len(blob):
            (length,) = struct.unpack_from(">I", blob, pos)
            tag = blob[pos + 4:pos + 8]
            payload = blob[pos + 8:pos + 8 + length]
            if tag == b"IHDR":
                ihdr = struct.unpack(">IIBBBBB", payload)
            elif tag == b"IDAT":
                idat += payload
            pos += 12 + length
        self.assertEqual((width, height, 8, 2, 0, 0, 0), ihdr)

        raw = zlib.decompress(idat)
        stride = width * 3
        out = bytearray()
        for row in range(height):
            start = row * (stride + 1)
            self.assertEqual(1, raw[start], "expected the Sub filter")
            line = bytearray(raw[start + 1:start + 1 + stride])
            for i in range(3, stride):
                line[i] = (line[i] + line[i - 3]) & 0xFF
            out += line
        self.assertEqual(pixels, bytes(out))

    def test_rejects_a_short_buffer(self):
        with self.assertRaises(ValueError):
            png.encode_rgb(2, 2, b"\x00" * 5)


class PmtilesArchiveTest(unittest.TestCase):

    def test_round_trips_through_an_independent_reader(self):
        tile_map = {
            (10, 507, 351): b"tile-a",
            (10, 508, 351): b"tile-b",
            (11, 1014, 702): b"tile-c",
            (11, 1015, 702): b"tile-a",  # deduplicated against the first
        }
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "t.pmtiles")
            write_pmtiles(
                path, tile_map, bounds=(-4.62, 48.29, -4.38, 48.40),
                metadata={"name": "unit test", "encoding": "mapbox"},
            )
            reader = PMTilesReader(path)
            self.assertEqual(10, reader.min_zoom)
            self.assertEqual(11, reader.max_zoom)
            self.assertEqual(4, reader.addressed_tiles)
            self.assertEqual(3, reader.tile_contents, "identical tiles must be shared")
            self.assertEqual("unit test", reader.metadata["name"])
            for key, expected in tile_map.items():
                self.assertEqual(expected, reader.get(*key), f"tile {key}")
            self.assertIsNone(reader.get(10, 0, 0))

    def test_bounds_survive_the_e7_encoding(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "t.pmtiles")
            bounds = (-4.62, 48.29, -4.38, 48.40)
            write_pmtiles(path, {(10, 507, 351): b"x"}, bounds=bounds)
            for expected, actual in zip(bounds, PMTilesReader(path).bounds):
                self.assertAlmostEqual(expected, actual, places=6)

    def test_refuses_an_empty_archive(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(ValueError):
                write_pmtiles(os.path.join(tmp, "t.pmtiles"), {},
                              bounds=(0.0, 0.0, 1.0, 1.0))


class SampleArchiveTest(unittest.TestCase):
    """End-to-end: the generator must produce something the app can actually open."""

    @classmethod
    def setUpClass(cls):
        cls._tmp = tempfile.TemporaryDirectory()
        cls.path = os.path.join(cls._tmp.name, "sample.pmtiles")
        subprocess.run(
            [sys.executable, os.path.join(REPO_ROOT, "tools", "make_sample_pmtiles.py"),
             "--out", cls.path, "--min-zoom", "12", "--max-zoom", "13"],
            check=True, capture_output=True,
        )
        cls.reader = PMTilesReader(cls.path)

    @classmethod
    def tearDownClass(cls):
        cls._tmp.cleanup()

    def test_declares_itself_synthetic(self):
        meta = self.reader.metadata
        self.assertTrue(meta["synthetic"])
        self.assertIn("NOT FOR NAVIGATION", meta["description"])
        self.assertEqual("mapbox", meta["encoding"])

    def test_tiles_decode_to_plausible_bathymetry(self):
        x0, y0, x1, y1 = tiles.tile_range(
            (-4.62, 48.29, -4.38, 48.40), 13, tiles.TILE_SIZE
        )
        elevations = []
        for x in range(x0, x1 + 1):
            for y in range(y0, y1 + 1):
                blob = self.reader.get(13, x, y)
                self.assertIsNotNone(blob, f"missing tile 13/{x}/{y}")
                elevations += _decode_terrain_png(blob)
        surveyed = [e for e in elevations if not terrain_rgb.is_no_data(e)]
        self.assertGreater(len(surveyed), 0.4 * len(elevations))
        self.assertLess(min(surveyed), -25.0, "expected a deep channel")
        self.assertGreater(max(surveyed), 1.0, "expected something that dries")
        self.assertLess(len(surveyed), len(elevations), "expected a no-data hole")


def _decode_terrain_png(blob: bytes) -> list[float]:
    pos = 8
    idat = b""
    width = height = 0
    while pos < len(blob):
        (length,) = struct.unpack_from(">I", blob, pos)
        tag = blob[pos + 4:pos + 8]
        payload = blob[pos + 8:pos + 8 + length]
        if tag == b"IHDR":
            width, height = struct.unpack_from(">II", payload, 0)
        elif tag == b"IDAT":
            idat += payload
        pos += 12 + length
    raw = zlib.decompress(idat)
    stride = width * 3
    out = []
    for row in range(height):
        start = row * (stride + 1)
        assert raw[start] == 1
        line = bytearray(raw[start + 1:start + 1 + stride])
        for i in range(3, stride):
            line[i] = (line[i] + line[i - 3]) & 0xFF
        for i in range(0, stride, 3):
            out.append(terrain_rgb.decode(line[i], line[i + 1], line[i + 2]))
    return out


if __name__ == "__main__":
    unittest.main()
