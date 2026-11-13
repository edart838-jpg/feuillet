package app.feuillet

import android.Manifest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.feuillet.core.Filter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/**
 * Essai de bout en bout sur un Android réel (émulé en intégration continue) :
 * import d'une photo de page courbée, détection, rendu, OCR, exports, puis parcours des écrans.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class EndToEndTest {

    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val camera: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun photo(): File {
        val f = File(context.cacheDir, "photo_test.jpg")
        InstrumentationRegistry.getInstrumentation().context.assets.open("photo_courbe.jpg").use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        return f
    }

    private fun waitReady(store: Store, docId: String) {
        val limit = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < limit && store.doc(docId)!!.pages.any { !it.ready }) Thread.sleep(200)
        assertTrue("le rendu n'a pas abouti en 2 minutes", store.doc(docId)!!.pages.all { it.ready })
    }

    @Test
    fun a_scanRenduOcrEtExports() = runBlocking {
        File(context.filesDir, "library.json").delete()
        File(context.filesDir, "docs").deleteRecursively()
        val store = Store(context)
        val engine = Engine(store)
        val languages = Languages(context)
        val ocr = Ocr(languages)
        val exporter = Exporter(context, store, ocr)
        assertEquals(listOf("eng", "fra"), languages.installed)

        val docId = "essai"
        val page = assertNotNullValue(engine.importPhoto(docId, photo(), deleteSource = true))
        assertTrue("la page doit être détectée automatiquement", page.detected)
        waitReady(store, docId)
        val done = store.doc(docId)!!.pages.single()
        assertTrue(store.outFile(docId, done.id).length() > 50_000)
        assertTrue(store.thumbFile(docId, done.id).exists())
        assertTrue("page en portrait attendue : ${done.outW}×${done.outH}", done.outH > done.outW && done.outW > 600)

        // une seconde page, en noir et blanc, pour les exports multipages
        val second = assertNotNullValue(engine.importPhoto(docId, photo(), deleteSource = true))
        store.updatePage(docId, second.id) { it.copy(filter = Filter.BW) }
        engine.render(docId, second.id)
        waitReady(store, docId)

        val doc = store.doc(docId)!!
        val files = exporter.export(
            ExportRequest(doc, doc.pages, "Essai émulateur", ExportFormat.values().toSet(), zipImages = true, quality = ExportQuality.BALANCED),
        ) { _, _ -> }
        val names = files.map { it.name }.sorted()
        assertEquals(
            listOf("Essai émulateur (JPEG).zip", "Essai émulateur (PNG).zip", "Essai émulateur (WebP).zip", "Essai émulateur (image).pdf",
                "Essai émulateur.pdf", "Essai émulateur.tif", "Essai émulateur.txt").sorted(),
            names,
        )
        files.forEach { assertTrue("${it.name} est vide", it.length() > 100) }

        // l'OCR a lu le document et le PDF cherchable contient la couche de texte
        val text = files.first { it.name.endsWith(".txt") }.readText()
        assertTrue("texte reconnu inattendu : ${text.take(300)}", text.contains("Mercier", ignoreCase = true) || text.contains("DEVIS", ignoreCase = true))
        val searchable = files.first { it.name == "Essai émulateur.pdf" }.readBytes().toString(Charsets.ISO_8859_1)
        assertTrue(searchable.startsWith("%PDF-1.4") && searchable.contains("3 Tr") && searchable.contains("/Count 2"))
        val plain = files.first { it.name == "Essai émulateur (image).pdf" }.readBytes().toString(Charsets.ISO_8859_1)
        assertTrue(!plain.contains("3 Tr"))
        assertNotNull(store.doc(docId)!!.pages.first().ocrText)
        assertTrue(store.search("mercier", null).isNotEmpty() || store.search("devis", null).isNotEmpty())

        val where = exporter.saveToDownloads(files.take(1))
        assertTrue(where.contains("Feuillet"))
        ocr.release()
    }

    @Test
    fun b_parcoursDesEcrans() {
        // s'appuie sur le document créé par le premier essai
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitUntilAtLeastOneExists(hasText("Feuillet"), 20_000)
            compose.onNodeWithText("Nouveau scan").assertIsDisplayed()

            // réglages et modules de langues
            compose.onNodeWithContentDescription("Réglages et langues").performClick()
            compose.waitUntilAtLeastOneExists(hasText("Français"), 10_000)
            compose.onNodeWithText("LANGUES INSTALLÉES").assertIsDisplayed()
            compose.onNodeWithContentDescription("Retour").performClick()

            // document, puis réglage d'une page
            compose.waitUntilAtLeastOneExists(hasText("Scan ", substring = true), 10_000)
            compose.onNodeWithText("Scan ", substring = true).performClick()
            compose.waitUntilAtLeastOneExists(hasText("Exporter"), 10_000)
            compose.onNodeWithContentDescription("Page 1").performClick()
            compose.waitUntilAtLeastOneExists(hasText("Courbure corrigée"), 15_000)
            compose.waitUntilAtLeastOneExists(hasContentDescription("Photo d'origine"), 20_000)
            compose.onNodeWithText("Courbure corrigée").performClick()
            compose.waitUntilAtLeastOneExists(hasText("Corriger la courbure"), 5_000)
            compose.onNodeWithText("Corriger la courbure").performClick()
            compose.onNodeWithText("Couleurs et rendu").performClick()
            compose.waitUntilAtLeastOneExists(hasContentDescription("Aperçu de la page"), 60_000)
            compose.onNodeWithText("Gris").performClick()
            compose.onNodeWithText("Terminer").performClick()

            // pivoter une page depuis le document
            compose.waitUntilAtLeastOneExists(hasText("Page suivante"), 10_000)
            compose.onNodeWithContentDescription("Pivoter la page 2").performClick()

            // nouvelle page : après la photo, l'écran des bords s'ouvre ; la page n'entre au document qu'à la validation
            var before = 0
            scenario.onActivity { before = it.model.store.docs.first().pages.size }
            val docId = "essai"
            val fresh = kotlinx.coroutines.runBlocking {
                var engine: Engine? = null
                scenario.onActivity { engine = it.model.engine }
                engine!!.preparePage(docId, photo(), deleteSource = true)
            }
            assertNotNull(fresh)
            scenario.onActivity { it.model.draft = fresh; it.model.push(Screen.Edit(docId, fresh!!.id)) }
            compose.waitUntilAtLeastOneExists(hasText("Nouvelle page"), 10_000)
            compose.waitUntilAtLeastOneExists(hasContentDescription("Photo d'origine"), 20_000)
            scenario.onActivity { assertEquals(before, it.model.store.docs.first().pages.size) }
            // le rendu de la dernière page du document (noir et blanc) est repris par défaut
            assertEquals(Filter.BW, fresh!!.filter)
            compose.onNodeWithText("Ajouter au document").performClick()
            compose.waitUntilAtLeastOneExists(hasText("Page suivante"), 10_000)
            scenario.onActivity { assertEquals(before + 1, it.model.store.docs.first().pages.size) }
            compose.waitUntilAtLeastOneExists(hasContentDescription("Page ${before + 1}"), 10_000)

            // export depuis l'interface
            compose.waitUntilAtLeastOneExists(hasText("Exporter"), 10_000)
            compose.onNodeWithText("Exporter").performClick()
            compose.waitUntilAtLeastOneExists(hasText("Enregistrer"), 10_000)
            compose.onNodeWithText("Enregistrer").performClick()
            compose.waitUntilAtLeastOneExists(hasText("enregistré", substring = true), 240_000)
            compose.onNodeWithContentDescription("Retour").performClick()
            compose.onNodeWithContentDescription("Retour").performClick()

            // écran de capture : l'appareil photo s'ouvre sans plantage
            compose.waitUntilAtLeastOneExists(hasText("Nouveau scan"), 10_000)
            scenario.onActivity { it.model.store.autoCapture = false }      // la scène virtuelle ne doit pas déclencher de prise
            compose.onNodeWithText("Nouveau scan").performClick()
            compose.waitUntilAtLeastOneExists(hasText("Capture auto"), 15_000)
            Thread.sleep(4000)
            compose.onNodeWithContentDescription("Importer depuis la galerie").assertIsDisplayed()
            compose.onNodeWithContentDescription("Fermer").performClick()
            compose.waitUntilAtLeastOneExists(hasText("Nouveau scan"), 10_000)
        }
    }

    private fun <T> assertNotNullValue(v: T?): T { assertNotNull(v); return v!! }
}
