package app.feuillet.core

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.Deflater

/**
 * Écrivain TIFF multipage (petit-boutiste), compression Deflate (code 8), une bande par page.
 * Les pages entièrement grises sont écrites en 8 bits, les autres en RVB 24 bits.
 */
object TiffWriter {

    /** @param dpi résolution inscrite dans le fichier (une valeur par page) */
    fun write(out: OutputStream, pages: List<Raster>, dpi: List<Int>) {
        require(pages.isNotEmpty() && pages.size == dpi.size)
        out.write(byteArrayOf(0x49, 0x49, 42, 0))
        var offset = 8L                                   // en-tête : 4 octets + position du premier IFD
        var pending: ByteArray? = null                    // IFD de la page précédente, écrit dès qu'on connaît le suivant
        for ((i, page) in pages.withIndex()) {
            val gray = Enhancer.isGray(page)
            val data = compress(page, gray)
            val pad = data.size % 2
            val prev = pending
            if (prev == null) {
                out.write(le32(8L + data.size + pad))
            } else {
                patchNext(prev, offset + prev.size + data.size + pad)
                out.write(prev); offset += prev.size
            }
            val dataOffset = offset
            out.write(data); if (pad == 1) out.write(0)
            offset += data.size + pad
            pending = ifd(page, gray, dataOffset, data.size, offset, dpi[i])
        }
        out.write(pending!!)
        out.flush()
    }

    private fun compress(page: Raster, gray: Boolean): ByteArray {
        val spp = if (gray) 1 else 3
        val raw = ByteArray(page.w * page.h * spp)
        var k = 0
        for (p in page.px) {
            if (gray) raw[k++] = (p and 255).toByte()
            else { raw[k++] = ((p shr 16) and 255).toByte(); raw[k++] = ((p shr 8) and 255).toByte(); raw[k++] = (p and 255).toByte() }
        }
        val def = Deflater(6)
        def.setInput(raw); def.finish()
        val bos = ByteArrayOutputStream(raw.size / 4 + 64)
        val buf = ByteArray(1 shl 16)
        while (!def.finished()) bos.write(buf, 0, def.deflate(buf))
        def.end()
        return bos.toByteArray()
    }

    /** IFD suivi de ses valeurs externes (BitsPerSample, résolutions). Le dernier champ pointe sur l'IFD suivant. */
    private fun ifd(page: Raster, gray: Boolean, dataOffset: Long, dataSize: Int, ifdOffset: Long, dpi: Int): ByteArray {
        val entries = ArrayList<ByteArray>()
        val n = 13
        val extra = ifdOffset + 2 + 12 * n + 4
        fun e(tag: Int, type: Int, count: Int, value: Long) = entries.add(le16(tag) + le16(type) + le32(count.toLong()) + le32(value))
        fun short(tag: Int, v: Int) = e(tag, 3, 1, v.toLong())
        fun long(tag: Int, v: Long) = e(tag, 4, 1, v)
        long(256, page.w.toLong()); long(257, page.h.toLong())
        if (gray) short(258, 8) else e(258, 3, 3, extra)                    // BitsPerSample
        short(259, 8)                                                        // Compression : Deflate
        short(262, if (gray) 1 else 2)                                       // Photometric
        long(273, dataOffset)                                                // StripOffsets
        short(277, if (gray) 1 else 3)                                       // SamplesPerPixel
        long(278, page.h.toLong())                                           // RowsPerStrip
        long(279, dataSize.toLong())                                         // StripByteCounts
        e(282, 5, 1, extra + 6); e(283, 5, 1, extra + 14)                    // X/YResolution
        short(284, 1)                                                        // PlanarConfiguration
        short(296, 2)                                                        // ResolutionUnit : pouce
        val b = ByteArrayOutputStream()
        b.write(le16(n)); for (x in entries) b.write(x)
        b.write(le32(0))
        b.write(le16(8)); b.write(le16(8)); b.write(le16(8))
        b.write(le32(dpi.toLong())); b.write(le32(1)); b.write(le32(dpi.toLong())); b.write(le32(1))
        return b.toByteArray()
    }

    private fun patchNext(ifd: ByteArray, next: Long) {
        val pos = 2 + 12 * 13
        val v = le32(next)
        for (i in 0 until 4) ifd[pos + i] = v[i]
    }

    private fun le16(v: Int) = byteArrayOf((v and 255).toByte(), ((v shr 8) and 255).toByte())
    private fun le32(v: Long) = byteArrayOf((v and 255).toByte(), ((v shr 8) and 255).toByte(), ((v shr 16) and 255).toByte(), ((v shr 24) and 255).toByte())
}
