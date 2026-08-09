#!/usr/bin/env python3
# Copyright (C) 2026 the Opennav authors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Look inside a .pmtiles before trusting it.

The failure mode this exists for: an archive that builds without complaint, copies to the
phone without complaint, and then renders as an empty screen. By then you are three steps
away from the cause. Thirty seconds here answers "did I actually make what I think I
made", on the machine that made it.

    ./tools/inspect_pmtiles.py morbihan-seamarks.pmtiles
    ./tools/inspect_pmtiles.py morbihan-bathy.pmtiles --sample 20

Reports the header, the metadata, and -- by decoding real tiles rather than trusting the
metadata -- what is actually inside: seamarks by type for an overlay, the range of
elevations and the share of unsurveyed cells for bathymetry.
"""

from __future__ import annotations

import argparse
import gzip
import os
import struct
import sys
import zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import terrain_rgb  # noqa: E402
import tiles as tilemath  # noqa: E402
from pmtiles import PMTilesReader  # noqa: E402

TILE_TYPES = {0: "inconnu", 1: "MVT (balisage)", 2: "PNG (bathymétrie)", 3: "JPEG", 4: "WebP"}
COMPRESSIONS = {0: "inconnue", 1: "aucune", 2: "gzip", 3: "brotli", 4: "zstd"}


def _decompress(blob: bytes, compression: int) -> bytes:
    return gzip.decompress(blob) if compression == 2 else blob


def _decode_terrain(blob: bytes) -> list[float]:
    pos, idat, width, height = 8, b"", 0, 0
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
        line = bytearray(raw[start + 1:start + 1 + stride])
        if raw[start] == 1:  # Sub filter, the only one tools/png.py emits
            for i in range(3, stride):
                line[i] = (line[i] + line[i - 3]) & 0xFF
        for i in range(0, stride, 3):
            out.append(terrain_rgb.decode(line[i], line[i + 1], line[i + 2]))
    return out


def _tiles_at(reader: PMTilesReader, zoom: int, limit: int):
    x0, y0, x1, y1 = tilemath.tile_range(reader.bounds, zoom, tilemath.TILE_SIZE)
    seen = 0
    for x in range(x0, x1 + 1):
        for y in range(y0, y1 + 1):
            blob = reader.get(zoom, x, y)
            if blob is None:
                continue
            yield (zoom, x, y), blob
            seen += 1
            if seen >= limit:
                return


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("archive")
    parser.add_argument("--sample", type=int, default=8,
                        help="nombre de tuiles a decoder au zoom natif")
    args = parser.parse_args()

    reader = PMTilesReader(args.archive)
    size = os.path.getsize(args.archive)

    print(f"=== {os.path.basename(args.archive)}")
    print(f"  {size / 1024 / 1024:.2f} Mo, {reader.addressed_tiles} tuiles")
    print(f"  contenu   : {TILE_TYPES.get(reader.tile_type, reader.tile_type)}")
    print(f"  compress. : {COMPRESSIONS.get(reader.tile_compression, reader.tile_compression)}")
    print(f"  zooms     : {reader.min_zoom} a {reader.max_zoom}")
    lon0, lat0, lon1, lat1 = reader.bounds
    print(f"  emprise   : {lon0:.4f} {lat0:.4f} -> {lon1:.4f} {lat1:.4f}")
    resolution = tilemath.ground_resolution_m((lat0 + lat1) / 2, reader.max_zoom)
    print(f"  finesse   : {resolution:.1f} m/pixel au zoom {reader.max_zoom}")

    metadata = reader.metadata
    print("\n=== metadonnees")
    for key in ("name", "survey", "vertical_shift_applied_m", "resampling",
                "attribution", "synthetic"):
        if key in metadata:
            print(f"  {key}: {metadata[key]}")

    print("\n=== contenu reel (tuiles decodees)")
    if reader.tile_type == 1:
        _report_seamarks(reader, args.sample)
    elif reader.tile_type == 2:
        _report_bathymetry(reader, args.sample)
    else:
        print("  type de tuile non reconnu, rien a decoder")
    return 0


def _report_seamarks(reader: PMTilesReader, limit: int) -> None:
    try:
        import mapbox_vector_tile
    except ImportError:
        print("  (pip install mapbox-vector-tile pour compter les marques)")
        return

    kinds: dict[str, int] = {}
    named: list[str] = []
    total = 0
    for _, blob in _tiles_at(reader, reader.max_zoom, limit):
        decoded = mapbox_vector_tile.decode(_decompress(blob, reader.tile_compression))
        for layer in decoded.values():
            for feature in layer["features"]:
                props = feature["properties"]
                kinds[props.get("type", "?")] = kinds.get(props.get("type", "?"), 0) + 1
                total += 1
                if props.get("name") and len(named) < 8:
                    named.append(f"{props['name']} ({props.get('type', '?')})")

    if total == 0:
        print("  AUCUNE marque dans les tuiles echantillonnees.")
        print("  L'archive est vide ou l'emprise ne recouvre pas les donnees.")
        return
    print(f"  {total} marques dans {limit} tuiles au zoom {reader.max_zoom} :")
    for kind, count in sorted(kinds.items(), key=lambda kv: -kv[1]):
        print(f"    {count:5d}  {kind}")
    if named:
        print("  quelques noms :")
        for name in named:
            print(f"    {name}")


def _report_bathymetry(reader: PMTilesReader, limit: int) -> None:
    elevations: list[float] = []
    for _, blob in _tiles_at(reader, reader.max_zoom, limit):
        elevations += _decode_terrain(_decompress(blob, reader.tile_compression))
    if not elevations:
        print("  aucune tuile lisible")
        return

    surveyed = [e for e in elevations if not terrain_rgb.is_no_data(e)]
    missing = len(elevations) - len(surveyed)
    print(f"  {len(elevations)} pixels echantillonnes")
    print(f"  non leves : {missing} ({100 * missing / len(elevations):.0f} %)")
    if not surveyed:
        print("  AUCUN pixel leve. L'archive ne contient que du vide.")
        return
    print(f"  altitudes : {min(surveyed):.1f} m a {max(surveyed):.1f} m (zero hydrographique)")
    print(f"  soit des sondes de {-max(surveyed):.1f} m a {-min(surveyed):.1f} m")
    if max(surveyed) > 60:
        print("  note : des altitudes tres positives = du relief terrestre dans l'emprise.")
    if min(surveyed) > -1.0:
        print("  ATTENTION : rien de plus profond que 1 m. Le decalage vertical")
        print("  (--datum-shift) est probablement faux ou manquant.")


if __name__ == "__main__":
    raise SystemExit(main())
