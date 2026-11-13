package app.feuillet.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Contour d'une page dans une photo.
 * @param corners 8 valeurs normalisées (0..1) : haut-gauche, haut-droit, bas-droit, bas-gauche.
 * @param curves 12 coefficients (a, b, c pour les bords haut, droit, bas, gauche). Chaque bord s'écarte
 *   de la droite entre ses deux coins de f(u) = u(1-u)(a + b·u + c·u²), exprimé dans le carré unité redressé.
 * @param confidence 0..1 ; 0 quand le contour est un repli (cadre entier).
 */
class Outline(val corners: FloatArray, val curves: FloatArray = FloatArray(12), val confidence: Float = 0f) {
    init {
        require(corners.size == 8 && curves.size == 12)
    }

    fun copy(corners: FloatArray = this.corners.copyOf(), curves: FloatArray = this.curves.copyOf()) =
        Outline(corners, curves, confidence)

    /** Valeur de f(0,5) pour un bord (0 haut, 1 droit, 2 bas, 3 gauche). */
    fun midOffset(edge: Int): Float {
        val a = curves[edge * 3]; val b = curves[edge * 3 + 1]; val c = curves[edge * 3 + 2]
        return 0.25f * (a + 0.5f * b + 0.25f * c)
    }

    /** Règle le coefficient « a » pour que le milieu du bord s'écarte de [offset]. */
    fun withMidOffset(edge: Int, offset: Float): Outline {
        val cv = curves.copyOf()
        cv[edge * 3] = 4f * offset.coerceIn(-0.3f, 0.3f) - 0.5f * cv[edge * 3 + 1] - 0.25f * cv[edge * 3 + 2]
        return Outline(corners.copyOf(), cv, confidence)
    }

    companion object {
        fun fullFrame(inset: Float = 0.04f) = Outline(
            floatArrayOf(inset, inset, 1 - inset, inset, 1 - inset, 1 - inset, inset, 1 - inset)
        )
    }
}

/** Homographie du carré unité vers un quadrilatère (formule de Heckbert). */
class Homography(q: DoubleArray) {
    private val a: Double; private val b: Double; private val c: Double
    private val d: Double; private val e: Double; private val f: Double
    private val g: Double; private val h: Double
    private val inv = DoubleArray(9)

    init {
        val x0 = q[0]; val y0 = q[1]; val x1 = q[2]; val y1 = q[3]
        val x2 = q[4]; val y2 = q[5]; val x3 = q[6]; val y3 = q[7]
        val dx1 = x1 - x2; val dx2 = x3 - x2; val sx = x0 - x1 + x2 - x3
        val dy1 = y1 - y2; val dy2 = y3 - y2; val sy = y0 - y1 + y2 - y3
        var den = dx1 * dy2 - dx2 * dy1
        if (abs(den) < 1e-12) den = 1e-12
        g = (sx * dy2 - dx2 * sy) / den
        h = (dx1 * sy - sx * dy1) / den
        a = x1 - x0 + g * x1; b = x3 - x0 + h * x3; c = x0
        d = y1 - y0 + g * y1; e = y3 - y0 + h * y3; f = y0
        inv[0] = e - f * h; inv[1] = c * h - b; inv[2] = b * f - c * e
        inv[3] = f * g - d; inv[4] = a - c * g; inv[5] = c * d - a * f
        inv[6] = d * h - e * g; inv[7] = b * g - a * h; inv[8] = a * e - b * d
    }

    fun map(u: Double, v: Double, out: DoubleArray) {
        val wv = g * u + h * v + 1.0
        out[0] = (a * u + b * v + c) / wv
        out[1] = (d * u + e * v + f) / wv
    }

    fun unmap(x: Double, y: Double, out: DoubleArray) {
        val wv = inv[6] * x + inv[7] * y + inv[8]
        out[0] = (inv[0] * x + inv[1] * y + inv[2]) / wv
        out[1] = (inv[3] * x + inv[4] * y + inv[5]) / wv
    }
}

internal object Lsq {
    /** Résout A·x = b (élimination de Gauss avec pivot partiel). A est modifiée. */
    fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        for (col in 0 until n) {
            var piv = col
            for (r in col + 1 until n) if (abs(a[r][col]) > abs(a[piv][col])) piv = r
            if (abs(a[piv][col]) < 1e-12) return null
            val t = a[col]; a[col] = a[piv]; a[piv] = t
            val tb = b[col]; b[col] = b[piv]; b[piv] = tb
            for (r in col + 1 until n) {
                val f = a[r][col] / a[col][col]
                if (f == 0.0) continue
                for (k in col until n) a[r][k] -= f * a[col][k]
                b[r] -= f * b[col]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            var s = b[r]
            for (k in r + 1 until n) s -= a[r][k] * x[k]
            x[r] = s / a[r][r]
        }
        return x
    }

