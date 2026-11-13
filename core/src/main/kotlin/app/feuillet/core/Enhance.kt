package app.feuillet.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

enum class Filter(val label: String) {
    AUTO("Auto"),
    COLOR("Couleur"),
    GRAY("Gris"),
    BW("Noir et blanc"),
    PHOTO("Photo"),
    ORIGINAL("Original"),
}

/**
 * Réglages de rendu d'une page.
 * @param brightness -1..1, @param contrast -1..1
 * @param deshadow retire ombres et dominante (fond de papier uniformisé)
 * @param sharpen accentuation du texte
 * @param ai netteté par micro-réseau de neurones (remplace l'accentuation classique)
 */
data class EnhanceParams(
    val filter: Filter = Filter.AUTO,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val deshadow: Boolean = true,
    val sharpen: Boolean = true,
    val ai: Boolean = false,
)

object Enhancer {

    /** Applique le filtre et renvoie une nouvelle image (l'entrée n'est pas modifiée). */
    fun apply(src: Raster, p: EnhanceParams): Raster {
        val out = Raster(src.w, src.h, src.px.copyOf())
        when (p.filter) {
            Filter.ORIGINAL -> {}
            Filter.PHOTO -> { autoLevels(out); saturate(out, 1.12f) }
            Filter.AUTO -> { if (p.deshadow) { flatten(out); neutralizePaper(out) }; curve(out, 0.06f, 0.96f, 1.08f) }
            Filter.COLOR -> { if (p.deshadow) flatten(out); curve(out, 0.05f, 0.95f, 1.05f); saturate(out, 1.4f) }
            Filter.GRAY -> { if (p.deshadow) flatten(out); curve(out, 0.06f, 0.96f, 1.08f); saturate(out, 0f) }
            Filter.BW -> {
                if (p.deshadow) flatten(out)
                if (p.ai) MicroNet.apply(out, MicroNet.Kind.TEXT)
                binarize(out)
            }
        }
        if (p.filter != Filter.ORIGINAL && p.filter != Filter.BW) {
            if (p.ai) MicroNet.apply(out, if (p.filter == Filter.PHOTO) MicroNet.Kind.PHOTO else MicroNet.Kind.TEXT)
            else if (p.sharpen) unsharp(out, if (p.filter == Filter.PHOTO) 0.35f else 0.7f)
        }
        if (p.brightness != 0f || p.contrast != 0f) tone(out, p.brightness, p.contrast)
        return out
    }

