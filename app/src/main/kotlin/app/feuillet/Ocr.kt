package app.feuillet

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.feuillet.core.OcrWord
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Langue OCR proposée au téléchargement. */
class OcrLanguage(val code: String, val name: String)

/** Variante de modèle : « rapide » (léger) ou « précis » (plus lourd, plus lent, meilleur sur les textes difficiles). */
enum class OcrQuality(val label: String, val repo: String) {
    FAST("Rapide", "tessdata_fast"),
    BEST("Précis", "tessdata_best"),
}

/**
 * Modules de langues OCR : français et anglais sont fournis avec l'application,
 * les autres se téléchargent à la demande depuis les dépôts officiels de Tesseract.
 */
class Languages(private val context: Context) {
    private val dir = File(context.filesDir, "tessdata").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("feuillet_langs", Context.MODE_PRIVATE)

    /** Dossier parent attendu par Tesseract (il contient « tessdata/ »). */
    val dataPath: String get() = context.filesDir.path

    var installed by mutableStateOf(listOf<String>())
        private set

    /** Progression des téléchargements en cours, par code de langue (0..1). */
    val progress = mutableStateMapOf<String, Float>()

    init {
        for (code in BUNDLED) {
            val f = File(dir, "$code.traineddata")
            if (!f.exists()) runCatching {
                context.assets.open("tessdata/$code.traineddata").use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            }
        }
        refresh()
    }

    private fun refresh() {
        installed = dir.listFiles().orEmpty().filter { it.name.endsWith(".traineddata") && it.length() > 10_000 }
            .map { it.name.removeSuffix(".traineddata") }.sorted()
    }

    fun name(code: String) = CATALOG.firstOrNull { it.code == code }?.name ?: code
    fun quality(code: String) = if (prefs.getString("q_$code", "FAST") == "BEST") OcrQuality.BEST else OcrQuality.FAST
    fun sizeMb(code: String) = File(dir, "$code.traineddata").length() / 1_000_000f
    fun isBundled(code: String) = code in BUNDLED

