# `tools/` — le pipeline de données

Tourne une fois, sur un poste de travail. **Jamais sur le téléphone.**

| script | dépendances | rôle |
|---|---|---|
| `make_sample_pmtiles.py` | aucune | fabrique un fond marin synthétique pour lancer l'app sans données SHOM |
| `build_bathymetry.py` | `rasterio` | Litto3D → Terrain-RGB → `.pmtiles` |
| `estimate_volume.py` | aucune | répond au risque « moins de 400 Mo pour la Bretagne ? » |
| `build_area.py` | selon les étapes | **le point d'entrée** : une zone, une commande |
| `build_seamarks.py` | `osmium` | extrait OSM → balisage en tuiles vectorielles |
| `build_basemap.py` | `osmium` | extrait OSM → fond de carte (côte, îles, ports) |
| `inspect_source.py` | `rasterio` | qu'ai-je téléchargé, et quel est le décalage vertical ? |
| `inspect_pmtiles.py` | aucune | qu'y a-t-il vraiment dans l'archive produite ? |
| `test_tools.py` | aucune | tests, dont le contrôle de parité Python ↔ Kotlin |

Modules partagés : `terrain_rgb.py` (encodage), `pmtiles.py` (écriture et lecture PMTiles
v3), `png.py`, `tiles.py` (arithmétique Web Mercator), `mvt.py` (tuiles vectorielles :
points, lignes, polygones, découpage, simplification).

## Le fond de carte

```bash
./tools/build_basemap.py --input bretagne-latest.osm.pbf --out bretagne-base.pmtiles \
    --clip-bounds -5.30 47.00 -1.00 48.95
```

Six couches, choisies pour ce dont un navigateur côtier a besoin plutôt que pour ce
qu'OSM contient : `coastline`, `land` (les îles), `water`, `harbour`, `structure` (jetées,
brise-lames, épis) et `place`.

Deux points valent qu'on s'y arrête.

**Le continent n'est pas rempli.** Voir le README principal : refermer des anneaux de
trait de côte contre le bord d'une emprise peut dessiner de la terre sur de l'eau
navigable, et c'est l'erreur à ne pas commettre en silence. Les îles le sont, parce
qu'une voie `natural=coastline` fermée *est* le contour de l'île.

**L'enroulement des anneaux compte.** MVT v2 exige une aire positive (formule de
l'arpenteur, y vers le bas) pour un anneau extérieur. Un anneau enroulé à l'envers est
dessiné comme un trou : l'île devient un lac, la tuile reste valide, et rien ne proteste.
`test_tools.py::MvtGeometryTest` le vérifie dans les deux sens.

Les noms de lieux sont dans les tuiles mais pas encore affichés : le texte demande une
source de glyphes, donc des fontes embarquées dans l'APK.

```bash
python3 -m unittest discover -s tools -v
```

## Démarrer sans compte SHOM

```bash
./tools/make_sample_pmtiles.py --out sample.pmtiles
```

Une quinzaine de secondes sur l'emprise par défaut, bibliothèque standard uniquement.
Produit un chenal dragué à −34 m, un haut-fond qui découvre à +1,4 m, et un trou sans
donnée — les trois objets nécessaires pour vérifier que la colorisation, les bandes et la
sentinelle fonctionnent sur un vrai GPU. L'archive se déclare `"synthetic": true` dans ses
métadonnées et son nom le dit aussi.

`--bounds` déplace l'emprise. Les trois objets sont placés en *fractions de la fenêtre*,
pas en coordonnées : ils suivent. Les profondeurs, elles, restent en mètres — élargir
l'emprise agrandit le chenal, ne le creuse pas. C'est ce qui permet de poser un fond
factice sous un vrai balisage OpenSeaMap en attendant les dalles Litto3D.

### Régénérer la carte de démonstration embarquée

`app/src/main/assets/demo-quiberon-synthetic.pmtiles` est le seul `.pmtiles` versionné du
dépôt. Il est produit par :

```bash
./tools/make_sample_pmtiles.py \
    --out app/src/main/assets/demo-quiberon-synthetic.pmtiles \
    --bounds -3.32 47.28 -2.65 47.66 \
    --min-zoom 8 --max-zoom 14 \
    --area-name "Baie de Quiberon"
```

