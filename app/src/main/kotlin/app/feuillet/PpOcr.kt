package app.feuillet

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import app.feuillet.core.OcrWord
import app.feuillet.core.TextLines
import java.nio.FloatBuffer

/**
 * Moteur OCR embarqué PP-OCR (PaddleOCR) : un réseau localise les lignes de texte, un second les lit.
 * Les deux modèles ONNX et l'alphabet sont dans les ressources de l'application et tournent avec ONNX Runtime,
 * entièrement sur l'appareil.
 */
class PpOcr(private val context: Context) {
    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private var det: OrtSession? = null
    private var rec: OrtSession? = null
    private var alphabet: List<String> = emptyList()

    /** Vrai si les modèles ont été inclus dans cette version de l'application. */
    val available: Boolean by lazy {
        runCatching { context.assets.open("ppocr/det.onnx").close(); context.assets.open("ppocr/rec.onnx").close(); true }.getOrDefault(false)
    }

    private fun load() {
        if (det != null) return
        det = env.createSession(context.assets.open("ppocr/det.onnx").use { it.readBytes() }, OrtSession.SessionOptions())
        rec = env.createSession(context.assets.open("ppocr/rec.onnx").use { it.readBytes() }, OrtSession.SessionOptions())
        alphabet = context.assets.open("ppocr/dict.txt").bufferedReader(Charsets.UTF_8).use { it.readLines() }.filter { it.isNotEmpty() }
    }

    private fun run(session: OrtSession, data: FloatArray, shape: LongArray): Pair<FloatArray, LongArray> {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { input ->
            session.run(mapOf(session.inputNames.first() to input)).use { result ->
                val out = result.get(0) as OnnxTensor
                val buf = out.floatBuffer
                val arr = FloatArray(buf.remaining()); buf.get(arr)
                return arr to out.info.shape
            }
        }
    }

    /** Lit le texte d'une page. Une entrée de [OcrResult.words] par ligne de texte, avec sa boîte. */
    @Synchronized
    fun recognize(bitmap: Bitmap): OcrResult {
        load()
        val img = bitmap.toRaster()
        val (input, w, h) = TextLines.detectionInput(img, 1600)
        val (prob, shape) = run(det!!, input, longArrayOf(1, 3, h.toLong(), w.toLong()))
        val pw = shape[shape.size - 1].toInt(); val ph = shape[shape.size - 2].toInt()
        // seuils publiés avec le modèle (fichier de configuration du détecteur PP-OCRv6)
        val boxes = TextLines.boxes(prob, pw, ph, thresh = 0.2f, boxThresh = 0.4f, unclip = 1.4f)
        val sx = img.w.toFloat() / pw; val sy = img.h.toFloat() / ph
        val words = ArrayList<OcrWord>(); val text = StringBuilder()
        for (b in boxes) {
            val x0 = (b.x0 * sx).toInt(); val y0 = (b.y0 * sy).toInt()
            val x1 = ((b.x1 + 1) * sx).toInt().coerceAtMost(img.w - 1); val y1 = ((b.y1 + 1) * sy).toInt().coerceAtMost(img.h - 1)
            if (x1 - x0 < 6 || y1 - y0 < 6) continue
            val (line, lw) = TextLines.recognitionInput(img, x0, y0, x1, y1)
            val (scores, s) = run(rec!!, line, longArrayOf(1, 3, 48, lw.toLong()))
            val steps = s[s.size - 2].toInt(); val classes = s[s.size - 1].toInt()
            val (value, conf) = TextLines.ctcDecode(scores, steps, classes, alphabet)
            val clean = value.trim()
            if (clean.isEmpty() || conf < 0.5f) continue
            words.add(OcrWord(clean, x0.toFloat() / img.w, y0.toFloat() / img.h, (x1 - x0 + 1).toFloat() / img.w, (y1 - y0 + 1).toFloat() / img.h))
            text.append(clean).append('\n')
        }
        return OcrResult(text.toString().trim(), words)
    }
}
