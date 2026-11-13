package app.feuillet.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Correspondance « page à plat » -> photo.
 *
 * Base : homographie exacte des quatre coins (perspective). Par-dessus, quand la correction de courbure
 * est active : décalage des bords courbes mélangé à l'intérieur (patch de Coons) et re-paramétrage par
 * longueur d'arc, pour dérouler la page au lieu de simplement l'étirer.
 */
class Mapping(
    outline: Outline,
    private val srcW: Int,
    private val srcH: Int,
    useCurves: Boolean,
    strength: Float = 1f,
    private val inset: Double = 0.004,
) {
    private val hom: Homography
    private val cv = DoubleArray(12)
    private val gTop: DoubleArray; private val gBot: DoubleArray
    private val gLeft: DoubleArray; private val gRight: DoubleArray

    /** Rapport largeur / hauteur estimé de la page à plat. */
    val aspect: Double

    /** Largeur et hauteur approximatives de la page dans la photo, en pixels source. */
    val sourceWidthPx: Double
    val sourceHeightPx: Double

    init {
        val q = DoubleArray(8) { outline.corners[it].toDouble() * (if (it % 2 == 0) srcW else srcH) }
        hom = Homography(q)
        if (useCurves) for (i in 0 until 12) cv[i] = outline.curves[i].toDouble() * strength
        val wq = (hypot(q[2] - q[0], q[3] - q[1]) + hypot(q[4] - q[6], q[5] - q[7])) / 2
        val hq = (hypot(q[6] - q[0], q[7] - q[1]) + hypot(q[4] - q[2], q[5] - q[3])) / 2
        val k = if (wq > 1) hq / wq else 1.0
        val lens = DoubleArray(4)
        gTop = arcTable(0, k, lens); gRight = arcTable(1, 1 / k, lens)
        gBot = arcTable(2, k, lens); gLeft = arcTable(3, 1 / k, lens)
        sourceWidthPx = wq * (lens[0] + lens[2]) / 2
        sourceHeightPx = hq * (lens[1] + lens[3]) / 2
        val arc = ((lens[0] + lens[2]) / 2) / ((lens[1] + lens[3]) / 2)
        val flat = perspectiveAspect(q, srcW / 2.0, srcH / 2.0) ?: (if (hq > 1) wq / hq else 1.0)
        aspect = (flat * arc).coerceIn(0.2, 5.0)
    }

    /**
     * Rapport largeur/hauteur réel d'un rectangle vu en perspective (Zhang et He, « Whiteboard scanning »),
     * en supposant des pixels carrés et un centre optique au milieu de l'image. Null si le calcul dégénère.
     */
    private fun perspectiveAspect(q: DoubleArray, cx: Double, cy: Double): Double? {
        val m1 = doubleArrayOf(q[0] - cx, q[1] - cy, 1.0); val m2 = doubleArrayOf(q[2] - cx, q[3] - cy, 1.0)
        val m3 = doubleArrayOf(q[6] - cx, q[7] - cy, 1.0); val m4 = doubleArrayOf(q[4] - cx, q[5] - cy, 1.0)
        fun cross(a: DoubleArray, b: DoubleArray) =
            doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
        fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        val d2 = dot(cross(m2, m4), m3); val d3 = dot(cross(m3, m4), m2)
        if (kotlin.math.abs(d2) < 1e-9 || kotlin.math.abs(d3) < 1e-9) return null
        val k2 = dot(cross(m1, m4), m3) / d2; val k3 = dot(cross(m1, m4), m2) / d3
        val n2 = DoubleArray(3) { k2 * m2[it] - m1[it] }; val n3 = DoubleArray(3) { k3 * m3[it] - m1[it] }
        val scale = hypot(q[4] - q[0], q[5] - q[1])
        val flatEnough = kotlin.math.abs(n2[2]) < 1e-4 || kotlin.math.abs(n3[2]) < 1e-4
        val ratio = if (flatEnough) {
            hypot(n2[0], n2[1]) / hypot(n3[0], n3[1])
        } else {
            val f2 = -(n2[0] * n3[0] + n2[1] * n3[1]) / (n2[2] * n3[2])
            // focale invraisemblable (hors 0,3 à 6 fois la diagonale de la page) : calcul rejeté
            if (!(f2 > 0) || sqrt(f2) < 0.3 * scale || sqrt(f2) > 6 * scale) return null
            sqrt(((n2[0] * n2[0] + n2[1] * n2[1]) / f2 + n2[2] * n2[2]) / ((n3[0] * n3[0] + n3[1] * n3[1]) / f2 + n3[2] * n3[2]))
        }
        return if (ratio.isFinite() && ratio > 0.05 && ratio < 20) ratio else null
    }

    private fun f(edge: Int, u: Double): Double {
        val o = edge * 3
        return u * (1 - u) * (cv[o] + cv[o + 1] * u + cv[o + 2] * u * u)
    }

    private fun df(edge: Int, u: Double): Double {
        val o = edge * 3
        val p = cv[o] + cv[o + 1] * u + cv[o + 2] * u * u
        return (1 - 2 * u) * p + u * (1 - u) * (cv[o + 1] + 2 * cv[o + 2] * u)
    }

    /** Table : fraction de longueur d'arc -> paramètre de corde, pour un bord. */
    private fun arcTable(edge: Int, k: Double, lens: DoubleArray): DoubleArray {
        val n = 128
        val cum = DoubleArray(n + 1)
        for (i in 1..n) {
            val u = (i - 0.5) / n
            val d = k * df(edge, u)
            cum[i] = cum[i - 1] + sqrt(1 + d * d) / n
        }
        lens[edge] = cum[n]
        val table = DoubleArray(TABLE + 1)
        var j = 0
        for (i in 0..TABLE) {
            val target = cum[n] * i / TABLE
            while (j < n - 1 && cum[j + 1] < target) j++
            val span = cum[j + 1] - cum[j]
            table[i] = (j + (if (span > 0) (target - cum[j]) / span else 0.0)) / n
        }
        table[0] = 0.0; table[TABLE] = 1.0
        return table
    }

    private fun lookup(t: DoubleArray, s: Double): Double {
        val x = min(max(s, 0.0), 1.0) * TABLE
        val i = min(TABLE - 1, x.toInt())
        return t[i] + (t[i + 1] - t[i]) * (x - i)
    }

    /** (s, t) dans la page à plat (0..1) -> coordonnées en pixels dans la photo. */
    fun map(s0: Double, t0: Double, out: DoubleArray) {
        val s = inset + s0 * (1 - 2 * inset)
        val t = inset + t0 * (1 - 2 * inset)
        val u0 = (1 - t) * lookup(gTop, s) + t * lookup(gBot, s)
        val v0 = (1 - s) * lookup(gLeft, t) + s * lookup(gRight, t)
        val u = u0 + (1 - u0) * f(3, v0) + u0 * f(1, v0)
        val v = v0 + (1 - v0) * f(0, u0) + v0 * f(2, u0)
        hom.map(u, v, out)
    }

    private companion object {
        const val TABLE = 64
    }
}

