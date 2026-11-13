# Banc d'essai de la détection

```
python3 gen.py 20        # écrit img/ et gt.csv (330 scènes) dans le dossier courant
kotlinc ../../core/src/main/kotlin -d core.jar
kotlinc -cp core.jar DetBench.kt -d bench.jar
java -cp core.jar:bench.jar:<kotlin-stdlib.jar> DetBench .    # ajouter « v » pour lister les échecs
```

Dépendances Python : numpy, scipy, opencv-python-headless, pillow, scikit-image.