    /** Moindres carrés régularisés : rows = lignes de base, y = cibles, poids optionnels. */
    fun fit(rows: List<DoubleArray>, y: DoubleArray, ridge: Double, weights: DoubleArray? = null): DoubleArray? {
        if (rows.isEmpty()) return null
        val n = rows[0].size
        val ata = Array(n) { DoubleArray(n) }
        val atb = DoubleArray(n)
        for (i in rows.indices) {
            val r = rows[i]; val wt = weights?.get(i) ?: 1.0
            if (wt == 0.0) continue
            for (p in 0 until n) {
                val rp = r[p] * wt
                atb[p] += rp * y[i]
                for (q in p until n) ata[p][q] += rp * r[q]
            }
        }
        for (p in 0 until n) {
            for (q in 0 until p) ata[p][q] = ata[q][p]
            ata[p][p] += ridge
        }
        return solve(ata, atb)
    }
}

/** Détection automatique de la page : seuillage, plus grande région claire, quadrilatère, bords courbes. */
object Detector {

    /** Détecte la page dans une photo couleur (réduite en interne). */
    fun detect(img: Raster, analysisSide: Int = 640): Outline? {
        val small = img.downscale(analysisSide)
        val luma = small.luma()
        // variante « blancheur » : pénalise les fonds colorés (bois, nappe…)
        val white = Plane(small.w, small.h)
        for (i in white.d.indices) {
            val p = small.px[i]
            val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
            val chroma = max(r, max(g, b)) - min(r, min(g, b))
            white.d[i] = max(0f, luma.d[i] - 1.5f * chroma)
        }
        val a = detectPlane(luma)
        val b = detectPlane(white)
        return when {
            a == null -> b
            b == null -> a
            else -> if (b.confidence > a.confidence + 0.05f) b else a
        }
    }

    /** Détecte la page sur un plan de luminance déjà réduit (aperçu caméra en direct). */
    fun detectPlane(src: Plane): Outline? {
        val w = src.w; val h = src.h
        if (w < 32 || h < 32) return null
        val blurred = src.boxBlur(max(1, w / 200))
        val thr = blurred.otsu()
        val raw = Mask(w, h)
        for (i in raw.m.indices) if (blurred.d[i] > thr) raw.m[i] = 1
        val r = max(2, w / 90)
        val (mask, area) = raw.close(r).open(max(1, r / 2)).largestComponent()
        val frac = area.toFloat() / (w * h)
        if (frac < 0.08f || frac > 0.97f) return null
        val contour = mask.traceBoundary()
        val n = contour.size / 2
        if (n < 40) return null

        val hull = simplifyHull(convexHull(contour), 18)
        if (hull.size < 8) return null
        val quad = maxAreaQuad(hull) ?: return null
        val quadArea = abs(polyArea(quad))
        val ratio = quadArea / area
        if (ratio < 0.7 || ratio > 1.45) return null

        // indices des coins sur le contour, dans l'ordre du parcours (horaire)
        val idx = IntArray(4)
        for (k in 0 until 4) {
            var best = 0; var bd = Double.MAX_VALUE
            for (i in 0 until n) {
                val d = hypot(contour[2 * i] - quad[2 * k], contour[2 * i + 1] - quad[2 * k + 1])
                if (d < bd) { bd = d; best = i }
            }
            idx[k] = best
        }
        idx.sort()
        if (idx[0] == idx[1] || idx[1] == idx[2] || idx[2] == idx[3]) return null
        var first = 0; var bestSum = Int.MAX_VALUE
        for (k in 0 until 4) {
            val s = contour[2 * idx[k]] + contour[2 * idx[k] + 1]
            if (s < bestSum) { bestSum = s; first = k }
        }
        val ord = IntArray(4) { idx[(first + it) % 4] }           // HG, HD, BD, BG

        val q = DoubleArray(8)
        for (k in 0 until 4) { q[2 * k] = contour[2 * ord[k]] + 0.5; q[2 * k + 1] = contour[2 * ord[k] + 1] + 0.5 }
        refineCorners(contour, ord, q, hypot(w.toDouble(), h.toDouble()))
        val hom = Homography(q)
        val curves = FloatArray(12)
        val uv = DoubleArray(2)
        for (edge in 0 until 4) {
            val from = ord[edge]; val to = ord[(edge + 1) % 4]
            val len = ((to - from + n) % n)
            if (len < 6) continue
            val ts = DoubleArray(len + 1); val os = DoubleArray(len + 1)
            for (j in 0..len) {
                val i = (from + j) % n
                hom.unmap(contour[2 * i] + 0.5, contour[2 * i + 1] + 0.5, uv)
                when (edge) {
                    0 -> { ts[j] = uv[0]; os[j] = uv[1] }
                    1 -> { ts[j] = uv[1]; os[j] = uv[0] - 1.0 }
                    2 -> { ts[j] = uv[0]; os[j] = uv[1] - 1.0 }
                    else -> { ts[j] = uv[1]; os[j] = uv[0] }
                }
            }
            val co = fitEdge(ts, os) ?: continue
            for (k in 0 until 3) curves[edge * 3 + k] = co[k].toFloat()
        }
        val corners = FloatArray(8)
        for (k in 0 until 4) {
            corners[2 * k] = (q[2 * k] / w).toFloat().coerceIn(0f, 1f)
            corners[2 * k + 1] = (q[2 * k + 1] / h).toFloat().coerceIn(0f, 1f)
        }
        val conf = (1.0 - abs(1.0 - ratio)).toFloat().coerceIn(0.05f, 1f)
        return Outline(corners, curves, conf)
    }

