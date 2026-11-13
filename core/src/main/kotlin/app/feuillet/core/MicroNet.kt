package app.feuillet.core

import java.util.stream.IntStream
import kotlin.math.max
import kotlin.math.min

/**
 * Micro-réseaux de neurones embarqués : trois convolutions 3×3 (1 → 8 → 8 → 1) qui prédisent, pour chaque pixel,
 * la correction de luminance rendant l'image plus nette (flou, bruit et artefacts JPEG appris à l'entraînement).
 *
 * Deux jeux de poids : TEXT (documents) et PHOTO. Inférence en Kotlin pur, par bandes et sur plusieurs cœurs.
 * Pour les documents, les zones unies (papier vierge) sont sautées : seuls les abords de l'encre sont calculés.
 */
object MicroNet {
    enum class Kind { TEXT, PHOTO }

    private const val C = 8                 // canaux cachés
    private const val HALO = 5              // lignes de marge nécessaires autour d'une bande
    private const val STRIP = 48

    private class Weights(flat: FloatArray) {
        val w1 = flat.copyOfRange(0, 72); val b1 = flat.copyOfRange(72, 80)
        val w2 = flat.copyOfRange(80, 656); val b2 = flat.copyOfRange(656, 664)
        val w3 = flat.copyOfRange(664, 736); val b3 = flat[736]
    }

    private val text by lazy { Weights(MicroNetWeights.TEXT) }
    private val photo by lazy { Weights(MicroNetWeights.PHOTO) }

    /** Applique le réseau à l'image, sur place. [strength] dose la correction (1 = telle qu'apprise). */
    fun apply(img: Raster, kind: Kind, strength: Float = 1f) {
        val w = img.w; val h = img.h
        val luma = ByteArray(w * h)
        for (i in luma.indices) {
            val p = img.px[i]
            luma[i] = ((77 * ((p shr 16) and 255) + 150 * ((p shr 8) and 255) + 29 * (p and 255)) shr 8).toByte()
        }
        val net = if (kind == Kind.TEXT) text else photo
        val strips = (h + STRIP - 1) / STRIP
        IntStream.range(0, strips).parallel().forEach { s ->
            strip(img, luma, net, s * STRIP, min(h, (s + 1) * STRIP), kind == Kind.TEXT, strength)
        }
    }

