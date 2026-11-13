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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.feuillet.AppModel
import app.feuillet.Doc
import app.feuillet.Screen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private fun dateLabel(t: Long): String {
    val day = 86_400_000L
    val today = System.currentTimeMillis() / day
    return when (t / day) {
        today -> "aujourd'hui " + SimpleDateFormat("HH:mm", Locale.FRENCH).format(Date(t))
        today - 1 -> "hier"
        else -> SimpleDateFormat("d MMM yyyy", Locale.FRENCH).format(Date(t))
    }
}

@Composable
fun LibraryScreen(model: AppModel) {
    val store = model.store
    var query by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf<String?>(null) }
    var selection by remember { mutableStateOf(setOf<String>()) }
    var newFolder by remember { mutableStateOf(false) }
    var moveDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    var folderToDelete by remember { mutableStateOf<String?>(null) }
    val docs = store.search(query, folder)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Feuillet", color = C.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconBtn(Ic.Settings, "Réglages et langues", { model.push(Screen.Settings) }, radius = 12.dp, outlined = true)
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("Rechercher un titre, un tag, du texte…", fontSize = 14.sp, color = C.Muted) },
                leadingIcon = { Icon(Ic.Search, contentDescription = null, tint = C.Muted, modifier = Modifier.size(18.dp)) },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            )
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Pill("Tous · ${store.docs.size}", folder == null, { folder = null }, solid = true) }
                items(store.folders) { f ->
                    Pill("$f · ${store.docs.count { it.folder == f }}", folder == f, { folder = f }, solid = true, onLongClick = { folderToDelete = f })
                }
                item { Pill("+ Dossier", false, { newFolder = true }) }
            }
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(if (query.isBlank()) "Récents" else "${docs.size} résultat${if (docs.size > 1) "s" else ""}", Modifier.weight(1f))
                Text(if (selection.isEmpty()) "Appui long : sélection" else "${selection.size} sélectionné${if (selection.size > 1) "s" else ""}", color = C.Muted, fontSize = 12.sp)
            }
            if (docs.isEmpty()) {
                Column(Modifier.fillMaxWidth().weight(1f).padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(
                        if (store.docs.isEmpty()) "Aucun scan pour l'instant" else "Aucun document ne correspond",
                        color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (store.docs.isEmpty()) "Touchez « Nouveau scan » et visez une page : ses bords sont repérés tout seuls."
                        else "Essayez un autre mot ou un autre dossier.",
                        color = C.Muted, fontSize = 14.sp, textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 110.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(docs, key = { it.id }) { d ->
                        DocRow(model, d, selected = d.id in selection,
                            onClick = {
                                if (selection.isEmpty()) model.push(Screen.Document(d.id))
                                else selection = if (d.id in selection) selection - d.id else selection + d.id
                            },
                            onLongClick = { selection = if (d.id in selection) selection - d.id else selection + d.id })
                    }
                }
            }
        }

        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(C.Ink).padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selection.isEmpty()) {
                BigButton("Nouveau scan", { model.push(Screen.Capture(UUID.randomUUID().toString().take(12))) }, Modifier.weight(1f), icon = Ic.Scan)
            } else {
                IconBtn(Ic.Close, "Annuler la sélection", { selection = emptySet() }, size = 56.dp, radius = 18.dp, outlined = true)
                BigButton("Déplacer", { moveDialog = true }, Modifier.weight(1f), primary = false, icon = Ic.Folder)
                BigButton("Supprimer", { deleteDialog = true }, Modifier.weight(1f), primary = false, icon = Ic.Trash)
            }
        }
    }

    if (newFolder) TextDialog("Nouveau dossier", "Nom du dossier", "", "Créer", { newFolder = false }) { store.addFolder(it); newFolder = false }
    if (moveDialog) FolderPicker(store.folders, { moveDialog = false }) { f ->
        selection.forEach { id -> store.doc(id)?.let { store.upsert(it.copy(folder = f)) } }
        selection = emptySet(); moveDialog = false
    }
    if (deleteDialog) ConfirmDialog(
        "Supprimer ${selection.size} document${if (selection.size > 1) "s" else ""} ?", "Les scans et leurs pages seront effacés de l'appareil.",
        "Supprimer", { deleteDialog = false },
    ) { selection.forEach { store.deleteDoc(it) }; selection = emptySet(); deleteDialog = false }
    folderToDelete?.let { f ->
        ConfirmDialog("Supprimer le dossier « $f » ?", "Ses documents ne sont pas effacés : ils reviennent dans « Tous ».", "Supprimer le dossier", { folderToDelete = null }) {
            store.deleteFolder(f); if (folder == f) folder = null; folderToDelete = null
        }
    }
}

@Composable
private fun DocRow(model: AppModel, d: Doc, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val first = d.pages.firstOrNull()
    val thumb = rememberBitmap(first?.let { model.store.thumbFile(d.id, it.id) }, 240, first?.rev)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(if (selected) C.AccentDim else C.Surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) C.Accent else C.Line, shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(54.dp, 76.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFFECEBE4))) {
            if (thumb != null) Image(thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(d.title, color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${d.pages.size} page${if (d.pages.size > 1) "s" else ""} · ${dateLabel(d.modified)}", color = C.Muted, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                d.folder?.let { Chip(it, accent = false) }
                d.tags.take(2).forEach { Chip(it, accent = true) }
                if (d.pages.any { it.ocrText != null }) Chip("texte reconnu", accent = false)
            }
        }
    }
}

@Composable
fun Chip(text: String, accent: Boolean) {
    Text(
        text, color = if (accent) C.Accent else Color(0xFFC9CED6), fontSize = 11.sp, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (accent) C.AccentDim else Color(0xFF22262E)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
fun TextDialog(title: String, label: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true, label = { Text(label) }) },
        confirmButton = { TextButton(onClick = { if (value.isNotBlank()) onConfirm(value.trim()) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = C.Danger) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

/** Choix d'un dossier de destination (ou « Aucun dossier »). */
@Composable
fun FolderPicker(folders: List<String>, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Déplacer vers") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Aucun dossier", false, { onPick(null) }, Modifier.fillMaxWidth())
                folders.forEach { f -> Pill(f, false, { onPick(f) }, Modifier.fillMaxWidth()) }
                if (folders.isEmpty()) Text("Créez d'abord un dossier depuis l'accueil (« + Dossier »).", color = C.Muted, fontSize = 13.sp)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
