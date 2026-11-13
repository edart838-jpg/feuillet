package app.feuillet

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import app.feuillet.core.PdfPage
import app.feuillet.core.PdfWriter
import app.feuillet.core.TiffWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

enum class ExportFormat(val label: String, val ext: String, val mime: String, val isImage: Boolean) {
    PDF_OCR("PDF cherchable", "pdf", "application/pdf", false),
    PDF("PDF", "pdf", "application/pdf", false),
    JPEG("JPEG", "jpg", "image/jpeg", true),
    PNG("PNG", "png", "image/png", true),
    WEBP("WebP", "webp", "image/webp", true),
    TIFF("TIFF", "tif", "image/tiff", false),
    TEXT("Texte", "txt", "text/plain", false),
}

enum class ExportQuality(val label: String, val jpeg: Int, val maxSide: Int) {
    LIGHT("Légère", 62, 1600),
    BALANCED("Équilibrée", 82, 2400),
    MAX("Maximale", 95, 3508),
}

class ExportRequest(
    val doc: Doc,
    val pages: List<Page>,
    val name: String,
    val formats: Set<ExportFormat>,
    val zipImages: Boolean,
    val quality: ExportQuality,
)

class Exporter(private val context: Context, private val store: Store, private val ocr: Ocr) {

    /**
     * Produit les fichiers demandés dans le cache de l'application.
     * [onProgress] reçoit un libellé et une fraction 0..1.
     */
    suspend fun export(req: ExportRequest, onProgress: (String, Float) -> Unit): List<File> = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports").apply { deleteRecursively(); mkdirs() }
        val base = Store.safeName(req.name)
        val out = ArrayList<File>()
        val n = req.pages.size

        // attend la fin des rendus en cours
        var waited = 0
        while (waited < 600 && req.pages.any { p -> store.doc(req.doc.id)?.pages?.firstOrNull { it.id == p.id }?.ready == false }) {
            onProgress("Finalisation des pages…", 0f); delay(250); waited++
        }
        val pages = req.pages.mapNotNull { p -> store.doc(req.doc.id)?.pages?.firstOrNull { it.id == p.id } }
            .filter { store.outFile(req.doc.id, it.id).exists() }
        if (pages.isEmpty()) return@withContext out

        val needText = ExportFormat.PDF_OCR in req.formats || ExportFormat.TEXT in req.formats
        val texts = HashMap<String, String>()
        if (needText) {
            val langs = store.ocrLangs
            val key = langs.joinToString("+")
            for ((i, p) in pages.withIndex()) {
                onProgress("Reconnaissance du texte · page ${i + 1} sur ${pages.size}", i.toFloat() / pages.size * 0.6f)
                if (p.ocrText != null && p.ocrLangs == key && store.wordsFile(req.doc.id, p.id).exists()) { texts[p.id] = p.ocrText; continue }
                val bmp = loadScaled(store.outFile(req.doc.id, p.id), 2400) ?: continue
                val res = ocr.recognize(bmp, langs)
                bmp.recycle()
                if (res != null) {
                    store.saveWords(req.doc.id, p.id, res.words)
                    store.updatePage(req.doc.id, p.id) { it.copy(ocrText = res.text, ocrLangs = key) }
                    texts[p.id] = res.text
                }
            }
        }
        val start = if (needText) 0.6f else 0f
        val steps = req.formats.size.coerceAtLeast(1)
        var step = 0
        fun tick(label: String) { onProgress(label, start + (1 - start) * step / steps); step++ }

