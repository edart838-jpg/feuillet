"""Entraîne les deux micro-réseaux d'amélioration embarqués dans Feuillet et écrit leurs poids en Kotlin.

Architecture (identique pour les deux) : 3 convolutions 3x3 (1 -> 8 -> 8 -> 1), ReLU, sortie = correction
résiduelle ajoutée à la luminance. 737 paramètres : l'inférence tient en Kotlin pur, sans bibliothèque.

  - « texte » : appris sur du texte rendu avec de nombreuses polices puis dégradé (flou, bruit, JPEG) ;
  - « photo » : appris sur des photographies libres (jeu d'exemples de scikit-image) dégradées de même.

Usage : python3 tools/train_micro_ai.py   (dépendances : jax optax pillow numpy opencv-python-headless scikit-image)
"""
import glob, io, os, subprocess, sys
import numpy as np, cv2
from PIL import Image, ImageDraw, ImageFont
import jax, jax.numpy as jnp, optax

rng = np.random.default_rng(3)
P = 48
OUT = os.path.join(os.path.dirname(__file__), "..", "core", "src", "main", "kotlin", "app", "feuillet", "core", "MicroNetWeights.kt")

WORDS = ("le la les un une des et ou mais donc car ni que qui dont où facture devis total montant TVA date client adresse "
         "numéro référence quantité prix unitaire hors taxes règlement échéance signature madame monsieur veuillez agréer "
         "société contrat article paragraphe conditions générales durée garantie livraison été élève çà noël œuvre à déjà "
         "the and of to in is that for with as on by this from invoice amount page 0123456789 12,50 € 3 596,00 2026-118 "
         "ABCDEFGHIJKLMNOPQRSTUVWXYZ (a) [b] « c » : ; ! ? % @ # & / - + =").split()


def fonts():
    out = subprocess.run(["fc-list", ":", "file"], capture_output=True, text=True).stdout
    files = sorted({l.split(":")[0] for l in out.splitlines() if l.split(":")[0].lower().endswith((".ttf", ".otf"))})
    keep = [f for f in files if not any(k in f.lower() for k in ("ipa", "math", "symbol", "emoji", "porson", "dingbat", "noto"))]
    return keep[:120]


FONTS = fonts()


def text_page(size=320):
    bg = int(rng.integers(236, 256)); ink = int(rng.integers(5, 85))
    im = Image.new("L", (size, size), bg); d = ImageDraw.Draw(im)
    y = int(rng.integers(0, 12))
    while y < size:
        fs = int(rng.integers(13, 46))
        try:
            f = ImageFont.truetype(FONTS[int(rng.integers(len(FONTS)))], fs)
        except Exception:
            continue
        line = " ".join(rng.choice(WORDS, size=14))
        d.text((int(rng.integers(-30, 10)), y), line, font=f, fill=ink)
        y += int(fs * rng.uniform(1.15, 1.7))
    if rng.random() < 0.3:                                        # filets de tableau
        for _ in range(int(rng.integers(1, 4))):
            p = int(rng.integers(0, size)); wd = int(rng.integers(1, 3))
            if rng.random() < 0.5: d.line([0, p, size, p], fill=ink, width=wd)
            else: d.line([p, 0, p, size], fill=ink, width=wd)
    return np.asarray(im, np.float32)


def photo_sources():
    import skimage.data as sd
    base = os.path.dirname(sd.__file__)
    names = ["astronaut.png", "coffee.png", "chelsea.png", "rocket.jpg", "camera.png", "motorcycle_left.png", "hubble_deep_field.jpg",
             "brick.png", "grass.png", "gravel.png", "coins.png", "moon.png", "retina.jpg", "ihc.png"]
    imgs = []
    for n in names:
        im = cv2.imread(os.path.join(base, n), cv2.IMREAD_GRAYSCALE)
        if im is not None: imgs.append(im.astype(np.float32))
    return imgs


def degrade(clean, blur, noise, jpeg):
    x = cv2.GaussianBlur(clean, (0, 0), rng.uniform(*blur))
    if rng.random() < 0.4:                                        # capture sous-résolue
        s = rng.uniform(0.6, 0.95); h, w = x.shape
        x = cv2.resize(cv2.resize(x, (int(w * s), int(h * s)), interpolation=cv2.INTER_AREA), (w, h), interpolation=cv2.INTER_LINEAR)
    x = x + rng.normal(0, rng.uniform(*noise), x.shape)
    x = np.clip(x, 0, 255).astype(np.uint8)
    ok, enc = cv2.imencode(".jpg", x, [cv2.IMWRITE_JPEG_QUALITY, int(rng.integers(*jpeg))])
    return cv2.imdecode(enc, cv2.IMREAD_GRAYSCALE).astype(np.float32)


