package app.feuillet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import app.feuillet.ui.C
import app.feuillet.ui.CaptureScreen
import app.feuillet.ui.DocumentScreen
import app.feuillet.ui.EditScreen
import app.feuillet.ui.ExportScreen
import app.feuillet.ui.FeuilletTheme
import app.feuillet.ui.LibraryScreen
import app.feuillet.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val model: AppModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FeuilletTheme {
                Box(Modifier.fillMaxSize().background(C.Ink)) {
                    BackHandler(enabled = model.stack.size > 1) { model.pop() }
                    when (val s = model.stack.last()) {
                        Screen.Library -> LibraryScreen(model)
                        is Screen.Capture -> CaptureScreen(model, s.docId)
                        is Screen.Document -> DocumentScreen(model, s.docId)
                        is Screen.Edit -> EditScreen(model, s.docId, s.pageId, s.render)
                        is Screen.Export -> ExportScreen(model, s.docId)
                        Screen.Settings -> SettingsScreen(model)
                    }
                }
            }
        }
    }
}
