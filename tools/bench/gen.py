"""Banc d'essai de détection : scènes simulées (page sur fonds variés) + scènes sans page, avec vérité terrain."""
import os, sys, numpy as np, cv2
from scipy.interpolate import griddata
src = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'page_demo.py')).read().split("# ---------- 2. Simulation")[0]
exec(src)
import skimage.data as sd
rng = np.random.default_rng(11)
SD = os.path.dirname(sd.__file__)
W2, H2 = 900, 1200
TEX = {n: cv2.imread(os.path.join(SD, f)) for n, f in [("brique", "brick.png"), ("gravier", "gravel.png"), ("herbe", "grass.png"),
       ("cafe", "coffee.png"), ("fouillis", "astronaut.png"), ("moto", "motorcycle_left.png")]}
base_page = make_page()

def page_variant(k):
    p = base_page.copy()
    if k == 1:   # page presque vide
        p[:] = (250, 250, 248); cv2.putText(p, "Note", (120, 220), cv2.FONT_HERSHEY_SIMPLEX, 2.2, (40, 40, 40), 4)
        for i in range(5): cv2.line(p, (120, 330 + i * 70), (int(1100 - 120 * rng.random()), 330 + i * 70), (70, 70, 70), 3)
    elif k == 2: # grande photo
        ph = cv2.resize(TEX["fouillis"], (1000, 760)); p[620:1380, 120:1120] = ph
    elif k == 3: # papier jaune
        p = (p.astype(np.float32) * np.array([0.78, 0.95, 1.0])).astype(np.uint8)
    elif k == 4: # bandeau sombre en tête
        p[0:230] = (60, 40, 30)
    return p

def background(kind):
    X, Y = np.meshgrid(np.arange(W2), np.arange(H2))
    if kind in ("bois_sombre", "bois_clair"):
        g = cv2.GaussianBlur(rng.normal(0, 1, (H2, W2)).astype(np.float32), (0, 0), sigmaX=50, sigmaY=2); g /= np.abs(g).max()
        base = np.array([38, 52, 74], np.float32) if kind == "bois_sombre" else np.array([120, 160, 195], np.float32)
        b = base * (1 + 0.4 * g)[..., None]
    elif kind == "blanc":
        b = np.full((H2, W2, 3), rng.uniform(205, 235), np.float32) + rng.normal(0, 2, (H2, W2, 1))
    elif kind == "gris":
        b = np.full((H2, W2, 3), rng.uniform(70, 140), np.float32) + rng.normal(0, 3, (H2, W2, 1))
    elif kind == "tissu":
        c = np.array([rng.uniform(30, 160), rng.uniform(30, 120), rng.uniform(30, 160)], np.float32)
        b = c * (1 + 0.08 * np.sin(X / 3.0) * np.sin(Y / 3.0))[..., None]
    elif kind == "noir":
        b = np.full((H2, W2, 3), rng.uniform(8, 30), np.float32)
    else:
        t = TEX[kind]; s = max(W2 / t.shape[1], H2 / t.shape[0]) * rng.uniform(1.0, 1.6)
        t = cv2.resize(t, None, fx=s, fy=s); y0 = int(rng.integers(0, t.shape[0] - H2 + 1)); x0 = int(rng.integers(0, t.shape[1] - W2 + 1))
        b = t[y0:y0 + H2, x0:x0 + W2].astype(np.float32)
    b *= (0.75 + 0.4 * (X / W2) * rng.uniform(0, 1) + 0.25 * (Y / H2) * rng.uniform(0, 1))[..., None]
    return b

