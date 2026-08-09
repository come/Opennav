# Données : conventions, pièges, provenance

Ce fichier documente les décisions qui, si elles sont fausses, sont fausses **partout
dans l'app en même temps**. Une erreur de signe ici ne provoque pas un bug visible : elle
décale toutes les profondeurs d'une constante et l'app reste plausible jusqu'au moment où
elle ne l'est plus.

## 1. Convention verticale

Tout le code travaille en **altitude, positive vers le haut, référencée au zéro
hydrographique** (ZH).

| grandeur | signe | exemple |
|---|---|---|
| fond à 3 m sous le ZH | `-3.0` | eau à marée nulle : 3 m |
| roche découvrant de 1,2 m | `+1.2` | assèche au ZH |
| profondeur portée sur la carte SHOM | `= -altitude` | |

La formule du plan (`profondeurCarte + hauteurMarée - tirant - marge`) devient donc :

```
clearance = marée - altitude - tirant - marge
          = offset - altitude          avec offset = marée - tirant - marge
```

C'est cette factorisation qui rend le curseur temporel gratuit : marée, tirant et marge ne
font que **translater** la rampe de couleurs le long de l'axe des altitudes. Voir
`UnderKeelClearance.kt`.

## 2. Le piège du rééchantillonnage

Le plan dit : *« rééchantillonner en mode `min`, on veut la profondeur la plus faible de
la maille »*.

L'intention est juste, le mot est un piège. La profondeur la plus faible = le point le
moins profond = **l'altitude la plus grande**. Sur un raster d'altitudes il faut donc
`max`, pas `min`.

```
gdalwarp -r min   # ← garde le point le plus PROFOND. Optimiste. Interdit.
gdalwarp -r max   # ← garde le point le moins profond. Ce qu'on veut.
```

`build_bathymetry.py` utilise `Resampling.max` et le répète à trois endroits, parce que
c'est exactement le genre de ligne qu'un contributeur bien intentionné « corrige ».

Corollaire : chaque niveau de zoom est reconstruit **depuis la source**, pas depuis le
niveau au-dessus. Six applications successives de « le plus haut des quatre » dérivent
vers le haut ; un rééchantillonnage direct depuis les points d'origine, non.

## 3. Absence de donnée

Le lidar bathymétrique ne pénètre que 10 à 20 m selon la turbidité. Au-delà, Litto3D n'a
rien. Ces trous sont fréquents et souvent collés à de l'eau navigable.

**La sentinelle vaut `+9000 m`, au-dessus de toute altitude réelle, pas en dessous.**

C'est contre-intuitif et c'est le point le plus important du fichier. Le GPU filtre la
texture du MNT : un pixel sur le bord d'un trou décode un mélange pondéré entre une valeur
réelle et la sentinelle. Avec une sentinelle basse (`-10000`), ce mélange donnerait une
altitude très négative, c'est-à-dire **du bleu profond sur une zone non levée**. Avec une
sentinelle haute, le même mélange donne du rouge puis du violet.

| | sentinelle basse | sentinelle haute |
|---|---|---|
| trou non levé | bleu profond ❌ | violet ✅ |
| bord filtré du trou | bleu profond ❌ | rouge ✅ |
| consommateur qui ignore la sentinelle | « eau très profonde » ❌ | « terre » ✅ |

Seuil de détection : `+8000 m`. L'écart de 1000 m entre le seuil et la sentinelle absorbe
le filtrage. Testé dans `DepthPaletteTest.every blend between real ground and the sentinel
renders as danger`.

Aux zooms d'ensemble, une maille n'est marquée « pas de donnée » que si **tous** ses fils
le sont. Propager la sentinelle dès un seul fils transformerait les petits zooms en mur de
violet. Le prix est que les trous ne sont fidèlement rendus qu'au zoom natif ; l'app
contraint donc le zoom maximum à celui de l'archive et affiche la plage de zooms dans le
HUD.

## 4. Quantification Terrain-RGB

Pas de 0,1 m. L'encodage **arrondit le fond vers le haut** (`ceil`), jamais vers le bas.
Coût : jusqu'à 10 cm d'eau affichée en moins. Bénéfice : la quantification ne peut pas
inventer d'eau. `TerrainRgb.encode` en Kotlin et `terrain_rgb.encode` en Python
implémentent la même règle, et `tools/test_tools.py::KotlinParityTest` relit les
constantes Kotlin pour vérifier que les deux ne divergent pas.

## 5. Référentiel vertical des dalles Litto3D — à vérifier avant tout levé réel

Litto3D est livré en altitudes **IGN 1969** (RGF93 / Lambert-93 en planimétrie). L'app
travaille au **zéro hydrographique**. L'écart n'est pas constant le long de la côte : il
vaut environ 3,64 m à Brest et diffère sensiblement à Saint-Malo.

`build_bathymetry.py` **refuse de tourner** sans que l'un des trois soit fourni :

```
--datum-shift 3.64          constante ; défendable sur un seul port
--datum-grid separation.tif  grille de séparation ; à préférer dès qu'on dépasse une rade
--already-chart-datum        seulement après avoir lu les métadonnées du produit
```

Une valeur par défaut silencieuse serait la pire option possible : elle produirait une app
qui marche, en se trompant de trois mètres.

## 6. Ce que le rééchantillonnage à 10 m ne peut pas rattraper

Un caillou isolé plus petit que la maille peut disparaître, même en `max`, s'il n'a pas
été échantillonné par le levé. `max` garantit qu'on ne perd pas un point **mesuré** ; il
ne crée pas ce que le lidar n'a pas vu. C'est écrit dans l'écran Sources de l'app, pas
seulement ici.

## 7. Surcotes

Les prédictions harmoniques (phase 1) supposent 1013 hPa et pas de vent. Une dépression ou
un coup de vent d'ouest décale le niveau réel de plusieurs dizaines de centimètres, dans
les deux sens. L'écran Sources le dit ; quand le moteur de marée arrivera, l'affichage de
la hauteur devra le redire à côté de la valeur.

## 8. Attribution

À conserver dans l'app **et** dans les métadonnées de chaque archive `.pmtiles` produite,
pour qu'elle voyage avec le fichier :

```
Shom - IGN, 2024. https://doi.org/10.17183/LITTO3D_BZH_2018_2021   (Licence Ouverte 2.0)
© OpenSeaMap / OpenStreetMap contributors                          (ODbL)
```
