package app.feuillet

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.feuillet.core.EnhanceParams
import app.feuillet.core.Filter
import app.feuillet.core.OcrWord
import app.feuillet.core.Outline
import app.feuillet.core.PaperFormat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Une page scannée : la photo d'origine, ses réglages, et l'état de son rendu. */
data class Page(
    val id: String = UUID.randomUUID().toString().take(12),
    val corners: List<Float>,
    val curves: List<Float>,
    val detected: Boolean,
    val useCurves: Boolean = true,
    val strength: Float = 1f,
    val format: PaperFormat = PaperFormat.AUTO,
    val rotation: Int = 0,
    val filter: Filter = Filter.SCANNER,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val deshadow: Boolean = true,
    val sharpen: Boolean = true,
    /** Netteté par micro-réseau de neurones embarqué (texte ou photo selon le filtre). */
    val ai: Boolean = true,
    /** Numéro de rendu : change à chaque nouveau fichier produit (sert à rafraîchir les images affichées). */
    val rev: Int = 0,
    val ready: Boolean = false,
    val outW: Int = 0,
    val outH: Int = 0,
    val widthMm: Float = 210f,
    val heightMm: Float = 297f,
    val ocrText: String? = null,
    val ocrLangs: String? = null,
) {
    fun outline() = Outline(corners.toFloatArray(), curves.toFloatArray(), if (detected) 1f else 0f)
    fun enhance() = EnhanceParams(filter, brightness, contrast, deshadow, sharpen, ai)

    /** Vrai si la géométrie (et donc le texte reconnu) diffère de [other]. */
    fun geometryDiffers(other: Page) = corners != other.corners || curves != other.curves || useCurves != other.useCurves ||
        strength != other.strength || format != other.format || rotation != other.rotation
}

data class Doc(
    val id: String = UUID.randomUUID().toString().take(12),
    val title: String,
    val folder: String? = null,
    val tags: List<String> = emptyList(),
    val created: Long = System.currentTimeMillis(),
    val modified: Long = System.currentTimeMillis(),
    val pages: List<Page> = emptyList(),
)

/** Bibliothèque : index JSON + fichiers image par document, dans le stockage privé de l'application. */
class Store(context: Context) {
    private val root = File(context.filesDir, "docs").apply { mkdirs() }
    private val index = File(context.filesDir, "library.json")
    private val prefs = context.getSharedPreferences("feuillet", Context.MODE_PRIVATE)

    var docs by mutableStateOf(listOf<Doc>())
        private set
    var folders by mutableStateOf(listOf<String>())
        private set

    var defaultFormat: PaperFormat
        get() = runCatching { PaperFormat.valueOf(prefs.getString("format", "AUTO")!!) }.getOrDefault(PaperFormat.AUTO)
        set(v) = prefs.edit().putString("format", v.name).apply()
    var autoCapture: Boolean
        get() = prefs.getBoolean("auto", true)
        set(v) = prefs.edit().putBoolean("auto", v).apply()
    var ocrLangs: List<String>
        get() = prefs.getString("langs", "fra+eng")!!.split("+").filter { it.isNotBlank() }
        set(v) = prefs.edit().putString("langs", v.joinToString("+")).apply()

    init {
        load()
    }

    fun dir(docId: String) = File(root, docId).apply { mkdirs() }
    fun srcFile(docId: String, pageId: String) = File(dir(docId), "${pageId}_src.jpg")
    fun outFile(docId: String, pageId: String) = File(dir(docId), "$pageId.jpg")
    fun thumbFile(docId: String, pageId: String) = File(dir(docId), "${pageId}_t.jpg")
    fun wordsFile(docId: String, pageId: String) = File(dir(docId), "${pageId}_ocr.json")

    fun doc(id: String) = docs.firstOrNull { it.id == id }

    @Synchronized
    fun upsert(doc: Doc) {
        val d = doc.copy(modified = System.currentTimeMillis())
        docs = if (docs.any { it.id == d.id }) docs.map { if (it.id == d.id) d else it } else listOf(d) + docs
        save()
    }

    /** Met à jour une page sans toucher aux autres (les rendus arrivent d'un fil d'arrière-plan). */
    @Synchronized
    fun updatePage(docId: String, pageId: String, change: (Page) -> Page) {
        val d = doc(docId) ?: return
        docs = docs.map { if (it.id == docId) d.copy(pages = d.pages.map { p -> if (p.id == pageId) change(p) else p }) else it }
        save()
    }

    @Synchronized
    fun deleteDoc(id: String) {
        docs = docs.filter { it.id != id }
        File(root, id).deleteRecursively()
        save()
    }

    @Synchronized
    fun deletePage(docId: String, pageId: String) {
        val d = doc(docId) ?: return
        listOf(srcFile(docId, pageId), outFile(docId, pageId), thumbFile(docId, pageId), wordsFile(docId, pageId)).forEach { it.delete() }
        upsert(d.copy(pages = d.pages.filter { it.id != pageId }))
    }

    @Synchronized
    fun addFolder(name: String) {
        val n = name.trim()
        if (n.isNotEmpty() && n !in folders) { folders = (folders + n).sortedBy { it.lowercase() }; save() }
    }

    @Synchronized
    fun deleteFolder(name: String) {
        folders = folders - name
        docs = docs.map { if (it.folder == name) it.copy(folder = null) else it }
        save()
    }

    fun saveWords(docId: String, pageId: String, words: List<OcrWord>) {
        val arr = JSONArray()
        for (w in words) arr.put(JSONArray().put(w.text).put(w.x.toDouble()).put(w.y.toDouble()).put(w.w.toDouble()).put(w.h.toDouble()))
        wordsFile(docId, pageId).writeText(arr.toString())
    }

