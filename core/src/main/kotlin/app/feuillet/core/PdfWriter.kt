package app.feuillet.core

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/** Mot reconnu par l'OCR. Boîte normalisée (0..1), origine en haut à gauche de la page. */
class OcrWord(val text: String, val x: Float, val y: Float, val w: Float, val h: Float)

/**
 * Page de PDF : une image JPEG pleine page et, en option, la couche de texte invisible.
 * @param widthPt, heightPt taille de la page en points (1/72 de pouce)
 */
class PdfPage(
    val jpeg: ByteArray,
    val pxW: Int,
    val pxH: Int,
    val widthPt: Float,
    val heightPt: Float,
    val words: List<OcrWord> = emptyList(),
    val gray: Boolean = false,
)

/**
 * Écrivain PDF minimal (PDF 1.4) : images JPEG incorporées telles quelles (aucune recompression)
 * et texte invisible (mode de rendu 3) pour la recherche et la sélection.
 *
 * Le texte utilise une police composite non incorporée en Identity-H, dont les codes sont les unités
 * UTF-16 ; la table ToUnicode rend l'extraction exacte pour toutes les écritures.
 */
object PdfWriter {

    fun write(out: OutputStream, pages: List<PdfPage>, title: String) {
        require(pages.isNotEmpty()) { "aucune page" }
        val w = Counting(out)
        val offsets = ArrayList<Long>()
        fun obj(body: ByteArray) {
            offsets.add(w.count)
            w.ascii("${offsets.size} 0 obj\n"); w.write(body); w.ascii("\nendobj\n")
        }
        fun stream(dict: String, data: ByteArray): ByteArray {
            val b = ByteArrayOutputStream()
            b.write("<< $dict /Length ${data.size} >>\nstream\n".toByteArray(Charsets.ISO_8859_1))
            b.write(data)
            b.write("\nendstream".toByteArray(Charsets.ISO_8859_1))
            return b.toByteArray()
        }
        w.ascii("%PDF-1.4\n%âãÏÓ\n")
        val n = pages.size
        // numéros d'objets : 1 catalogue, 2 pages, 3 police, 4 police descendante, 5 descripteur, 6 ToUnicode, 7 info,
        // puis pour chaque page : page, contenu, image
        val first = 8
        obj("<< /Type /Catalog /Pages 2 0 R >>".toByteArray())
        val kids = (0 until n).joinToString(" ") { "${first + 3 * it} 0 R" }
        obj("<< /Type /Pages /Count $n /Kids [ $kids ] >>".toByteArray())
        obj("<< /Type /Font /Subtype /Type0 /BaseFont /FeuilletInvisible /Encoding /Identity-H /DescendantFonts [ 4 0 R ] /ToUnicode 6 0 R >>".toByteArray())
        obj(("<< /Type /Font /Subtype /CIDFontType2 /BaseFont /FeuilletInvisible /CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> " +
            "/FontDescriptor 5 0 R /DW 500 /CIDToGIDMap /Identity >>").toByteArray())
        obj("<< /Type /FontDescriptor /FontName /FeuilletInvisible /Flags 4 /FontBBox [ 0 -200 1000 800 ] /ItalicAngle 0 /Ascent 800 /Descent -200 /CapHeight 700 /StemV 80 >>".toByteArray())
        obj(stream("", toUnicodeCMap().toByteArray(Charsets.ISO_8859_1)))
        obj("<< /Title ${hexString(title)} /Producer (Feuillet) /Creator (Feuillet) >>".toByteArray())
        for ((i, p) in pages.withIndex()) {
            val base = first + 3 * i
            obj(("<< /Type /Page /Parent 2 0 R /MediaBox [ 0 0 ${num(p.widthPt)} ${num(p.heightPt)} ] /Contents ${base + 1} 0 R " +
                "/Resources << /XObject << /Im0 ${base + 2} 0 R >> /Font << /F1 3 0 R >> >> >>").toByteArray())
            obj(stream("", content(p).toByteArray(Charsets.ISO_8859_1)))
            obj(stream("/Type /XObject /Subtype /Image /Width ${p.pxW} /Height ${p.pxH} /ColorSpace ${if (p.gray) "/DeviceGray" else "/DeviceRGB"} " +
                "/BitsPerComponent 8 /Filter /DCTDecode", p.jpeg))
        }
        val xref = w.count
        w.ascii("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) w.ascii(String.format("%010d 00000 n \n", o))
        w.ascii("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R /Info 7 0 R >>\nstartxref\n$xref\n%%EOF\n")
        w.flush()
    }

    private fun content(p: PdfPage): String {
        val sb = StringBuilder()
        sb.append("q ${num(p.widthPt)} 0 0 ${num(p.heightPt)} 0 0 cm /Im0 Do Q\n")
        if (p.words.isNotEmpty()) {
            sb.append("BT 3 Tr\n")
            for (word in p.words) {
                val text = word.text.trim()
                if (text.isEmpty() || word.w <= 0f || word.h <= 0f) continue
                val size = (word.h * p.heightPt * 0.92f).coerceAtLeast(1f)
                val boxW = word.w * p.widthPt
                val natural = text.length * 0.5f * size
                val tz = (100f * boxW / natural).coerceIn(5f, 1000f)
                val x = word.x * p.widthPt
                val y = p.heightPt - (word.y + word.h) * p.heightPt + 0.2f * size
                sb.append("/F1 ${num(size)} Tf ${num(tz)} Tz 1 0 0 1 ${num(x)} ${num(y)} Tm <")
                for (ch in "$text ") sb.append(String.format("%04X", ch.code))
                sb.append("> Tj\n")
            }
            sb.append("ET\n")
        }
        return sb.toString()
    }

    private fun toUnicodeCMap(): String {
        val sb = StringBuilder()
        sb.append("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n")
        sb.append("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n")
        sb.append("/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n")
        sb.append("1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n")
        var hi = 0
        while (hi < 256) {
            val cnt = minOf(100, 256 - hi)
            sb.append("$cnt beginbfrange\n")
            for (k in hi until hi + cnt) sb.append(String.format("<%02X00> <%02XFF> <%02X00>\n", k, k, k))
            sb.append("endbfrange\n")
            hi += cnt
        }
        sb.append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n")
        return sb.toString()
    }

    private fun hexString(s: String): String {
        val sb = StringBuilder("<FEFF")
        for (ch in s) sb.append(String.format("%04X", ch.code))
        return sb.append(">").toString()
    }

    private fun num(v: Float): String = String.format(java.util.Locale.ROOT, "%.2f", v)

    private class Counting(val out: OutputStream) {
        var count = 0L
        fun write(b: ByteArray) { out.write(b); count += b.size }
        fun ascii(s: String) = write(s.toByteArray(Charsets.ISO_8859_1))
        fun flush() = out.flush()
    }
}
