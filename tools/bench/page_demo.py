"""Démo avant/après : photo simulée d'une page courbée -> pipeline réel
(détection des bords, dé-courbure par patch de Coons, format A4, correction couleur).
Le pipeline ne connaît PAS la déformation appliquée à la simulation."""
import numpy as np, cv2
from PIL import Image, ImageDraw, ImageFont
from scipy.interpolate import griddata

rng = np.random.default_rng(7)
OUT = "./"
F = "/usr/share/fonts/truetype/dejavu/"
W, H = 1240, 1754  # A4 @150 dpi


def font(name, size):
    return ImageFont.truetype(F + name, size)


# ---------- 1. Page A4 d'origine ----------
def make_page():
    im = Image.new("RGB", (W, H), (252, 251, 247))
    d = ImageDraw.Draw(im)
    d.rectangle([90, 90, 150, 150], fill=(28, 44, 84))
    d.text((170, 92), "Atelier Mercier", font=font("DejaVuSerif-Bold.ttf", 40), fill=(20, 24, 36))
    d.text((170, 142), "12 rue des Tanneurs · 69003 Lyon", font=font("DejaVuSans.ttf", 20), fill=(90, 96, 110))
    d.text((W - 400, 100), "DEVIS N° 2026-118", font=font("DejaVuSans-Bold.ttf", 26), fill=(28, 44, 84))
    d.text((W - 400, 138), "Lyon, le 13 novembre 2026", font=font("DejaVuSans.ttf", 20), fill=(90, 96, 110))
    d.line([90, 200, W - 90, 200], fill=(28, 44, 84), width=3)
    d.text((90, 240), "Objet : rénovation de la bibliothèque murale", font=font("DejaVuSerif-Bold.ttf", 28), fill=(20, 24, 36))
    para = ("Madame, Monsieur, suite à notre visite du 4 novembre, nous avons le plaisir de vous "
            "adresser notre proposition détaillée pour la fabrication et la pose d'une bibliothèque "
            "sur mesure en chêne massif. Les dimensions relevées sur place figurent dans le tableau "
            "ci-dessous. Les délais annoncés tiennent compte du séchage des finitions et de la "
            "disponibilité des essences retenues lors de notre échange.")
    y = 300
    f = font("DejaVuSerif.ttf", 22)
    words, line = para.split(), ""
    for w in words:
        if d.textlength(line + " " + w, font=f) > W - 180:
            d.text((90, y), line, font=f, fill=(35, 38, 48)); y += 34; line = w
        else:
            line = (line + " " + w).strip()
    d.text((90, y), line, font=f, fill=(35, 38, 48)); y += 60
    # tableau
    rows = [("Désignation", "Qté", "P.U. HT", "Total HT"),
            ("Montants chêne massif 32 mm", "6", "148,00 €", "888,00 €"),
            ("Tablettes réglables 28 mm", "14", "62,00 €", "868,00 €"),
            ("Portes basses à cadre", "4", "195,00 €", "780,00 €"),
            ("Finition huile-cire, 2 couches", "1", "420,00 €", "420,00 €"),
            ("Pose et ajustements sur site", "1", "640,00 €", "640,00 €")]
    cols = [90, 640, 780, 960, W - 90]
    for i, r in enumerate(rows):
        if i == 0:
            d.rectangle([90, y, W - 90, y + 48], fill=(28, 44, 84))
        elif i % 2 == 0:
            d.rectangle([90, y, W - 90, y + 48], fill=(240, 240, 234))
        for j, c in enumerate(r):
            d.text((cols[j] + 14, y + 12), c, font=font("DejaVuSans-Bold.ttf" if i == 0 else "DejaVuSans.ttf", 20),
                   fill=(255, 255, 255) if i == 0 else (35, 38, 48))
        y += 48
    d.rectangle([90, y - 48 * len(rows), W - 90, y], outline=(28, 44, 84), width=2)
    d.text((780, y + 20), "Total HT", font=font("DejaVuSans-Bold.ttf", 24), fill=(20, 24, 36))
    d.text((960 + 14, y + 20), "3 596,00 €", font=font("DejaVuSans-Bold.ttf", 24), fill=(170, 40, 40))
    y += 100
    # graphique en barres (zone "photo/couleur")
    d.text((90, y), "Répartition du budget", font=font("DejaVuSerif-Bold.ttf", 24), fill=(20, 24, 36)); y += 50
    vals = [888, 868, 780, 420, 640]
    cols_b = [(28, 44, 84), (196, 120, 40), (60, 130, 110), (170, 40, 40), (120, 96, 160)]
    for i, (v, c) in enumerate(zip(vals, cols_b)):
        x0 = 110 + i * 110
        d.rectangle([x0, y + 260 - v * 0.27, x0 + 70, y + 260], fill=c)
    d.line([90, y + 260, 680, y + 260], fill=(60, 60, 60), width=2)
    # bloc conditions
    f2 = font("DejaVuSerif.ttf", 20)
    cond = ["Validité de l'offre : 60 jours.", "Acompte de 30 % à la commande.",
            "Délai de fabrication : 5 semaines.", "Garantie décennale incluse.",
            "TVA 10 % applicable (rénovation)."]
    for i, c in enumerate(cond):
        d.text((740, y + 20 + i * 44), "•  " + c, font=f2, fill=(35, 38, 48))
    y += 330
    for k in range(5):
        d.text((90, y), ("Les travaux seront réalisés dans le respect des règles de l'art ; toute modification "
                         "fera l'objet d'un avenant signé.")[: 96 - k * 3], font=font("DejaVuSerif.ttf", 19), fill=(70, 74, 84))
        y += 32
    d.text((W - 430, H - 230), "Bon pour accord, le client :", font=font("DejaVuSans.ttf", 20), fill=(60, 64, 74))
    d.line([W - 430, H - 120, W - 90, H - 120], fill=(120, 120, 120), width=2)
    d.text((90, H - 90), "SIRET 812 345 678 00021 · contact@atelier-mercier.example", font=font("DejaVuSans.ttf", 17), fill=(120, 124, 134))
    return cv2.cvtColor(np.array(im), cv2.COLOR_RGB2BGR)