def dataset(kind, n):
    X = np.empty((n, P, P, 1), np.float32); Y = np.empty((n, P, P, 1), np.float32)
    photos = photo_sources() if kind == "photo" else None
    i = 0
    while i < n:
        if kind == "texte":
            clean = text_page(); deg = degrade(clean, (0.4, 1.8), (0.5, 5.0), (55, 96))
        else:
            src = photos[int(rng.integers(len(photos)))]
            s = rng.uniform(0.5, 1.0)
            src = cv2.resize(src, None, fx=s, fy=s, interpolation=cv2.INTER_AREA)
            if min(src.shape) < 200: continue
            y0 = int(rng.integers(0, src.shape[0] - 199)); x0 = int(rng.integers(0, src.shape[1] - 199))
            clean = src[y0:y0 + 200, x0:x0 + 200]
            if rng.random() < 0.5: clean = clean[:, ::-1]
            clean = np.ascontiguousarray(clean)
            deg = degrade(clean, (0.3, 1.2), (1.0, 6.0), (60, 96))
        for _ in range(8):
            if i >= n: break
            y0 = int(rng.integers(0, clean.shape[0] - P)); x0 = int(rng.integers(0, clean.shape[1] - P))
            c = clean[y0:y0 + P, x0:x0 + P]
            if kind == "texte" and c.std() < 6 and rng.random() < 0.8: continue      # peu de patchs vides
            X[i, :, :, 0] = deg[y0:y0 + P, x0:x0 + P] / 255; Y[i, :, :, 0] = c / 255; i += 1
    return X, Y


def init(key):
    ks = jax.random.split(key, 3)
    shapes = [(3, 3, 1, 8), (3, 3, 8, 8), (3, 3, 8, 1)]
    params = []
    for k, s in zip(ks, shapes):
        fan = s[0] * s[1] * s[2]
        params.append((jax.random.normal(k, s) * np.sqrt(2.0 / fan) * (0.1 if s[3] == 1 else 1.0), jnp.zeros(s[3])))
    return params


def conv(x, w, b):
    return jax.lax.conv_general_dilated(x, w, (1, 1), "SAME", dimension_numbers=("NHWC", "HWIO", "NHWC")) + b


def net(params, x):
    h = jax.nn.relu(conv(x, *params[0]))
    h = jax.nn.relu(conv(h, *params[1]))
    return x + conv(h, *params[2])


def psnr(a, b):
    return float(10 * np.log10(1.0 / np.mean((np.clip(a, 0, 1) - b) ** 2)))


def unsharp(x, amount=0.7):
    blur = np.stack([cv2.blur(i[:, :, 0], (3, 3))[..., None] for i in x])
    return x + amount * (x - blur)


def train(kind, steps=9000):
    X, Y = dataset(kind, 24000)
    Xv, Yv = dataset(kind, 3000)
    params = init(jax.random.PRNGKey(0 if kind == "texte" else 1))
    sched = optax.cosine_decay_schedule(3e-3, steps)
    opt = optax.adam(sched); state = opt.init(params)

    def loss(p, x, y):
        return jnp.mean(jnp.abs(net(p, x) - y))

    @jax.jit
    def step(p, s, x, y):
        l, g = jax.value_and_grad(loss)(p, x, y)
        u, s = opt.update(g, s); return optax.apply_updates(p, u), s, l

    for it in range(steps):
        idx = rng.integers(0, len(X), 64)
        params, state, l = step(params, state, X[idx], Y[idx])
        if it % 1500 == 0: print(f"  {kind} étape {it} perte {float(l):.5f}", flush=True)
    out = np.asarray(net(params, Xv))
    res = (psnr(Xv, Yv), psnr(unsharp(Xv), Yv), psnr(out, Yv))
    print(f"{kind} : PSNR entrée {res[0]:.2f} dB | accentuation classique {res[1]:.2f} dB | micro-réseau {res[2]:.2f} dB", flush=True)
    return params, res


def kotlin_array(name, params):
    flat = np.concatenate([np.concatenate([np.asarray(w).transpose(3, 2, 0, 1).ravel(), np.asarray(b).ravel()]) for w, b in params])
    body = ",\n        ".join(", ".join(f"{v:.6g}f" for v in flat[i:i + 8]) for i in range(0, len(flat), 8))
    return f"    val {name}: FloatArray = floatArrayOf(\n        {body},\n    )\n", len(flat)


if __name__ == "__main__":
    print(f"{len(FONTS)} polices")
    pt, rt = train("texte"); pp, rp = train("photo")
    a, n = kotlin_array("TEXT", pt); b, _ = kotlin_array("PHOTO", pp)
    with open(OUT, "w") as f:
        f.write("package app.feuillet.core\n\n"
                "// Fichier généré par tools/train_micro_ai.py : ne pas modifier à la main.\n"
                f"// Mesures sur des extraits jamais vus à l'entraînement (PSNR, plus haut = plus fidèle à l'image nette) :\n"
                f"//   texte : entrée {rt[0]:.2f} dB, accentuation classique {rt[1]:.2f} dB, micro-réseau {rt[2]:.2f} dB\n"
                f"//   photo : entrée {rp[0]:.2f} dB, accentuation classique {rp[1]:.2f} dB, micro-réseau {rp[2]:.2f} dB\n"
                "// Ordre des poids, couche par couche : [sortie][entrée][ky][kx], puis les biais.\n"
                f"object MicroNetWeights {{\n    const val COUNT = {n}\n\n{a}\n{b}}}\n")
    print("écrit :", os.path.normpath(OUT))