/**
 * Champ de déplacement vertical, en fraction de la hauteur de page : grille de nœuds interpolée bilinéairement.
 * Assez souple pour suivre un pli ou une bosse locale, pas seulement un cintrage d'ensemble.
 */
class Displacement(val nodes: DoubleArray) {
    init {
        require(nodes.size == NX * NY)
    }

    fun at(s: Double, t: Double): Double {
        val fx = min(max(s, 0.0), 1.0) * (NX - 1); val fy = min(max(t, 0.0), 1.0) * (NY - 1)
        val i = min(NX - 2, fx.toInt()); val j = min(NY - 2, fy.toInt())
        val ax = fx - i; val ay = fy - j
        val v = (nodes[j * NX + i] * (1 - ax) + nodes[j * NX + i + 1] * ax) * (1 - ay) +
            (nodes[(j + 1) * NX + i] * (1 - ax) + nodes[(j + 1) * NX + i + 1] * ax) * ay
        return min(max(v, -0.05), 0.05)
    }

    companion object {
        const val NX = 11
        const val NY = 15
    }
}

class DewarpResult(val image: Raster, val displacements: List<Displacement>, val residualPx: Double)

object Dewarper {

    /** Taille de sortie : conserve la résolution de la photo, plafonnée à [maxSide]. */
    fun outputSize(mapping: Mapping, aspect: Double, maxSide: Int = 3000): Pair<Int, Int> {
        val wPx = max(mapping.sourceWidthPx, mapping.sourceHeightPx * aspect)
        var w = wPx; var h = wPx / aspect
        val scale = min(1.0, maxSide / max(w, h))
        w *= scale; h *= scale
        return max(16, w.roundToInt()) to max(16, h.roundToInt())
    }

