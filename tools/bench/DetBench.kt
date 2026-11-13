import app.feuillet.core.*
import java.io.File
import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import kotlin.math.hypot

fun load(p: String): Raster { val b = ImageIO.read(File(p)); val w=b.width; val h=b.height
  return Raster(w,h,b.getRGB(0,0,w,h,null,0,w)) }
fun save(r: Raster, p: String) { val b = BufferedImage(r.w,r.h,BufferedImage.TYPE_INT_RGB); b.setRGB(0,0,r.w,r.h,r.px,0,r.w); ImageIO.write(b, if (p.endsWith("png")) "png" else "jpg", File(p)) }

object DetBench { @JvmStatic fun main(a: Array<String>) {
  val dir = a[0]; val verbose = a.size > 1
  val stats = sortedMapOf<String, IntArray>()   // kind -> [pos, ok, missed, wrong, neg, falsePos]
  var t = 0L
  for (line in File("$dir/gt.csv").readLines()) {
    val f = line.split(","); val img = load("$dir/img/${f[0]}"); val s = stats.getOrPut(f[1]) { IntArray(6) }
    val t0 = System.nanoTime(); val o = Detector.detect(img); t += System.nanoTime() - t0
    if (f[2] == "1") {
      s[0]++
      if (o == null) { s[2]++; if (verbose) println("MANQUE ${f[0]}") } else {
        var worst = 0.0
        for (k in 0 until 4) worst = maxOf(worst, hypot(o.corners[2*k]*img.w - f[3+2*k].toDouble(), o.corners[2*k+1]*img.h - f[4+2*k].toDouble()))
        if (worst < 0.025 * hypot(img.w.toDouble(), img.h.toDouble())) s[1]++ else { s[3]++; if (verbose) println("FAUX ${f[0]} écart ${worst.toInt()} px conf ${o.confidence}") }
      }
    } else { s[4]++; if (o != null) { s[5]++; if (verbose) println("FANTOME ${f[0]} conf ${o.confidence}") } }
  }
  val tot = IntArray(6)
  println("%-12s %5s %5s %6s %5s | %5s %8s".format("fond", "pages", "ok", "manqué", "faux", "vides", "fantômes"))
  for ((k, s) in stats) { println("%-12s %5d %5d %6d %5d | %5d %8d".format(k, s[0], s[1], s[2], s[3], s[4], s[5])); for (i in 0 until 6) tot[i] += s[i] }
  println("%-12s %5d %5d %6d %5d | %5d %8d".format("TOTAL", tot[0], tot[1], tot[2], tot[3], tot[4], tot[5]))
  println("pages bien détectées : %.1f %% · fausses : %.1f %% · fantômes sur scènes vides : %.1f %% · %.0f ms/image".format(100.0*tot[1]/tot[0], 100.0*tot[3]/tot[0], 100.0*tot[5]/tot[4], t/1e6/(tot[0]+tot[4])))
} }
