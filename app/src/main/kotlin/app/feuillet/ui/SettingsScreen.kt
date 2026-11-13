package app.feuillet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.feuillet.AppModel
import app.feuillet.Languages
import app.feuillet.OcrQuality
import app.feuillet.core.PaperFormat
import kotlinx.coroutines.launch

/** Réglages : format par défaut et modules de langues OCR (installés, à télécharger). */
@Composable
fun SettingsScreen(model: AppModel) {
    val store = model.store
    val langs = model.languages
    var format by remember { mutableStateOf(store.defaultFormat) }
    var active by remember { mutableStateOf(store.ocrLangs) }
    var error by remember { mutableStateOf<String?>(null) }
    var engine by remember { mutableStateOf(store.ocrEngine) }

    fun setActive(code: String, on: Boolean) {
        val next = if (on) (active + code).distinct() else active - code
        if (next.isEmpty()) { error = "Gardez au moins une langue active."; return }
        active = next; store.ocrLangs = next; error = null
    }

    fun download(code: String, quality: OcrQuality) {
        error = null
        model.scope.launch {
            val failure = langs.download(code, quality)
            if (failure != null) error = "${langs.name(code)} : $failure" else model.ocr.release()
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("Réglages", { model.pop() })
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                SectionLabel("Format de sortie par défaut", Modifier.padding(top = 8.dp, bottom = 4.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(PaperFormat.values().toList()) { f -> Pill(f.label, format == f, { format = f; store.defaultFormat = f }) }
                }
                Text(
                    "« Auto » garde les proportions de la page et l'accroche à l'A4 ou au format carte quand elle en est proche.",
                    color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
                )
            }
            item {
                SectionLabel("Moteur de reconnaissance du texte", Modifier.padding(top = 16.dp, bottom = 4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("PP-OCR embarqué", engine == "ppocr", { engine = "ppocr"; store.ocrEngine = "ppocr" }, enabledHint(model.ppOcr.available))
                    Pill("Tesseract", engine != "ppocr", { engine = "tesseract"; store.ocrEngine = "tesseract" })
                }
                Text(
                    if (model.ppOcr.available) "PP-OCR lit les langues à alphabet latin, le chinois et le japonais sans réglage. Tesseract sert pour les autres langues (modules ci-dessous) et prend le relais si PP-OCR ne lit rien."
                    else "PP-OCR n'est pas inclus dans cette version : Tesseract est utilisé.",
                    color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
                )
            }
            item {
                SectionLabel("Langues installées (Tesseract)", Modifier.padding(top = 16.dp, bottom = 4.dp))
                Text("Cochez les langues à reconnaître dans vos documents. Moins il y en a, plus la reconnaissance est rapide.", color = C.Muted, fontSize = 12.sp)
                error?.let { Text(it, color = C.Danger, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
            }
            items(langs.installed, key = { "i_$it" }) { code ->
                LangRow {
                    Checkbox(checked = code in active, onCheckedChange = { setActive(code, it) })
                    Column(Modifier.weight(1f)) {
                        Text(langs.name(code), color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${langs.quality(code).label} · ${"%.1f".format(langs.sizeMb(code))} Mo" + if (langs.isBundled(code)) " · fournie" else "",
                            color = C.Muted, fontSize = 12.sp,
                        )
                    }
                    val p = langs.progress[code]
                    when {
                        p != null -> Text("${(p * 100).toInt()} %", color = C.Accent, fontSize = 13.sp)
                        langs.quality(code) == OcrQuality.FAST -> Pill("Version précise", false, { download(code, OcrQuality.BEST) })
                        else -> Pill(if (langs.isBundled(code)) "Revenir à rapide" else "Supprimer", false, {
                            langs.delete(code)
                            if (code !in langs.installed && code in active) setActive(code, false)
                            model.scope.launch { model.ocr.release() }
                        })
                    }
                    if (p == null && !langs.isBundled(code) && langs.quality(code) == OcrQuality.FAST) {
                        IconBtn(Ic.Trash, "Supprimer ${langs.name(code)}", {
                            langs.delete(code); if (code in active) setActive(code, false)
                            model.scope.launch { model.ocr.release() }
                        }, outlined = true)
                    }
                }
            }
            item {
                SectionLabel("Langues à télécharger", Modifier.padding(top = 16.dp, bottom = 4.dp))
                Text("Chaque module pèse de 1 à 15 Mo en version rapide. Il reste ensuite disponible hors ligne.", color = C.Muted, fontSize = 12.sp)
            }
            items(Languages.CATALOG.filter { it.code !in langs.installed }, key = { "c_${it.code}" }) { lang ->
                LangRow {
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(lang.name, color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        val p = langs.progress[lang.code]
                        if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp, end = 12.dp), color = C.Accent, trackColor = C.Line)
                    }
                    if (langs.progress[lang.code] == null) {
                        Pill("Rapide", false, { download(lang.code, OcrQuality.FAST) })
                        Pill("Précis", false, { download(lang.code, OcrQuality.BEST) })
                    }
                }
            }
        }
    }
}

@Composable
private fun LangRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(C.Surface).border(1.dp, C.Line, shape).padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), content = content,
    )
}

private fun enabledHint(available: Boolean): Modifier = if (available) Modifier else Modifier.alpha(0.4f)
