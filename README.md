# Opennav

Application Android de navigation côtière, libre et hors-ligne, centrée sur une seule
question :

> **ai-je assez d'eau sous la quille, ici et maintenant, et dans deux heures ?**

Le cœur du produit est une couche de bathymétrie corrigée en temps réel de la marée et du
tirant d'eau du bateau, colorisée dynamiquement sur la carte. Zone couverte en v1 : la
Bretagne.

> ⚠️ **Outil d'aide à la décision.** Opennav ne remplace ni les cartes marines
> officielles du SHOM, ni le sondeur, ni la veille. Les profondeurs affichées viennent
> d'un levé de 2018-2021 rééchantillonné à 10 m, corrigé d'une hauteur d'eau saisie à la
> main. Elles peuvent être fausses.

## État : phase 0 (spike) terminée

Le plan de développement commence par un spike technique dont le but est de dérisquer
avant d'écrire quoi que ce soit d'autre. Les conclusions sont dans
**[docs/PHASE0.md](docs/PHASE0.md)** ; en deux lignes :

* **La colorisation dynamique ne demande aucun code natif.** MapLibre Native 13.x fournit
  une source `pmtiles://` et une couche `color-relief` dont le shader lit des stops en
  float exacts. Marée, tirant et marge se replient en un scalaire qui *translate* la
  rampe : bouger le curseur ne redécode aucune tuile.
* **Le budget de 400 Mo tient largement** — 3 761 tuiles pour la Bretagne, soit 109 KiB
  par tuile de marge, contre 40 à 90 KiB pour une tuile Terrain-RGB réelle.

