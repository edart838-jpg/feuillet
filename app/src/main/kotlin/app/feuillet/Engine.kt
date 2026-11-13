package app.feuillet

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import app.feuillet.core.Dewarper
import app.feuillet.core.Detector
import app.feuillet.core.Enhancer
import app.feuillet.core.Mapping
import app.feuillet.core.Outline
import app.feuillet.core.PaperFormat
import app.feuillet.core.Raster
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max

fun Bitmap.toRaster(): Raster {
    val px = IntArray(width * height)
    getPixels(px, 0, width, 0, 0, width, height)
    return Raster(width, height, px)
}

fun Raster.toBitmap(): Bitmap = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)

/** Page aplanie prête à recevoir un filtre, avec ses dimensions physiques. */
class Flattened(val image: Raster, val widthMm: Float, val heightMm: Float)

/** Pont entre le moteur d'image et les fichiers de la bibliothèque. Un seul rendu à la fois (mémoire). */
class Engine(private val store: Store) {
    private val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + worker)

    /** Formats que le mode Auto peut reconnaître : Lettre s'ajoute dans les pays qui l'utilisent. */
    private val autoCandidates: List<PaperFormat> =
        if (Locale.getDefault().country in setOf("US", "CA", "MX", "PH", "CL", "CO")) listOf(PaperFormat.LETTER, PaperFormat.A4, PaperFormat.CARD)
        else listOf(PaperFormat.A4, PaperFormat.CARD)

    /** Décode une image en limitant son grand côté, puis applique l'orientation EXIF. */
    fun decode(file: File, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val degrees = when (runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val scale = minOf(1f, maxSide.toFloat() / max(bmp.width, bmp.height))
        if (degrees == 0f && scale == 1f) return bmp
        val m = Matrix().apply { postScale(scale, scale); postRotate(degrees) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true).also { if (it != bmp) bmp.recycle() }
    }

    /**
     * Ajoute une photo au document : la copie redressée dans la bibliothèque, détecte la page,
     * puis lance le rendu en arrière-plan. Renvoie la page créée (ou null si l'image est illisible).
     */
    suspend fun importPhoto(docId: String, source: File, deleteSource: Boolean): Page? = withContext(Dispatchers.IO) {
        val bmp = decode(source, 3600) ?: return@withContext null
        if (deleteSource) source.delete()
        val page0 = Page(corners = emptyList(), curves = emptyList(), detected = false, format = store.defaultFormat)
        store.srcFile(docId, page0.id).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        val small = Bitmap.createScaledBitmap(bmp, max(1, bmp.width * 800 / max(bmp.width, bmp.height)), max(1, bmp.height * 800 / max(bmp.width, bmp.height)), true)
        bmp.recycle()
        val outline = Detector.detect(small.toRaster())
        small.recycle()
        val o = outline ?: Outline.fullFrame()
        val page = page0.copy(corners = o.corners.toList(), curves = o.curves.toList(), detected = outline != null)
        val doc = store.doc(docId) ?: Doc(id = docId, title = Store.defaultTitle())
        store.upsert(doc.copy(pages = doc.pages + page))
        render(docId, page.id)
        page
    }

    suspend fun importStream(docId: String, input: InputStream, cacheDir: File): Page? {
        val tmp = withContext(Dispatchers.IO) {
            File.createTempFile("import", ".img", cacheDir).also { f -> input.use { i -> f.outputStream().use { o -> i.copyTo(o) } } }
        }
        return importPhoto(docId, tmp, deleteSource = true)
    }

    /** Aplanit une page (géométrie seule) à la résolution demandée. */
    fun flatten(src: Raster, page: Page, maxSide: Int): Flattened {
        val mapping = Mapping(page.outline(), src.w, src.h, page.useCurves, page.strength)
        val (aspect, wMm, hMm) = PaperFormat.resolve(page.format, mapping.aspect, autoCandidates)
        val (ow, oh) = Dewarper.outputSize(mapping, aspect, maxSide)
        val flat = Dewarper.dewarp(src, mapping, ow, oh, refine = page.useCurves).image
        val q = ((page.rotation % 4) + 4) % 4
        return if (q % 2 == 0) Flattened(flat.rotated(q), wMm, hMm) else Flattened(flat.rotated(q), hMm, wMm)
    }

    /** Demande le rendu complet d'une page (fichier final + vignette). */
    fun render(docId: String, pageId: String) {
        store.updatePage(docId, pageId) { it.copy(ready = false) }
        scope.launch {
            runCatching {
                val page = store.doc(docId)?.pages?.firstOrNull { it.id == pageId } ?: return@launch
                val bmp = decode(store.srcFile(docId, pageId), 3600) ?: return@launch
                val src = bmp.toRaster(); bmp.recycle()
                val flat = flatten(src, page, 3000)
                val out = Enhancer.apply(flat.image, page.enhance())
                val ob = out.toBitmap()
                store.outFile(docId, pageId).outputStream().use { ob.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                val tw = 480; val th = max(1, ob.height * tw / ob.width)
                val tb = Bitmap.createScaledBitmap(ob, tw, th, true)
                store.thumbFile(docId, pageId).outputStream().use { tb.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                tb.recycle(); ob.recycle()
                store.updatePage(docId, pageId) { cur ->
                    cur.copy(ready = true, rev = cur.rev + 1, outW = out.w, outH = out.h, widthMm = flat.widthMm, heightMm = flat.heightMm)
                }
            }.onFailure {
                // rendu impossible (mémoire, fichier manquant) : on rend la main pour ne pas bloquer l'export
                store.updatePage(docId, pageId) { cur -> cur.copy(ready = true) }
            }
        }
    }

    /** Relance les rendus restés en plan (application fermée pendant un traitement). */
    fun resumePending() {
        for (d in store.docs) for (p in d.pages) if (!p.ready) render(d.id, p.id)
    }
}