    private fun strip(img: Raster, luma: ByteArray, net: Weights, y0: Int, y1: Int, skipFlat: Boolean, strength: Float) {
        val w = img.w; val h = img.h
        val rows = y1 - y0 + 2 * HALO
        // luminance de la bande et de sa marge, en 0..1 (bords de l'image répétés)
        val l = FloatArray(rows * w)
        for (r in 0 until rows) {
            val sy = min(h - 1, max(0, y0 - HALO + r)) * w
            for (x in 0 until w) l[r * w + x] = (luma[sy + x].toInt() and 255) / 255f
        }
        // niveaux de besoin : 1 = sortie, 2 = couche 2, 3 = couche 1 (chaque niveau englobe le précédent, élargi d'un pixel)
        val need = ByteArray(rows * w)
        if (skipFlat) {
            val edge = ByteArray(rows * w)
            for (r in 1 until rows - 1) for (x in 0 until w) {
                var mn = 2f; var mx = -1f
                for (dy in -1..1) for (dx in -1..1) {
                    val v = l[(r + dy) * w + min(w - 1, max(0, x + dx))]
                    if (v < mn) mn = v; if (v > mx) mx = v
                }
                if (mx - mn > 0.03f) edge[r * w + x] = 1
            }
            val d2 = dilate(edge, w, rows, 2); val d3 = dilate(d2, w, rows, 1); val d4 = dilate(d3, w, rows, 1)
            for (i in need.indices) need[i] = (d2[i] + d3[i] + d4[i]).toByte().let { n -> if (n.toInt() == 0) 0 else (4 - n).toByte() }
        } else {
            java.util.Arrays.fill(need, 1)
        }

        val a1 = FloatArray(C * rows * w)
        val a2 = FloatArray(C * rows * w)
        val plane = rows * w
        // couche 1
        for (r in 1 until rows - 1) for (x in 0 until w) {
            if (need[r * w + x].toInt() == 0) continue
            val xl = max(0, x - 1); val xr = min(w - 1, x + 1)
            val t0 = l[(r - 1) * w + xl]; val t1 = l[(r - 1) * w + x]; val t2 = l[(r - 1) * w + xr]
            val m0 = l[r * w + xl]; val m1 = l[r * w + x]; val m2 = l[r * w + xr]
            val u0 = l[(r + 1) * w + xl]; val u1 = l[(r + 1) * w + x]; val u2 = l[(r + 1) * w + xr]
            for (o in 0 until C) {
                val k = o * 9
                val v = net.b1[o] + net.w1[k] * t0 + net.w1[k + 1] * t1 + net.w1[k + 2] * t2 +
                    net.w1[k + 3] * m0 + net.w1[k + 4] * m1 + net.w1[k + 5] * m2 +
                    net.w1[k + 6] * u0 + net.w1[k + 7] * u1 + net.w1[k + 8] * u2
                a1[o * plane + r * w + x] = if (v > 0f) v else 0f
            }
        }
        // couche 2
        for (r in 2 until rows - 2) for (x in 0 until w) {
            val n = need[r * w + x].toInt()
            if (n == 0 || n == 3) continue
            val xl = max(0, x - 1); val xr = min(w - 1, x + 1)
            val up = (r - 1) * w; val mid = r * w; val dn = (r + 1) * w
            for (o in 0 until C) {
                var v = net.b2[o]
                var k = o * C * 9
                for (i in 0 until C) {
                    val base = i * plane
                    v += net.w2[k] * a1[base + up + xl] + net.w2[k + 1] * a1[base + up + x] + net.w2[k + 2] * a1[base + up + xr] +
                        net.w2[k + 3] * a1[base + mid + xl] + net.w2[k + 4] * a1[base + mid + x] + net.w2[k + 5] * a1[base + mid + xr] +
                        net.w2[k + 6] * a1[base + dn + xl] + net.w2[k + 7] * a1[base + dn + x] + net.w2[k + 8] * a1[base + dn + xr]
                    k += 9
                }
                a2[o * plane + r * w + x] = if (v > 0f) v else 0f
            }
        }
        // couche 3 : correction résiduelle, ajoutée aux trois canaux
        for (y in y0 until y1) {
            val r = y - y0 + HALO
            for (x in 0 until w) {
                if (need[r * w + x].toInt() != 1) continue
                val xl = max(0, x - 1); val xr = min(w - 1, x + 1)
                val up = (r - 1) * w; val mid = r * w; val dn = (r + 1) * w
                var v = net.b3
                for (i in 0 until C) {
                    val base = i * plane; val k = i * 9
                    v += net.w3[k] * a2[base + up + xl] + net.w3[k + 1] * a2[base + up + x] + net.w3[k + 2] * a2[base + up + xr] +
                        net.w3[k + 3] * a2[base + mid + xl] + net.w3[k + 4] * a2[base + mid + x] + net.w3[k + 5] * a2[base + mid + xr] +
                        net.w3[k + 6] * a2[base + dn + xl] + net.w3[k + 7] * a2[base + dn + x] + net.w3[k + 8] * a2[base + dn + xr]
                }
                val d = (v * strength * 255f).toInt()
                if (d == 0) continue
                val p = img.px[y * w + x]
                val nr = (((p shr 16) and 255) + d).coerceIn(0, 255)
                val ng = (((p shr 8) and 255) + d).coerceIn(0, 255)
                val nb = ((p and 255) + d).coerceIn(0, 255)
                img.px[y * w + x] = (255 shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
    }

    /** Dilatation carrée de rayon r d'un masque 0/1 (séparable). */
    private fun dilate(m: ByteArray, w: Int, h: Int, r: Int): ByteArray {
        val tmp = ByteArray(w * h); val out = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var v = 0
            for (k in max(0, x - r)..min(w - 1, x + r)) if (m[y * w + k].toInt() != 0) { v = 1; break }
            tmp[y * w + x] = v.toByte()
        }
        for (y in 0 until h) for (x in 0 until w) {
            var v = 0
            for (k in max(0, y - r)..min(h - 1, y + r)) if (tmp[k * w + x].toInt() != 0) { v = 1; break }
            out[y * w + x] = v.toByte()
        }
        return out
    }
}
