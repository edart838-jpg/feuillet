package app.feuillet.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.feuillet.AppModel
import app.feuillet.Page
import app.feuillet.Screen

/** Un document : ses pages en grille, et les actions de rangement (titre, tags, dossier, ordre des pages). */
@Composable
fun DocumentScreen(model: AppModel, docId: String) {
    val store = model.store
    val doc = store.doc(docId)
    if (doc == null) { LaunchedEffect(Unit) { model.pop() }; return }

    var rename by remember { mutableStateOf(false) }
    var tags by remember { mutableStateOf(false) }
    var move by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    var pageMenu by remember { mutableStateOf<Page?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopBar(doc.title, { model.pop() }) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconBtn(Ic.Edit, "Renommer", { rename = true }, outlined = true)
                IconBtn(Ic.Trash, "Supprimer le document", { delete = true }, outlined = true)
            }
        }
        Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(doc.folder ?: "Aucun dossier", doc.folder != null, { move = true })
            Pill(if (doc.tags.isEmpty()) "+ Tags" else doc.tags.joinToString(" · "), doc.tags.isNotEmpty(), { tags = true }, Modifier.weight(1f, fill = false))
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2), modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(doc.pages, key = { _, p -> p.id }) { i, p ->
                val thumb = rememberBitmap(store.thumbFile(docId, p.id), 480, p.rev)
                val shape = RoundedCornerShape(12.dp)
                fun moveTo(j: Int) {
                    val list = doc.pages.toMutableList(); list.removeAt(i); list.add(j, p)
                    store.upsert(doc.copy(pages = list))
                }
                Column(Modifier.fillMaxWidth().clip(shape).background(C.Surface).border(1.dp, C.Line, shape)) {
                    Box(Modifier.fillMaxWidth().aspectRatio(0.78f).clickable(onClickLabel = "Régler la page ${i + 1}") { model.push(Screen.Edit(docId, p.id)) }) {
                        if (thumb != null) Image(thumb, contentDescription = "Page ${i + 1}", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(6.dp))
                        if (!p.ready) CircularProgressIndicator(Modifier.align(Alignment.Center), color = C.Accent)
                        Text(
                            "${i + 1}", color = C.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xE60E1014)).padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        IconBtn(Ic.Up, "Avancer la page ${i + 1}", { if (i > 0) moveTo(i - 1) }, size = 40.dp, radius = 10.dp, background = Color.Transparent, modifier = Modifier.alpha(if (i > 0) 1f else 0.3f))
                        IconBtn(Ic.Down, "Reculer la page ${i + 1}", { if (i < doc.pages.size - 1) moveTo(i + 1) }, size = 40.dp, radius = 10.dp, background = Color.Transparent, modifier = Modifier.alpha(if (i < doc.pages.size - 1) 1f else 0.3f))
                        IconBtn(Ic.Rotate, "Pivoter la page ${i + 1}", {
                            store.updatePage(docId, p.id) { it.copy(rotation = (it.rotation + 1) % 4, ocrText = null, ocrLangs = null) }
                            model.engine.render(docId, p.id)
                        }, size = 40.dp, radius = 10.dp, background = Color.Transparent)
                        IconBtn(Ic.Trash, "Supprimer la page ${i + 1}", { pageMenu = p }, size = 40.dp, radius = 10.dp, background = Color.Transparent)
                    }
                }
            }
            item(span = { GridItemSpan(2) }) {
                Text("Touchez une page pour revoir ses bords et son rendu. Sous chaque page : avancer, reculer, pivoter, supprimer.", color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigButton("Page suivante", { model.push(Screen.Capture(docId)) }, Modifier.weight(1.3f), icon = Ic.Scan)
            BigButton("Exporter", { model.push(Screen.Export(docId)) }, Modifier.weight(1f), primary = false, icon = Ic.Share, enabled = doc.pages.isNotEmpty())
        }
    }

    if (rename) TextDialog("Renommer", "Titre du document", doc.title, "Enregistrer", { rename = false }) { store.upsert(doc.copy(title = it)); rename = false }
    if (tags) TextDialog("Tags", "Séparés par des virgules", doc.tags.joinToString(", "), "Enregistrer", { tags = false }) { v ->
        store.upsert(doc.copy(tags = v.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct())); tags = false
    }
    if (move) FolderPicker(store.folders, { move = false }) { f -> store.upsert(doc.copy(folder = f)); move = false }
    if (delete) ConfirmDialog("Supprimer « ${doc.title} » ?", "Le document et ses ${doc.pages.size} page(s) seront effacés de l'appareil.", "Supprimer", { delete = false }) {
        delete = false; model.pop(); store.deleteDoc(docId)
    }
    pageMenu?.let { p ->
        val i = doc.pages.indexOfFirst { it.id == p.id }
        ConfirmDialog("Supprimer la page ${i + 1} ?", "La page sera retirée du document et effacée de l'appareil.", "Supprimer", { pageMenu = null }) {
            store.deletePage(docId, p.id); pageMenu = null
        }
    }
}