    /**
     * Rend la page à plat. [disp] : déplacements d'affinage, le plus récent en premier.
     * Rendu par maillage (pas de 16 px) puis interpolation bilinéaire dans la photo.
     */
    fun render(src: Raster, mapping: Mapping, outW: Int, outH: Int, disp: List<Displacement> = emptyList()): Raster {
        val g = 16
        val gw = (outW - 1) / g + 2; val gh = (outH - 1) / g + 2
        val mx = FloatArray(gw * gh); val my = FloatArray(gw * gh)
        val p = DoubleArray(2)
        for (j in 0 until gh) for (i in 0 until gw) {
            val s = (i * g).toDouble() / (outW - 1).coerceAtLeast(1)
            var t = (j * g).toDouble() / (outH - 1).coerceAtLeast(1)
            for (d in disp) t += d.at(s, t)
            mapping.map(s, t, p)
            mx[j * gw + i] = p[0].toFloat() - 0.5f; my[j * gw + i] = p[1].toFloat() - 0.5f
        }
        val out = IntArray(outW * outH)
        val sw = src.w; val sh = src.h; val sp = src.px
        for (y in 0 until outH) {
            val j = y / g; val fy = (y - j * g) / g.toFloat()
            for (i in 0 until gw - 1) {
                val x0 = i * g
                if (x0 >= outW) break
                val a = j * gw + i
                val lx = mx[a] + (mx[a + gw] - mx[a]) * fy; val ly = my[a] + (my[a + gw] - my[a]) * fy
                val rx = mx[a + 1] + (mx[a + 1 + gw] - mx[a + 1]) * fy; val ry = my[a + 1] + (my[a + 1 + gw] - my[a + 1]) * fy
                val dx = (rx - lx) / g; val dy = (ry - ly) / g
                var sx = lx; var sy = ly
                val xEnd = min(outW, x0 + g)
                for (x in x0 until xEnd) {
                    val cx = if (sx < 0f) 0f else if (sx > sw - 1.001f) sw - 1.001f else sx
                    val cy = if (sy < 0f) 0f else if (sy > sh - 1.001f) sh - 1.001f else sy
                    val ix = cx.toInt(); val iy = cy.toInt()
                    val ax = ((cx - ix) * 256).toInt(); val ay = ((cy - iy) * 256).toInt()
                    val o = iy * sw + ix
                    val o2 = if (iy + 1 < sh) o + sw else o
                    val ix1 = if (ix + 1 < sw) 1 else 0
                    val p00 = sp[o]; val p01 = sp[o + ix1]; val p10 = sp[o2]; val p11 = sp[o2 + ix1]
                    val w00 = (256 - ax) * (256 - ay); val w01 = ax * (256 - ay); val w10 = (256 - ax) * ay; val w11 = ax * ay
                    val r = (((p00 shr 16) and 255) * w00 + ((p01 shr 16) and 255) * w01 + ((p10 shr 16) and 255) * w10 + ((p11 shr 16) and 255) * w11) shr 16
                    val gr = (((p00 shr 8) and 255) * w00 + ((p01 shr 8) and 255) * w01 + ((p10 shr 8) and 255) * w10 + ((p11 shr 8) and 255) * w11) shr 16
                    val b = ((p00 and 255) * w00 + (p01 and 255) * w01 + (p10 and 255) * w10 + (p11 and 255) * w11) shr 16
                    out[y * outW + x] = (255 shl 24) or (r shl 16) or (gr shl 8) or b
                    sx += dx; sy += dy
                }
            }
        }
        return Raster(outW, outH, out)
    }

    /**
     * Chaîne complète : rendu, puis jusqu'à [passes] affinages sur les lignes de texte.
     * L'image finale est ré-échantillonnée une seule fois depuis la photo.
     */
    fun dewarp(src: Raster, mapping: Mapping, outW: Int, outH: Int, refine: Boolean, passes: Int = 3): DewarpResult {
        val disp = ArrayList<Displacement>()
        var residual = 0.0
        if (refine) {
            val lw = min(outW, 1000)
            val lh = max(16, (lw.toLong() * outH / outW).toInt())
            for (pass in 0 until passes) {
                val low = render(src, mapping, lw, lh, disp).luma()
                val (d, res) = estimateDisplacement(low)
                residual = res * outW / lw
                if (d == null || res < 0.3) break
                disp.add(0, d)
            }
            if (disp.isNotEmpty()) {
                val check = estimateDisplacement(render(src, mapping, lw, lh, disp).luma())
                residual = check.second * outW / lw
            }
        }
        return DewarpResult(render(src, mapping, outW, outH, disp), disp, residual)
    }

