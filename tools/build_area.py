#!/usr/bin/env python3
# Copyright (C) 2026 the Opennav authors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Build every chart for one sailing area, in one command.

Wraps ``build_bathymetry.py`` and ``build_seamarks.py`` so that a zone is one invocation
instead of two sets of bounds to keep in sync by hand -- getting the bathymetry and the
buoyage on slightly different extents is the kind of mistake that shows up as a buoy
floating over a blank patch.

    ./tools/build_area.py --area morbihan \\
        --litto3d '~/litto3d/morbihan/*.asc' \\
        --osm ~/bretagne-latest.osm.pbf \\
        --datum-shift 3.1

Areas are presets so the bounds are written down once and reviewed, rather than retyped
from memory each time. `--bounds` overrides them for anywhere else.

Neither input is optional in spirit, but both are optional in practice: pass only
``--litto3d`` to rebuild the depths, only ``--osm`` to rebuild the base map and the
buoyage.

``--osm`` produces two archives, not one: the base map (coastline, islands, harbours)
and the seamark overlay. They are what "OpenSeaMap" means when you see it on the web --
an ordinary OSM map with the buoyage drawn on top -- and they are separate files because
they change at different rates and a user may want one without waiting for the other.
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import tiles as tilemath  # noqa: E402

#: Named sailing areas, as (min_lon, min_lat, max_lon, max_lat).
#:
#: Bounds are generous on purpose: a chart that stops at the edge of the pilotage area is
#: worse than useless, because you notice at the moment you leave it.
AREAS: dict[str, tuple[str, tuple[float, float, float, float]]] = {
    "bretagne": (
        "Toute la Bretagne, du Mont-Saint-Michel a la baie de Bourgneuf",
        (-5.30, 47.00, -1.00, 48.95),
    ),
    "morbihan": (
        "Baie de Quiberon, Belle-Ile, Houat, Hoedic et golfe du Morbihan",
        (-3.32, 47.28, -2.65, 47.66),
    ),
    "morbihan-large": (
        "Idem, plus Belle-Ile ouest, Etel et les approches de Lorient",
        (-3.45, 47.25, -2.60, 47.75),
    ),
    "rade-de-brest": (
        "Rade de Brest et goulet",
        (-4.62, 48.26, -4.25, 48.42),
    ),
    "golfe-normand-breton": (
        "Saint-Malo, Cancale, Chausey",
        (-2.35, 48.55, -1.75, 48.85),
    ),
}


class StepFailed(Exception):
    """A sub-command failed. Carries the step name so the summary stays readable."""


def run(step: str, command: list[str]) -> None:
    print(f"\n=== {step}\n", flush=True)
    result = subprocess.run(command)
    if result.returncode != 0:
        raise StepFailed(step)


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--area", choices=sorted(AREAS), help="zone predefinie")
    parser.add_argument(
        "--bounds", type=float, nargs=4,
        metavar=("MIN_LON", "MIN_LAT", "MAX_LON", "MAX_LAT"),
        help="emprise explicite, a la place de --area",
    )
    parser.add_argument("--litto3d", help="dalles Litto3D (glob), pour la bathymetrie")
    parser.add_argument("--osm", help="extrait .osm.pbf, pour le fond de carte ET le balisage")
    parser.add_argument("--skip-basemap", action="store_true",
                        help="ne produire que le balisage a partir de --osm")
    parser.add_argument("--out-dir", default=".")
    parser.add_argument("--name", help="prefixe des fichiers produits")

    datum = parser.add_mutually_exclusive_group()
    datum.add_argument("--datum-shift", type=float)
    datum.add_argument("--datum-grid")
    datum.add_argument("--already-chart-datum", action="store_true")

    parser.add_argument("--max-zoom", type=int, default=14)
    args = parser.parse_args()

    if not args.area and not args.bounds:
        parser.error("choisissez --area ou --bounds")
    if not args.litto3d and not args.osm:
        parser.error("rien a faire : passez --litto3d et/ou --osm")

    if args.bounds:
        label, bounds = args.name or "zone", tuple(args.bounds)
    else:
        label, bounds = AREAS[args.area]
        label = args.name or args.area

    print(f"zone : {label}")
    print(f"emprise : {bounds[0]} {bounds[1]} -> {bounds[2]} {bounds[3]}")
    total = sum(tilemath.tile_count(bounds, z, 256) for z in range(args.max_zoom + 1))
    print(f"rectangle plein : {total:,} tuiles, soit ~{total * 70 / 1024:.0f} Mo a 70 KiB/tuile")
    print("(majorant : seules les tuiles contenant de la donnee sont ecrites)")

    os.makedirs(args.out_dir, exist_ok=True)
    produced: list[str] = []
    failures: list[str] = []

    if args.litto3d:
        if not (args.datum_shift is not None or args.datum_grid or args.already_chart_datum):
            parser.error(
                "la bathymetrie exige de trancher le referentiel vertical : "
                "--datum-shift, --datum-grid ou --already-chart-datum. Voir docs/DATA.md."
            )
        out = os.path.join(args.out_dir, f"{label}-bathy.pmtiles")
        command = [
            sys.executable, os.path.join(HERE, "build_bathymetry.py"),
            "--input", args.litto3d, "--out", out,
            "--area-name", label,
            "--clip-bounds", *[str(b) for b in bounds],
            "--max-zoom", str(args.max_zoom),
        ]
        if args.datum_shift is not None:
            command += ["--datum-shift", str(args.datum_shift)]
        elif args.datum_grid:
            command += ["--datum-grid", args.datum_grid]
        else:
            command += ["--already-chart-datum"]
        try:
            run("bathymetrie", command)
            produced.append(out)
        except StepFailed:
            failures.append("bathymetrie")

    if args.osm:
        # Two archives from the one extract, because they answer different questions and
        # a user may well want the buoyage without waiting for the coastline.
        if not args.skip_basemap:
            out = os.path.join(args.out_dir, f"{label}-base.pmtiles")
            try:
                run("fond de carte", [
                    sys.executable, os.path.join(HERE, "build_basemap.py"),
                    "--input", args.osm, "--out", out,
                    "--area-name", f"{label} (fond de carte)",
                    "--clip-bounds", *[str(b) for b in bounds],
                    "--max-zoom", str(args.max_zoom),
                ])
                produced.append(out)
            except StepFailed:
                failures.append("fond de carte")

        out = os.path.join(args.out_dir, f"{label}-seamarks.pmtiles")
        try:
            run("balisage", [
                sys.executable, os.path.join(HERE, "build_seamarks.py"),
                "--input", args.osm, "--out", out,
                "--area-name", label,
                "--clip-bounds", *[str(b) for b in bounds],
                "--max-zoom", str(args.max_zoom),
            ])
            produced.append(out)
        except StepFailed:
            failures.append("balisage")

    # One step failing must not throw away the other one: rebuilding a departement of
    # bathymetry because the OSM extract was wrong would be a cruel way to find out.
    if produced:
        print("\n=== a copier sur le telephone ===")
        for path in produced:
            print(f"  {path}  ({os.path.getsize(path) / 1024 / 1024:.1f} Mo)")
        print("\nDans l'app : roue -> Carte -> Importer une carte, une fois par fichier.")

    if failures:
        print(f"\n!!! echec : {', '.join(failures)} (message ci-dessus)")
        if produced:
            print("Le reste a bien ete produit et reste utilisable.")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
