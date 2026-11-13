package app.feuillet.core

import kotlin.math.max
import kotlin.math.min

/** Ligne de texte localisée : boîte en pixels de l'image analysée et score moyen de détection. */
class TextBox(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val score: Float)

/**
 * Outils communs aux moteurs OCR à deux étages de type PP-OCR (détection des lignes par carte de probabilité
 * « DB », puis reconnaissance ligne par ligne avec décodage CTC). Tout est en Kotlin pur pour être testable ;
 * seule l'exécution des réseaux est faite ailleurs.
 */
object TextLines {

    /**
     * Prépare l'image pour le détecteur : grand côté ramené à [maxSide], dimensions multiples de 32,
     * normalisation ImageNet, canaux dans l'ordre BGR, disposition CHW.
     * @return tenseur, largeur, hauteur
     */
    fun detectionInput(img: Raster, maxSide: Int = 1280): Triple<FloatArray, Int, Int> {
        val scale = min(1f, maxSide.toFloat() / max(img.w, img.h))
        val w = max(32, ((img.w * scale) / 32f).toInt() * 32)
        val h = max(32, ((img.h * scale) / 32f).toInt() * 32)
        val out = FloatArray(3 * w * h)
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f); val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        val plane = w * h
        for (y in 0 until h) {
            val sy = min(img.h - 1, (y * img.h.toLong() / h).toInt())
            for (x in 0 until w) {
                val p = img.px[sy * img.w + min(img.w - 1, (x * img.w.toLong() / w).toInt())]
                val b = (p and 255) / 255f; val g = ((p shr 8) and 255) / 255f; val r = ((p shr 16) and 255) / 255f
                val i = y * w + x
                out[i] = (b - mean[0]) / std[0]; out[plane + i] = (g - mean[1]) / std[1]; out[2 * plane + i] = (r - mean[2]) / std[2]
            }
        }
        return Triple(out, w, h)
    }

    /**
     * Extrait les lignes de texte d'une carte de probabilité (une valeur par pixel, 0..1).
     * Seuil [thresh], composantes connexes, score moyen >= [boxThresh], puis élargissement « unclip » :
     * le détecteur prédit des régions rétrécies, qu'on regonfle de aire × [unclip] / périmètre.
     */
    fun boxes(prob: FloatArray, w: Int, h: Int, thresh: Float = 0.3f, boxThresh: Float = 0.6f, unclip: Float = 1.5f): List<TextBox> {
        val seen = ByteArray(w * h); val stack = IntArray(w * h)
        val out = ArrayList<TextBox>()
        for (start in 0 until w * h) {
            if (prob[start] < thresh || seen[start].toInt() != 0) continue
            var sp = 0; var n = 0; var sum = 0f
            var x0 = w; var x1 = -1; var y0 = h; var y1 = -1
            stack[sp++] = start; seen[start] = 1
            while (sp > 0) {
                val p = stack[--sp]; n++; sum += prob[p]
                val x = p % w; val y = p / w
                if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
                if (x > 0 && prob[p - 1] >= thresh && seen[p - 1].toInt() == 0) { seen[p - 1] = 1; stack[sp++] = p - 1 }
                if (x < w - 1 && prob[p + 1] >= thresh && seen[p + 1].toInt() == 0) { seen[p + 1] = 1; stack[sp++] = p + 1 }
                if (y > 0 && prob[p - w] >= thresh && seen[p - w].toInt() == 0) { seen[p - w] = 1; stack[sp++] = p - w }
                if (y < h - 1 && prob[p + w] >= thresh && seen[p + w].toInt() == 0) { seen[p + w] = 1; stack[sp++] = p + w }
            }
            val bw = x1 - x0 + 1; val bh = y1 - y0 + 1
            if (bw < 3 || bh < 3 || n < 9) continue
            val score = sum / n
            if (score < boxThresh) continue
            val d = (bw * bh * unclip / (2f * (bw + bh))).toInt()
            out.add(TextBox(max(0, x0 - d), max(0, y0 - d), min(w - 1, x1 + d), min(h - 1, y1 + d), score))
        }
        // ordre de lecture : de haut en bas, puis de gauche à droite pour les boîtes d'une même rangée
        val byY = out.sortedBy { it.y0 + it.y1 }
        val ordered = ArrayList<TextBox>(); val row = ArrayList<TextBox>()
        var rowCenter = 0f; var rowHeight = 0f
        for (b in byY) {
            val cy = (b.y0 + b.y1) / 2f
            if (row.isNotEmpty() && cy > rowCenter + 0.6f * rowHeight) { ordered.addAll(row.sortedBy { it.x0 }); row.clear() }
            if (row.isEmpty()) { rowCenter = cy; rowHeight = (b.y1 - b.y0 + 1).toFloat() }
            row.add(b)
        }
        ordered.addAll(row.sortedBy { it.x0 })
        return ordered
    }

    /**
     * Prépare une ligne pour le reconnaisseur : découpe de la boîte (coordonnées dans [img]),
     * hauteur ramenée à [height], largeur proportionnelle, normalisation (x/255 - 0,5)/0,5, BGR, CHW.
     * @return tenseur et largeur
     */
    fun recognitionInput(img: Raster, x0: Int, y0: Int, x1: Int, y1: Int, height: Int = 48, maxWidth: Int = 2400): Pair<FloatArray, Int> {
        val bw = max(1, x1 - x0 + 1); val bh = max(1, y1 - y0 + 1)
        val w = min(maxWidth, max(height / 2, (bw.toLong() * height / bh).toInt()))
        val out = FloatArray(3 * w * height)
        val plane = w * height
        for (y in 0 until height) {
            val fy = y0 + (y + 0.5f) * bh / height - 0.5f
            val ya = min(img.h - 1, max(0, fy.toInt())); val yb = min(img.h - 1, ya + 1); val ay = (fy - ya).coerceIn(0f, 1f)
            for (x in 0 until w) {
                val fx = x0 + (x + 0.5f) * bw / w - 0.5f
                val xa = min(img.w - 1, max(0, fx.toInt())); val xb = min(img.w - 1, xa + 1); val ax = (fx - xa).coerceIn(0f, 1f)
                val p00 = img.px[ya * img.w + xa]; val p01 = img.px[ya * img.w + xb]; val p10 = img.px[yb * img.w + xa]; val p11 = img.px[yb * img.w + xb]
                val i = y * w + x
                for (c in 0 until 3) {
                    val sh = c * 8                                  // c = 0 : bleu, 1 : vert, 2 : rouge
                    val v = (((p00 shr sh) and 255) * (1 - ax) + ((p01 shr sh) and 255) * ax) * (1 - ay) +
                        (((p10 shr sh) and 255) * (1 - ax) + ((p11 shr sh) and 255) * ax) * ay
                    out[c * plane + i] = (v / 255f - 0.5f) / 0.5f
                }
            }
        }
        return out to w
    }

    /**
     * Décodage CTC glouton. [scores] : [steps] × [classes] valeurs ; la classe 0 est le « blanc ».
     * [alphabet] : caractère de chaque classe à partir de 1 (l'espace peut suivre la dernière entrée).
     * @return texte et confiance moyenne des caractères retenus
     */
    fun ctcDecode(scores: FloatArray, steps: Int, classes: Int, alphabet: List<String>): Pair<String, Float> {
        val sb = StringBuilder()
        var last = 0; var conf = 0f; var kept = 0
        for (t in 0 until steps) {
            var best = 0; var bv = scores[t * classes]
            for (c in 1 until classes) { val v = scores[t * classes + c]; if (v > bv) { bv = v; best = c } }
            if (best != 0 && best != last) {
                sb.append(if (best - 1 < alphabet.size) alphabet[best - 1] else " ")
                conf += bv; kept++
            }
            last = best
        }
        return sb.toString() to (if (kept > 0) conf / kept else 0f)
    }
}
