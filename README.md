# Feuillet

Scanner de documents pour Android : photographiez une page, Feuillet repère ses bords, l'aplanit même si elle
est bombée, la met au format (A4, A5, Lettre, carte…), nettoie l'image et l'exporte. Tout le traitement se fait
sur l'appareil, sans connexion.

## Installer

1. Sur le téléphone, touchez ce lien : **[Télécharger Feuillet.apk](https://github.com/edart838-jpg/feuillet/releases/latest/download/Feuillet.apk)** (toujours la dernière version).
2. Ouvrez le fichier téléchargé et autorisez l'installation depuis cette source. Android 8 ou plus récent.

Toutes les versions : <https://github.com/edart838-jpg/feuillet/releases>

Chaque version s'installe par-dessus la précédente sans perdre les scans.

## Ce que fait l'application

| Étape | Détail |
| --- | --- |
| Capture | Plusieurs pages à la suite, contour affiché en direct, déclenchement automatique quand la page est stable, lampe, import depuis la galerie. |
| Bords et courbure | Quatre coins détectés automatiquement et déplaçables (avec loupe), bords courbes suivis, bouton « Corriger la courbure », réglage de force, poignées de courbure manuelles, rotation, format de sortie. |
| Rendu | Filtres Auto, Couleur, Gris, Noir et blanc, Photo, Original ; suppression des ombres ; netteté par micro-réseau de neurones (texte ou photo) ; luminosité et contraste ; comparaison avec l'original en maintenant le doigt. |
| Bibliothèque | Recherche dans les titres, tags, dossiers et texte reconnu ; dossiers ; tags ; sélection multiple ; réordonner et supprimer des pages. |
| Export | PDF cherchable (texte invisible sélectionnable), PDF, JPEG, PNG, WebP, TIFF multipage, texte ; archive ZIP pour les images ; trois niveaux de qualité ; partage ou enregistrement dans Téléchargements/Feuillet. |
| OCR | Français et anglais fournis. Plus de quarante autres langues téléchargeables depuis les réglages, en version rapide ou précise. |

## Comment la courbure est corrigée

1. **Détection** : seuillage de la luminance et de la « blancheur », plus grande région claire, enveloppe convexe,
   quadrilatère d'aire maximale, puis coins affinés par intersection des bords voisins.
2. **Perspective** : homographie exacte des quatre coins ; le rapport largeur/hauteur réel de la feuille est
   retrouvé à partir de la perspective (méthode de Zhang et He).
3. **Bords courbes** : chaque bord est décrit par un polynôme ajusté sur le contour ; l'intérieur de la page est
   interpolé entre les quatre bords (patch de Coons) et re-paramétré par longueur d'arc pour dérouler la page.
4. **Lignes de texte** : l'ondulation restante de chaque ligne est mesurée dans la page aplanie, puis compensée
   par un champ de déplacement lissé. Jusqu'à deux passes.

Sur la photo de test (page bombée simulée), l'ondulation moyenne des lignes passe de plus de 3 pixels à moins
de 0,6 pixel ; ce seuil est vérifié par les tests.

## Micro-réseaux de neurones

Deux réseaux de 737 paramètres (trois convolutions 3×3) prédisent la correction de netteté de chaque pixel.
Ils sont exécutés en Kotlin pur, sur plusieurs cœurs, sans bibliothèque d'IA. Sur des extraits jamais vus à
l'entraînement (fidélité à l'image nette, PSNR) :

| Réseau | Image dégradée | Accentuation classique | Micro-réseau |
| --- | --- | --- | --- |
| Texte | 18,83 dB | 19,50 dB | 23,12 dB |
| Photo | 29,21 dB | 29,17 dB | 30,47 dB |

Ces mesures portent sur des dégradations simulées (flou, bruit, compression). `tools/train_micro_ai.py`
régénère les poids.

## Organisation du code

- `core/` : moteur d'image en Kotlin pur, sans dépendance Android (détection, dé-courbure, filtres,
  micro-réseaux, écrivains PDF et TIFF). Testé par `core/src/test`.
- `app/` : application Android (Jetpack Compose, CameraX, Tesseract pour l'OCR).
- `app/src/androidTest` : essai de bout en bout exécuté sur émulateur.
- `.github/workflows/build.yml` : tests, compilation de l'APK, publication dans les Releases, essai sur émulateur.

## Compiler

```
gradle :core:test            # tests du moteur
gradle :app:assembleRelease  # APK dans app/build/outputs/apk/release/
```

Nécessite le JDK 17, Gradle 8.9 et le SDK Android 34. Chaque envoi sur le dépôt lance la compilation
sur GitHub Actions.

## Limites connues

- La détection automatique suppose une page plus claire que son support. Sur un fond clair ou très chargé,
  les coins sont à placer à la main.
- La correction de courbure vise les pages bombées ou cintrées (livre ouvert, feuille roulée). Les pages
  froissées ou pliées en plusieurs sens ne sont redressées qu'en partie.
- L'application a été vérifiée par ses tests et sur émulateur, pas encore sur un téléphone physique : la
  qualité avec un vrai capteur photo reste à confirmer.
- La clé de signature est dans le dépôt (`signing/`) pour que les mises à jour s'installent les unes sur les
  autres. Il faut la remplacer par une clé privée avant toute publication sur un magasin d'applications.
- Les modules de langues sont téléchargés depuis les dépôts GitHub de Tesseract ; c'est le seul usage du réseau.
