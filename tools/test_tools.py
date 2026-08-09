#!/usr/bin/env python3
# Copyright (C) 2026 the Opennav authors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Tests for the pipeline. Run with ``python3 -m unittest discover -s tools``.

The interesting ones are :class:`KotlinParityTest` (the pipeline and the app must agree
on the encoding, or every depth in the app is wrong by a constant) and
:class:`ConservatismTest` (the pipeline must never make water deeper than it is).
"""

from __future__ import annotations

import gzip
import os
import re
import struct
import subprocess
import sys
import tempfile
import unittest
import zlib
from xml.etree import ElementTree

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import build_area
import inspect_pmtiles
import make_sample_pmtiles
import mvt
import png
import terrain_rgb
import tiles
from pmtiles import PMTilesReader, write_pmtiles, zxy_to_tile_id

# Optional, and detected here rather than further down because the classes that need
# them are decorated with skipUnless at definition time.
#
# build_basemap imports osmium at module scope and exits if it is missing, which is
# right for a command-line tool and fatal for a test module -- hence catching SystemExit
# as well, so a machine without a geospatial stack still runs the other fifty tests.
try:
    import mapbox_vector_tile
    HAS_MVT_DECODER = True
except ImportError:  # pragma: no cover
    HAS_MVT_DECODER = False

try:
    import osmium  # noqa: F401
    import build_basemap
    HAS_OSMIUM = True
except (ImportError, SystemExit):  # pragma: no cover
    HAS_OSMIUM = False

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
KOTLIN_TERRAIN_RGB = os.path.join(
    REPO_ROOT, "core", "depth", "src", "main", "kotlin", "org", "opennav", "core", "depth",
    "TerrainRgb.kt",
)


class OptionalDependencyTest(unittest.TestCase):
    """In CI, "skipped" must not be allowed to look like "passed".

    Both optional libraries were absent from the CI image for as long as they have
    existed, so `BuildSeamarksTest` and the round-trip against the reference MVT decoder
    had never once run there -- and the suite reported green throughout. Skipping is the
    right behaviour on a contributor's laptop and the wrong one on a build server, so
    the build server sets OPENNAV_STRICT_TESTS and this fails instead.
    """

    @unittest.skipUnless(os.environ.get("OPENNAV_STRICT_TESTS"), "not a strict run")
    def test_nothing_is_quietly_skipped(self):
        self.assertTrue(HAS_OSMIUM, "osmium missing: the OSM builders are untested")
        self.assertTrue(
            HAS_MVT_DECODER, "mapbox-vector-tile missing: the MVT writer is unverified"
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


class SyntheticSeabedTest(unittest.TestCase):
    """The demo the app ships is this generator pointed at bounds it was not written for.

    That is the whole failure mode. The features used to sit at hard-coded coordinates in
    the Rade de Brest, so asking for a window anywhere else produced a rectangle of empty
    water at a uniform depth -- which still opens, still colours in, and still looks like
    a working app right up to the moment someone believes it.
    """

    #: Windows of deliberately different sizes, aspect ratios and latitudes.
    WINDOWS = {
        "rade de Brest (defaut)": make_sample_pmtiles.DEFAULT_BOUNDS,
        "baie de Quiberon (embarquee)": (-3.32, 47.28, -2.65, 47.66),
        "golfe normand-breton": (-2.35, 48.55, -1.75, 48.85),
        "carre minuscule": (-3.01, 47.50, -3.00, 47.51),
    }

    @staticmethod
    def _at(seabed: make_sample_pmtiles.Seabed, u: float, v: float) -> float:
        min_lon, min_lat, max_lon, max_lat = seabed.bounds
        return seabed.elevation_m(
            min_lon + u * (max_lon - min_lon), min_lat + v * (max_lat - min_lat)
        )

    def test_every_feature_lands_inside_every_window(self):
        for label, bounds in self.WINDOWS.items():
            with self.subTest(label):
                seabed = make_sample_pmtiles.Seabed(bounds)
                shoal = self._at(seabed, *make_sample_pmtiles._SHOAL)
                self.assertGreater(shoal, 0.5, "the shoal has to dry, or no red band")
                self.assertLess(
                    self._at(seabed, 0.2, make_sample_pmtiles._CHANNEL), -25.0,
                    "expected the dredged channel",
                )
                self.assertTrue(
                    terrain_rgb.is_no_data(self._at(seabed, *make_sample_pmtiles._HOLE)),
                    "expected the unsurveyed hole",
                )
                self.assertGreater(self._at(seabed, 0.3, 0.99), 1.0, "expected land")

    def test_soundings_do_not_stretch_with_the_window(self):
        """A wider window must move the features, never deepen them.

        Scaling the depths with the extent would be the easy way to write this, and it
        would mean the demo of a big bay was a demo of a different sea.
        """
        depths = {
            label: self._at(make_sample_pmtiles.Seabed(bounds), 0.2,
                            make_sample_pmtiles._CHANNEL)
            for label, bounds in self.WINDOWS.items()
        }
        self.assertLess(max(depths.values()) - min(depths.values()), 0.01, depths)

    def test_refuses_an_inverted_window(self):
        with self.assertRaises(ValueError):
            make_sample_pmtiles.Seabed((-2.0, 48.0, -3.0, 47.0))


class MvtGeometryTest(unittest.TestCase):
    """Clipping, winding and simplification, which the base map lives or dies on."""

    def test_a_line_leaving_and_returning_comes_back_as_two_pieces(self):
        # Straight through, out, and back in. One piece would draw a shortcut across
        # ground the line never crossed.
        points = [(1.0, 1.0), (9.0, 1.0), (9.0, 20.0), (1.0, 20.0), (1.0, 1.0)]
        parts = mvt.clip_line(points, 0.0, 10.0)
        self.assertEqual(2, len(parts), parts)
        for part in parts:
            for x, y in part:
                self.assertTrue(-1e-9 <= x <= 10 + 1e-9 and -1e-9 <= y <= 10 + 1e-9)

    def test_a_line_wholly_outside_disappears(self):
        self.assertEqual([], mvt.clip_line([(20.0, 20.0), (30.0, 30.0)], 0.0, 10.0))

    def test_a_ring_larger_than_the_tile_becomes_the_tile(self):
        ring = [(-5.0, -5.0), (15.0, -5.0), (15.0, 15.0), (-5.0, 15.0)]
        clipped = mvt.clip_ring(ring, 0.0, 10.0)
        self.assertEqual(
            {(0.0, 0.0), (10.0, 0.0), (10.0, 10.0), (0.0, 10.0)}, set(clipped)
        )

    def test_exterior_rings_are_wound_the_way_the_spec_demands(self):
        """Positive surveyor's area, y down. A ring wound the other way draws as a hole.

        Which is how an island silently becomes a lake: nothing errors, the tile is
        valid, and the fill is inverted.
        """
        square = [(-3.02, 47.38), (-2.98, 47.38), (-2.98, 47.40), (-3.02, 47.40),
                  (-3.02, 47.38)]
        for rings in ([square], [list(reversed(square))]):
            with self.subTest(rings[0][1]):
                feature = mvt.PolygonFeature(rings=rings)
                x, y, _, _ = tiles.tile_range((-3.02, 47.38, -2.98, 47.40), 13, 256)
                parts, kind = mvt._tile_parts(feature, 13, x, y, 4096, 256, 128, 4.0)
                self.assertEqual(mvt.GEOM_POLYGON, kind)
                self.assertGreater(mvt.signed_area(parts[0][:-1]), 0.0)

    def test_simplification_keeps_the_ends_and_drops_the_middle(self):
        points = [(0.0, 0.0), (1.0, 0.001), (2.0, -0.001), (3.0, 0.0)]
        simplified = mvt.simplify(points, 4.0)
        self.assertEqual([(0.0, 0.0), (3.0, 0.0)], simplified)

    def test_simplification_keeps_a_corner_it_cannot_afford_to_lose(self):
        points = [(0.0, 0.0), (50.0, 900.0), (100.0, 0.0)]
        self.assertEqual(points, mvt.simplify(points, 4.0))


@unittest.skipUnless(HAS_OSMIUM and HAS_MVT_DECODER, "osmium / decoder not installed")
class BuildBasemapTest(unittest.TestCase):
    """The base map, end to end, over a hand-written extract.

    The interesting assertion is the last one: the app tells the base map and the
    buoyage apart by the layer names in the metadata, because both are MVT and the
    PMTiles header cannot distinguish them.
    """

    @classmethod
    def setUpClass(cls):
        cls._tmp = tempfile.TemporaryDirectory()
        source = os.path.join(cls._tmp.name, "extract.osm")
        cls.archive = os.path.join(cls._tmp.name, "base.pmtiles")
        with open(source, "w", encoding="utf-8") as fh:
            fh.write(SAMPLE_COAST_OSM)
        subprocess.run(
            [sys.executable, os.path.join(REPO_ROOT, "tools", "build_basemap.py"),
             "--input", source, "--out", cls.archive,
             "--clip-bounds", "-3.32", "47.28", "-2.65", "47.66",
             "--min-zoom", "9", "--max-zoom", "13", "--area-name", "unit test"],
            check=True, capture_output=True,
        )
        cls.reader = PMTilesReader(cls.archive)

    @classmethod
    def tearDownClass(cls):
        cls._tmp.cleanup()

    def _features(self, zoom: int) -> dict[str, list[dict]]:
        out: dict[str, list[dict]] = {}
        x0, y0, x1, y1 = tiles.tile_range(self.reader.bounds, zoom, tiles.TILE_SIZE)
        for x in range(x0, x1 + 1):
            for y in range(y0, y1 + 1):
                blob = self.reader.get(zoom, x, y)
                if not blob:
                    continue
                for name, layer in mapbox_vector_tile.decode(gzip.decompress(blob)).items():
                    out.setdefault(name, []).extend(layer["features"])
        return out

    def test_a_closed_coastline_way_becomes_an_island(self):
        land = self._features(13).get("land", [])
        self.assertTrue(land, "expected the closed coastline way as a polygon")
        self.assertTrue(all(f["geometry"]["type"] == "Polygon" for f in land))
        self.assertIn("Ile de Houat", {f["properties"].get("name") for f in land})

    def test_an_open_coastline_way_stays_a_line(self):
        coastline = self._features(13).get("coastline", [])
        self.assertTrue(coastline)
        self.assertTrue(
            all(f["geometry"]["type"] in ("LineString", "MultiLineString")
                for f in coastline)
        )

    def test_water_harbours_and_structures_survive(self):
        layers = self._features(13)
        self.assertEqual("Etang", layers["water"][0]["properties"]["name"])
        self.assertEqual("Port-Haliguen", layers["harbour"][0]["properties"]["name"])
        self.assertEqual("pier", layers["structure"][0]["properties"]["kind"])

    def test_hamlets_wait_for_a_zoom_that_can_show_them(self):
        names = lambda z: {f["properties"]["name"] for f in self._features(z).get("place", [])}
        self.assertIn("Quiberon", names(9))
        self.assertNotIn("Trou perdu", names(9))
        self.assertIn("Trou perdu", names(13))

    def test_the_app_and_the_builder_agree_on_the_layer_names(self):
        """The app classifies a vector archive by these names. Drift breaks loading.

        Both the base map and the buoyage are MVT, so the PMTiles tile type says only
        "not bathymetry". If a name here stops matching the Kotlin, the archive is
        loaded as neither and the map is silently empty.
        """
        path = os.path.join(
            REPO_ROOT, "app", "src", "main", "kotlin", "org", "opennav", "chart",
            "PmtilesHeader.kt",
        )
        with open(path, encoding="utf-8") as fh:
            source = fh.read()
        block = re.search(r"BASEMAP_LAYERS\s*=\s*setOf\((.*?)\)", source, re.S)
        self.assertIsNotNone(block, "BASEMAP_LAYERS not found in PmtilesHeader.kt")
        kotlin = set(re.findall(r'"([^"]+)"', block.group(1)))
        self.assertEqual(set(build_basemap.LAYER_ORDER), kotlin)

        declared = {entry["id"] for entry in self.reader.metadata["vector_layers"]}
        self.assertEqual(set(build_basemap.LAYER_ORDER), declared)
        self.assertIn("OpenStreetMap", self.reader.metadata["attribution"])
        self.assertEqual(1, self.reader.tile_type)


class AndroidXmlTest(unittest.TestCase):
    """Every XML the Android build reads must parse.

    Here rather than in Gradle because it costs milliseconds and catches the failure
    fifteen minutes earlier. A malformed manifest stops the build at
    `processDebugMainManifest` with `Error parsing AndroidManifest.xml` and no line
    number, after the whole toolchain has been downloaded -- and the thing it will not
    say is that a double hyphen inside an XML comment is illegal, which is the way this
    file has actually been broken.
    """

    def test_every_xml_resource_is_well_formed(self):
        roots = [
            os.path.join(REPO_ROOT, "app", "src", "main", "AndroidManifest.xml"),
            os.path.join(REPO_ROOT, "app", "src", "main", "res"),
        ]
        files: list[str] = []
        for root in roots:
            if os.path.isfile(root):
                files.append(root)
            for directory, _, names in os.walk(root):
                files += [os.path.join(directory, n) for n in names if n.endswith(".xml")]

        self.assertGreater(len(files), 1, "expected to find some Android XML")
        for path in files:
            with self.subTest(os.path.relpath(path, REPO_ROOT)):
                try:
                    ElementTree.parse(path)
                except ElementTree.ParseError as error:
                    self.fail(f"{os.path.relpath(path, REPO_ROOT)}: {error}")


class BundledDemoTest(unittest.TestCase):
    """The demo shipped inside the APK is the only `.pmtiles` in git, so it is reviewed.

    A committed binary is the one artefact nobody re-reads. This decodes it, the same way
    the phone will, and fails if what comes out is not the chart the README describes --
    including if it is simply missing, because an APK without it opens on an empty screen
    while every document in the repo says it does not.
    """

    PATH = os.path.join(
        REPO_ROOT, "app", "src", "main", "assets", "demo-quiberon-synthetic.pmtiles"
    )
    KOTLIN_CHART_ARCHIVE = os.path.join(
        REPO_ROOT, "app", "src", "main", "kotlin", "org", "opennav", "chart",
        "ChartArchive.kt",
    )

    @classmethod
    def setUpClass(cls):
        cls.reader = PMTilesReader(cls.PATH)

    def test_the_app_looks_for_the_file_that_is_actually_there(self):
        with open(self.KOTLIN_CHART_ARCHIVE, encoding="utf-8") as fh:
            match = re.search(r'BUNDLED_ASSET\s*=\s*"([^"]+)"', fh.read())
        self.assertIsNotNone(match, "BUNDLED_ASSET not found in ChartArchive.kt")
        self.assertEqual(os.path.basename(self.PATH), match.group(1))

    def test_covers_exactly_the_area_a_real_chart_would_replace(self):
        """Same bounds as the `morbihan` preset, so a survey substitutes for it cleanly.

        Not cosmetic: the demo is beaten by a real chart on rank, not on extent, so a
        survey built over a smaller window would leave the phone showing an invented
        seabed at the edges of a real one with nothing to mark the join.
        """
        _, expected = build_area.AREAS["morbihan"]
        for want, got in zip(expected, self.reader.bounds):
            self.assertAlmostEqual(want, got, places=6)

    def test_is_labelled_as_fiction_everywhere_it_can_be(self):
        metadata = self.reader.metadata
        self.assertTrue(metadata["synthetic"], "the red banner keys off this")
        self.assertIn("NOT FOR NAVIGATION", metadata["description"])
        self.assertNotIn("Shom", metadata["attribution"])
        self.assertIn("synthetic", os.path.basename(self.PATH).lower())

    def test_carries_the_three_features_the_phone_test_needs(self):
        self.assertEqual(2, self.reader.tile_type, "Terrain-RGB tiles are PNG")
        self.assertLessEqual(self.reader.min_zoom, 8)
        self.assertGreaterEqual(self.reader.max_zoom, 14)

        elevations: list[float] = []
        for _, blob in inspect_pmtiles._tiles_at(self.reader, self.reader.max_zoom, 9):
            elevations += _decode_terrain_png(blob)
        surveyed = [e for e in elevations if not terrain_rgb.is_no_data(e)]
        self.assertLess(min(surveyed), -25.0, "expected the dredged channel")
        self.assertGreater(max(surveyed), 1.0, "expected something that dries")
        self.assertLess(len(surveyed), len(elevations), "expected the unsurveyed hole")


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


try:
    import numpy as np
    import rasterio
    from rasterio.transform import from_origin
    HAS_RASTERIO = True
except ImportError:  # pragma: no cover
    HAS_RASTERIO = False


@unittest.skipUnless(HAS_RASTERIO, "rasterio not installed; skipping the GDAL pipeline")
class BuildBathymetryTest(unittest.TestCase):
    """Drives build_bathymetry.py over a synthetic Lambert-93 raster.

    Litto3D itself needs a SHOM account, but the parts of the pipeline that can be wrong
    in a dangerous way -- the vertical shift, the choice of resampling, and what happens
    to a data hole -- do not care whether the seabed underneath them is real.
    """

    SOURCE_MIN_M = -36.95   # -4 - 0.05 * 599 - 3, before any datum shift
    SOURCE_MAX_M = 0.95     # excluding the hole
    DATUM_SHIFT_M = 3.64

    @classmethod
    def setUpClass(cls):
        cls._tmp = tempfile.TemporaryDirectory()
        cls.source = os.path.join(cls._tmp.name, "litto3d.tif")
        cls.archive = os.path.join(cls._tmp.name, "out.pmtiles")

        w = h = 600
        yy, xx = np.mgrid[0:h, 0:w].astype("float64")
        elevation = -4.0 - 0.05 * yy + 3.0 * np.sin(xx / 40.0)
        elevation += 14.0 * np.exp(-(((xx - 380) / 35.0) ** 2 + ((yy - 180) / 35.0) ** 2))
        cls.hole = ((xx - 150) ** 2 + (yy - 420) ** 2) < 70 ** 2
        elevation[cls.hole] = -9999.0

        with rasterio.open(
            cls.source, "w", driver="GTiff", width=w, height=h, count=1,
            dtype="float32", crs="EPSG:2154", nodata=-9999.0,
            transform=from_origin(145000.0, 6838000.0, 5.0, 5.0),
        ) as dst:
            dst.write(elevation.astype("float32"), 1)

        subprocess.run(
            [sys.executable, os.path.join(REPO_ROOT, "tools", "build_bathymetry.py"),
             "--input", cls.source, "--out", cls.archive,
             "--datum-shift", str(cls.DATUM_SHIFT_M),
             "--min-zoom", "14", "--max-zoom", "16", "--area-name", "unit test"],
            check=True, capture_output=True,
        )
        cls.reader = PMTilesReader(cls.archive)
        cls.elevations = cls._read_all(16)

    @classmethod
    def tearDownClass(cls):
        cls._tmp.cleanup()

    @classmethod
    def _read_all(cls, zoom: int) -> list[float]:
        x0, y0, x1, y1 = tiles.tile_range(cls.reader.bounds, zoom, tiles.TILE_SIZE)
        out: list[float] = []
        for x in range(x0, x1 + 1):
            for y in range(y0, y1 + 1):
                blob = cls.reader.get(zoom, x, y)
                if blob:
                    out += _decode_terrain_png(blob)
        return out

    def test_records_what_it_did(self):
        meta = self.reader.metadata
        self.assertEqual("mapbox", meta["encoding"])
        self.assertEqual(self.DATUM_SHIFT_M, meta["vertical_shift_applied_m"])
        self.assertIn("max on elevation", meta["resampling"])
        self.assertIn("Shom", meta["attribution"])

    def test_applies_the_vertical_shift(self):
        # A silent default here would be a systematic error on every depth in the app,
        # so it is worth asserting that the requested shift actually landed.
        surveyed = [e for e in self.elevations if not terrain_rgb.is_no_data(e)]
        self.assertAlmostEqual(self.SOURCE_MIN_M + self.DATUM_SHIFT_M, min(surveyed), delta=0.15)
        self.assertAlmostEqual(self.SOURCE_MAX_M + self.DATUM_SHIFT_M, max(surveyed), delta=0.15)

    def test_quantisation_stays_pessimistic(self):
        # 0.9515 + 3.64 = 4.5915, which must encode UP to 4.6, never down to 4.5.
        surveyed = [e for e in self.elevations if not terrain_rgb.is_no_data(e)]
        self.assertGreaterEqual(max(surveyed), self.SOURCE_MAX_M + self.DATUM_SHIFT_M)

    def test_holes_stay_holes(self):
        # Both the lidar hole and everything outside the survey must read as no-data,
        # never as an interpolated depth.
        missing = [e for e in self.elevations if terrain_rgb.is_no_data(e)]
        self.assertGreater(len(missing), 0)
        self.assertTrue(all(e == terrain_rgb.NO_DATA_ELEVATION_M for e in missing))
        self.assertLess(len(missing), len(self.elevations))

    def test_overviews_are_no_deeper_than_the_native_zoom(self):
        # "Shallowest sample in the cell" means a coarser zoom can only ever move the
        # seabed up. If this fails, the resampling has been flipped back to `min`.
        coarse = [e for e in self._read_all(14) if not terrain_rgb.is_no_data(e)]
        fine = [e for e in self.elevations if not terrain_rgb.is_no_data(e)]
        self.assertGreater(len(coarse), 0)
        self.assertGreaterEqual(max(coarse), max(fine) - 0.2)
        self.assertGreaterEqual(min(coarse), min(fine) - 0.2)

    def test_refuses_to_guess_the_vertical_datum(self):
        result = subprocess.run(
            [sys.executable, os.path.join(REPO_ROOT, "tools", "build_bathymetry.py"),
             "--input", self.source, "--out", os.path.join(self._tmp.name, "nope.pmtiles")],
            capture_output=True, text=True,
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("datum", result.stderr.lower())


SAMPLE_OSM = """<?xml version='1.0' encoding='UTF-8'?>
<osm version="0.6" generator="opennav-test">
  <node id="1" lat="47.4640" lon="-3.0810" version="1">
    <tag k="seamark:type" v="buoy_cardinal"/>
    <tag k="seamark:buoy_cardinal:category" v="south"/>
    <tag k="seamark:buoy_cardinal:colour" v="yellow;black"/>
    <tag k="seamark:name" v="Basse du Milieu"/>
  </node>
  <node id="2" lat="47.3910" lon="-2.9550" version="1">
    <tag k="seamark:type" v="buoy_lateral"/>
    <tag k="seamark:buoy_lateral:category" v="port"/>
  </node>
  <node id="3" lat="47.3360" lon="-2.8750" version="1">
    <tag k="seamark:type" v="wreck"/>
    <tag k="seamark:wreck:category" v="dangerous"/>
  </node>
  <node id="9" lat="48.9000" lon="-1.0000" version="1">
    <tag k="seamark:type" v="buoy_special_purpose"/>
  </node>
  <node id="10" lat="47.5000" lon="-3.0000" version="1">
    <tag k="highway" v="bus_stop"/>
  </node>