# ---------- 2. Simulation de la photo ----------
PW, PH = 1500, 2000


def simulate(page):
    gu, gv = np.meshgrid(np.linspace(0, 1, 260), np.linspace(0, 1, 360))
    z = np.sin(np.pi * np.clip(gu * 0.85 + 0.05, 0, 1)) ** 1.3          # bombement (reliure à gauche)
    x = 0.04 * np.sin(np.pi * gu) + gu
    y = gv - 0.085 * z * (1.25 - 0.75 * gv)
    pts = np.stack([x * 1000 + 250, y * 1414 + 330, np.ones_like(x)], -1)
    Hm = np.array([[0.93, -0.11, 60], [0.07, 0.90, 40], [-0.00006, 0.00011, 1.0]])
    q = pts @ Hm.T
    q = q[..., :2] / q[..., 2:]
    X, Y = np.meshgrid(np.arange(PW), np.arange(PH))
    mu = griddata(q.reshape(-1, 2), gu.ravel(), (X, Y), method="linear")
    mv = griddata(q.reshape(-1, 2), gv.ravel(), (X, Y), method="linear")
    mz = griddata(q.reshape(-1, 2), z.ravel(), (X, Y), method="linear")
    mask = ~np.isnan(mu)
    mapx = np.nan_to_num(mu * (W - 1)).astype(np.float32)
    mapy = np.nan_to_num(mv * (H - 1)).astype(np.float32)
    warped = cv2.remap(page, mapx, mapy, cv2.INTER_CUBIC).astype(np.float32)
    # ombrage dû à la courbure + éclairage latéral
    slope = np.nan_to_num(np.gradient(np.nan_to_num(mz), axis=1)) * 420
    shade = np.clip(0.86 + 0.75 * slope, 0.70, 1.03)
    shade = cv2.GaussianBlur(shade.astype(np.float32), (0, 0), 14)
    light = 0.80 + 0.24 * (X / PW) * (1 - 0.35 * Y / PH)
    warped *= (shade * light)[..., None]
    warped *= np.array([0.84, 0.95, 1.03])                               # dominante chaude (BGR)
    # bureau
    desk = np.zeros((PH, PW, 3), np.float32)
    grain = cv2.GaussianBlur(rng.normal(0, 1, (PH, PW)).astype(np.float32), (0, 0), sigmaX=60, sigmaY=2)
    grain = grain / np.abs(grain).max()
    base = np.array([38, 52, 74], np.float32)
    desk[:] = base
    desk *= (1 + 0.45 * grain)[..., None]
    desk *= (0.75 + 0.4 * X / PW)[..., None]
    # ombre portée
    m = mask.astype(np.float32)
    sh = cv2.GaussianBlur(np.roll(np.roll(m, 26, 0), -18, 1), (0, 0), 22)
    desk *= (1 - 0.6 * sh)[..., None]
    a = cv2.GaussianBlur(m, (0, 0), 1.2)[..., None]
    photo = desk * (1 - a) + warped * a
    photo = cv2.GaussianBlur(photo, (0, 0), 0.9)
    photo += rng.normal(0, 4.0, photo.shape)
    return np.clip(photo, 0, 255).astype(np.uint8)