    /**
     * Affine chaque coin : intersection des droites ajustées sur les deux bords voisins, près du coin.
     * Compense l'arrondi laissé par la morphologie et le pas de l'enveloppe simplifiée.
     */
    private fun refineCorners(contour: IntArray, ord: IntArray, q: DoubleArray, diag: Double) {
        val n = contour.size / 2
        val lines = Array(8) { DoubleArray(4) }          // par coin : droite « avant » et droite « après »
        val ok = BooleanArray(8)
        for (k in 0 until 4) {
            val prevLen = (ord[k] - ord[(k + 3) % 4] + n) % n
            val nextLen = (ord[(k + 1) % 4] - ord[k] + n) % n
            ok[2 * k] = fitLine(contour, n, ord[k] - (prevLen * 0.30).toInt(), ord[k] - (prevLen * 0.06).toInt(), lines[2 * k])
            ok[2 * k + 1] = fitLine(contour, n, ord[k] + (nextLen * 0.06).toInt(), ord[k] + (nextLen * 0.30).toInt(), lines[2 * k + 1])
        }
        for (k in 0 until 4) {
            if (!ok[2 * k] || !ok[2 * k + 1]) continue
            val a = lines[2 * k]; val b = lines[2 * k + 1]
            val den = a[2] * b[3] - a[3] * b[2]
            if (abs(den) < 0.2) continue                  // bords presque parallèles
            val t = ((b[0] - a[0]) * b[3] - (b[1] - a[1]) * b[2]) / den
            val x = a[0] + t * a[2]; val y = a[1] + t * a[3]
            if (hypot(x - q[2 * k], y - q[2 * k + 1]) < 0.04 * diag) { q[2 * k] = x; q[2 * k + 1] = y }
        }
    }

    /** Droite (point, direction unitaire) par moindres carrés totaux sur une portion du contour. */
    private fun fitLine(contour: IntArray, n: Int, from: Int, to: Int, out: DoubleArray): Boolean {
        val cnt = to - from + 1
        if (cnt < 5) return false
        var mx = 0.0; var my = 0.0
        for (j in from..to) { val i = ((j % n) + n) % n; mx += contour[2 * i] + 0.5; my += contour[2 * i + 1] + 0.5 }
        mx /= cnt; my /= cnt
        var sxx = 0.0; var sxy = 0.0; var syy = 0.0
        for (j in from..to) {
            val i = ((j % n) + n) % n
            val dx = contour[2 * i] + 0.5 - mx; val dy = contour[2 * i + 1] + 0.5 - my
            sxx += dx * dx; sxy += dx * dy; syy += dy * dy
        }
        val theta = 0.5 * kotlin.math.atan2(2 * sxy, sxx - syy)
        out[0] = mx; out[1] = my; out[2] = kotlin.math.cos(theta); out[3] = kotlin.math.sin(theta)
        return true
    }