    fun loadWords(docId: String, pageId: String): List<OcrWord> = runCatching {
        val arr = JSONArray(wordsFile(docId, pageId).readText())
        List(arr.length()) { i ->
            val a = arr.getJSONArray(i)
            OcrWord(a.getString(0), a.getDouble(1).toFloat(), a.getDouble(2).toFloat(), a.getDouble(3).toFloat(), a.getDouble(4).toFloat())
        }
    }.getOrDefault(emptyList())

    /** Recherche dans le titre, les tags, le dossier et le texte reconnu ; tous les mots doivent être présents. */
    fun search(query: String, folder: String?): List<Doc> {
        val terms = normalize(query).split(" ").filter { it.isNotBlank() }
        return docs.filter { d ->
            (folder == null || d.folder == folder) && terms.all { t ->
                normalize(d.title).contains(t) || d.tags.any { normalize(it).contains(t) } ||
                    (d.folder?.let { normalize(it).contains(t) } ?: false) ||
                    d.pages.any { p -> p.ocrText?.let { normalize(it).contains(t) } ?: false }
            }
        }.sortedByDescending { it.modified }
    }

    private fun save() {
        val o = JSONObject()
        o.put("folders", JSONArray(folders))
        o.put("docs", JSONArray().also { arr -> docs.forEach { arr.put(docJson(it)) } })
        val tmp = File(index.path + ".tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(index)
    }

    private fun load() {
        if (!index.exists()) return
        runCatching {
            val o = JSONObject(index.readText())
            folders = o.optJSONArray("folders")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
            val arr = o.getJSONArray("docs")
            docs = List(arr.length()) { docFrom(arr.getJSONObject(it)) }
        }
    }

    private fun docJson(d: Doc) = JSONObject().apply {
        put("id", d.id); put("title", d.title); put("folder", d.folder ?: JSONObject.NULL)
        put("tags", JSONArray(d.tags)); put("created", d.created); put("modified", d.modified)
        put("pages", JSONArray().also { a -> d.pages.forEach { a.put(pageJson(it)) } })
    }

    private fun pageJson(p: Page) = JSONObject().apply {
        put("id", p.id)
        put("corners", JSONArray().also { a -> p.corners.forEach { a.put(it.toDouble()) } })
        put("curves", JSONArray().also { a -> p.curves.forEach { a.put(it.toDouble()) } })
        put("detected", p.detected); put("useCurves", p.useCurves); put("strength", p.strength.toDouble())
        put("format", p.format.name); put("rotation", p.rotation); put("filter", p.filter.name)
        put("brightness", p.brightness.toDouble()); put("contrast", p.contrast.toDouble())
        put("deshadow", p.deshadow); put("sharpen", p.sharpen); put("ai", p.ai); put("rev", p.rev); put("ready", p.ready)
        put("outW", p.outW); put("outH", p.outH); put("widthMm", p.widthMm.toDouble()); put("heightMm", p.heightMm.toDouble())
        put("ocrText", p.ocrText ?: JSONObject.NULL); put("ocrLangs", p.ocrLangs ?: JSONObject.NULL)
    }

    private fun floats(a: JSONArray) = List(a.length()) { a.getDouble(it).toFloat() }

    private fun docFrom(o: JSONObject): Doc {
        val pa = o.getJSONArray("pages")
        val ta = o.optJSONArray("tags") ?: JSONArray()
        return Doc(
            id = o.getString("id"), title = o.getString("title"),
            folder = if (o.isNull("folder")) null else o.getString("folder"),
            tags = List(ta.length()) { ta.getString(it) },
            created = o.getLong("created"), modified = o.getLong("modified"),
            pages = List(pa.length()) { pageFrom(pa.getJSONObject(it)) },
        )
    }

    private fun pageFrom(o: JSONObject) = Page(
        id = o.getString("id"), corners = floats(o.getJSONArray("corners")), curves = floats(o.getJSONArray("curves")),
        detected = o.optBoolean("detected"), useCurves = o.optBoolean("useCurves", true), strength = o.optDouble("strength", 1.0).toFloat(),
        format = runCatching { PaperFormat.valueOf(o.getString("format")) }.getOrDefault(PaperFormat.AUTO),
        rotation = o.optInt("rotation"),
        filter = runCatching { Filter.valueOf(o.getString("filter")) }.getOrDefault(Filter.AUTO),
        brightness = o.optDouble("brightness", 0.0).toFloat(), contrast = o.optDouble("contrast", 0.0).toFloat(),
        deshadow = o.optBoolean("deshadow", true), sharpen = o.optBoolean("sharpen", true), ai = o.optBoolean("ai", true),
        rev = o.optInt("rev"), ready = o.optBoolean("ready"), outW = o.optInt("outW"), outH = o.optInt("outH"),
        widthMm = o.optDouble("widthMm", 210.0).toFloat(), heightMm = o.optDouble("heightMm", 297.0).toFloat(),
        ocrText = if (o.isNull("ocrText")) null else o.getString("ocrText"),
        ocrLangs = if (o.isNull("ocrLangs")) null else o.getString("ocrLangs"),
    )

    companion object {
        fun defaultTitle(): String = "Scan " + SimpleDateFormat("d MMM yyyy HH:mm", Locale.FRENCH).format(Date())

        /** Minuscules sans accents, pour une recherche tolérante. */
        fun normalize(s: String): String =
            java.text.Normalizer.normalize(s.lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

        fun safeName(s: String): String = s.trim().replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]+"), " ").replace(Regex("\\s+"), " ").take(80).ifBlank { "Scan" }
    }
}
