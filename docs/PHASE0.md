# Phase 0 — spike bathymétrie

> **Objectif** : dérisquer. Si le volume disque ou les perfs ne passent pas, toute
> l'architecture change, donc rien d'autre ne doit être construit avant.

## Résultat : les deux risques sont levés côté conception, un reste à confirmer sur device

### Risque 1 — « comment coloriser dynamiquement un MNT dans MapLibre ? »

**Levé, et beaucoup plus simplement que prévu.** Aucun code natif, aucun NDK, aucune
`CustomLayer`.

MapLibre Native 13.x apporte deux choses qui, ensemble, suppriment le problème :

* une **source de fichiers `pmtiles://` native** — l'archive est lue en place, en
  range-reads, sans serveur de tuiles et sans permission réseau ;
* une **couche `color-relief`** dont le fragment shader décode le Terrain-RGB sur le GPU,
  puis fait une **recherche dichotomique dans un tableau de stops en float exacts**.

Ce dernier point est celui qui décide de l'architecture. Le shader ne rééchantillonne pas
la rampe dans une texture de résolution fixe : il lit les altitudes de stop telles
quelles. Conséquences directes :

* les bords de bande sont **exacts** — deux stops distants d'un millimètre produisent une
  vraie marche, donc les bandes rouge / orange / jaune du plan survivent ;
* un stop très éloigné des autres (la sentinelle « pas de donnée » à 9000 m) ne coûte
  aucune précision aux bandes de 50 cm, et peut donc vivre dans la même rampe.

Et surtout, la marée, le tirant d'eau et la marge se replient en **un seul scalaire** :

```
offset = marée − tirant − marge
clearance = offset − altitude
```

Faire bouger le curseur ne change que `offset`, c'est-à-dire **translate la rampe**. Une
image du curseur temporel = une mise à jour de propriété. Rien n'est redécodé, aucune
tuile n'est retraitée, le CPU ne touche pas un pixel. C'est ce qui rend le critère de
sortie « 60 fps pendant le scrub » atteignable plutôt qu'optimiste.

`DepthPaletteTest.sliding the time cursor only translates the ramp` verrouille cette
propriété : sept stops se décalent de la variation de marée, trois restent fixes, aucune
couleur ne change.

### Risque 2 — « moins de 400 Mo pour toute la Bretagne ? »

**Levé avec une marge confortable**, sous un modèle explicite.

Le nombre de tuiles est de la géométrie pure, donc calculable exactement. Le poids par
tuile dépend du bruit réel du levé, donc `tools/estimate_volume.py` refuse de le deviner
et affiche le **seuil de rentabilité** à la place :

```
$ ./tools/estimate_volume.py
Brittany bathymetry, 2730 km of coastline x 2.0 km of Litto3D coverage
tile size 256 px, zooms 0-14
...
    14      6.3        2,071
         total         3,761
      native zoom is 2,071 tiles (55 % of the archive)

break-even: 108.9 KiB per tile to stay inside 400 MB
```

**109 KiB par tuile de budget**, quand une tuile Terrain-RGB 256 px de bathymétrie réelle
pèse typiquement 40 à 90 KiB. Le budget tient. À titre de contrôle, le mode `--mode bbox`
(rectangle entier, terre et large compris) donne 13,2 KiB/tuile et **ne tiendrait pas** —
ce qui montre que la conclusion dépend entièrement de l'hypothèse « Litto3D est une bande
côtière », qui est vraie mais qui doit être dite.

À confirmer avec un vrai département :

```
./tools/build_bathymetry.py --input '~/litto3d/finistere/*.asc' \
    --out finistere.pmtiles --datum-shift 3.64
./tools/estimate_volume.py --bytes-per-tile <taille / nombre de tuiles>
```

### Ce qui n'a pas pu être vérifié ici

Honnêtement, et ce sont les seules cases du plan qui restent ouvertes :

* **le framerate sur device.** Aucun téléphone, et l'environnement de build n'atteignait
  pas `dl.google.com`, donc pas de SDK Android en local. La CI compile l'APK ; personne
  n'a encore regardé l'écran.
* **la forme exacte de l'URL `pmtiles://`.** `ChartArchive.sourceUri` produit
  `pmtiles://file:///chemin/x.pmtiles`, qui est la composition documentée du protocole
  PMTiles avec le schéma `file://` (les deux existent bien dans `libmaplibre.so` 13.4.1).
  Si MapLibre la rejette, l'app affiche l'erreur en rouge en bas de l'écran plutôt que de
  rester noire — c'est le premier truc à regarder au premier lancement.
* **le rendu des trous de données sur un vrai GPU.** La propriété est démontrée en test
  unitaire pour tout mélange sentinelle/réel, mais le filtrage exact de la texture MNT par
  MapLibre n'a pas été observé.

## Critère de sortie

> *« la colorisation change quand on bouge un slider de seuil, à 60 fps sur un mobile
> milieu de gamme. »*

Protocole, une fois l'APK installé :

1. `./tools/make_sample_pmtiles.py --out sample.pmtiles` (15 s, aucune dépendance)
2. `adb push sample.pmtiles /sdcard/Android/data/org.opennav/files/charts/`
3. Lancer l'app. Le HUD en haut à gauche donne `frame` en millisecondes : **16,6 ms est la
   ligne des 60 fps**. Le chiffre vient de MapLibre lui-même
   (`onDidFinishRenderingFrame`), pas de Compose, donc il mesure bien le shader.
4. Balayer le slider « Hauteur d'eau simulée » de bout en bout. Le chenal doit rester
   bleu, le haut-fond doit passer du bleu au jaune, à l'orange, au rouge, et le trou de
   données doit rester violet quoi qu'il arrive.
5. Noter `carte` (Mo) dans le HUD pour recouper l'estimation de volume.

L'échantillon synthétique contient exactement les trois objets nécessaires à ce test : un
chenal dragué, un haut-fond qui découvre, et un trou non levé.

## Ce qui a été construit, et ce qui ne l'a pas été

Construit :

* `:core:depth` — la totalité de l'arithmétique critique, JVM pur, 23 tests
* `:core:geo` — distances et caps, JVM pur, 10 tests (ajouté pour l'outil de mesure)
* `:app` — carte plein écran, roue de paramètres, barre d'outils, GPS, mesure deux points
* `tools/` — pipeline Litto3D → PMTiles, générateur d'échantillon, estimateur de volume,
  17 tests dont un contrôle de parité entre les constantes Python et Kotlin
* CI — tests JVM, tests Python, APK signé avec une clé stable

Volontairement **pas** construit, pour ne pas préempter les phases suivantes :
`:core:tide` (moteur harmonique), `:data:persistence` (Room), `:feature:anchor`,
`:feature:trip`, la superposition OpenSeaMap, le mode nuit rouge.

La hauteur d'eau est réglée à la main et l'interface le dit explicitement : « Hauteur
d'eau simulée / Moteur de marée réel : phase 1 ». Un slider qui afficherait « 14:30,
PM+2h » en inventant le chiffre serait exactement le genre d'erreur confiante que cette
app existe pour éviter.

## Ordre suggéré pour la suite

1. Faire tourner le protocole ci-dessus sur un vrai téléphone et remplir les deux
   chiffres manquants (fps, Mo).
2. Construire une vraie dalle Litto3D avec `build_bathymetry.py` — c'est là que la
   question du référentiel vertical (docs/DATA.md §5) doit être tranchée pour de bon.
3. Seulement ensuite, phase 1 : `:core:tide`.