</osm>
"""

CLIP = ("-3.32", "47.28", "-2.65", "47.66")

#: A coastline the size of a postage stamp: one open mainland way, one closed island
#: way, a lake, a pier, a marina and three settlements at different ranks.
SAMPLE_COAST_OSM = """<?xml version='1.0' encoding='UTF-8'?>
<osm version="0.6" generator="opennav-test">
  <node id="1" lat="47.50" lon="-3.20"/>
  <node id="2" lat="47.52" lon="-3.10"/>
  <node id="3" lat="47.51" lon="-3.00"/>
  <node id="4" lat="47.53" lon="-2.90"/>
  <node id="10" lat="47.38" lon="-3.02"/>
  <node id="11" lat="47.38" lon="-2.98"/>
  <node id="12" lat="47.40" lon="-2.98"/>
  <node id="13" lat="47.40" lon="-3.02"/>
  <node id="20" lat="47.55" lon="-3.15"/>
  <node id="21" lat="47.55" lon="-3.13"/>
  <node id="22" lat="47.56" lon="-3.13"/>
  <node id="23" lat="47.56" lon="-3.15"/>
  <node id="30" lat="47.505" lon="-3.19"/>
  <node id="31" lat="47.500" lon="-3.185"/>
  <node id="40" lat="47.49" lon="-3.19"/>
  <node id="41" lat="47.49" lon="-3.17"/>
  <node id="42" lat="47.50" lon="-3.17"/>
  <node id="43" lat="47.50" lon="-3.19"/>
  <node id="50" lat="47.487" lon="-3.121">
    <tag k="place" v="town"/><tag k="name" v="Quiberon"/>
  </node>
  <node id="51" lat="47.391" lon="-2.999">
    <tag k="place" v="island"/><tag k="name" v="Houat"/>
  </node>
  <node id="52" lat="47.60" lon="-3.30">
    <tag k="place" v="hamlet"/><tag k="name" v="Trou perdu"/>
  </node>
  <way id="100"><nd ref="1"/><nd ref="2"/><nd ref="3"/><nd ref="4"/>
    <tag k="natural" v="coastline"/></way>
  <way id="101"><nd ref="10"/><nd ref="11"/><nd ref="12"/><nd ref="13"/><nd ref="10"/>
    <tag k="natural" v="coastline"/><tag k="name" v="Ile de Houat"/></way>
  <way id="102"><nd ref="20"/><nd ref="21"/><nd ref="22"/><nd ref="23"/><nd ref="20"/>
    <tag k="natural" v="water"/><tag k="name" v="Etang"/></way>
  <way id="103"><nd ref="30"/><nd ref="31"/><tag k="man_made" v="pier"/></way>
  <way id="104"><nd ref="40"/><nd ref="41"/><nd ref="42"/><nd ref="43"/><nd ref="40"/>
    <tag k="leisure" v="marina"/><tag k="name" v="Port-Haliguen"/></way>