    /**
     * Mesure l'ondulation des lignes de texte dans une page déjà aplanie et renvoie le champ qui la compense,
     * avec l'ondulation moyenne mesurée (en pixels de [gray]). Renvoie null quand il y a trop peu de lignes.
     */
    fun estimateDisplacement(gray: Plane): Pair<Displacement?, Double> {
        val w = gray.w; val h = gray.h
        val mean = gray.boxBlur(max(4, w / 80))
        val ink = ByteArray(w * h)
        for (i in ink.indices) if (gray.d[i] < mean.d[i] * 0.86f - 4f) ink[i] = 1
        val rawInk = ink.copyOf()                       // encre avant fermeture : sert à repérer la ligne de base
        // fermeture horizontale : relie les lettres d'une même ligne
        val gap = max(6, w * 40 / 1240)
        for (y in 0 until h) {
            var last = -1
            for (x in 0 until w) {
                if (ink[y * w + x].toInt() != 0) {
                    if (last >= 0 && x - last in 2..gap) for (k in last + 1 until x) ink[y * w + k] = 1
                    last = x
                }
            }
        }
        val minW = (w * 0.18).toInt(); val maxH = max(8, (w * 0.05).toInt())
        val seen = ByteArray(w * h)
        val stack = IntArray(w * h)
        val sx = ArrayList<Double>(); val sy = ArrayList<Double>(); val sd = ArrayList<Double>()
        var lines = 0; var sumAbs = 0.0; var count = 0
        val colCnt = IntArray(w)
        for (start in 0 until w * h) {
            if (ink[start].toInt() == 0 || seen[start].toInt() != 0) continue
            var sp = 0; var n = 0
            var x0 = w; var x1 = -1; var y0 = h; var y1 = -1
            stack[sp++] = start; seen[start] = 1
            val pix = ArrayList<Int>()
            while (sp > 0) {
                val p = stack[--sp]; n++
                val x = p % w; val y = p / w
                if (pix.size < 200000) pix.add(p)
                if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
                if (x > 0 && ink[p - 1].toInt() != 0 && seen[p - 1].toInt() == 0) { seen[p - 1] = 1; stack[sp++] = p - 1 }
                if (x < w - 1 && ink[p + 1].toInt() != 0 && seen[p + 1].toInt() == 0) { seen[p + 1] = 1; stack[sp++] = p + 1 }
                if (y > 0 && ink[p - w].toInt() != 0 && seen[p - w].toInt() == 0) { seen[p - w] = 1; stack[sp++] = p - w }
                if (y < h - 1 && ink[p + w].toInt() != 0 && seen[p + w].toInt() == 0) { seen[p + w] = 1; stack[sp++] = p + w }
            }
            val bw = x1 - x0 + 1; val bh = y1 - y0 + 1
            if (bw < minW || bh > maxH || bh < 2 || n >= 200000) continue
            // Ligne de base : bas de l'encre de chaque colonne, puis médiane glissante. Les jambages (p, g, q…)
            // sont minoritaires dans la fenêtre : ils ne font plus onduler un mot comme « packaging ».
            val xsCol = ArrayList<Int>(); val raw = ArrayList<Double>()
            // seuls les pixels de cette ligne comptent (les lignes voisines, courbes, peuvent chevaucher sa boîte)
            for (x in x0..x1) colCnt[x] = -1
            for (p in pix) if (rawInk[p].toInt() != 0) { val x = p % w; val y = p / w; if (y > colCnt[x]) colCnt[x] = y }
            for (x in x0..x1) if (colCnt[x] >= 0) { xsCol.add(x); raw.add(colCnt[x].toDouble()) }
            if (xsCol.size < minW * 0.5) continue
            val half = max(12, w / 22)
            val medianed = DoubleArray(raw.size)
            var lo = 0; var hi = -1
            val window = ArrayList<Double>()
            for (i in raw.indices) {
                while (hi < raw.size - 1 && xsCol[hi + 1] - xsCol[i] <= half) hi++
                while (xsCol[i] - xsCol[lo] > half) lo++
                window.clear()
                for (k in lo..hi) window.add(raw[k])
                window.sort()
                medianed[i] = window[window.size / 2]
            }
            // léger lissage de la médiane (elle avance par paliers d'un pixel)
            val smooth = DoubleArray(raw.size)
            val sh = max(4, half / 3)
            for (i in raw.indices) {
                var acc = 0.0; var c = 0
                var k = i
                while (k >= 0 && xsCol[i] - xsCol[k] <= sh) { acc += medianed[k]; c++; k-- }
                k = i + 1
                while (k < raw.size && xsCol[k] - xsCol[i] <= sh) { acc += medianed[k]; c++; k++ }
                smooth[i] = acc / c
            }
            val med = smooth.sortedArray()[smooth.size / 2]
            lines++
            var i = 0
            while (i < smooth.size) {
                val dy = smooth[i] - med
                sx.add(xsCol[i].toDouble() / (w - 1)); sy.add(smooth[i] / (h - 1)); sd.add(dy / h)
                sumAbs += abs(dy); count++
                i += 4
            }
        }
        if (lines < 4 || count < 60) return null to 0.0
        // Moindres carrés sur la grille : fidélité aux mesures + lissage (dérivées secondes) + rappel vers zéro
        val nx = Displacement.NX; val ny = Displacement.NY; val n = nx * ny
        val ata = Array(n) { DoubleArray(n) }; val atb = DoubleArray(n)
        val idx = IntArray(4); val wt = DoubleArray(4)
        for (k in 0 until count) {
            val fx = sx[k].coerceIn(0.0, 1.0) * (nx - 1); val fy = sy[k].coerceIn(0.0, 1.0) * (ny - 1)
            val i = min(nx - 2, fx.toInt()); val j = min(ny - 2, fy.toInt())
            val ax = fx - i; val ay = fy - j
            idx[0] = j * nx + i; idx[1] = idx[0] + 1; idx[2] = idx[0] + nx; idx[3] = idx[2] + 1
            wt[0] = (1 - ax) * (1 - ay); wt[1] = ax * (1 - ay); wt[2] = (1 - ax) * ay; wt[3] = ax * ay
            for (p in 0 until 4) {
                atb[idx[p]] += wt[p] * sd[k]
                for (q in 0 until 4) ata[idx[p]][idx[q]] += wt[p] * wt[q]
            }
        }
        val lambda = 1.2 * count / n
        fun smoothTerm(a: Int, b: Int, c: Int) {
            val ids = intArrayOf(a, b, c); val cf = doubleArrayOf(1.0, -2.0, 1.0)
            for (p in 0 until 3) for (q in 0 until 3) ata[ids[p]][ids[q]] += lambda * cf[p] * cf[q]
        }
        for (j in 0 until ny) for (i in 1 until nx - 1) smoothTerm(j * nx + i - 1, j * nx + i, j * nx + i + 1)
        for (j in 1 until ny - 1) for (i in 0 until nx) smoothTerm((j - 1) * nx + i, j * nx + i, (j + 1) * nx + i)
        for (k in 0 until n) ata[k][k] += 0.02 * count / n
        val co = Lsq.solve(ata, atb) ?: return null to sumAbs / count
        return Displacement(co) to sumAbs / count
    }
}