Environ deux minutes, 4,4 Mo, 1 124 tuiles. L'emprise est celle du préréglage `morbihan`
de `build_area.py`, pour qu'une vraie carte fabriquée avec `--area morbihan` se substitue
exactement à la démonstration.

Vérifiez le résultat avant de le committer — l'inspecteur échantillonne sur un damier et
décode réellement les tuiles :

```bash
./tools/inspect_pmtiles.py app/src/main/assets/demo-quiberon-synthetic.pmtiles --sample 36
```

Les altitudes doivent aller d'environ −34 m à +6 m. Si la plage est étroite, le fond a été
fabriqué ailleurs que sous l'emprise demandée et l'app affichera un rectangle uni.

## Avec de vraies dalles Litto3D

La procédure complète, depuis le compte SHOM jusqu'au fichier sur le téléphone, est dans
le [README principal](../README.md#obtenir-la-bathymétrie-de-la-bretagne). En résumé :

1. Télécharger les prépaquets Bretagne sur `diffusion.shom.fr` (compte gratuit requis),
   en prenant le **MNT raster** et non le nuage de points LAZ.
2. **Trancher la question du référentiel vertical** — voir [../docs/DATA.md](../docs/DATA.md) §5.
   Le script refuse de tourner sans réponse explicite, et c'est volontaire : une valeur
   par défaut silencieuse produirait une app qui marche en se trompant de trois mètres.
   La valeur de 3,64 m utilisée dans les exemples est un ordre de grandeur pour Brest,
   pas une donnée vérifiée : lisez l'annuaire du SHOM pour votre port de référence.

```bash
pip install -r tools/requirements.txt

./tools/build_bathymetry.py \
    --input '~/litto3d/finistere/*.asc' \
    --out finistere.pmtiles \
    --area-name "Finistère" \
    --datum-shift 3.64
```

Le découpage par département vient du plan : il permet un téléchargement partiel. Passez
`--clip-bounds` pour restreindre davantage.

### Ce que le script fait, et pourquoi

* **Reprojection RGF93 / Lambert-93 → Web Mercator**, paresseusement, une fenêtre de tuile
  à la fois : un département n'a jamais besoin de tenir en mémoire.
* **Rééchantillonnage `max`.** Le plan dit `min` ; sur un raster d'*altitudes* c'est
  l'inverse de ce qu'il faut, parce que la profondeur minimale est l'altitude maximale.
  C'est la ligne la plus dangereuse du dépôt et elle est commentée trois fois.
* **Chaque zoom reconstruit depuis la source**, jamais depuis le niveau au-dessus : six
  applications successives de « le plus haut des quatre » dérivent vers le haut.
* **Les trous restent des trous**, encodés par une sentinelle au-dessus de toute altitude
  réelle. Jamais d'interpolation dans une zone non levée.
* **Attribution embarquée** dans les métadonnées de l'archive, pour qu'elle voyage avec le
  fichier et pas seulement avec le dépôt.

## Estimer le volume

```bash
./tools/estimate_volume.py                        # seuil de rentabilité
./tools/estimate_volume.py --bytes-per-tile 62000 # après une vraie mesure
./tools/estimate_volume.py --mode bbox            # borne supérieure grossière
```

Le nombre de tuiles est de la géométrie exacte, donc calculé. Le poids par tuile dépend du
bruit réel de la bathymétrie, donc **non deviné** : le script affiche combien d'octets par
tuile vous pouvez vous permettre, et vous comparez après avoir construit un département.

## Écrire un `.pmtiles` sans ces scripts

`pmtiles.py` est un écrivain minimal (répertoire racine, un niveau de feuilles,
déduplication et run-length). Il est là pour que le générateur d'échantillon n'ait aucune
dépendance. Pour de gros volumes, l'outil officiel `go-pmtiles` fait mieux — mais il
faudra alors réimplémenter l'encodage Terrain-RGB, et donc reproduire exactement les
règles de `terrain_rgb.py`. `test_tools.py::KotlinParityTest` relit les constantes du
Kotlin pour empêcher les deux implémentations de diverger ; gardez ce test vert.
