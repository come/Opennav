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

### Avec de vraies données

```bash
pip install -r tools/requirements.txt
./tools/build_bathymetry.py \
    --input '~/litto3d/finistere/*.asc' \
    --out finistere.pmtiles \
    --datum-shift 3.64          # obligatoire : voir docs/DATA.md §5
```

## Interface

Carte plein écran, et le minimum autour :

| | |
|---|---|
| roue en haut à droite | tirant d'eau, marge de sécurité, légende, sources |
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

```
Shom - IGN, 2024. https://doi.org/10.17183/LITTO3D_BZH_2018_2021   (Licence Ouverte 2.0)
© OpenSeaMap / OpenStreetMap contributors                          (ODbL)
```

Opennav est distribué sous **GNU GPL v3 ou ultérieure**, sans aucune garantie. Voir
[LICENSE](LICENSE).