    /**
     * Uniformise l'éclairage : estime le fond du papier (maximum local lissé, par canal) puis divise.
     * Retire d'un coup les ombres, le vignettage et la dominante de couleur.
     */
    fun flatten(img: Raster) {
        val f = max(1, max(img.w, img.h) / 400)
        val sw = max(1, img.w / f); val sh = max(1, img.h / f)
        val planes = Array(3) { Plane(sw, sh) }
        for (y in 0 until sh) for (x in 0 until sw) {
            var r = 0; var g = 0; var b = 0
            for (dy in 0 until f) {
                var i = (y * f + dy) * img.w + x * f
                for (dx in 0 until f) {
                    val p = img.px[i++]
                    r += (p shr 16) and 255; g += (p shr 8) and 255; b += p and 255
                }
            }
            val n = (f * f).toFloat()
            planes[0].d[y * sw + x] = r / n; planes[1].d[y * sw + x] = g / n; planes[2].d[y * sw + x] = b / n
        }
        val rad = max(3, max(sw, sh) / 36)
        val bg = Array(3) { c ->
            val closed = planes[c].maxFilter(rad).minFilter(rad).boxBlur(rad).boxBlur(rad)
            // garde-fou : une grande zone sombre (photo, aplat) ne doit pas être prise pour une ombre
            val sorted = closed.d.sortedArray()
            val floor = sorted[(sorted.size * 0.9f).toInt().coerceAtMost(sorted.size - 1)] * 0.5f
            for (i in closed.d.indices) if (closed.d[i] < floor) closed.d[i] = floor
            closed
        }
        val w = img.w; val h = img.h
        val gains = FloatArray(3)
        for (y in 0 until h) {
            val v = (y + 0.5f) / h
            for (x in 0 until w) {
                if (x % 8 == 0) {
                    val u = (x + 0.5f) / w
                    for (c in 0 until 3) gains[c] = min(5f, 248f / max(1f, bg[c].sample(u, v)))
                }
                val p = img.px[y * w + x]
                val r = min(255f, ((p shr 16) and 255) * gains[0]).toInt()
                val g = min(255f, ((p shr 8) and 255) * gains[1]).toInt()
                val b = min(255f, (p and 255) * gains[2]).toInt()
                img.px[y * w + x] = (255 shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    /**
     * Retire les teintes pâles (papier jauni, reflets colorés) sans toucher aux couleurs franches :
     * un pixel à peine coloré devient gris, un pixel nettement coloré est conservé.
     */
    private fun neutralizePaper(img: Raster) {
        for (i in img.px.indices) {
            val p = img.px[i]
            val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
            val chroma = max(r, max(g, b)) - min(r, min(g, b))
            if (chroma == 0 || chroma >= 60) continue
            val k = ((chroma - 24) / 36f).coerceIn(0f, 1f)
            val y = (77 * r + 150 * g + 29 * b) / 256f
            val nr = (y + (r - y) * k).toInt().coerceIn(0, 255)
            val ng = (y + (g - y) * k).toInt().coerceIn(0, 255)
            val nb = (y + (b - y) * k).toInt().coerceIn(0, 255)
            img.px[i] = (255 shl 24) or (nr shl 16) or (ng shl 8) or nb
        }
    }

    private fun lut(fn: (Float) -> Float) = IntArray(256) { (fn(it / 255f).coerceIn(0f, 1f) * 255f + 0.5f).toInt() }

    private fun applyLut(img: Raster, l: IntArray) {
        for (i in img.px.indices) {
            val p = img.px[i]
            img.px[i] = (255 shl 24) or (l[(p shr 16) and 255] shl 16) or (l[(p shr 8) and 255] shl 8) or l[p and 255]
        }
    }

    /** Courbe : étire [lo, hi] puis gamma. */
    private fun curve(img: Raster, lo: Float, hi: Float, gamma: Float) =
        applyLut(img, lut { ((it - lo) / (hi - lo)).coerceIn(0f, 1f).pow(gamma) })

    private fun tone(img: Raster, brightness: Float, contrast: Float) {
        val k = if (contrast >= 0) 1f + 1.5f * contrast else 1f + 0.7f * contrast
        applyLut(img, lut { (it - 0.5f) * k + 0.5f + 0.35f * brightness })
    }

    private fun autoLevels(img: Raster) {
        val hist = IntArray(256)
        for (p in img.px) hist[(77 * ((p shr 16) and 255) + 150 * ((p shr 8) and 255) + 29 * (p and 255)) shr 8]++
        val n = img.px.size
        var lo = 0; var acc = 0
        while (lo < 255 && acc + hist[lo] < n * 0.005) acc += hist[lo++]
        var hi = 255; acc = 0
        while (hi > 0 && acc + hist[hi] < n * 0.005) acc += hist[hi--]
        if (hi - lo < 40) return
        val l = min(lo, 60) / 255f; val hgh = max(hi, 190) / 255f
        applyLut(img, lut { (it - l) / (hgh - l) })
    }

    private fun saturate(img: Raster, s: Float) {
        for (i in img.px.indices) {
            val p = img.px[i]
            val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
            val y = (77 * r + 150 * g + 29 * b) / 256f
            val nr = (y + (r - y) * s).toInt().coerceIn(0, 255)
            val ng = (y + (g - y) * s).toInt().coerceIn(0, 255)
            val nb = (y + (b - y) * s).toInt().coerceIn(0, 255)
            img.px[i] = (255 shl 24) or (nr shl 16) or (ng shl 8) or nb
        }
    }

    /** Luminance en octets (économe en mémoire pour les grandes pages). */
    private fun lumaBytes(img: Raster): ByteArray {
        val out = ByteArray(img.px.size)
        for (i in out.indices) {
            val p = img.px[i]
            out[i] = ((77 * ((p shr 16) and 255) + 150 * ((p shr 8) and 255) + 29 * (p and 255)) shr 8).toByte()
        }
        return out
    }

    private fun boxBlurBytes(src: ByteArray, w: Int, h: Int, r: Int): ByteArray {
        val tmp = ByteArray(w * h); val out = ByteArray(w * h)
        val win = 2 * r + 1
        for (y in 0 until h) {
            val o = y * w
            var s = 0
            for (k in -r..r) s += src[o + min(w - 1, max(0, k))].toInt() and 255
            for (x in 0 until w) {
                tmp[o + x] = (s / win).toByte()
                s += (src[o + min(w - 1, x + r + 1)].toInt() and 255) - (src[o + max(0, x - r)].toInt() and 255)
            }
        }
        for (x in 0 until w) {
            var s = 0
            for (k in -r..r) s += tmp[min(h - 1, max(0, k)) * w + x].toInt() and 255
            for (y in 0 until h) {
                out[y * w + x] = (s / win).toByte()
                s += (tmp[min(h - 1, y + r + 1) * w + x].toInt() and 255) - (tmp[max(0, y - r) * w + x].toInt() and 255)
            }
        }
        return out
    }

    /** Masque flou sur la luminance, appliqué à l'identique aux trois canaux. */
    private fun unsharp(img: Raster, amount: Float) {
        val l = lumaBytes(img)
        val r = max(1, max(img.w, img.h) / 1200)
        val blur = boxBlurBytes(l, img.w, img.h, r)
        val a = (amount * 256).toInt()
        for (i in img.px.indices) {
            val d = (((l[i].toInt() and 255) - (blur[i].toInt() and 255)) * a) shr 8
            if (d == 0) continue
            val p = img.px[i]
            val nr = (((p shr 16) and 255) + d).coerceIn(0, 255)
            val ng = (((p shr 8) and 255) + d).coerceIn(0, 255)
            val nb = ((p and 255) + d).coerceIn(0, 255)
            img.px[i] = (255 shl 24) or (nr shl 16) or (ng shl 8) or nb
        }
    }

    /** Noir et blanc adaptatif à bords doux (seuil local). */
    private fun binarize(img: Raster) {
        val l = lumaBytes(img)
        val r = max(6, max(img.w, img.h) / 70)
        val mean = boxBlurBytes(l, img.w, img.h, r)
        for (i in img.px.indices) {
            val m = max(1, mean[i].toInt() and 255)
            val ratio = (l[i].toInt() and 255) / m.toFloat()
            val t = ((ratio - 0.78f) / 0.14f).coerceIn(0f, 1f)
            // seuil global en renfort : les grands aplats sombres restent pleins au lieu d'être évidés
            val gl = (((l[i].toInt() and 255) - 96) / 48f).coerceIn(0f, 1f)
            val v = (min(t * t * (3 - 2 * t), gl * gl * (3 - 2 * gl)) * 255f).toInt()
            img.px[i] = (255 shl 24) or (v shl 16) or (v shl 8) or v
        }
    }

    /** Vrai si tous les pixels sont gris (pour un export en niveaux de gris). */
    fun isGray(img: Raster): Boolean {
        var i = 0
        while (i < img.px.size) {
            val p = img.px[i]
            if (((p shr 16) and 255) != ((p shr 8) and 255) || ((p shr 8) and 255) != (p and 255)) return false
            i += 7
        }
        return true
    }
}