def scene(kind, page, curved, landscape=False):
    gu, gv = np.meshgrid(np.linspace(0, 1, 60), np.linspace(0, 1, 80))
    amp = rng.uniform(0.03, 0.085) if curved else 0.0
    z = np.sin(np.pi * np.clip(gu * 0.85 + 0.05, 0, 1)) ** 1.3
    x = gu + (0.04 * np.sin(np.pi * gu) if curved else 0); y = gv - amp * z * (1.25 - 0.75 * gv)
    while True:                                      # la page entière doit tenir dans le cadre
        sc = rng.uniform(0.45, 0.8) * W2; ang = np.radians(rng.uniform(-22, 22))
        px = (x - 0.5) * sc; py = (y - 0.5) * sc * 1.414
        if landscape: px, py = py * 0.75, -px * 0.75
        rx = px * np.cos(ang) - py * np.sin(ang); ry = px * np.sin(ang) + py * np.cos(ang)
        f = rng.uniform(1100, 2500); tilt = np.radians(rng.uniform(-22, 22)); tilt2 = np.radians(rng.uniform(-12, 12))
        zz = f + ry * np.sin(tilt) + rx * np.sin(tilt2)
        qx = W2 / 2 + rng.uniform(-40, 40) + f * rx / zz; qy = H2 / 2 + rng.uniform(-60, 60) + f * ry * np.cos(tilt) / zz
        if qx.min() > 0.025 * W2 and qx.max() < 0.975 * W2 and qy.min() > 0.025 * H2 and qy.max() < 0.975 * H2: break
    q = np.stack([qx, qy], -1)
    corners = [q[0, 0], q[0, -1], q[-1, -1], q[-1, 0]]
    sx, sy = np.meshgrid(np.arange(0, W2, 4), np.arange(0, H2, 4))
    mu = griddata(q.reshape(-1, 2), gu.ravel(), (sx, sy), method="linear"); mv = griddata(q.reshape(-1, 2), gv.ravel(), (sx, sy), method="linear")
    mz = griddata(q.reshape(-1, 2), z.ravel(), (sx, sy), method="linear")
    mask = cv2.resize((~np.isnan(mu)).astype(np.float32), (W2, H2), interpolation=cv2.INTER_LINEAR)
    mapx = cv2.resize(np.nan_to_num(mu).astype(np.float32), (W2, H2)) * (W - 1); mapy = cv2.resize(np.nan_to_num(mv).astype(np.float32), (W2, H2)) * (H - 1)
    warped = cv2.remap(page, mapx, mapy, cv2.INTER_AREA).astype(np.float32)
    slope = cv2.resize(np.nan_to_num(np.gradient(np.nan_to_num(mz), axis=1)).astype(np.float32), (W2, H2)) * 100 * (amp / 0.085 if curved else 0)
    shade = cv2.GaussianBlur(np.clip(0.93 + 0.6 * slope, 0.8, 1.03), (0, 0), 9)
    X, Y = np.meshgrid(np.arange(W2), np.arange(H2))
    light = rng.uniform(0.62, 0.95) + rng.uniform(0, 0.25) * (X / W2)
    warped *= (shade * light)[..., None] * np.array([rng.uniform(0.85, 1), rng.uniform(0.93, 1), 1.0])
    bg = background(kind)
    m = (mask > 0.5).astype(np.float32)
    sh = cv2.GaussianBlur(np.roll(np.roll(m, int(rng.integers(6, 22)), 0), int(rng.integers(-16, 16)), 1), (0, 0), rng.uniform(8, 20))
    bg *= (1 - rng.uniform(0.25, 0.6) * sh)[..., None]
    a = cv2.GaussianBlur(m, (0, 0), 1.0)[..., None]
    out = bg * (1 - a) + warped * a
    out = cv2.GaussianBlur(out, (0, 0), rng.uniform(0.6, 1.4)) + rng.normal(0, rng.uniform(2, 6), out.shape)
    return np.clip(out, 0, 255).astype(np.uint8), corners

def negative(kind):
    if kind == "halo":   # scène sombre avec une lueur diffuse, comme un bureau dans la pénombre
        X, Y = np.meshgrid(np.arange(W2), np.arange(H2))
        cx, cy = rng.uniform(0.3, 1.1) * W2, rng.uniform(0.3, 1.0) * H2
        g = np.exp(-(((X - cx) / (W2 * rng.uniform(0.3, 0.6))) ** 2 + ((Y - cy) / (H2 * rng.uniform(0.3, 0.6))) ** 2))
        b = (rng.uniform(6, 14) + rng.uniform(15, 45) * g)[..., None] * np.array([0.7, 0.85, 1.0])
    else:
        b = background(kind)
    b = cv2.GaussianBlur(b.astype(np.float32), (0, 0), 1.0) + rng.normal(0, rng.uniform(2, 7), b.shape)
    return np.clip(b, 0, 255).astype(np.uint8)

kinds = ["bois_sombre", "bois_clair", "blanc", "gris", "tissu", "noir", "brique", "gravier", "herbe", "cafe", "fouillis", "moto"]
rows = []
n = int(sys.argv[1]) if len(sys.argv) > 1 else 20
os.makedirs("img", exist_ok=True)
for kind in kinds:
    for i in range(n):
        img, c = scene(kind, page_variant(0 if (kind == 'noir' and i % 5 == 4) else i % 5), curved=(i % 3 == 0), landscape=(i % 7 == 6))
        # ordre HG, HD, BD, BG à l'écran
        pts = np.array(c); order = np.argsort(np.arctan2(pts[:, 1] - pts[:, 1].mean(), pts[:, 0] - pts[:, 0].mean()))
        pts = pts[order]; s = int(np.argmin(pts.sum(1))); pts = np.roll(pts, -s, axis=0)
        name = f"pos_{kind}_{i:02d}.jpg"; cv2.imwrite("img/" + name, img, [cv2.IMWRITE_JPEG_QUALITY, 88])
        rows.append(f"{name},{kind},1," + ",".join(f"{v:.1f}" for v in pts.ravel()))
for kind in kinds + ["halo", "halo", "halo"]:
    for i in range(max(4, n // 3)):
        name = f"neg_{kind}_{len(rows)}.jpg"; cv2.imwrite("img/" + name, negative(kind), [cv2.IMWRITE_JPEG_QUALITY, 88])
        rows.append(f"{name},{kind},0,0,0,0,0,0,0,0,0")
open("gt.csv", "w").write("\n".join(rows) + "\n")
print(len(rows), "scènes")
