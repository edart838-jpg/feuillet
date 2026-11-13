package app.feuillet.core

import kotlin.math.max
import kotlin.math.min

/** Image couleur : pixels ARGB (même disposition que Bitmap.getPixels sur Android). */
class Raster(val w: Int, val h: Int, val px: IntArray = IntArray(w * h)) {
    init {
        require(w > 0 && h > 0 && px.size == w * h) { "dimensions invalides" }
    }

    /** Réduction par moyenne de blocs entiers, pour que le grand côté soit <= maxSide. */
    fun downscale(maxSide: Int): Raster {
        val f = max(1, (max(w, h) + maxSide - 1) / maxSide)
        if (f == 1) return this
        val ow = max(1, w / f)
        val oh = max(1, h / f)
        val out = IntArray(ow * oh)
        val n = f * f
        for (oy in 0 until oh) {
            for (ox in 0 until ow) {
                var r = 0; var g = 0; var b = 0
                for (dy in 0 until f) {
                    var i = (oy * f + dy) * w + ox * f
                    for (dx in 0 until f) {
                        val p = px[i++]
                        r += (p shr 16) and 255; g += (p shr 8) and 255; b += p and 255
                    }
                }
                out[oy * ow + ox] = (255 shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
        return Raster(ow, oh, out)
    }

    /** Luminance 0..255. */
    fun luma(): Plane {
        val d = FloatArray(w * h)
        for (i in d.indices) {
            val p = px[i]
            d[i] = (77 * ((p shr 16) and 255) + 150 * ((p shr 8) and 255) + 29 * (p and 255)) / 256f
        }
        return Plane(w, h, d)
    }

    /** Rotation par quarts de tour dans le sens horaire. */
    fun rotated(quarterTurns: Int): Raster {
        val q = ((quarterTurns % 4) + 4) % 4
        if (q == 0) return this
        val ow = if (q == 2) w else h
        val oh = if (q == 2) h else w
        val out = IntArray(ow * oh)
        for (y in 0 until h) for (x in 0 until w) {
            val (nx, ny) = when (q) {
                1 -> (h - 1 - y) to x
                2 -> (w - 1 - x) to (h - 1 - y)
                else -> y to (w - 1 - x)
            }
            out[ny * ow + nx] = px[y * w + x]
        }
        return Raster(ow, oh, out)
    }
}

/** Plan mono-canal en flottants (0..255 en général). */
class Plane(val w: Int, val h: Int, val d: FloatArray = FloatArray(w * h)) {

    /** Flou « boîte » séparable de rayon r (bords répétés). */
    fun boxBlur(r: Int): Plane {
        if (r <= 0) return this
        val tmp = FloatArray(w * h)
        val out = FloatArray(w * h)
        val win = 2 * r + 1
        for (y in 0 until h) {
            val o = y * w
            var s = 0f
            for (k in -r..r) s += d[o + min(w - 1, max(0, k))]
            for (x in 0 until w) {
                tmp[o + x] = s / win
                s += d[o + min(w - 1, x + r + 1)] - d[o + max(0, x - r)]
            }
        }
        for (x in 0 until w) {
            var s = 0f
            for (k in -r..r) s += tmp[min(h - 1, max(0, k)) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = s / win
                s += tmp[min(h - 1, y + r + 1) * w + x] - tmp[max(0, y - r) * w + x]
            }
        }
        return Plane(w, h, out)
    }

    private fun extremum(r: Int, isMax: Boolean): Plane {
        val tmp = FloatArray(w * h)
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var m = d[y * w + x]
            for (k in max(0, x - r)..min(w - 1, x + r)) {
                val v = d[y * w + k]
                if (if (isMax) v > m else v < m) m = v
            }
            tmp[y * w + x] = m
        }
        for (y in 0 until h) for (x in 0 until w) {
            var m = tmp[y * w + x]
            for (k in max(0, y - r)..min(h - 1, y + r)) {
                val v = tmp[k * w + x]
                if (if (isMax) v > m else v < m) m = v
            }
            out[y * w + x] = m
        }
        return Plane(w, h, out)
    }

    fun maxFilter(r: Int) = extremum(r, true)
    fun minFilter(r: Int) = extremum(r, false)

    /** Réduction par moyenne de blocs (facteur entier). */
    fun downscale(f: Int): Plane {
        if (f <= 1) return this
        val ow = max(1, w / f); val oh = max(1, h / f)
        val out = FloatArray(ow * oh)
        for (oy in 0 until oh) for (ox in 0 until ow) {
            var s = 0f
            for (dy in 0 until f) for (dx in 0 until f) s += d[(oy * f + dy) * w + ox * f + dx]
            out[oy * ow + ox] = s / (f * f)
        }
        return Plane(ow, oh, out)
    }

    /** Échantillonnage bilinéaire en coordonnées normalisées (0..1). */
    fun sample(u: Float, v: Float): Float {
        val fx = min(max(u * w - 0.5f, 0f), w - 1f)
        val fy = min(max(v * h - 0.5f, 0f), h - 1f)
        val x0 = fx.toInt(); val y0 = fy.toInt()
        val x1 = min(w - 1, x0 + 1); val y1 = min(h - 1, y0 + 1)
        val ax = fx - x0; val ay = fy - y0
        val a = d[y0 * w + x0] * (1 - ax) + d[y0 * w + x1] * ax
        val b = d[y1 * w + x0] * (1 - ax) + d[y1 * w + x1] * ax
        return a * (1 - ay) + b * ay
    }

    /** Seuil d'Otsu sur un histogramme 256 niveaux. */
    fun otsu(): Float {
        val hist = IntArray(256)
        for (v in d) hist[min(255, max(0, v.toInt()))]++
        val total = d.size
        var sum = 0.0
        for (i in 0..255) sum += i * hist[i].toDouble()
        var sumB = 0.0; var wB = 0; var best = -1.0; var thr = 127
        for (i in 0..255) {
            wB += hist[i]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += i * hist[i].toDouble()
            val mB = sumB / wB; val mF = (sum - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; thr = i }
        }
        return thr.toFloat()
    }
}

/** Masque binaire avec morphologie par image intégrale. */
class Mask(val w: Int, val h: Int, val m: ByteArray = ByteArray(w * h)) {
    private fun integral(): IntArray {
        val s = IntArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0
            for (x in 0 until w) {
                row += m[y * w + x]
                s[(y + 1) * (w + 1) + x + 1] = s[y * (w + 1) + x + 1] + row
            }
        }
        return s
    }

    private fun morph(r: Int, dilate: Boolean): Mask {
        val s = integral()
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            val y0 = max(0, y - r); val y1 = min(h, y + r + 1)
            for (x in 0 until w) {
                val x0 = max(0, x - r); val x1 = min(w, x + r + 1)
                val c = s[y1 * (w + 1) + x1] - s[y0 * (w + 1) + x1] - s[y1 * (w + 1) + x0] + s[y0 * (w + 1) + x0]
                // érosion : l'extérieur de l'image compte comme « plein » (la page peut toucher le bord)
                out[y * w + x] = if (dilate) (if (c > 0) 1 else 0) else (if (c == (y1 - y0) * (x1 - x0)) 1 else 0)
            }
        }
        return Mask(w, h, out)
    }