        for (format in ExportFormat.values()) {
            if (format !in req.formats) continue
            tick("Création : ${format.label}")
            when (format) {
                ExportFormat.PDF, ExportFormat.PDF_OCR -> {
                    val suffix = if (format == ExportFormat.PDF && ExportFormat.PDF_OCR in req.formats) " (image)" else ""
                    val f = File(dir, "$base$suffix.pdf")
                    val pdfPages = pages.map { p ->
                        val bmp = loadScaled(store.outFile(req.doc.id, p.id), req.quality.maxSide)!!
                        val bos = ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.JPEG, req.quality.jpeg, bos)
                        val page = PdfPage(
                            bos.toByteArray(), bmp.width, bmp.height, p.widthMm / 25.4f * 72f, p.heightMm / 25.4f * 72f,
                            if (format == ExportFormat.PDF_OCR) store.loadWords(req.doc.id, p.id) else emptyList(),
                        )
                        bmp.recycle()
                        page
                    }
                    f.outputStream().buffered().use { PdfWriter.write(it, pdfPages, req.name) }
                    out.add(f)
                }
                ExportFormat.TIFF -> {
                    val f = File(dir, "$base.tif")
                    // le TIFF garde chaque page en mémoire le temps de la compresser : résolution plafonnée
                    val side = minOf(req.quality.maxSide, 2400)
                    val widths = IntArray(pages.size)
                    f.outputStream().buffered().use { os ->
                        TiffWriter.write(
                            os, pages.size,
                            { i -> loadScaled(store.outFile(req.doc.id, pages[i].id), side)!!.let { b -> b.toRaster().also { r -> widths[i] = r.w; b.recycle() } } },
                            { i -> (widths[i] / (pages[i].widthMm / 25.4f)).roundToInt().coerceIn(50, 1200) },
                        )
                    }
                    out.add(f)
                }
                ExportFormat.TEXT -> {
                    val f = File(dir, "$base.txt")
                    f.writeText(pages.mapIndexed { i, p -> (if (pages.size > 1) "— Page ${i + 1} —\n" else "") + (texts[p.id] ?: "") }.joinToString("\n\n"))
                    out.add(f)
                }
                else -> {
                    val files = pages.mapIndexed { i, p ->
                        val name = if (n == 1) "$base.${format.ext}" else "$base - ${(i + 1).toString().padStart(2, '0')}.${format.ext}"
                        val f = File(dir, name)
                        val bmp = loadScaled(store.outFile(req.doc.id, p.id), req.quality.maxSide)!!
                        f.outputStream().use { os ->
                            when (format) {
                                ExportFormat.PNG -> bmp.compress(Bitmap.CompressFormat.PNG, 100, os)
                                ExportFormat.WEBP -> bmp.compress(webp(), req.quality.jpeg, os)
                                else -> bmp.compress(Bitmap.CompressFormat.JPEG, req.quality.jpeg, os)
                            }
                        }
                        bmp.recycle()
                        f
                    }
                    if (req.zipImages && files.size > 1) {
                        val zip = File(dir, "$base (${format.label}).zip")
                        ZipOutputStream(zip.outputStream().buffered()).use { z ->
                            z.setLevel(0)                                  // images déjà compressées
                            for (f in files) { z.putNextEntry(ZipEntry(f.name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry(); f.delete() }
                        }
                        out.add(zip)
                    } else out.addAll(files)
                }
            }
        }
        onProgress("Terminé", 1f)
        out
    }

    @Suppress("DEPRECATION")
    private fun webp(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP

    private fun loadScaled(file: File, maxSide: Int): Bitmap? {
        val bmp = BitmapFactory.decodeFile(file.path) ?: return null
        val side = max(bmp.width, bmp.height)
        if (side <= maxSide) return bmp
        val s = maxSide.toFloat() / side
        return Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * s).roundToInt()), max(1, (bmp.height * s).roundToInt()), true).also { bmp.recycle() }
    }

    private fun mimeOf(f: File) = when (f.extension.lowercase()) {
        "pdf" -> "application/pdf"; "jpg" -> "image/jpeg"; "png" -> "image/png"; "webp" -> "image/webp"
        "tif" -> "image/tiff"; "txt" -> "text/plain"; "zip" -> "application/zip"; else -> "application/octet-stream"
    }

    /** Ouvre la feuille de partage d'Android avec les fichiers produits. */
    fun share(files: List<File>) {
        if (files.isEmpty()) return
        val uris = ArrayList(files.map { FileProvider.getUriForFile(context, "app.feuillet.files", it) })
        val mimes = files.map { mimeOf(it) }.distinct()
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        intent.type = if (mimes.size == 1) mimes[0] else "*/*"
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "Partager").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * Enregistre les fichiers dans « Téléchargements/Feuillet ».
     * Renvoie le chemin lisible du dossier de destination.
     */
    suspend fun saveToDownloads(files: List<File>): String = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            for (f in files) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(f))
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Feuillet")
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: continue
                resolver.openOutputStream(uri)?.use { os -> f.inputStream().use { it.copyTo(os) } }
            }
            "Téléchargements/Feuillet"
        } else {
            // avant Android 10 : dossier de l'application, visible depuis un gestionnaire de fichiers
            val dir = File(context.getExternalFilesDir(null), "Feuillet").apply { mkdirs() }
            for (f in files) f.copyTo(File(dir, f.name), overwrite = true)
            dir.path
        }
    }
}