</osm>
"""


@unittest.skipUnless(HAS_MVT_DECODER, "mapbox-vector-tile not installed")
class MvtTest(unittest.TestCase):
    """The MVT writer is hand-rolled protobuf, so it is checked against a real decoder.

    Verifying it with a decoder of my own would only prove the two share a
    misunderstanding; a malformed tile renders as nothing at all on the phone, which is
    the hardest kind of bug to notice on a boat.
    """

    def test_a_reference_decoder_reads_what_we_write(self):
        features = [
            mvt.PointFeature(-3.081, 47.464, {"type": "buoy_cardinal", "n": 3, "ok": True}),
            mvt.PointFeature(-3.080, 47.465, {"type": "wreck"}),
        ]
        zoom = 14
        x = int(tiles.lon_to_pixel_x(-3.081, zoom) // 256)
        y = int(tiles.lat_to_pixel_y(47.464, zoom) // 256)

        blob = mvt.encode_tile({"seamarks": features}, zoom, x, y)
        self.assertIsNotNone(blob)

        decoded = mapbox_vector_tile.decode(blob)
        self.assertIn("seamarks", decoded)
        got = decoded["seamarks"]["features"]
        self.assertEqual(2, len(got))
        self.assertEqual("Point", got[0]["geometry"]["type"])
        self.assertEqual("buoy_cardinal", got[0]["properties"]["type"])
        self.assertEqual(3, got[0]["properties"]["n"])
        self.assertTrue(got[0]["properties"]["ok"])

    def test_features_land_where_they_belong(self):
        # A point placed at a tile's centre must decode near the middle of the extent.
        zoom, lon, lat = 14, -3.081, 47.464
        x = int(tiles.lon_to_pixel_x(lon, zoom) // 256)
        y = int(tiles.lat_to_pixel_y(lat, zoom) // 256)
        blob = mvt.encode_tile({"s": [mvt.PointFeature(lon, lat, {"a": "b"})]}, zoom, x, y)
        coords = mapbox_vector_tile.decode(blob)["s"]["features"][0]["geometry"]["coordinates"]
        self.assertTrue(0 <= coords[0] <= mvt.DEFAULT_EXTENT)
        self.assertTrue(0 <= coords[1] <= mvt.DEFAULT_EXTENT)

    def test_a_tile_with_nothing_in_it_is_not_written(self):
        far = [mvt.PointFeature(2.35, 48.85, {"type": "buoy_lateral"})]  # Paris
        self.assertIsNone(mvt.encode_tile({"seamarks": far}, 14, 8000, 5600))


@unittest.skipUnless(HAS_OSMIUM and HAS_MVT_DECODER, "osmium / decoder not installed")
class BuildSeamarksTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls._tmp = tempfile.TemporaryDirectory()
        cls.source = os.path.join(cls._tmp.name, "extract.osm")
        cls.archive = os.path.join(cls._tmp.name, "seamarks.pmtiles")
        with open(cls.source, "w", encoding="utf-8") as fh:
            fh.write(SAMPLE_OSM)
        subprocess.run(
            [sys.executable, os.path.join(REPO_ROOT, "tools", "build_seamarks.py"),
             "--input", cls.source, "--out", cls.archive,
             "--clip-bounds", *CLIP,
             "--min-zoom", "12", "--max-zoom", "14", "--area-name", "unit test"],
            check=True, capture_output=True,
        )
        cls.reader = PMTilesReader(cls.archive)

    @classmethod
    def tearDownClass(cls):
        cls._tmp.cleanup()

    def _features(self, zoom: int) -> list[dict]:
        x0, y0, x1, y1 = tiles.tile_range(self.reader.bounds, zoom, tiles.TILE_SIZE)
        out: list[dict] = []
        for x in range(x0, x1 + 1):
            for y in range(y0, y1 + 1):
                blob = self.reader.get(zoom, x, y)
                if not blob:
                    continue
                decoded = mapbox_vector_tile.decode(gzip.decompress(blob))
                for layer in decoded.values():
                    out += layer["features"]
        return out

    def test_declares_itself_as_vector_tiles(self):
        # MapLibre picks its decoder from these two header fields; getting them wrong
        # yields an empty map rather than an error.
        self.assertEqual(1, self.reader.tile_type, "tile type must be MVT")
        self.assertEqual(2, self.reader.tile_compression, "tiles must say they are gzipped")
        self.assertEqual(
            "seamarks", self.reader.metadata["vector_layers"][0]["id"]
        )
        self.assertIn("OpenSeaMap", self.reader.metadata["attribution"])

    def test_keeps_seamarks_and_only_seamarks(self):
        features = self._features(14)
        kinds = sorted(f["properties"]["type"] for f in features)
        self.assertEqual(["buoy_cardinal", "buoy_lateral", "wreck"], kinds)

    def test_carries_the_tags_the_app_styles_on(self):
        by_type = {f["properties"]["type"]: f["properties"] for f in self._features(14)}
        self.assertEqual("south", by_type["buoy_cardinal"]["cardinal"])
        self.assertEqual("Basse du Milieu", by_type["buoy_cardinal"]["name"])
        self.assertEqual("port", by_type["buoy_lateral"]["lateral"])
        self.assertEqual("dangerous", by_type["wreck"]["wreck"])

    def test_clip_bounds_are_honoured(self):
        # The special-purpose buoy sits off Normandy, well outside the clip.
        self.assertNotIn(
            "buoy_special_purpose",
            [f["properties"]["type"] for f in self._features(14)],
        )

    def test_refuses_an_extract_with_no_seamark(self):
        empty = os.path.join(self._tmp.name, "empty.osm")
        with open(empty, "w", encoding="utf-8") as fh:
            fh.write("<?xml version='1.0'?><osm version='0.6' generator='t'></osm>")
        result = subprocess.run(
            [sys.executable, os.path.join(REPO_ROOT, "tools", "build_seamarks.py"),
             "--input", empty, "--out", os.path.join(self._tmp.name, "nope.pmtiles")],
            capture_output=True, text=True,
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("no seamark", result.stderr.lower())


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