    fun dilate(r: Int) = morph(r, true)
    fun erode(r: Int) = morph(r, false)
    fun close(r: Int) = dilate(r).erode(r)
    fun open(r: Int) = erode(r).dilate(r)

    /** Garde la plus grande composante connexe (4-connexité). Renvoie le masque et son aire. */
    fun largestComponent(): Pair<Mask, Int> {
        val label = IntArray(w * h)
        val stack = IntArray(w * h)
        var bestLabel = 0; var bestArea = 0; var cur = 0
        for (start in 0 until w * h) {
            if (m[start].toInt() == 0 || label[start] != 0) continue
            cur++
            var sp = 0; var area = 0
            stack[sp++] = start; label[start] = cur
            while (sp > 0) {
                val p = stack[--sp]; area++
                val x = p % w; val y = p / w
                if (x > 0 && m[p - 1].toInt() != 0 && label[p - 1] == 0) { label[p - 1] = cur; stack[sp++] = p - 1 }
                if (x < w - 1 && m[p + 1].toInt() != 0 && label[p + 1] == 0) { label[p + 1] = cur; stack[sp++] = p + 1 }
                if (y > 0 && m[p - w].toInt() != 0 && label[p - w] == 0) { label[p - w] = cur; stack[sp++] = p - w }
                if (y < h - 1 && m[p + w].toInt() != 0 && label[p + w] == 0) { label[p + w] = cur; stack[sp++] = p + w }
            }
            if (area > bestArea) { bestArea = area; bestLabel = cur }
        }
        val out = ByteArray(w * h)
        if (bestLabel != 0) for (i in out.indices) if (label[i] == bestLabel) out[i] = 1
        return Mask(w, h, out) to bestArea
    }

    /**
     * Suivi de contour de Moore (8-connexité), dans le sens horaire à l'écran.
     * Renvoie les points (x, y) entrelacés, en partant du pixel le plus haut puis le plus à gauche.
     */
    fun traceBoundary(): IntArray {
        var start = -1
        for (i in 0 until w * h) if (m[i].toInt() != 0) { start = i; break }
        if (start < 0) return IntArray(0)
        val dx = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)
        val dy = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)
        val pts = ArrayList<Int>(4 * (w + h))
        var cx = start % w; var cy = start / w
        val sx = cx; val sy = cy
        var dir = 0
        var firstDir = -1
        val cap = 8 * w * h
        var steps = 0
        while (steps++ < cap) {
            pts.add(cx); pts.add(cy)
            var k = (dir + if (dir % 2 == 0) 6 else 5) % 8
            var found = -1
            for (i in 0 until 8) {
                val nx = cx + dx[k]; val ny = cy + dy[k]
                if (nx in 0 until w && ny in 0 until h && m[ny * w + nx].toInt() != 0) { found = k; break }
                k = (k + 1) % 8
            }
            if (found < 0) break                       // pixel isolé
            if (cx == sx && cy == sy) {
                if (firstDir < 0) firstDir = found
                else if (found == firstDir) { pts.removeAt(pts.size - 1); pts.removeAt(pts.size - 1); break }
            }
            cx += dx[found]; cy += dy[found]; dir = found
        }
        return pts.toIntArray()
    }
}