    /** Ajuste o(t) = t(1-t)(a + b·t + c·t²), avec rejet des points aberrants (ombres, doigts). */
    private fun fitEdge(ts: DoubleArray, os: DoubleArray): DoubleArray? {
        val rows = ArrayList<DoubleArray>(); val ys = ArrayList<Double>()
        for (i in ts.indices) {
            val t = ts[i]
            if (t < 0.02 || t > 0.98) continue
            val k = t * (1 - t)
            rows.add(doubleArrayOf(k, k * t, k * t * t)); ys.add(os[i])
        }
        if (rows.size < 8) return null
        val y = ys.toDoubleArray()
        var co = Lsq.fit(rows, y, 1e-4) ?: return null
        repeat(2) {
            val res = DoubleArray(rows.size) { i -> abs(y[i] - (rows[i][0] * co[0] + rows[i][1] * co[1] + rows[i][2] * co[2])) }
            val sorted = res.sortedArray()
            val cut = max(0.004, 2.5 * sorted[sorted.size / 2])
            val wts = DoubleArray(rows.size) { i -> if (res[i] <= cut) 1.0 else 0.0 }
            co = Lsq.fit(rows, y, 1e-4, wts) ?: return co
        }
        // garde-fou : un bord ne s'écarte jamais de plus de 25 % de la page
        var worst = 0.0
        for (i in 0..20) {
            val t = i / 20.0
            worst = max(worst, abs(t * (1 - t) * (co[0] + co[1] * t + co[2] * t * t)))
        }
        if (worst > 0.25) for (k in 0 until 3) co[k] *= 0.25 / worst
        return co
    }

    /** Enveloppe convexe (chaîne monotone). Entrée et sortie : points entrelacés. */
    private fun convexHull(pts: IntArray): DoubleArray {
        val n = pts.size / 2
        val order = (0 until n).sortedWith(compareBy({ pts[2 * it] }, { pts[2 * it + 1] }))
        val hx = DoubleArray(2 * n + 2); val hy = DoubleArray(2 * n + 2)
        var k = 0
        fun cross(i: Int, x: Double, y: Double) =
            (hx[i - 1] - hx[i - 2]) * (y - hy[i - 2]) - (hy[i - 1] - hy[i - 2]) * (x - hx[i - 2])
        for (i in order) {
            val x = pts[2 * i].toDouble(); val y = pts[2 * i + 1].toDouble()
            while (k >= 2 && cross(k, x, y) <= 0) k--
            hx[k] = x; hy[k] = y; k++
        }
        val lower = k + 1
        for (j in n - 2 downTo 0) {
            val i = order[j]
            val x = pts[2 * i].toDouble(); val y = pts[2 * i + 1].toDouble()
            while (k >= lower && cross(k, x, y) <= 0) k--
            hx[k] = x; hy[k] = y; k++
        }
        k--
        val out = DoubleArray(2 * k)
        for (i in 0 until k) { out[2 * i] = hx[i]; out[2 * i + 1] = hy[i] }
        return out
    }

    /** Retire les sommets dont la suppression enlève le moins d'aire, jusqu'à [keep] sommets. */
    private fun simplifyHull(hull: DoubleArray, keep: Int): DoubleArray {
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
        for (i in 0 until hull.size / 2) { xs.add(hull[2 * i]); ys.add(hull[2 * i + 1]) }
        while (xs.size > keep) {
            var best = 0; var ba = Double.MAX_VALUE
            val m = xs.size
            for (i in 0 until m) {
                val p = (i + m - 1) % m; val q = (i + 1) % m
                val ar = abs((xs[i] - xs[p]) * (ys[q] - ys[p]) - (ys[i] - ys[p]) * (xs[q] - xs[p]))
                if (ar < ba) { ba = ar; best = i }
            }
            xs.removeAt(best); ys.removeAt(best)
        }
        val out = DoubleArray(2 * xs.size)
        for (i in xs.indices) { out[2 * i] = xs[i]; out[2 * i + 1] = ys[i] }
        return out
    }

    private fun polyArea(p: DoubleArray): Double {
        var s = 0.0
        val n = p.size / 2
        for (i in 0 until n) {
            val j = (i + 1) % n
            s += p[2 * i] * p[2 * j + 1] - p[2 * j] * p[2 * i + 1]
        }
        return s / 2
    }

    /** Quadrilatère d'aire maximale parmi les sommets de l'enveloppe. */
    private fun maxAreaQuad(hull: DoubleArray): DoubleArray? {
        val n = hull.size / 2
        if (n < 4) return null
        var best = -1.0
        val res = DoubleArray(8)
        val q = DoubleArray(8)
        for (i in 0 until n - 3) for (j in i + 1 until n - 2) for (k in j + 1 until n - 1) for (l in k + 1 until n) {
            q[0] = hull[2 * i]; q[1] = hull[2 * i + 1]; q[2] = hull[2 * j]; q[3] = hull[2 * j + 1]
            q[4] = hull[2 * k]; q[5] = hull[2 * k + 1]; q[6] = hull[2 * l]; q[7] = hull[2 * l + 1]
            val ar = abs(polyArea(q))
            if (ar > best) { best = ar; q.copyInto(res) }
        }
        return if (best > 0) res else null
    }
}