# ---------- 3. Pipeline réel ----------
def resample(poly, n):
    poly = poly.astype(np.float64)
    seg = np.linalg.norm(np.diff(poly, axis=0), axis=1)
    s = np.concatenate([[0], np.cumsum(seg)])
    t = np.linspace(0, s[-1], n)
    return np.stack([np.interp(t, s, poly[:, 0]), np.interp(t, s, poly[:, 1])], -1)


def detect(photo):
    lab = cv2.cvtColor(photo, cv2.COLOR_BGR2LAB)
    L = cv2.GaussianBlur(lab[..., 0], (0, 0), 5)
    _, th = cv2.threshold(L, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    th = cv2.morphologyEx(th, cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (41, 41)))
    th = cv2.morphologyEx(th, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (15, 15)))
    cnts, _ = cv2.findContours(th, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    c = max(cnts, key=cv2.contourArea)[:, 0, :]
    # coins : sommets du contour les plus "anguleux", un par quadrant autour du centre
    hull_quad = cv2.approxPolyDP(cv2.convexHull(c.reshape(-1, 1, 2)), 0.02 * cv2.arcLength(c.reshape(-1, 1, 2), True), True)[:, 0, :]
    ctr = c.mean(0)

    def corner(sx, sy):
        cand = [p for p in hull_quad if np.sign(p[0] - ctr[0]) == sx and np.sign(p[1] - ctr[1]) == sy]
        tgt = max(cand, key=lambda p: np.hypot(*(p - ctr))) if cand else None
        if tgt is None:
            tgt = c[np.argmax(sx * c[:, 0] + sy * c[:, 1])]
        return int(np.argmin(np.linalg.norm(c - tgt, axis=1)))

    i_tl, i_tr, i_br, i_bl = corner(-1, -1), corner(1, -1), corner(1, 1), corner(-1, 1)

    def seg(a, b):  # chemin le plus court sur le contour entre a et b
        n = len(c)
        f = c[a:b + 1] if a <= b else np.vstack([c[a:], c[:b + 1]])
        g = (c[b:a + 1] if b <= a else np.vstack([c[b:], c[:a + 1]]))[::-1]
        return f if len(f) <= len(g) else g

    return dict(top=smooth(seg(i_tl, i_tr), 0), right=smooth(seg(i_tr, i_br), 1, 2), bottom=smooth(seg(i_bl, i_br), 0),
                left=smooth(seg(i_tl, i_bl), 1, 2), contour=c)


def smooth(poly, axis, deg=4):
    """Ajuste un polynôme sur un bord (robuste aux ombres / bords irréguliers)."""
    p = poly.astype(np.float64)
    u = p[:, axis]
    un = (u - u.min()) / max(np.ptp(u), 1)
    co = np.polyfit(un, p[:, 1 - axis], deg)
    uu = np.linspace(0, 1, 400)
    if un[0] > un[-1]:
        uu = uu[::-1]
    out = np.empty((400, 2))
    out[:, axis] = uu * np.ptp(u) + u.min()
    out[:, 1 - axis] = np.polyval(co, uu)
    return out


def dewarp(photo, e, ow=W, oh=H):
    top, bot = resample(e["top"], ow), resample(e["bottom"], ow)
    lef, rig = resample(e["left"], oh), resample(e["right"], oh)
    # raccord exact aux coins
    lef += (1 - np.linspace(0, 1, oh))[:, None] * (top[0] - lef[0]) + np.linspace(0, 1, oh)[:, None] * (bot[0] - lef[-1])
    rig += (1 - np.linspace(0, 1, oh))[:, None] * (top[-1] - rig[0]) + np.linspace(0, 1, oh)[:, None] * (bot[-1] - rig[-1])
    s = np.linspace(0, 1, ow)[None, :, None]
    t = np.linspace(0, 1, oh)[:, None, None]
    T, B, Lf, R = top[None], bot[None], lef[:, None], rig[:, None]
    bil = ((1 - s) * (1 - t) * top[0] + s * (1 - t) * top[-1] + (1 - s) * t * bot[0] + s * t * bot[-1])
    P = (1 - t) * T + t * B + (1 - s) * Lf + s * R - bil            # patch de Coons
    return cv2.remap(photo, P[..., 0].astype(np.float32), P[..., 1].astype(np.float32), cv2.INTER_CUBIC,
                     borderMode=cv2.BORDER_REPLICATE)


def refine(img):
    """Affinage interne : mesure l'ondulation résiduelle des lignes de texte et la compense.
    Renvoie l'image corrigée et l'ondulation moyenne (px) avant correction."""
    g = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    ink = cv2.adaptiveThreshold(g, 255, cv2.ADAPTIVE_THRESH_MEAN_C, cv2.THRESH_BINARY_INV, 31, 18)
    lines = cv2.morphologyEx(ink, cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_RECT, (41, 1)))
    lines = cv2.morphologyEx(lines, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_RECT, (9, 3)))
    n, lab, st, _ = cv2.connectedComponentsWithStats(lines)
    xs, ys, ds = [], [], []
    for i in range(1, n):
        x, y, w, h, a = st[i]
        if w < 220 or h > 46 or h < 6:
            continue
        m = lab[y:y + h, x:x + w] == i
        cnt = m.sum(0)
        ok = cnt > 0
        cy = (m * np.arange(h)[:, None]).sum(0) / np.maximum(cnt, 1) + y
        cx = np.arange(w) + x
        cx, cy = cx[ok], cy[ok]
        if len(cx) < 150:
            continue
        cy = np.polyval(np.polyfit(cx, cy, 3), cx)          # lisse hampes et jambages
        xs.append(cx[::6]); ys.append(cy[::6]); ds.append((cy - np.median(cy))[::6])
    if len(xs) < 4:
        return img, 0.0
    X, Y, D = np.concatenate(xs), np.concatenate(ys), np.concatenate(ds)
    h, w = g.shape
    xn, yn = X / w * 2 - 1, Y / h * 2 - 1
    terms = lambda a, b: np.stack([a ** i * b ** j for i in range(5) for j in range(3)], -1)
    A = terms(xn, yn)
    co = np.linalg.solve(A.T @ A + 1e-3 * np.eye(A.shape[1]), A.T @ D)
    gx, gy = np.meshgrid(np.arange(w, dtype=np.float32), np.arange(h, dtype=np.float32))
    P = (terms(gx / w * 2 - 1, gy / h * 2 - 1) @ co).astype(np.float32)
    P = np.clip(P, -40, 40)
    out = cv2.remap(img, gx, gy + P, cv2.INTER_CUBIC, borderMode=cv2.BORDER_REPLICATE)
    return out, float(np.mean(np.abs(D)))


