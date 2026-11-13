package app.feuillet.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

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

    /**
     * Points d'un bord (0 haut, 1 droit, 2 bas, 3 gauche) en coordonnées normalisées, pour l'affichage.
     * @return n+1 points (x, y) entrelacés
     */
    fun edgePolyline(edge: Int, n: Int, useCurves: Boolean, strength: Float): FloatArray {
        val hom = Homography(DoubleArray(8) { corners[it].toDouble() })
        val out = FloatArray(2 * (n + 1)); val p = DoubleArray(2)
        val a = curves[edge * 3]; val b = curves[edge * 3 + 1]; val c = curves[edge * 3 + 2]
        for (i in 0..n) {
            val t = i.toDouble() / n
            val f = if (useCurves) strength * t * (1 - t) * (a + b * t + c * t * t) else 0.0
            when (edge) {
                0 -> hom.map(t, f, p)
                1 -> hom.map(1 + f, t, p)
                2 -> hom.map(t, 1 + f, p)
                else -> hom.map(f, t, p)
            }
            out[2 * i] = p[0].toFloat(); out[2 * i + 1] = p[1].toFloat()
        }
        return out
    }

    /**
     * Écart au bord droit (dans le carré redressé) d'un point de la photo, pour le réglage manuel
     * d'une poignée de courbure. Coordonnées normalisées.
     */
    fun offsetFromEdge(edge: Int, x: Float, y: Float): Float {
        val p = DoubleArray(2)
        Homography(DoubleArray(8) { corners[it].toDouble() }).unmap(x.toDouble(), y.toDouble(), p)
        return when (edge) { 0 -> p[1]; 1 -> p[0] - 1; 2 -> p[1] - 1; else -> p[0] }.toFloat()
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

/**
 * Détection automatique de la page.
 *
 * 1. Candidats : quadrilatères issus des droites dominantes de l'image (transformée de Hough signée)
 *    et des grandes régions claires ou sombres (seuillage d'Otsu).
 * 2. Ajustement : chaque côté est recalé sur le vrai bord par programmation dynamique (chemin lisse de
 *    contraste maximal), ce qui donne à la fois des coins précis et la courbure du bord.
 * 3. Vérification : un candidat n'est retenu que si ses côtés suivent un bord réel et séparent deux zones
 *    d'aspect différent. Une scène sans page (pénombre, texture, fouillis) ne donne donc aucun contour.
 */
object Detector {
    private const val SAMPLES = 40
    var DEBUG = false

    /** Détecte la page dans une photo. [analysisSide] : taille de travail ; les bords sont ensuite affinés à plus haute résolution. */
    fun detect(img: Raster, analysisSide: Int = 320): Outline? {
        val small = img.downscale(analysisSide)
        val found = detectIn(Scene(small), allowFrameSides = true) ?: return null
        // affinage sur une image deux à trois fois plus fine, autour du contour trouvé
        val fine = img.downscale(analysisSide * 3)
        if (fine.w <= small.w * 1.4) return found.outline
        val k = fine.w.toDouble() / small.w
        val scene = Scene(fine)
        val quad = DoubleArray(8) { found.quad[it] * k }
        val curve = DoubleArray(12) { found.curve[it] * k }
        val fit = fitQuad(scene, quad, curve, found.polarity, band = 3.0 * k.coerceAtMost(3.0), wide = false) ?: return found.outline
        return toOutline(scene, fit, found.confidence)
    }

    /** Détecte la page sur un plan de luminance déjà réduit (aperçu caméra en direct). La page doit tenir dans le cadre. */
    fun detectPlane(src: Plane): Outline? = detectIn(Scene(src, null, null), allowFrameSides = false)?.outline

    // ------------------------------------------------------------------------------------------------

    /** Plans d'analyse : luminance lissée, chrominances (si disponibles), gradients et seuil de contraste. */
    private class Scene(val l: Plane, val cb: Plane?, val cr: Plane?) {
        val w = l.w; val h = l.h
        val gx = FloatArray(w * h); val gy = FloatArray(w * h)
        val tau: Float
        val unit = max(w, h) / 320.0            // 1 pixel de l'échelle de référence

        constructor(img: Raster) : this(lumaOf(img), chroma(img, true), chroma(img, false))

        init {
            for (y in 1 until h - 1) for (x in 1 until w - 1) {
                gx[y * w + x] = l.d[y * w + x + 1] - l.d[y * w + x - 1]
                gy[y * w + x] = l.d[(y + 1) * w + x] - l.d[(y - 1) * w + x]
            }
            val sorted = l.d.copyOf(); sorted.sort()
            val range = sorted[(sorted.size * 0.95f).toInt()] - sorted[(sorted.size * 0.05f).toInt()]
            tau = max(6f, 0.08f * range)
        }

        fun at(p: Plane, x: Double, y: Double): Float {
            val fx = min(max(x, 0.0), w - 1.001); val fy = min(max(y, 0.0), h - 1.001)
            val x0 = fx.toInt(); val y0 = fy.toInt()
            val ax = (fx - x0).toFloat(); val ay = (fy - y0).toFloat()
            val o = y0 * w + x0
            return (p.d[o] * (1 - ax) + p.d[o + 1] * ax) * (1 - ay) + (p.d[o + w] * (1 - ax) + p.d[o + w + 1] * ax) * ay
        }

        companion object {
            fun lumaOf(img: Raster): Plane = img.luma().boxBlur(1)
            fun chroma(img: Raster, blue: Boolean): Plane {
                val p = Plane(img.w, img.h)
                for (i in p.d.indices) {
                    val c = img.px[i]
                    val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
                    p.d[i] = if (blue) (b - (77 * r + 150 * g + 29 * b) / 256f) else (r - (77 * r + 150 * g + 29 * b) / 256f)
                }
                return p.boxBlur(1)
            }
        }
    }

    private class Fit(val quad: DoubleArray, val curve: DoubleArray, val support: DoubleArray, val distinct: BooleanArray, val onFrame: BooleanArray, val paths: Array<DoubleArray>, val edge: Array<DoubleArray>, val strength: DoubleArray)
    private class Found(val outline: Outline, val quad: DoubleArray, val curve: DoubleArray, val polarity: IntArray, val confidence: Float)
    private class Verified(val fit: Fit, val polarity: IntArray, val conf: Double, val score: Double)

    private fun detectIn(s: Scene, allowFrameSides: Boolean): Found? {
        if (s.w < 48 || s.h < 48) return null
        val candidates = ArrayList<Pair<DoubleArray, Int>>()
        candidates.addAll(houghQuads(s))
        regionQuad(s.l, bright = true)?.let { candidates.add(it to 1) }
        regionQuad(s.l, bright = false)?.let { candidates.add(it to -1) }
        if (s.cb != null && s.cr != null) {
            // « blancheur » : pénalise les fonds colorés (bois, nappe…)
            val white = Plane(s.w, s.h)
            for (i in white.d.indices) white.d[i] = max(0f, s.l.d[i] - 1.5f * (abs(s.cb.d[i]) + abs(s.cr.d[i])))
            regionQuad(white, bright = true)?.let { candidates.add(it to 1) }
        }
        val area = (s.w * s.h).toDouble()
        fun attempt(quad: DoubleArray, polarity: IntArray): Verified? {
            var fit = fitQuad(s, quad, DoubleArray(12), polarity, band = 0.0, wide = true) ?: return null
            fit = fitQuad(s, fit.quad, fit.curve, polarity, band = 5.0 * s.unit, wide = false) ?: return null
            val conf = verify(s, fit, allowFrameSides)
            if (DEBUG) println("cand pol=${polarity.toList()} q0=${quad.map { it.toInt() }} -> q=${fit.quad.map { it.toInt() }} sup=${fit.support.map { "%.2f".format(it) }} dist=${fit.distinct.toList()} conf=$conf")
            if (conf == null) return null
            val vigor = (fit.strength.average() / (5 * s.tau)).coerceIn(0.4, 1.0)
            val framed = if (fit.onFrame.any { it }) 0.75 else 1.0
            return Verified(fit, polarity, conf, conf * vigor * framed * sqrt(abs(polyArea(fit.quad)) / area))
        }
        val all = candidates.mapNotNull { (q, p) -> attempt(q, IntArray(4) { p }) }.sortedByDescending { it.score }
        // une zone sombre entourée de clair est le plus souvent une figure imprimée : la page claire passe d'abord
        val brightOnes = all.filter { it.polarity[0] == 1 }
        val verified = brightOnes.ifEmpty { all }
        var best: Verified? = null
        for (start in verified.take(3)) {
            val grown = grow(s, start, ::attempt)
            if (best == null || grown.score > best.score) best = grown
        }
        val b = best ?: return null
        return Found(toOutline(s, b.fit, b.conf.toFloat()), b.fit.quad, b.fit.curve, b.polarity, b.conf.toFloat())
    }

    /**
     * Élargit un contour valide tant qu'un bord plus extérieur tient la vérification : un bandeau sombre,
     * un pli ou une photo imprimée ne doivent pas être pris pour le bord de la page.
     */
    private fun grow(s: Scene, start: Verified, attempt: (DoubleArray, IntArray) -> Verified?): Verified {
        var cur = start
        var rounds = 0
        while (rounds++ < 8) {
            var improved = false
            search@ for (i in 0 until 4) {
                val q = cur.fit.quad
                val px = q[2 * i]; val py = q[2 * i + 1]; val qx = q[(2 * i + 2) % 8]; val qy = q[(2 * i + 3) % 8]
                val len = hypot(qx - px, qy - py)
                val cx = (q[0] + q[2] + q[4] + q[6]) / 4; val cy = (q[1] + q[3] + q[5] + q[7]) / 4
                var nx = (qy - py) / len; var ny = -(qx - px) / len
                if (nx * (cx - px) + ny * (cy - py) > 0) { nx = -nx; ny = -ny }
                val across = hypot(q[(2 * i + 6) % 8] - px, q[(2 * i + 7) % 8] - py)
                val curArea = abs(polyArea(q))
                for (shift in doubleArrayOf(0.07, 0.14, 0.22, 0.32, 0.45)) for (sign in intArrayOf(cur.polarity[i], -cur.polarity[i])) {
                    val moved = q.copyOf()
                    val d = shift * across
                    moved[2 * i] += d * nx; moved[2 * i + 1] += d * ny
                    moved[(2 * i + 2) % 8] += d * nx; moved[(2 * i + 3) % 8] += d * ny
                    val pol = cur.polarity.copyOf(); pol[i] = sign
                    val next = attempt(moved, pol) ?: continue
                    if (next.fit.support[i] < 0.6 || !next.fit.distinct[i]) continue
                    // Les côtés voisins doivent se prolonger par un vrai bord dans la zone ajoutée. Si le nouveau bord
                    // est moins franc que l'ancien (ombre portée, veinage du support), ce prolongement doit être net.
                    val weaker = next.fit.strength[i] < 0.8 * cur.fit.strength[i]
                    val part = d / (across + d)
                    val nNew = max(2, (part * SAMPLES).toInt())
                    val ip = (i + 3) % 4; val inx = (i + 1) % 4
                    val needP = if (weaker) max(s.tau.toDouble(), 0.35 * next.fit.strength[ip]) else s.tau.toDouble()
                    val needN = if (weaker) max(s.tau.toDouble(), 0.35 * next.fit.strength[inx]) else s.tau.toDouble()
                    var hp = 0; var hn = 0
                    for (k in 0 until nNew) { if (next.fit.edge[ip][SAMPLES - 1 - k] >= needP) hp++; if (next.fit.edge[inx][k] >= needN) hn++ }
                    if (hp < 0.65 * nNew || hn < 0.65 * nNew) continue
                    if (abs(polyArea(next.fit.quad)) < curArea * 1.03 || next.conf < cur.conf - 0.12) continue
                    cur = next; improved = true
                    break@search
                }
            }
            if (!improved) {
                // tous les côtés à la fois : un cadre intérieur (photo, tableau) entouré de marges blanches
                val q = cur.fit.quad
                val cx = (q[0] + q[2] + q[4] + q[6]) / 4; val cy = (q[1] + q[3] + q[5] + q[7]) / 4
                val curArea = abs(polyArea(q))
                for (scale in doubleArrayOf(1.08, 1.16, 1.26, 1.4, 1.6)) {
                    val moved = DoubleArray(8) { if (it % 2 == 0) cx + (q[it] - cx) * scale else cy + (q[it] - cy) * scale }
                    for (sign in intArrayOf(1, -1)) {
                        val next = attempt(moved, IntArray(4) { sign }) ?: continue
                        if ((0 until 4).any { next.fit.support[it] < 0.7 || !next.fit.distinct[it] }) continue
                        if (abs(polyArea(next.fit.quad)) < curArea * 1.08) continue
                        cur = next; improved = true
                        break
                    }
                    if (improved) break
                }
            }
            if (!improved) break
        }
        return cur
    }

    /** Contrôle géométrique et photométrique d'un candidat ajusté. Renvoie la confiance (0..1) ou null s'il est rejeté. */
    private fun verify(s: Scene, f: Fit, allowFrameSides: Boolean): Double? {
        val q = f.quad
        val areaFrac = abs(polyArea(q)) / (s.w * s.h)
        if (areaFrac < 0.08) return null
        val len = DoubleArray(4) { hypot(q[(2 * it + 2) % 8] - q[2 * it], q[(2 * it + 3) % 8] - q[2 * it + 1]) }
        if (max(len[0], len[2]) > 3.2 * min(len[0], len[2]) || max(len[1], len[3]) > 3.2 * min(len[1], len[3])) return null
        for (k in 0 until 4) {
            // angle au coin k, entre le côté précédent et le côté suivant
            val ax = q[(2 * k + 2) % 8] - q[2 * k]; val ay = q[(2 * k + 3) % 8] - q[2 * k + 1]
            val bx = q[(2 * k + 6) % 8] - q[2 * k]; val by = q[(2 * k + 7) % 8] - q[2 * k + 1]
            val cos = (ax * bx + ay * by) / (hypot(ax, ay) * hypot(bx, by) + 1e-9)
            if (abs(cos) > 0.72) return null                       // coin plus aigu que 44° ou plus ouvert que 136°
            val margin = if (allowFrameSides) 0.06 else -0.004     // en direct : la page doit tenir dans le cadre
            if (q[2 * k] < -margin * s.w || q[2 * k] > (1 + margin) * s.w || q[2 * k + 1] < -margin * s.h || q[2 * k + 1] > (1 + margin) * s.h) return null
        }
        var good = 0; var sum = 0.0; var weakest = 1.0
        for (i in 0 until 4) {
            val framed = allowFrameSides && f.onFrame[i]
            val sup = if (framed) 0.6 else f.support[i]
            if (framed || (f.support[i] >= 0.5 && f.distinct[i])) good++
            sum += sup; weakest = min(weakest, sup)
        }
        if (allowFrameSides && f.onFrame.count { it } > 1) return null
        if (good < 3 || weakest < 0.25 || sum / 4 < 0.55) return null
        if (good == 3 && sum / 4 < 0.62) return null
        return (sum / 4).coerceIn(0.0, 1.0)
    }

    /**
     * Recale les quatre côtés d'un quadrilatère sur les bords de l'image.
     * @param curve écart courbe initial de chaque côté (a, b, c en pixels), nul au premier passage
     * @param band demi-largeur de recherche en pixels (ignorée si [wide] : la bande vaut alors 9 % de la page)
     */
    private fun fitQuad(s: Scene, quad: DoubleArray, curve: DoubleArray, polarity: IntArray, band: Double, wide: Boolean): Fit? {
        val cx = (quad[0] + quad[2] + quad[4] + quad[6]) / 4; val cy = (quad[1] + quad[3] + quad[5] + quad[7]) / 4
        val lines = Array(4) { DoubleArray(4) }                  // côté recalé : point de départ et point d'arrivée
        val newCurve = DoubleArray(12); val support = DoubleArray(4)
        val distinct = BooleanArray(4); val onFrame = BooleanArray(4)
        val paths = Array(4) { DoubleArray(2 * SAMPLES) }
        val edge = Array(4) { DoubleArray(SAMPLES) }
        val meanStrength = DoubleArray(4)
        val tau = s.tau.toDouble()
        for (i in 0 until 4) {
            val px = quad[2 * i]; val py = quad[2 * i + 1]
            val qx = quad[(2 * i + 2) % 8]; val qy = quad[(2 * i + 3) % 8]
            val len = hypot(qx - px, qy - py)
            if (len < 8) return null
            var nx = (qy - py) / len; var ny = -(qx - px) / len
            if (nx * (cx - px) + ny * (cy - py) > 0) { nx = -nx; ny = -ny }       // normale vers l'extérieur
            val across = hypot(quad[(2 * i + 6) % 8] - px, quad[(2 * i + 7) % 8] - py)
            val half = if (wide) min(40.0 * s.unit, max(4.0, 0.09 * across)) else band
            val m = (2 * half).toInt() + 1
            val e = 1.5 * s.unit
            // la page touche-t-elle le bord du cadre sur ce côté ?
            val edgeTol = 2.5 * s.unit
            onFrame[i] = (px < edgeTol && qx < edgeTol) || (px > s.w - 1 - edgeTol && qx > s.w - 1 - edgeTol) ||
                (py < edgeTol && qy < edgeTol) || (py > s.h - 1 - edgeTol && qy > s.h - 1 - edgeTol)

            // programmation dynamique : chemin lisse d'écarts qui maximise le contraste intérieur / extérieur
            val gain = Array(SAMPLES) { DoubleArray(m) }
            val raw = Array(SAMPLES) { DoubleArray(m) }
            val centers = DoubleArray(SAMPLES)
            val o = i * 3
            for (k in 0 until SAMPLES) {
                val t = 0.03 + 0.94 * k / (SAMPLES - 1)
                val c0 = t * (1 - t) * (curve[o] + curve[o + 1] * t + curve[o + 2] * t * t)
                centers[k] = c0
                val sx = px + t * (qx - px); val sy = py + t * (qy - py)
                for (j in 0 until m) {
                    val off = c0 - half + j
                    val inside = s.at(s.l, sx + (off - e) * nx, sy + (off - e) * ny)
                    val outside = s.at(s.l, sx + (off + e) * nx, sy + (off + e) * ny)
                    val d = polarity[i] * (inside - outside).toDouble()
                    raw[k][j] = d
                    gain[k][j] = min(d, 10 * tau) - (tau / 6) * abs(off - c0) / s.unit
                }
            }
            val acc = Array(SAMPLES) { DoubleArray(m) }; val from = Array(SAMPLES) { IntArray(m) }
            gain[0].copyInto(acc[0])
            val lambda = tau / 3
            for (k in 1 until SAMPLES) for (j in 0 until m) {
                var bestV = -1e18; var bestJ = j
                for (dj in -2..2) {
                    val jj = j + dj
                    if (jj < 0 || jj >= m) continue
                    val v = acc[k - 1][jj] - lambda * dj * dj
                    if (v > bestV) { bestV = v; bestJ = jj }
                }
                acc[k][j] = bestV + gain[k][j]; from[k][j] = bestJ
            }
            var j = 0
            for (jj in 1 until m) if (acc[SAMPLES - 1][jj] > acc[SAMPLES - 1][j]) j = jj
            val offs = DoubleArray(SAMPLES); val strength = DoubleArray(SAMPLES)
            for (k in SAMPLES - 1 downTo 0) {
                offs[k] = centers[k] - half + j; strength[k] = raw[k][j]
                j = from[k][j]
            }
            // modèle : droite + courbe nulle aux extrémités
            val rows = ArrayList<DoubleArray>(); val ys = ArrayList<Double>()
            for (k in 0 until SAMPLES) if (strength[k] >= tau) {
                val t = 0.03 + 0.94 * k / (SAMPLES - 1); val b = t * (1 - t)
                rows.add(doubleArrayOf(1.0, t, b, b * t, b * t * t)); ys.add(offs[k])
            }
            var co = DoubleArray(5)
            if (rows.size >= 10) {
                val y = ys.toDoubleArray()
                co = Lsq.fit(rows, y, 1e-3) ?: DoubleArray(5)
                // second ajustement sans les points aberrants
                val wts = DoubleArray(rows.size) { r -> if (abs(y[r] - dot(rows[r], co)) <= 2.0 * s.unit) 1.0 else 0.0 }
                if (wts.count { it > 0 } >= 10) co = Lsq.fit(rows, y, 1e-3, wts) ?: co
            }
            var ok = 0
            for (k in 0 until SAMPLES) {
                val t = 0.03 + 0.94 * k / (SAMPLES - 1); val b = t * (1 - t)
                val model = co[0] + co[1] * t + b * (co[2] + co[3] * t + co[4] * t * t)
                if (strength[k] >= tau && abs(offs[k] - model) <= 1.5 * s.unit) ok++
                paths[i][2 * k] = px + t * (qx - px) + model * nx; paths[i][2 * k + 1] = py + t * (qy - py) + model * ny
            }
            support[i] = if (rows.size >= 10) ok.toDouble() / SAMPLES else 0.0
            meanStrength[i] = strength.sumOf { max(0.0, min(it, 12 * tau)) } / SAMPLES
            // garde-fou : la courbe reste modérée par rapport à la taille de la page
            var worst = 0.0
            for (z in 1..9) { val t = z / 10.0; worst = max(worst, abs(t * (1 - t) * (co[2] + co[3] * t + co[4] * t * t))) }
            val limit = 0.11 * across
            if (worst > limit) { val r = limit / worst; co[2] *= r; co[3] *= r; co[4] *= r }
            newCurve[o] = co[2]; newCurve[o + 1] = co[3]; newCurve[o + 2] = co[4]
            lines[i][0] = px + co[0] * nx; lines[i][1] = py + co[0] * ny
            lines[i][2] = qx + (co[0] + co[1]) * nx; lines[i][3] = qy + (co[0] + co[1]) * ny
            distinct[i] = stripsDiffer(s, paths[i], nx, ny)
            // présence d'un bord le long du côté ajusté, quel que soit son sens (clair/sombre)
            for (k in 0 until SAMPLES) edge[i][k] = contrastAt(s, paths[i][2 * k], paths[i][2 * k + 1], nx, ny)
        }
        // Une page se cintre comme un cylindre : une seule paire de côtés opposés peut être nettement courbe.
        val bend = DoubleArray(4) { i -> val o = i * 3; abs(0.25 * (newCurve[o] + 0.5 * newCurve[o + 1] + 0.25 * newCurve[o + 2])) / hypot(quad[(2 * i + 6) % 8] - quad[2 * i], quad[(2 * i + 7) % 8] - quad[2 * i + 1]) }
        val flatPair = if (max(bend[0], bend[2]) >= max(bend[1], bend[3])) 1 else 0
        for (i in intArrayOf(flatPair, flatPair + 2)) if (bend[i] > 0.012) {
            // côté censé être droit mais trouvé courbe : il suit autre chose que le bord de la page
            support[i] *= 0.5
        }
        // nouveaux coins : intersections des côtés recalés
        val out = DoubleArray(8)
        for (k in 0 until 4) {
            val a = lines[(k + 3) % 4]; val b = lines[k]
            val p = intersect(a, b) ?: return null
            if (hypot(p[0] - quad[2 * k], p[1] - quad[2 * k + 1]) > 0.2 * hypot(s.w.toDouble(), s.h.toDouble())) return null
            out[2 * k] = p[0]; out[2 * k + 1] = p[1]
        }
        return Fit(out, newCurve, support, distinct, onFrame, paths, edge, meanStrength)
    }

    /** Contraste (luminance et couleur) de part et d'autre d'un point du contour. */
    private fun contrastAt(s: Scene, x: Double, y: Double, nx: Double, ny: Double): Double {
        val e = 2.5 * s.unit
        var d = abs(s.at(s.l, x - e * nx, y - e * ny) - s.at(s.l, x + e * nx, y + e * ny)).toDouble()
        if (s.cb != null && s.cr != null) {
            d += 0.8 * (abs(s.at(s.cb, x - e * nx, y - e * ny) - s.at(s.cb, x + e * nx, y + e * ny)) + abs(s.at(s.cr, x - e * nx, y - e * ny) - s.at(s.cr, x + e * nx, y + e * ny)))
        }
        return d
    }

    private fun dot(r: DoubleArray, c: DoubleArray): Double { var v = 0.0; for (i in r.indices) v += r[i] * c[i]; return v }

    /** Les bandes situées juste à l'intérieur et juste à l'extérieur d'un côté ont-elles un aspect différent ? */
    private fun stripsDiffer(s: Scene, path: DoubleArray, nx: Double, ny: Double): Boolean {
        val n = path.size / 2
        val inL = DoubleArray(4 * n); val outL = DoubleArray(4 * n)
        var inB = 0.0; var outB = 0.0; var inR = 0.0; var outR = 0.0
        var c = 0
        for (k in 0 until n) for (d in intArrayOf(4, 8, 12, 16)) {
            val dd = d * s.unit
            val ix = path[2 * k] - dd * nx; val iy = path[2 * k + 1] - dd * ny
            val ox = path[2 * k] + dd * nx; val oy = path[2 * k + 1] + dd * ny
            inL[c] = s.at(s.l, ix, iy).toDouble(); outL[c] = s.at(s.l, ox, oy).toDouble()
            if (s.cb != null && s.cr != null) {
                inB += s.at(s.cb, ix, iy); outB += s.at(s.cb, ox, oy); inR += s.at(s.cr, ix, iy); outR += s.at(s.cr, ox, oy)
            }
            c++
        }
        val mi = inL.average(); val mo = outL.average()
        val si = sqrt(inL.sumOf { (it - mi) * (it - mi) } / c); val so = sqrt(outL.sumOf { (it - mo) * (it - mo) } / c)
        val diff = abs(mi - mo) + 0.8 * (abs(inB - outB) + abs(inR - outR)) / c
        return diff >= max(1.5 * s.tau, 0.7 * (si + so))
    }

    private fun intersect(a: DoubleArray, b: DoubleArray): DoubleArray? {
        val dax = a[2] - a[0]; val day = a[3] - a[1]; val dbx = b[2] - b[0]; val dby = b[3] - b[1]
        val den = dax * dby - day * dbx
        if (abs(den) < 1e-6 * (hypot(dax, day) * hypot(dbx, dby) + 1e-9) || abs(den) < 1e-9) return null
        val t = ((b[0] - a[0]) * dby - (b[1] - a[1]) * dbx) / den
        return doubleArrayOf(a[0] + t * dax, a[1] + t * day)
    }

    /** Convertit un ajustement (pixels) en contour normalisé, courbes exprimées dans le carré redressé. */
    private fun toOutline(s: Scene, f: Fit, confidence: Float): Outline {
        val hom = Homography(f.quad)
        val curves = FloatArray(12); val uv = DoubleArray(2)
        for (i in 0 until 4) {
            val rows = ArrayList<DoubleArray>(); val ys = ArrayList<Double>()
            for (k in 0 until SAMPLES) {
                hom.unmap(f.paths[i][2 * k], f.paths[i][2 * k + 1], uv)
                val t: Double; val off: Double
                when (i) {
                    0 -> { t = uv[0]; off = uv[1] }
                    1 -> { t = uv[1]; off = uv[0] - 1 }
                    2 -> { t = uv[0]; off = uv[1] - 1 }
                    else -> { t = uv[1]; off = uv[0] }
                }
                if (t < 0.01 || t > 0.99) continue
                val b = t * (1 - t)
                rows.add(doubleArrayOf(b, b * t, b * t * t)); ys.add(off)
            }
            val co = Lsq.fit(rows, ys.toDoubleArray(), 1e-4) ?: continue
            // un bord quasi droit est déclaré droit : évite d'« onduler » une page plate
            var worst = 0.0
            for (z in 1..9) { val t = z / 10.0; worst = max(worst, abs(t * (1 - t) * (co[0] + co[1] * t + co[2] * t * t))) }
            if (worst < 0.004) continue
            for (k in 0 until 3) curves[i * 3 + k] = co[k].toFloat()
        }
        val corners = FloatArray(8) { (f.quad[it] / (if (it % 2 == 0) s.w else s.h)).toFloat().coerceIn(0f, 1f) }
        return Outline(corners, curves, confidence)
    }

    // ---- candidats par droites (transformée de Hough signée) ----

    private const val THETA = 120                       // 3° par case, sur 360° : le sens du gradient est conservé

    private fun houghQuads(s: Scene): List<Pair<DoubleArray, Int>> {
        val w = s.w; val h = s.h
        val diag = hypot(w.toDouble(), h.toDouble())
        val rb = (2 * diag).toInt() + 3
        val acc = IntArray(THETA * rb)
        val tg = s.tau * 0.9f
        val cosT = DoubleArray(THETA) { Math.cos(it * 2 * Math.PI / THETA) }; val sinT = DoubleArray(THETA) { Math.sin(it * 2 * Math.PI / THETA) }
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val gx = s.gx[y * w + x]; val gy = s.gy[y * w + x]
            if (gx * gx + gy * gy < tg * tg) continue
            val ang = Math.atan2(gy.toDouble(), gx.toDouble())
            val bin = Math.floorMod(Math.round(ang / (2 * Math.PI) * THETA).toInt(), THETA)
            for (db in -1..1) {
                val b = Math.floorMod(bin + db, THETA)
                val rho = x * cosT[b] + y * sinT[b]
                acc[b * rb + (rho + diag).toInt() + 1]++
            }
        }
        // pics : les 16 droites les plus soutenues, sans doublons proches
        val lines = ArrayList<DoubleArray>()              // nx, ny, rho
        val minVotes = (0.12 * min(w, h)).toInt()
        repeat(16) {
            var bi = -1; var bv = minVotes
            for (i in acc.indices) if (acc[i] > bv) { bv = acc[i]; bi = i }
            if (bi < 0) return@repeat
            val b = bi / rb; val r = bi % rb
            lines.add(doubleArrayOf(cosT[b], sinT[b], r - 1 - diag + 0.5))
            val dr = (0.03 * diag).toInt() + 2
            for (db in -3..3) {
                val bb = Math.floorMod(b + db, THETA)
                for (rr in max(0, r - dr)..min(rb - 1, r + dr)) acc[bb * rb + rr] = 0
            }
        }
        if (lines.size < 4) return emptyList()
        val order = lines.indices.sortedBy { Math.atan2(lines[it][1], lines[it][0]) }
        val sorted = order.map { lines[it] }
        val n = sorted.size
        val results = ArrayList<Triple<Double, DoubleArray, Int>>()
        val q = DoubleArray(8)
        for (a in 0 until n - 3) for (b in a + 1 until n - 2) for (c in b + 1 until n - 1) for (d in c + 1 until n) {
            val ls = arrayOf(sorted[a], sorted[b], sorted[c], sorted[d])
            var bad = false
            for (i in 0 until 4) {
                val u = ls[i]; val v = ls[(i + 1) % 4]
                val cosAng = u[0] * v[0] + u[1] * v[1]
                if (abs(cosAng) > 0.75) { bad = true; break }            // côtés voisins trop parallèles
                val den = u[0] * v[1] - u[1] * v[0]
                q[2 * i] = (u[2] * v[1] - u[1] * v[2]) / den; q[2 * i + 1] = (u[0] * v[2] - u[2] * v[0]) / den
                if (q[2 * i] < -0.06 * w || q[2 * i] > 1.06 * w || q[2 * i + 1] < -0.06 * h || q[2 * i + 1] > 1.06 * h) { bad = true; break }
            }
            if (bad) continue
            val area = abs(polyArea(q))
            if (area < 0.08 * w * h) continue
            val cx = (q[0] + q[2] + q[4] + q[6]) / 4; val cy = (q[1] + q[3] + q[5] + q[7]) / 4
            var inward = 0
            for (i in 0 until 4) if (ls[i][0] * cx + ls[i][1] * cy - ls[i][2] > 0) inward++
            val polarity = if (inward == 4) 1 else if (inward == 0) -1 else continue
            // soutien : part de chaque côté réellement posée sur un bord de même sens
            var total = 0.0; var weakest = 1.0
            for (i in 0 until 4) {
                // le côté porté par la droite i va du coin i-1 au coin i
                val x0 = q[(2 * i + 6) % 8]; val y0 = q[(2 * i + 7) % 8]; val x1 = q[2 * i]; val y1 = q[2 * i + 1]
                val steps = max(8, (hypot(x1 - x0, y1 - y0) / 3).toInt())
                var hit = 0
                for (k in 1 until steps) {
                    val x = x0 + (x1 - x0) * k / steps; val y = y0 + (y1 - y0) * k / steps
                    var m = 0f
                    for (off in -2..2) {
                        val xi = (x + off * ls[i][0]).toInt(); val yi = (y + off * ls[i][1]).toInt()
                        if (xi < 1 || yi < 1 || xi >= w - 1 || yi >= h - 1) continue
                        val g = (s.gx[yi * w + xi] * ls[i][0] + s.gy[yi * w + xi] * ls[i][1]).toFloat()
                        if (g > m) m = g
                    }
                    if (m >= tg) hit++
                }
                val sup = hit.toDouble() / (steps - 1)
                total += sup; weakest = min(weakest, sup)
            }
            if (total / 4 < 0.4 || weakest < 0.15) continue
            results.add(Triple(total / 4 * sqrt(area / (w * h)), orderQuad(q), polarity))
        }
        return results.sortedByDescending { it.first }.take(6).map { it.second to it.third }
    }

    /** Remet quatre coins dans l'ordre haut-gauche, haut-droit, bas-droit, bas-gauche. */
    private fun orderQuad(q: DoubleArray): DoubleArray {
        val pts = Array(4) { doubleArrayOf(q[2 * it], q[2 * it + 1]) }
        val list = if (polyArea(q) >= 0) pts.toList() else pts.reversed()
        var first = 0
        for (k in 1 until 4) if (list[k][0] + list[k][1] < list[first][0] + list[first][1]) first = k
        return DoubleArray(8) { list[(first + it / 2) % 4][it % 2] }
    }

    // ---- candidats par régions ----

    /** Plus grande région claire (ou sombre) : quadrilatère d'aire maximale de son enveloppe convexe. */
    private fun regionQuad(src: Plane, bright: Boolean): DoubleArray? {
        val w = src.w; val h = src.h
        val blurred = src.boxBlur(max(1, w / 200))
        val thr = blurred.otsu()
        val raw = Mask(w, h)
        for (i in raw.m.indices) if ((blurred.d[i] > thr) == bright) raw.m[i] = 1
        val r = max(2, w / 90)
        val (mask, area) = raw.close(r).open(max(1, r / 2)).largestComponent()
        val frac = area.toFloat() / (w * h)
        if (frac < 0.08f || frac > 0.97f) return null
        val contour = mask.traceBoundary()
        if (contour.size < 80) return null
        val hull = simplifyHull(convexHull(contour), 18)
        if (hull.size < 8) return null
        val quad = maxAreaQuad(hull) ?: return null
        val ratio = abs(polyArea(quad)) / area
        if (ratio < 0.6 || ratio > 1.6) return null
        return orderQuad(quad)
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