Reste à faire tourner l'APK sur un vrai téléphone pour remplir les deux derniers chiffres
(fps et Mo). Le protocole est dans [docs/PHASE0.md](docs/PHASE0.md#critère-de-sortie).

## Essayer

Aucune donnée SHOM n'est nécessaire pour un premier lancement : le dépôt sait fabriquer un
morceau de fond marin synthétique, avec un chenal, un haut-fond qui découvre et un trou
non levé.

```bash
# 1. Une carte d'essai (15 s, bibliothèque standard uniquement)
./tools/make_sample_pmtiles.py --out sample.pmtiles

# 2. L'APK — ou récupérez l'artefact `opennav-apk-debug` de la CI
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 3. La carte, là où l'app la cherche
adb shell mkdir -p /sdcard/Android/data/org.opennav/files/charts
adb push sample.pmtiles /sdcard/Android/data/org.opennav/files/charts/
```

Puis balayez le slider « Hauteur d'eau simulée » : le haut-fond doit passer du bleu au
jaune, à l'orange, au rouge, et le trou de données doit rester violet quoi qu'il arrive.

## Obtenir la bathymétrie de la Bretagne

**Il n'existe pas de carte Bretagne à télécharger.** Le dépôt ne contient aucune donnée
SHOM et n'en contiendra pas : Litto3D fait plusieurs dizaines de gigaoctets et sa licence
demande une attribution que seul l'écran Sources peut porter. Vous fabriquez le fichier
une fois, sur un poste de travail, puis vous le poussez sur le téléphone. Comptez une
soirée pour la première zone, quelques minutes pour les suivantes.

### 1. Récupérer les dalles Litto3D

Sur **[diffusion.shom.fr](https://diffusion.shom.fr)** — compte gratuit obligatoire.
Cherchez le produit **Litto3D® Bretagne 2018-2021**
([DOI 10.17183/LITTO3D_BZH_2018_2021](https://doi.org/10.17183/LITTO3D_BZH_2018_2021)),
puis téléchargez les prépaquets **de la zone qui vous intéresse seulement** — la Bretagne
entière est inutilement lourde pour commencer.

Deux choses à ne pas rater au moment de choisir :

* prenez le **MNT raster** (grille régulière, `.asc` ou `.tif`), **pas** le nuage de
  points LAZ. Le pipeline lit des rasters ;
* notez le **référentiel altimétrique** annoncé dans la notice du produit. C'est la seule
  information dont vous aurez besoin ensuite, et c'est celle qui peut tout fausser.

### 1 bis. Regarder ce que vous avez téléchargé

```bash
./tools/inspect_source.py --input '~/litto3d/morbihan/*.asc'
```

Projection, résolution, nodata, et la plage d'altitudes réellement présente. Ça attrape
les erreurs classiques avant la conversion : un nuage de points à la place d'un raster,
un `.asc` livré sans son `.prj` (donc sans projection, donc refusé), ou une dalle qui ne
couvre pas la zone visée.

### 2. Trancher la question du zéro hydrographique

Litto3D est livré en altitudes **IGN 1969**. L'app raisonne en hauteurs au-dessus du
**zéro hydrographique** (ZH). L'écart entre les deux vaut plusieurs mètres et **varie le
long de la côte** : ce n'est pas un détail cosmétique, c'est un décalage systématique sur
toutes les profondeurs affichées.

`build_bathymetry.py` refuse donc de tourner sans réponse explicite. Trois façons de
répondre, de la moins bonne à la meilleure :

```bash
--datum-shift 3.64            # une constante, en mètres, à ajouter aux altitudes source
--datum-grid separation.tif   # une grille de séparation, échantillonnée par pixel
--already-chart-datum         # le produit est déjà référencé au ZH
```

> **La valeur `3.64` est un exemple, pas une donnée vérifiée par ce dépôt.**

Plutôt que de chercher ce nombre dans une table, **mesurez-le sur vos propres fichiers**.
Prenez trois ou quatre points bien répartis, relevez la sonde portée sur la carte SHOM à
ces positions, et demandez :

```bash
./tools/inspect_source.py --input '~/litto3d/morbihan/*.asc' \
    --calibrate -2.9000 47.5430 5.0 \
    --calibrate -3.0810 47.4640 12.3 \
    --calibrate -2.8750 47.3360 22.0
```

Le script sort le décalage impliqué par chaque point, la moyenne, et surtout la
**dispersion** :

* moins de 15 cm d'écart → une constante est défendable, il vous donne la valeur ;
* jusqu'à 40 cm → ça passe, mais découpez la zone en deux archives pour être propre ;
* au-delà → la zone est trop grande pour une constante (ou une sonde est mal relevée), et
  `--datum-grid` est la bonne réponse ;
* un décalage quasi nul → vos données sont déjà au ZH, passez `--already-chart-datum`.

C'est plus fiable qu'une table, parce que ça mesure *vos* fichiers plutôt que ce que la
notice dit d'eux.

### 3. Construire, et vérifier

Une fois le décalage connu :

```bash
pip install -r tools/requirements.txt

./tools/build_bathymetry.py \
    --input '~/litto3d/rade-de-brest/*.asc' \
    --out rade-de-brest.pmtiles \
    --area-name "Rade de Brest" \
    --datum-shift 3.64
```

Puis relisez ce que vous venez de produire, avant de le copier sur le téléphone :

```bash
./tools/inspect_pmtiles.py rade-de-brest-bathy.pmtiles
```

Il décode de vraies tuiles plutôt que de croire les métadonnées : plage d'altitudes, part
de cellules non levées, et un avertissement si rien n'est plus profond qu'un mètre — la
signature d'un `--datum-shift` oublié.

`./tools/estimate_volume.py --mode band` donne le nombre de tuiles et le budget par tuile
avant de lancer une grosse zone.

### 4. Le mettre dans l'app

Le plus simple, sans câble : copiez le `.pmtiles` sur le téléphone par le moyen qui vous
arrange (clé USB, cloud, partage), puis dans l'app **roue → Carte → Importer une carte**.
Le sélecteur de fichiers Android s'ouvre, l'app recopie l'archive dans son dossier et
bascule dessus immédiatement. Aucune permission de stockage, aucun accès réseau : le
fichier est déjà là.

L'import refuse un fichier qui n'est pas une archive PMTiles v3 plutôt que de vous laisser
avec une carte vide, et il écrit sous un nom temporaire jusqu'à la fin de la copie — un
import interrompu ne laisse pas une archive tronquée qui aurait l'air valide.

Avec un câble et `adb`, ça marche aussi :

```bash
adb shell mkdir -p /sdcard/Android/data/org.opennav/files/charts
adb push rade-de-brest.pmtiles /sdcard/Android/data/org.opennav/files/charts/
```

Le dossier `Android/data/org.opennav/files/charts` est accessible sans root ; n'importe
quel gestionnaire de fichiers fait l'affaire. Redémarrez l'app après avoir déposé le
fichier de cette façon.

Quand plusieurs cartes sont installées, celle que vous avez importée en dernier gagne ;
sinon c'est la plus grosse, pour qu'un vrai levé l'emporte sur l'échantillon synthétique.
Vérifiez laquelle est chargée dans **Sources** : le nom du fichier, la plage de zooms et
l'emprise y sont affichés.

### Ce que l'app ne vérifie pas encore

Elle colorise n'importe quel Terrain-RGB qu'on lui donne, **sans savoir d'où il vient**.
Un MNT à 100 m de maille (EMODnet, GEBCO) s'affichera exactement comme un levé à 10 m,
avec la même confiance apparente et sans le moindre avertissement. N'y mettez que ce que
vous avez construit avec `build_bathymetry.py`, à partir d'un levé dont vous connaissez la
résolution et le référentiel.

## Interface

Carte plein écran, et le minimum autour :

| | |
|---|---|
| roue en haut à droite | tirant d'eau, marge de sécurité, import de carte, légende, sources |
| barre du bas | recentrer sur le GPS · mesurer · marée · sources |
| **mesurer** | deux taps posent deux points ; la carte affiche la distance en milles et la route fond initiale |
| bandeau permanent | le rappel légal, qui ne se ferme pas |
| HUD en haut à gauche | ms/image, zoom, poids de la carte, SOG, COG — désactivable |

Pinch, rotation et inertie sont ceux de MapLibre. L'écran reste allumé tant que l'app est
au premier plan.

## Ce que l'app ne fait pas, et ne fera pas en v1

Pas de routage météo, pas d'AIS, pas de NMEA ni de Signal K, pas de compte, pas de
backend, pas de synchronisation. **Aucune permission réseau** : `INTERNET` est
explicitement retirée du manifeste fusionné, y compris celle que déclare le SDK MapLibre.
Aucune télémétrie, aucun traceur.

Le GPS passe par le `LocationManager` de la plateforme et non par
`FusedLocationProviderClient` — c'est un écart assumé au plan initial, parce que le
fournisseur fusionné vit dans les Google Play Services, qui sont propriétaires, absents
des téléphones dégooglisés et demandent le réseau. Le contrat « aucun traceur » l'emporte
sur le tableau des choix techniques. Revenir en arrière serait un changement d'un seul
fichier.

## Architecture

```
:app                 UI Compose, carte, outils de bord
:core:depth          eau sous la quille, seuils, rampe de couleurs   ← JVM pur, testé
:core:geo            distances, caps, orthodromie                    ← JVM pur, testé
tools/               pipeline Litto3D → PMTiles (hors téléphone)
```

Règle : `:core:*` ne dépend pas d'Android. C'est là que vit l'arithmétique qui peut mettre
un bateau au sec, donc c'est là que se concentrent les tests — et ils tournent sur un JDK
nu, sans SDK Android :

```bash
./gradlew :core:depth:test :core:geo:test --configure-on-demand
python3 -m unittest discover -s tools
```

Les modules `:core:tide`, `:data:persistence`, `:feature:anchor` et `:feature:trip` du
plan n'existent pas encore, volontairement.

## Conservatisme

En cas de doute sur une donnée, l'app affiche la valeur la plus pessimiste. Trois endroits
où cette règle a des conséquences non évidentes, toutes documentées dans
**[docs/DATA.md](docs/DATA.md)** :

1. La quantification Terrain-RGB **remonte** le fond (`ceil`), jamais l'inverse. Coût
   maximum : 10 cm d'eau affichée en moins.
2. L'absence de levé est encodée **au-dessus** de toute altitude réelle, pas en dessous.
   Le GPU filtre la texture du MNT : avec une sentinelle basse, le bord d'un trou
   s'afficherait en bleu profond. Avec une sentinelle haute, il s'affiche en rouge.
3. Le rééchantillonnage garde le point **le moins profond** de chaque maille. Sur un
   raster d'altitudes cela s'écrit `max`, pas `min` — le mot du plan est un piège, et
   l'inverser rendrait tous les zooms d'ensemble optimistes.

## Signature des APK

`ci/opennav-ci.keystore` est versionné exprès, mot de passe publié dans
[ci/README.md](ci/README.md). Android refuse d'installer une version signée par une autre
clé : une clé régénérée à chaque build obligerait à désinstaller — et donc à perdre le
profil bateau — avant chaque mise à jour. Ce n'est **pas** une clé de publication ; la CI
utilise automatiquement une vraie clé si le secret `RELEASE_KEYSTORE_BASE64` est présent.

## Sources et licences

Actuellement affiché, et donc actuellement attribué :

```
Shom - IGN, 2024. https://doi.org/10.17183/LITTO3D_BZH_2018_2021   (Licence Ouverte 2.0)
```

**OpenSeaMap n'est pas utilisé.** La superposition du balisage est prévue en phase 2 ;
tant qu'elle n'affiche rien, l'app ne la crédite pas — attribuer une source dont on ne
montre aucune donnée laisserait croire que le balisage est à l'écran alors qu'il n'y est
pas. Le jour où la couche existera :

```
© OpenSeaMap / OpenStreetMap contributors                          (ODbL)
```

Opennav est distribué sous **GNU GPL v3 ou ultérieure**, sans aucune garantie. Voir
[LICENSE](LICENSE).