def enhance(img):
    f = img.astype(np.float32)
    out = np.empty_like(f)
    k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (45, 45))
    for ch in range(3):                                              # fond d'éclairage par canal
        bg = cv2.morphologyEx(f[..., ch], cv2.MORPH_CLOSE, k)
        bg = cv2.GaussianBlur(cv2.medianBlur(bg.astype(np.uint8), 51).astype(np.float32), (0, 0), 25)
        out[..., ch] = f[..., ch] / np.maximum(bg, 1) * 250
    out = np.clip(out, 0, 255)
    den = cv2.fastNlMeansDenoisingColored(out.astype(np.uint8), None, 4, 6, 5, 15).astype(np.float32)
    x = den / 255.0
    x = np.clip((x - 0.06) / 0.90, 0, 1) ** 1.08                     # courbe de contraste
    den = x * 255
    blur = cv2.GaussianBlur(den, (0, 0), 1.6)
    return np.clip(den + 0.7 * (den - blur), 0, 255).astype(np.uint8)


def overlay(photo, e):
    o = photo.copy()
    acc = (74, 246, 215)  # BGR de #D7F64A
    dim = (o * 0.55).astype(np.uint8)
    m = np.zeros(o.shape[:2], np.uint8)
    cv2.fillPoly(m, [e["contour"].reshape(-1, 1, 2)], 255)
    o = np.where(m[..., None] > 0, o, dim)
    for k in ("top", "right", "bottom", "left"):
        cv2.polylines(o, [e[k].astype(np.int32).reshape(-1, 1, 2)], False, acc, 7, cv2.LINE_AA)
    for p in (e["top"][0], e["top"][-1], e["bottom"][0], e["bottom"][-1]):
        cv2.circle(o, tuple(int(v) for v in p), 26, (20, 17, 14), -1, cv2.LINE_AA)
        cv2.circle(o, tuple(int(v) for v in p), 26, acc, 6, cv2.LINE_AA)
    # points de contrôle de courbure
    for k in ("top", "bottom"):
        for p in resample(e[k], 7)[1:-1]:
            cv2.circle(o, tuple(int(v) for v in p), 11, acc, -1, cv2.LINE_AA)
    return o


