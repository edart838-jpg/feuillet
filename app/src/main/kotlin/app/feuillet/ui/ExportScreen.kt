package app.feuillet.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.feuillet.AppModel
import app.feuillet.ExportFormat
import app.feuillet.ExportQuality
import app.feuillet.ExportRequest
import kotlinx.coroutines.launch

@Composable
fun ExportScreen(model: AppModel, docId: String) {
    val store = model.store
    val doc = store.doc(docId)
    if (doc == null) { LaunchedEffect(Unit) { model.pop() }; return }

    var name by remember { mutableStateOf(doc.title) }
    var selected by remember { mutableStateOf(doc.pages.map { it.id }.toSet()) }
    var formats by remember { mutableStateOf(setOf(ExportFormat.PDF_OCR)) }
    var zip by remember { mutableStateOf(true) }
    var quality by remember { mutableStateOf(ExportQuality.BALANCED) }
    var progress by remember { mutableStateOf<Pair<String, Float>?>(null) }
    var result by remember { mutableStateOf<String?>(null) }

    fun run(share: Boolean) {
        if (progress != null) return
        val pages = doc.pages.filter { it.id in selected }
        if (pages.isEmpty() || formats.isEmpty()) { result = "Choisissez au moins une page et un format."; return }
        progress = "Préparation…" to 0f; result = null
        model.scope.launch {
            val outcome = runCatching {
                val files = model.exporter.export(ExportRequest(doc, pages, name.ifBlank { doc.title }, formats, zip, quality)) { label, f -> progress = label to f }
                when {
                    files.isEmpty() -> "Aucun fichier n'a pu être produit."
                    share -> { model.exporter.share(files); null }
                    else -> "${files.size} fichier${if (files.size > 1) "s" else ""} enregistré${if (files.size > 1) "s" else ""} dans ${model.exporter.saveToDownloads(files)}"
                }
            }
            result = outcome.getOrElse { "L'export a échoué : ${it.message ?: "erreur inconnue"}" }
            progress = null
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("Exporter", { model.pop() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Nom du document") }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row {
                    Text("Pages", color = C.Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text("${selected.size} sur ${doc.pages.size} sélectionnée${if (selected.size > 1) "s" else ""}", color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(doc.pages, key = { _, p -> p.id }) { i, p ->
                        val on = p.id in selected
                        val thumb = rememberBitmap(store.thumbFile(docId, p.id), 200, p.rev)
                        val shape = RoundedCornerShape(6.dp)
                        Box(
                            Modifier.size(56.dp, 79.dp).alpha(if (on) 1f else 0.4f).clip(shape).background(C.Surface)
                                .border(2.dp, if (on) C.Accent else C.Line, shape)
                                .clickable(onClickLabel = "Page ${i + 1}", role = Role.Checkbox) { selected = if (on) selected - p.id else selected + p.id },
                        ) {
                            if (thumb != null) Image(thumb, contentDescription = "Page ${i + 1}", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Format · plusieurs choix possibles", color = C.Muted, fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExportFormat.values().forEach { f ->
                        Pill(f.label, f in formats, { formats = if (f in formats) formats - f else formats + f })
                    }
                }
                if (formats.any { it.isImage }) {
                    Row(Modifier.clickable(role = Role.Checkbox) { zip = !zip }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = zip, onCheckedChange = { zip = it })
                        Text("Regrouper les images dans une archive ZIP", color = C.Text, fontSize = 13.sp)
                    }
                }
                if (ExportFormat.PDF_OCR in formats || ExportFormat.TEXT in formats) {
                    Text(
                        if (store.ocrEngine == "ppocr" && model.ppOcr.available) "Texte reconnu par PP-OCR, sur l'appareil. Moteur et langues dans les réglages."
                        else "Texte reconnu en : ${store.ocrLangs.joinToString(", ") { model.languages.name(it) }}. Autres langues dans les réglages.",
                        color = C.Muted, fontSize = 12.sp,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Qualité", color = C.Muted, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExportQuality.values().forEach { q -> Pill(q.label, quality == q, { quality = q }, Modifier.weight(1f), solid = true) }
                }
            }

            progress?.let { (label, f) ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, color = C.Text, fontSize = 13.sp)
                    LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth(), color = C.Accent, trackColor = C.Line)
                }
            }
            result?.let { Text(it, color = C.Text, fontSize = 13.sp) }
        }
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigButton("Enregistrer", { run(false) }, Modifier.weight(1f), primary = false, icon = Ic.Download, enabled = progress == null)
            BigButton("Partager", { run(true) }, Modifier.weight(1f), icon = Ic.Share, enabled = progress == null)
        }
    }
}
