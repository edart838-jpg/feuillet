package app.feuillet

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope

/** Écrans de l'application. La navigation est une simple pile. */
sealed interface Screen {
    data object Library : Screen
    data class Capture(val docId: String) : Screen
    data class Document(val docId: String) : Screen
    data class Edit(val docId: String, val pageId: String, val render: Boolean = true) : Screen
    data class Export(val docId: String) : Screen
    data object Settings : Screen
}

class AppModel(app: Application) : AndroidViewModel(app) {
    val store = Store(app)
    val languages = Languages(app)
    val ppOcr = PpOcr(app)
    val ocr = Ocr(languages, ppOcr) { store.ocrEngine == "ppocr" }
    val engine = Engine(store)
    val exporter = Exporter(app, store, ocr)

    val stack = mutableStateListOf<Screen>(Screen.Library)

    /** Page photographiée en attente de validation (écrans Bords puis Rendu) ; pas encore dans le document. */
    var draft by mutableStateOf<Page?>(null)

    /** Portée des travaux qui doivent survivre à un changement d'écran (import, export, téléchargement). */
    val scope: CoroutineScope get() = viewModelScope

    init {
        engine.resumePending()
    }

    fun push(s: Screen) { stack.add(s) }
    fun replace(s: Screen) { if (stack.size > 1) stack.removeAt(stack.lastIndex); stack.add(s) }
    fun pop(): Boolean = if (stack.size > 1) { stack.removeAt(stack.lastIndex); true } else false
}