/** Formats de sortie. Les dimensions sont en millimètres, côté court puis côté long. */
enum class PaperFormat(val label: String, val shortMm: Float, val longMm: Float) {
    AUTO("Auto", 0f, 0f),
    A4("A4", 210f, 297f),
    A5("A5", 148f, 210f),
    A3("A3", 297f, 420f),
    LETTER("Lettre", 215.9f, 279.4f),
    LEGAL("Legal", 215.9f, 355.6f),
    CARD("Carte", 53.98f, 85.6f);

    companion object {
        /**
         * Rapport largeur/hauteur à utiliser et dimensions physiques (mm) de la page.
         * En mode AUTO, accroche au format normalisé le plus proche parmi [autoCandidates] si l'écart est inférieur à 10 %.
         */
        fun resolve(
            format: PaperFormat,
            measuredAspect: Double,
            autoCandidates: List<PaperFormat> = listOf(A4, CARD),
        ): Triple<Double, Float, Float> {
            val portrait = measuredAspect <= 1.0
            var chosen = format
            if (format == AUTO) {
                val ratio = if (portrait) 1 / measuredAspect else measuredAspect
                var bestErr = 0.10
                for (f in autoCandidates) {
                    val err = abs(f.longMm / f.shortMm - ratio) / ratio
                    if (err < bestErr) { bestErr = err; chosen = f }
                }
                if (chosen == AUTO) {
                    // format libre : on ramène le côté court à 210 mm
                    val ratioLong = if (portrait) 1 / measuredAspect else measuredAspect
                    val wMm = if (portrait) 210f else (210f * ratioLong).toFloat()
                    val hMm = if (portrait) (210f * ratioLong).toFloat() else 210f
                    return Triple(measuredAspect, wMm, hMm)
                }
            }
            val wMm = if (portrait) chosen.shortMm else chosen.longMm
            val hMm = if (portrait) chosen.longMm else chosen.shortMm
            return Triple((wMm / hMm).toDouble(), wMm, hMm)
        }
    }
}