def save(name, img, w):
    h = int(img.shape[0] * w / img.shape[1])
    cv2.imwrite(OUT + name, cv2.resize(img, (w, h), interpolation=cv2.INTER_AREA), [cv2.IMWRITE_JPEG_QUALITY, 84])


page = make_page()
photo = simulate(page)
edges = detect(photo)
flat = dewarp(photo, edges)
coons = enhance(flat)
final, wav0 = refine(coons)
_, wav1 = refine(final)
final2, _ = refine(final)          # seconde passe
_, wav2 = refine(final2)
print("ondulation moyenne des lignes (px @150dpi) : coons %.2f -> affinage %.2f -> 2e passe %.2f" % (wav0, wav1, wav2))
final = final2
cv2.imwrite(OUT + "cmp.jpg", np.hstack([coons[900:1500, 0:1240], final[900:1500, 0:1240]]))
# variante "simple perspective 4 coins" (ce que font les scanners basiques) pour comparaison
src = np.float32([edges["top"][0], edges["top"][-1], edges["bottom"][-1], edges["bottom"][0]])
persp = cv2.warpPerspective(photo, cv2.getPerspectiveTransform(src, np.float32([[0, 0], [W, 0], [W, H], [0, H]])), (W, H))
gray = cv2.cvtColor(final, cv2.COLOR_BGR2GRAY)
bw = cv2.adaptiveThreshold(gray, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY, 31, 14)


def err(a):  # erreur résiduelle vs page d'origine (niveaux de gris, flou léger)
    g = lambda i: cv2.GaussianBlur(cv2.cvtColor(i, cv2.COLOR_BGR2GRAY), (0, 0), 3).astype(np.float32)
    return float(np.mean(np.abs(g(a) - g(page))))


print("erreur moyenne  4 coins seuls :", round(err(enhance(persp)), 2))
print("erreur moyenne  dé-courbure   :", round(err(final), 2))
save("01_photo.jpg", photo, 720)
save("02_detection.jpg", overlay(photo, edges), 720)
save("03_perspective_seule.jpg", enhance(persp), 720)
save("04_corrige.jpg", final, 720)
save("05_nb.jpg", cv2.cvtColor(bw, cv2.COLOR_GRAY2BGR), 720)
save("thumb.jpg", final, 240)
# planche de contrôle
hh = 900
tiles = [cv2.resize(i, (int(i.shape[1] * hh / i.shape[0]), hh)) for i in (photo, overlay(photo, edges), enhance(persp), final)]
cv2.imwrite(OUT + "sheet.jpg", np.hstack(tiles), [cv2.IMWRITE_JPEG_QUALITY, 80])
