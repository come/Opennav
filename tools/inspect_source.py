#!/usr/bin/env python3
# Copyright (C) 2026 the Opennav authors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Look at Litto3D tiles before converting them, and work out the vertical shift.

Two jobs.

**What did I download?** Projection, resolution, nodata, and the range of elevations
actually present. Enough to catch the common mistakes -- a point cloud instead of a
raster, a tile that does not cover the area, a nodata value the file forgot to declare.

**Which datum are these altitudes in?** This is the question ``build_bathymetry.py``
refuses to guess, and the answer is measurable rather than lookup-able:

    ./tools/inspect_source.py --input '~/litto3d/*.asc' \\
        --calibrate -2.9000 47.5430 5.0

Give it a position and the sounding printed on the SHOM chart at that position, and it
prints the shift to pass to ``--datum-shift``. Do it at three or four points spread over
the area: if they agree to within a few centimetres, a constant is defensible. If they
disagree by half a metre, they are telling you the area is too big for one and you want
``--datum-grid``.

That beats reading a number out of a table, because it measures *your* files rather than
what the product notice says about them.
"""

from __future__ import annotations

import argparse
import glob
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

try:
    import numpy as np
    import rasterio
    from rasterio.warp import transform as warp_transform
    from rasterio.warp import transform_bounds
except ImportError as exc:  # pragma: no cover
    raise SystemExit(
        f"missing dependency ({exc}); install with: pip install -r tools/requirements.txt"
    ) from exc

WGS84 = "EPSG:4326"


def expand(patterns: list[str]) -> list[str]:
    out: list[str] = []
    for pattern in patterns:
        expanded = sorted(glob.glob(os.path.expanduser(pattern)))
        out += expanded if expanded else [os.path.expanduser(pattern)]
    return [p for p in out if os.path.exists(p)]


def sample_at(paths: list[str], lon: float, lat: float) -> float | None:
    """Raw elevation at a position, straight out of the file. No shift applied."""
    for path in paths:
        with rasterio.open(path) as ds:
            xs, ys = warp_transform(WGS84, ds.crs, [lon], [lat])
            row, col = ds.index(xs[0], ys[0])
            if not (0 <= row < ds.height and 0 <= col < ds.width):
                continue
            value = ds.read(1, window=((row, row + 1), (col, col + 1)))[0][0]
            if ds.nodata is not None and value == ds.nodata:
                continue
            if np.isnan(value):
                continue
            return float(value)
    return None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--input", nargs="+", required=True)
    parser.add_argument(
        "--calibrate", action="append", nargs=3, type=float,
        metavar=("LON", "LAT", "SONDE_M"),
        help="position et sonde portee sur la carte SHOM (positive vers le bas), "
             "repetable",
    )
    parser.add_argument("--at", action="append", nargs=2, type=float,
                        metavar=("LON", "LAT"), help="altitude brute en un point")
    parser.add_argument("--sample-pixels", type=int, default=400_000)
    args = parser.parse_args()

    paths = expand(args.input)
    if not paths:
        raise SystemExit("aucun fichier trouve. Verifiez le chemin et les guillemets.")

    print(f"=== {len(paths)} fichier(s)")
    crs_seen: set[str] = set()
    lo, hi = None, None
    total_valid = 0

    for path in paths[:20]:
        with rasterio.open(path) as ds:
            crs_seen.add(str(ds.crs))
            resolution = abs(ds.transform.a)
            print(f"  {os.path.basename(path)}")
            print(f"    {ds.width} x {ds.height} px, {resolution:.2f} m, "
                  f"{ds.count} bande(s), nodata={ds.nodata}, {ds.dtypes[0]}")

            step = max(1, int((ds.width * ds.height / args.sample_pixels) ** 0.5))
            data = ds.read(1, out_shape=(ds.height // step or 1, ds.width // step or 1))
            values = data[np.isfinite(data)]
            if ds.nodata is not None:
                values = values[values != ds.nodata]
            if values.size:
                total_valid += values.size
                lo = float(values.min()) if lo is None else min(lo, float(values.min()))
                hi = float(values.max()) if hi is None else max(hi, float(values.max()))
    if len(paths) > 20:
        print(f"  ... et {len(paths) - 20} autres (non echantillonnes)")

    print(f"\n=== projection : {', '.join(sorted(crs_seen)) or 'AUCUNE'}")
    if not crs_seen or "None" in crs_seen:
        print("  ATTENTION : sans projection declaree, le pipeline refusera le fichier.")
        print("  Un .asc a souvent besoin de son .prj a cote.")
    elif not any("2154" in c for c in crs_seen):
        print("  Attendu EPSG:2154 (RGF93 / Lambert-93) pour Litto3D. A verifier.")

    with rasterio.open(paths[0]) as ds:
        if ds.crs:
            print("  emprise du 1er fichier, en WGS84 :")
            print("    {:.4f} {:.4f} -> {:.4f} {:.4f}".format(
                *transform_bounds(ds.crs, WGS84, *ds.bounds)))

    if lo is not None:
        print(f"\n=== altitudes brutes : {lo:.2f} m a {hi:.2f} m ({total_valid} px echantillonnes)")
        print("  (telles qu'ecrites dans le fichier, AVANT tout decalage)")

    if args.at:
        print("\n=== altitudes brutes aux points demandes")
        for lon, lat in args.at:
            value = sample_at(paths, lon, lat)
            print(f"  {lon:.4f} {lat:.4f} : "
                  + (f"{value:.2f} m" if value is not None else "hors emprise / non leve"))

    if args.calibrate:
        print("\n=== calage vertical")
        print("  sonde carte = profondeur sous le zero hydrographique (positive)")
        print("  altitude au ZH = -sonde ; decalage = altitude_ZH - altitude_brute\n")
        shifts: list[float] = []
        for lon, lat, sounding in args.calibrate:
            raw = sample_at(paths, lon, lat)
            if raw is None:
                print(f"  {lon:.4f} {lat:.4f} : hors emprise / non leve")
                continue
            shift = -sounding - raw
            shifts.append(shift)
            print(f"  {lon:.4f} {lat:.4f} : brute {raw:+.2f} m, sonde {sounding:.2f} m "
                  f"-> decalage {shift:+.2f} m")

        if not shifts:
            print("\n  Aucun point exploitable.")
            return 1
        mean = sum(shifts) / len(shifts)
        spread = max(shifts) - min(shifts)
        print(f"\n  moyenne : {mean:+.2f} m     dispersion : {spread:.2f} m")
        if len(shifts) == 1:
            print("  Un seul point ne prouve rien. Recommencez sur trois ou quatre,")
            print("  repartis sur toute la zone.")
        elif spread <= 0.15:
            print(f"  Coherent. Utilisez :  --datum-shift {mean:.2f}")
        elif spread <= 0.40:
            print(f"  Dispersion notable. --datum-shift {mean:.2f} passe, mais decoupez")
            print("  la zone en deux archives si vous voulez etre propre.")
        else:
            print("  TROP DISPERSE pour une constante. La zone est trop grande, ou une")
            print("  sonde a ete mal relevee. Utilisez --datum-grid, ou reduisez l'emprise.")
        if abs(mean) < 0.20:
            print("\n  Decalage quasi nul : vos donnees sont probablement deja au zero")
            print("  hydrographique. Dans ce cas passez --already-chart-datum.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
