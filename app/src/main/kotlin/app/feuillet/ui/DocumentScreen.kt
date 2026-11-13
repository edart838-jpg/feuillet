package app.feuillet.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
                Box(
                    Modifier.fillMaxWidth().aspectRatio(0.72f).clip(shape).background(C.Surface).border(1.dp, C.Line, shape)
                        .combinedClickable(onClick = { model.push(Screen.Edit(docId, p.id)) }, onLongClick = { pageMenu = p }),
                ) {
                    if (thumb != null) Image(thumb, contentDescription = "Page ${i + 1}", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(6.dp))
                    if (!p.ready) CircularProgressIndicator(Modifier.align(Alignment.Center), color = C.Accent)
                    Text(
                        "${i + 1}", color = C.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xE60E1014)).padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
            item(span = { GridItemSpan(2) }) {
                Text("Touchez une page pour régler ses bords et son rendu. Appui long : déplacer ou supprimer.", color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigButton("Ajouter", { model.push(Screen.Capture(docId)) }, Modifier.weight(1f), primary = false, icon = Ic.Plus)
            BigButton("Exporter", { model.push(Screen.Export(docId)) }, Modifier.weight(1f), icon = Ic.Share, enabled = doc.pages.isNotEmpty())
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
        fun moveTo(j: Int) {
            val list = doc.pages.toMutableList(); list.removeAt(i); list.add(j, p)
            store.upsert(doc.copy(pages = list)); pageMenu = null
        }
        AlertDialog(
            onDismissRequest = { pageMenu = null },
            title = { Text("Page ${i + 1}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (i > 0) Pill("Monter d'un rang", false, { moveTo(i - 1) }, Modifier.fillMaxWidth())
                    if (i < doc.pages.size - 1) Pill("Descendre d'un rang", false, { moveTo(i + 1) }, Modifier.fillMaxWidth())
                    Pill("Supprimer cette page", false, { store.deletePage(docId, p.id); pageMenu = null }, Modifier.fillMaxWidth())
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pageMenu = null }) { Text("Annuler") } },
        )
    }
}
