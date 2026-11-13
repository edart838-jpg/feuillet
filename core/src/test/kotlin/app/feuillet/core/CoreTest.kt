package app.feuillet.core

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreTest {

    private fun resource(name: String): Raster {
        val b = ImageIO.read(javaClass.getResourceAsStream("/$name"))
        return Raster(b.width, b.height, b.getRGB(0, 0, b.width, b.height, null, 0, b.width))
    }

    private fun jpeg(r: Raster): ByteArray {
        val b = BufferedImage(r.w, r.h, BufferedImage.TYPE_INT_RGB)
        b.setRGB(0, 0, r.w, r.h, r.px, 0, r.w)
        val o = ByteArrayOutputStream()
        ImageIO.write(b, "jpg", o)
        return o.toByteArray()
    }

    /** Écart moyen de luminance entre une page rendue et la page d'origine (toutes deux ramenées à 620 px). */
    private fun meanError(page: Raster, reference: Raster): Double {
        val a = page.luma().boxBlur(6); val b = reference.luma().boxBlur(6)
        var s = 0.0; var n = 0
        for (y in 20 until b.h - 20 step 2) for (x in 20 until b.w - 20 step 2) {
            s += abs(a.sample((x + 0.5f) / b.w, (y + 0.5f) / b.h) - b.d[y * b.w + x]); n++
        }
        return s / n
    }

    // ---- détection ----

    @Test
    fun detecteLesCoinsDeLaPageCourbee() {
        val photo = resource("photo_courbe.jpg")
        val o = assertNotNull(Detector.detect(photo))
        // coins relevés lors de la génération de la photo simulée (pixels, image 1500×2000)
        val truth = doubleArrayOf(256.0, 333.0, 1242.0, 412.0, 923.0, 1509.0, 91.0, 1378.0)
        for (k in 0 until 4) {
            val d = hypot(o.corners[2 * k] * photo.w - truth[2 * k], o.corners[2 * k + 1] * photo.h - truth[2 * k + 1])
            assertTrue(d < 14.0, "coin $k à $d px de la vérité")
        }
        assertTrue(o.confidence > 0.8f)
        assertTrue(abs(o.midOffset(0)) > 0.03f, "le bord haut doit être vu comme courbe")
        assertTrue(abs(o.midOffset(1)) < 0.01f && abs(o.midOffset(3)) < 0.01f, "les côtés doivent rester droits")
    }

    @Test
    fun aucuneDetectionSurImageUnie() {
        val flat = Raster(400, 300, IntArray(400 * 300) { 0xFF808080.toInt() })
        assertNull(Detector.detect(flat))
    }

    @Test
    fun detecteUnRectangleInclineSansCourbure() {
        val w = 800; val h = 600
        val img = Raster(w, h, IntArray(w * h) { 0xFF303030.toInt() })
        val a = Math.toRadians(12.0)
        for (y in 0 until h) for (x in 0 until w) {
            val dx = x - 400.0; val dy = y - 300.0
            val u = dx * cos(a) + dy * sin(a); val v = -dx * sin(a) + dy * cos(a)
            if (abs(u) < 180 && abs(v) < 230) img.px[y * w + x] = 0xFFF0F0F0.toInt()
        }
        val o = assertNotNull(Detector.detect(img))
        for (e in 0 until 4) assertTrue(abs(o.midOffset(e)) < 0.012f, "bord $e faussement courbe : ${o.midOffset(e)}")
        val m = Mapping(o, w, h, useCurves = true)
        assertEquals(360.0 / 460.0, m.aspect, 0.03)
    }

    // ---- géométrie ----

    @Test
    fun homographieAllerRetour() {
        val hom = Homography(doubleArrayOf(10.0, 20.0, 300.0, 40.0, 280.0, 400.0, 30.0, 380.0))
        val p = DoubleArray(2); val q = DoubleArray(2)
        hom.map(0.0, 0.0, p); assertEquals(10.0, p[0], 1e-9); assertEquals(20.0, p[1], 1e-9)
        hom.map(1.0, 1.0, p); assertEquals(280.0, p[0], 1e-9); assertEquals(400.0, p[1], 1e-9)
        hom.map(0.37, 0.81, p); hom.unmap(p[0], p[1], q)
        assertEquals(0.37, q[0], 1e-9); assertEquals(0.81, q[1], 1e-9)
    }

    @Test
    fun rapportA4RetrouveSousPerspective() {
        // feuille A4 dans l'espace, basculée de 35° et tournée de 20°, vue par une caméra de focale 3000 px
        val f = 3000.0; val cx = 2000.0; val cy = 1500.0
        val tilt = Math.toRadians(35.0); val yaw = Math.toRadians(20.0)
        fun project(x: Double, y: Double): Pair<Double, Double> {
            val x1 = x * cos(yaw) - y * sin(yaw); val y1 = x * sin(yaw) + y * cos(yaw)
            val y2 = y1 * cos(tilt); val z2 = y1 * sin(tilt) + 900.0
            return (cx + f * x1 / z2) to (cy + f * y2 / z2)
        }
        val pts = listOf(project(-105.0, -148.5), project(105.0, -148.5), project(105.0, 148.5), project(-105.0, 148.5))
        val corners = FloatArray(8)
        for (k in 0 until 4) { corners[2 * k] = (pts[k].first / 4000).toFloat(); corners[2 * k + 1] = (pts[k].second / 3000).toFloat() }
        val m = Mapping(Outline(corners), 4000, 3000, useCurves = false)
        assertEquals(210.0 / 297.0, m.aspect, 0.01)
        val (aspect, wMm, hMm) = PaperFormat.resolve(PaperFormat.AUTO, m.aspect)
        assertEquals(210f, wMm); assertEquals(297f, hMm); assertEquals(210.0 / 297.0, aspect, 1e-6)
    }

    @Test
    fun formatsDePapier() {
        val (a, w, h) = PaperFormat.resolve(PaperFormat.A4, 1.3)          // page vue en paysage
        assertEquals(297f, w); assertEquals(210f, h); assertEquals(297.0 / 210.0, a, 1e-6)
        val (free, fw, _) = PaperFormat.resolve(PaperFormat.AUTO, 0.5)   // ticket étroit : format libre conservé
        assertEquals(0.5, free, 1e-9); assertEquals(210f, fw)
    }

    // ---- dé-courbure ----

    @Test
    fun laCorrectionDeCourbureRedresseLesLignes() {
        val photo = resource("photo_courbe.jpg")
        val reference = resource("page_reference.png")
        val o = assertNotNull(Detector.detect(photo))

        val flatMap = Mapping(o, photo.w, photo.h, useCurves = false)
        val flat = Dewarper.dewarp(photo, flatMap, 620, 877, refine = false).image
        val curved = Dewarper.dewarp(photo, Mapping(o, photo.w, photo.h, useCurves = true), 620, 877, refine = true)

        // ondulation des lignes de texte : forte sans correction, quasi nulle avec
        val wavyBefore = Dewarper.estimateDisplacement(flat.luma()).second
        assertTrue(wavyBefore > 3.0, "la photo de test doit être nettement ondulée ($wavyBefore px)")
        assertTrue(curved.residualPx < 0.6, "ondulation résiduelle trop forte : ${curved.residualPx} px")
        assertTrue(curved.displacements.isNotEmpty(), "l'affinage par lignes de texte doit s'activer")

        // fidélité à la page d'origine : la version corrigée doit en être bien plus proche
        val p = EnhanceParams(Filter.GRAY)
        val errFlat = meanError(Enhancer.apply(flat, p), reference)
        val errCurved = meanError(Enhancer.apply(curved.image, p), reference)
        assertTrue(errCurved < errFlat * 0.8, "corrigé $errCurved contre non corrigé $errFlat")
    }

    @Test
    fun sansCourbureLeRenduEstUneSimplePerspective() {
        val src = Raster(200, 100, IntArray(200 * 100) { i -> if ((i % 200) < 100) 0xFFFF0000.toInt() else 0xFF0000FF.toInt() })
        val m = Mapping(Outline.fullFrame(0f), 200, 100, useCurves = false, inset = 0.0)
        val out = Dewarper.render(src, m, 200, 100)
        assertEquals(0xFFFF0000.toInt(), out.px[50 * 200 + 20])
        assertEquals(0xFF0000FF.toInt(), out.px[50 * 200 + 180])
    }

    @Test
    fun poigneeDeCourbureManuelle() {
        val o = Outline.fullFrame().withMidOffset(0, 0.08f)
        assertEquals(0.08f, o.midOffset(0), 1e-5f)
        assertEquals(0f, o.midOffset(2), 1e-6f)
    }

    // ---- filtres ----

    @Test
    fun leFiltreAutoBlanchitLePapierEtRetireLOmbre() {
        val w = 300; val h = 200
        // papier jaunâtre avec une ombre progressive à droite et une ligne de « texte »
        val img = Raster(w, h, IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val shade = 1.0 - 0.45 * x / w
            val ink = y in 95..104 && x in 40..260
            val r = ((if (ink) 40 else 225) * shade).toInt(); val g = ((if (ink) 40 else 210) * shade).toInt(); val b = ((if (ink) 40 else 170) * shade).toInt()
            (255 shl 24) or (r shl 16) or (g shl 8) or b
        })
        val out = Enhancer.apply(img, EnhanceParams(Filter.AUTO))
        fun rgb(x: Int, y: Int) = out.px[y * w + x].let { Triple((it shr 16) and 255, (it shr 8) and 255, it and 255) }
        val left = rgb(20, 30); val right = rgb(280, 30)
        assertTrue(left.first > 235 && left.third > 235, "papier pas assez blanc à gauche : $left")
        assertTrue(right.first > 235 && right.third > 235, "ombre encore visible à droite : $right")
        assertTrue(abs(left.first - left.third) < 12, "dominante jaune restante : $left")
        assertTrue(rgb(150, 100).first < 90, "le texte doit rester sombre")
        assertEquals(img.px[0], Enhancer.apply(img, EnhanceParams(Filter.ORIGINAL)).px[0])
    }

    @Test
    fun noirEtBlancGardeLesAplatsPleins() {
        val w = 300; val h = 300
        val img = Raster(w, h, IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            if (x in 60..240 && y in 60..240) 0xFF202020.toInt() else 0xFFF4F4F4.toInt()
        })
        val out = Enhancer.apply(img, EnhanceParams(Filter.BW))
        assertEquals(0, out.px[150 * w + 150] and 255, "le centre de l'aplat doit rester noir")
        assertEquals(255, out.px[10 * w + 10] and 255)
        assertTrue(Enhancer.isGray(out))
    }

    @Test
    fun rotationParQuartsDeTour() {
        val r = Raster(3, 2, intArrayOf(1, 2, 3, 4, 5, 6))
        val q = r.rotated(1)
        assertEquals(2, q.w); assertEquals(3, q.h)
        assertEquals(listOf(4, 1, 5, 2, 6, 3), q.px.toList())
        assertEquals(r.px.toList(), r.rotated(1).rotated(3).px.toList())
        assertEquals(listOf(6, 5, 4, 3, 2, 1), r.rotated(2).px.toList())
    }

    // ---- exports ----

    @Test
    fun pdfCherchableRelisibleParPdftotext() {
        val page = Raster(300, 424, IntArray(300 * 424) { 0xFFFFFFFF.toInt() })
        val words = listOf(
            OcrWord("Devis", 0.1f, 0.1f, 0.2f, 0.03f), OcrWord("n°", 0.32f, 0.1f, 0.05f, 0.03f),
            OcrWord("2026-118", 0.4f, 0.1f, 0.3f, 0.03f), OcrWord("chêne", 0.1f, 0.2f, 0.2f, 0.03f),
            OcrWord("日本語", 0.1f, 0.3f, 0.2f, 0.03f),
        )
        val bos = ByteArrayOutputStream()
        PdfWriter.write(bos, listOf(PdfPage(jpeg(page), 300, 424, 595.28f, 841.89f, words), PdfPage(jpeg(page), 300, 424, 595.28f, 841.89f)), "Devis été")
        val bytes = bos.toByteArray()
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("%PDF-1.4"))
        assertTrue(text.contains("/Count 2"))
        // la table xref doit pointer exactement sur chaque objet
        val xrefPos = text.substringAfterLast("startxref\n").substringBefore("\n").toInt()
        assertTrue(text.startsWith("xref", xrefPos))
        val entries = text.substring(xrefPos).lines().drop(3).takeWhile { it.endsWith(" n ") }
        entries.forEachIndexed { i, line -> assertTrue(text.startsWith("${i + 1} 0 obj", line.substring(0, 10).toInt()), "objet ${i + 1} mal référencé") }

        // contrôle de bout en bout quand poppler est installé (intégration continue et poste de développement)
        val tool = listOf("/usr/bin/pdftotext", "/usr/local/bin/pdftotext").firstOrNull { File(it).canExecute() } ?: return
        val f = File.createTempFile("feuillet", ".pdf"); f.writeBytes(bytes)
        val proc = ProcessBuilder(tool, "-enc", "UTF-8", f.path, "-").redirectErrorStream(true).start()
        val extracted = proc.inputStream.readBytes().toString(Charsets.UTF_8)
        proc.waitFor(); f.delete()
        for (wd in words) assertTrue(extracted.contains(wd.text), "« ${wd.text} » introuvable dans : $extracted")
    }

    @Test
    fun tiffMultipageRelisible() {
        val color = Raster(40, 30, IntArray(1200) { i -> (255 shl 24) or ((i % 40) * 6 shl 16) or (((i / 40) * 8) shl 8) or 99 })
        val gray = Raster(20, 10, IntArray(200) { i -> val v = i % 256; (255 shl 24) or (v shl 16) or (v shl 8) or v })
        val bos = ByteArrayOutputStream()
        TiffWriter.write(bos, listOf(color, gray), listOf(300, 150))
        val b = bos.toByteArray()
        fun u16(o: Int) = (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8)
        fun u32(o: Int) = u16(o) or (u16(o + 2) shl 16)
        assertEquals(0x4949, u16(0)); assertEquals(42, u16(2))
        var ifd = u32(4); var pages = 0
        val sizes = ArrayList<Pair<Int, Int>>()
        while (ifd != 0) {
            val n = u16(ifd)
            val tags = (0 until n).associate { u16(ifd + 2 + 12 * it) to u32(ifd + 2 + 12 * it + 8) }
            sizes.add(tags.getValue(256) to tags.getValue(257))
            // décompresse la bande et vérifie sa taille
            val inf = java.util.zip.Inflater()
            inf.setInput(b, tags.getValue(273), tags.getValue(279))
            val raw = ByteArray(tags.getValue(256) * tags.getValue(257) * (tags.getValue(277) and 0xFFFF))
            assertEquals(raw.size, inf.inflate(raw))
            if (pages == 0) { assertEquals(0, raw[0].toInt() and 255); assertEquals(99, raw[2].toInt() and 255) }
            ifd = u32(ifd + 2 + 12 * n); pages++
        }
        assertEquals(2, pages)
        assertEquals(listOf(40 to 30, 20 to 10), sizes)
    }
}