    /** Télécharge (ou remplace) le module d'une langue. Renvoie null en cas de succès, sinon le message d'erreur. */
    suspend fun download(code: String, quality: OcrQuality): String? = withContext(Dispatchers.IO) {
        progress[code] = 0f
        val part = File(dir, "$code.part")
        try {
            val url = URL("https://raw.githubusercontent.com/tesseract-ocr/${quality.repo}/main/$code.traineddata")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000; conn.readTimeout = 30000
            if (conn.responseCode != 200) return@withContext "Téléchargement refusé (code ${conn.responseCode})."
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16); var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); done += n
                        if (total > 0) progress[code] = done.toFloat() / total
                    }
                }
            }
            if (part.length() < 10_000) return@withContext "Fichier reçu incomplet."
            val target = File(dir, "$code.traineddata")
            target.delete()
            if (!part.renameTo(target)) return@withContext "Impossible d'enregistrer le module."
            prefs.edit().putString("q_$code", quality.name).apply()
            null
        } catch (e: Exception) {
            "Téléchargement impossible : vérifiez la connexion."
        } finally {
            part.delete()
            progress.remove(code)
            refresh()
        }
    }

    /** Supprime un module téléchargé. Les langues fournies reviennent à leur version d'origine. */
    fun delete(code: String) {
        File(dir, "$code.traineddata").delete()
        prefs.edit().remove("q_$code").apply()
        if (code in BUNDLED) runCatching {
            context.assets.open("tessdata/$code.traineddata").use { i -> File(dir, "$code.traineddata").outputStream().use { o -> i.copyTo(o) } }
        }
        refresh()
    }

    companion object {
        val BUNDLED = listOf("fra", "eng")

        /** Langues proposées (codes Tesseract). */
        val CATALOG = listOf(
            OcrLanguage("fra", "Français"), OcrLanguage("eng", "Anglais"), OcrLanguage("deu", "Allemand"),
            OcrLanguage("spa", "Espagnol"), OcrLanguage("ita", "Italien"), OcrLanguage("por", "Portugais"),
            OcrLanguage("nld", "Néerlandais"), OcrLanguage("cat", "Catalan"), OcrLanguage("lat", "Latin"),
            OcrLanguage("pol", "Polonais"), OcrLanguage("ces", "Tchèque"), OcrLanguage("slk", "Slovaque"),
            OcrLanguage("hun", "Hongrois"), OcrLanguage("ron", "Roumain"), OcrLanguage("bul", "Bulgare"),
            OcrLanguage("hrv", "Croate"), OcrLanguage("srp", "Serbe"), OcrLanguage("slv", "Slovène"),
            OcrLanguage("ell", "Grec"), OcrLanguage("tur", "Turc"), OcrLanguage("rus", "Russe"),
            OcrLanguage("ukr", "Ukrainien"), OcrLanguage("swe", "Suédois"), OcrLanguage("nor", "Norvégien"),
            OcrLanguage("dan", "Danois"), OcrLanguage("fin", "Finnois"), OcrLanguage("est", "Estonien"),
            OcrLanguage("lav", "Letton"), OcrLanguage("lit", "Lituanien"), OcrLanguage("ara", "Arabe"),
            OcrLanguage("heb", "Hébreu"), OcrLanguage("fas", "Persan"), OcrLanguage("hin", "Hindi"),
            OcrLanguage("ben", "Bengali"), OcrLanguage("tam", "Tamoul"), OcrLanguage("tha", "Thaï"),
            OcrLanguage("vie", "Vietnamien"), OcrLanguage("ind", "Indonésien"), OcrLanguage("chi_sim", "Chinois simplifié"),
            OcrLanguage("chi_tra", "Chinois traditionnel"), OcrLanguage("jpn", "Japonais"), OcrLanguage("kor", "Coréen"),
            OcrLanguage("equ", "Formules mathématiques"), OcrLanguage("osd", "Orientation et écriture"),
        )
    }
}

class OcrResult(val text: String, val words: List<OcrWord>)

/** Reconnaissance de texte hors ligne (Tesseract). Un seul moteur, sur un fil dédié. */
class Ocr(private val languages: Languages) {
    private val thread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private var api: TessBaseAPI? = null
    private var apiLangs = ""

    /** Reconnaît le texte d'une page. Renvoie null si aucune langue demandée n'est installée ou si le moteur échoue. */
    suspend fun recognize(bitmap: Bitmap, langs: List<String>): OcrResult? = withContext(thread) {
        val usable = langs.filter { it in languages.installed }
        if (usable.isEmpty()) return@withContext null
        val key = usable.joinToString("+")
        if (api == null || apiLangs != key) {
            api?.recycle(); api = null
            val t = TessBaseAPI()
            if (!t.init(languages.dataPath, key)) { t.recycle(); return@withContext null }
            t.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
            api = t; apiLangs = key
        }
        val t = api!!
        runCatching {
            t.setImage(bitmap)
            val text = t.getUTF8Text() ?: ""
            val words = ArrayList<OcrWord>()
            val iter = t.getResultIterator()
            if (iter != null) {
                iter.begin()
                val w = bitmap.width.toFloat(); val h = bitmap.height.toFloat()
                do {
                    val s = iter.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                    val r = iter.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                    if (!s.isNullOrBlank() && r != null && r.width() > 0 && r.height() > 0) {
                        words.add(OcrWord(s, r.left / w, r.top / h, r.width() / w, r.height() / h))
                    }
                } while (iter.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
                iter.delete()
            }
            t.clear()
            OcrResult(text.trim(), words)
        }.getOrNull()
    }

    suspend fun release() = withContext(thread) { api?.recycle(); api = null; apiLangs = "" }
}
