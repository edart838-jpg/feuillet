package app.feuillet.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.feuillet.AppModel
import app.feuillet.Page
import app.feuillet.core.Detector
import app.feuillet.core.Enhancer
import app.feuillet.core.Filter
import app.feuillet.core.Outline
import app.feuillet.core.PaperFormat
import app.feuillet.core.Raster
import app.feuillet.toBitmap
import app.feuillet.toRaster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Réglage d'une page : onglet « Bords » (coins, courbure, format) et onglet « Rendu » (filtres, améliorations).
 * Les changements sont enregistrés, et la page recalculée, en quittant l'écran ou en changeant de page.
 */
@Composable
fun EditScreen(model: AppModel, docId: String, pageId: String, startOnRender: Boolean) {
    val store = model.store
    val doc = store.doc(docId)
    val stored = doc?.pages?.firstOrNull { it.id == pageId }
    if (doc == null || stored == null) { LaunchedEffect(Unit) { model.pop() }; return }
    val index = doc.pages.indexOfFirst { it.id == pageId }

    var page by remember(pageId) { mutableStateOf(stored) }
    var renderTab by remember(pageId) { mutableStateOf(startOnRender) }
    val scope = rememberCoroutineScope()

    // photo d'origine réduite : sert à l'affichage des bords et à l'aperçu du rendu
    var source by remember(pageId) { mutableStateOf<Raster?>(null) }
    var sourceImage by remember(pageId) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(pageId) {
        withContext(Dispatchers.IO) {
            model.engine.decode(store.srcFile(docId, pageId), 1600)?.let { bmp ->
                source = bmp.toRaster(); sourceImage = bmp.asImageBitmap()
            }
        }
    }

    fun commit() {
        val before = store.doc(docId)?.pages?.firstOrNull { it.id == pageId } ?: return
        val now = page
        if (now.copy(rev = before.rev, ready = before.ready, outW = before.outW, outH = before.outH, widthMm = before.widthMm, heightMm = before.heightMm,
                ocrText = before.ocrText, ocrLangs = before.ocrLangs) == before) return
        val geometry = now.geometryDiffers(before)
        store.updatePage(docId, pageId) { cur ->
            now.copy(rev = cur.rev, ready = cur.ready, outW = cur.outW, outH = cur.outH, widthMm = cur.widthMm, heightMm = cur.heightMm,
                ocrText = if (geometry) null else cur.ocrText, ocrLangs = if (geometry) null else cur.ocrLangs)
        }
        model.engine.render(docId, pageId)
    }

    fun leave() { commit(); model.pop() }
    BackHandler { leave() }

    Column(Modifier.fillMaxSize()) {
        TopBar("Page ${index + 1} sur ${doc.pages.size}", { leave() }) {
            val next = doc.pages.getOrNull(index + 1)
            Pill(if (next != null) "Suivant" else "Terminer", true, {
                commit()
                if (next != null) model.replace(app.feuillet.Screen.Edit(docId, next.id, renderTab)) else model.pop()
            }, solid = true)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Bords et courbure", !renderTab, { renderTab = false }, Modifier.weight(1f))
            Pill("Couleurs et rendu", renderTab, { renderTab = true }, Modifier.weight(1f))
        }
        if (!renderTab) {
            EdgesTab(page, sourceImage, onChange = { page = it }, onRedetect = {
                val src = source ?: return@EdgesTab
                scope.launch {
                    val found = withContext(Dispatchers.Default) { Detector.detect(src) }
                    val o = found ?: Outline.fullFrame()
                    page = page.copy(corners = o.corners.toList(), curves = o.curves.toList(), detected = found != null)
                }
            })
        } else {
            RenderTab(model, page, source, onChange = { page = it }, onApplyAll = {
                commit()
                val cur = page
                store.doc(docId)?.pages?.forEach { other ->
                    if (other.id != pageId) {
                        store.updatePage(docId, other.id) {
                            it.copy(filter = cur.filter, brightness = cur.brightness, contrast = cur.contrast, deshadow = cur.deshadow, sharpen = cur.sharpen, ai = cur.ai)
                        }
                        model.engine.render(docId, other.id)
                    }
                }
            })
        }
    }
}

/** Rectangle occupé par une image affichée « entière et centrée » dans une zone. */
private fun fitRect(areaW: Float, areaH: Float, imgW: Int, imgH: Int): Rect {
    val a = imgW.toFloat() / imgH
    val w: Float; val h: Float
    if (areaW / areaH > a) { h = areaH; w = areaH * a } else { w = areaW; h = areaW / a }
    return Rect((areaW - w) / 2, (areaH - h) / 2, (areaW + w) / 2, (areaH + h) / 2)
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.EdgesTab(page: Page, image: ImageBitmap?, onChange: (Page) -> Unit, onRedetect: () -> Unit) {
    // poignée en cours de déplacement : 0..3 coins, 4..7 milieux de bords
    var dragging by remember { mutableStateOf(-1) }
    val current by androidx.compose.runtime.rememberUpdatedState(page)
    val update by androidx.compose.runtime.rememberUpdatedState(onChange)

    Box(Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp).background(Color.Black)) {
        if (image == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = C.Accent)
        } else {
            Image(image, contentDescription = "Photo d'origine", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            Canvas(
                Modifier.fillMaxSize().pointerInput(image) {
                    detectDragGestures(
                        onDragStart = { pos ->
                            val r = fitRect(size.width.toFloat(), size.height.toFloat(), image.width, image.height)
                            val p = current
                            val o = p.outline()
                            var best = -1; var bd = 56.dp.toPx()
                            for (k in 0 until 4) {
                                val d = hypot(r.left + p.corners[2 * k] * r.width - pos.x, r.top + p.corners[2 * k + 1] * r.height - pos.y)
                                if (d < bd) { bd = d; best = k }
                            }
                            if (p.useCurves) for (e in 0 until 4) {
                                val mid = o.edgePolyline(e, 2, true, p.strength)
                                val d = hypot(r.left + mid[2] * r.width - pos.x, r.top + mid[3] * r.height - pos.y)
                                if (d < bd) { bd = d; best = 4 + e }
                            }
                            dragging = best
                        },
                        onDragEnd = { dragging = -1 },
                        onDragCancel = { dragging = -1 },
                        onDrag = { change, _ ->
                            val h = dragging
                            if (h >= 0) {
                                change.consume()
                                val r = fitRect(size.width.toFloat(), size.height.toFloat(), image.width, image.height)
                                val nx = ((change.position.x - r.left) / r.width).coerceIn(0f, 1f)
                                val ny = ((change.position.y - r.top) / r.height).coerceIn(0f, 1f)
                                val p = current
                                if (h < 4) {
                                    val c = p.corners.toMutableList(); c[2 * h] = nx; c[2 * h + 1] = ny
                                    update(p.copy(corners = c))
                                } else {
                                    val o = p.outline()
                                    val off = o.offsetFromEdge(h - 4, nx, ny) / p.strength.coerceAtLeast(0.2f)
                                    update(p.copy(curves = o.withMidOffset(h - 4, off).curves.toList()))
                                }
                            }
                        },
                    )
                },
            ) {
                val r = fitRect(size.width, size.height, image.width, image.height)
                val o = page.outline()
                val path = Path()
                for (edge in 0 until 4) {
                    val pts = o.edgePolyline(edge, 24, page.useCurves, page.strength)
                    val order = if (edge >= 2) (24 downTo 0) else (0..24)
                    for (i in order) {
                        val x = r.left + pts[2 * i] * r.width; val y = r.top + pts[2 * i + 1] * r.height
                        if (edge == 0 && i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                }
                path.close()
                drawPath(path, C.Accent.copy(alpha = 0.10f))
                drawPath(path, C.Accent, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                if (page.useCurves) for (e in 0 until 4) {
                    val mid = o.edgePolyline(e, 2, true, page.strength)
                    val c = Offset(r.left + mid[2] * r.width, r.top + mid[3] * r.height)
                    drawCircle(C.Ink, 9.dp.toPx(), c); drawCircle(C.Accent, 6.dp.toPx(), c)
                }
                for (k in 0 until 4) {
                    val c = Offset(r.left + page.corners[2 * k] * r.width, r.top + page.corners[2 * k + 1] * r.height)
                    drawCircle(C.Ink, 13.dp.toPx(), c)
                    drawCircle(C.Accent, 13.dp.toPx(), c, style = Stroke(3.dp.toPx()))
                }
                // loupe : agrandit la zone sous le doigt, dans le coin opposé
                val h = dragging
                if (h in 0..3) {
                    val cx = page.corners[2 * h]; val cy = page.corners[2 * h + 1]
                    val rad = 52.dp.toPx()
                    val center = Offset(if (cx < 0.5f) size.width - rad - 14.dp.toPx() else rad + 14.dp.toPx(), rad + 14.dp.toPx())
                    val srcHalf = (image.width * 0.045f).roundToInt().coerceAtLeast(8)
                    val sx = (cx * image.width).roundToInt(); val sy = (cy * image.height).roundToInt()
                    val circle = Path().apply { addOval(Rect(center, rad)) }
                    clipPath(circle) {
                        drawRect(Color.Black, Offset(center.x - rad, center.y - rad), androidx.compose.ui.geometry.Size(2 * rad, 2 * rad))
                        val left = (sx - srcHalf).coerceIn(0, (image.width - 2 * srcHalf).coerceAtLeast(0))
                        val top = (sy - srcHalf).coerceIn(0, (image.height - 2 * srcHalf).coerceAtLeast(0))
                        drawImage(
                            image, srcOffset = IntOffset(left, top),
                            srcSize = IntSize(minOf(2 * srcHalf, image.width), minOf(2 * srcHalf, image.height)),
                            dstOffset = IntOffset((center.x - rad).roundToInt(), (center.y - rad).roundToInt()),
                            dstSize = IntSize((2 * rad).roundToInt(), (2 * rad).roundToInt()),
                        )
                        // repère du coin, placé là où il se trouve dans la zone agrandie
                        val k = rad / srcHalf
                        val mark = Offset(center.x - rad + (sx - left) * k, center.y - rad + (sy - top) * k)
                        drawLine(C.Accent, Offset(mark.x - 10.dp.toPx(), mark.y), Offset(mark.x + 10.dp.toPx(), mark.y), 2.dp.toPx())
                        drawLine(C.Accent, Offset(mark.x, mark.y - 10.dp.toPx()), Offset(mark.x, mark.y + 10.dp.toPx()), 2.dp.toPx())
                    }
                    drawCircle(C.Accent, rad, center, style = Stroke(2.dp.toPx()))
                }
            }
            if (!page.detected) {
                Text(
                    "Bords non trouvés : placez les quatre coins à la main.", color = C.Text, fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xD90E1014)).padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }

    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton(
                if (page.useCurves) "Courbure corrigée" else "Corriger la courbure", { onChange(page.copy(useCurves = !page.useCurves)) },
                Modifier.weight(1f), primary = page.useCurves, icon = Ic.Curve,
            )
            IconBtn(Ic.Detect, "Redétecter les bords", onRedetect, size = 56.dp, radius = 16.dp, outlined = true)
            IconBtn(Ic.Rotate, "Pivoter de 90 degrés", { onChange(page.copy(rotation = (page.rotation + 1) % 4)) }, size = 56.dp, radius = 16.dp, outlined = true)
        }
        if (page.useCurves) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Force de l'aplanissement", color = C.Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("${(page.strength * 100).roundToInt()} %", color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            Slider(value = page.strength, onValueChange = { onChange(page.copy(strength = it)) }, valueRange = 0f..1.5f, modifier = Modifier.height(36.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Format de sortie", color = C.Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            if (page.rotation != 0) Text("pivoté de ${page.rotation * 90}°", color = C.Muted, fontSize = 12.sp)
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(PaperFormat.values().toList()) { f -> Pill(f.label, page.format == f, { onChange(page.copy(format = f)) }) }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.RenderTab(
    model: AppModel, page: Page, source: Raster?, onChange: (Page) -> Unit, onApplyAll: () -> Unit,
) {
    var flat by remember { mutableStateOf<Raster?>(null) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var original by remember { mutableStateOf<ImageBitmap?>(null) }
    var thumbs by remember { mutableStateOf(mapOf<Filter, ImageBitmap>()) }
    var showOriginal by remember { mutableStateOf(false) }
    var applied by remember { mutableStateOf(false) }

    // 1. géométrie : recalculée seulement quand les bords, la courbure, le format ou la rotation changent
    LaunchedEffect(source, page.corners, page.curves, page.useCurves, page.strength, page.format, page.rotation) {
        val src = source ?: return@LaunchedEffect
        val f = withContext(Dispatchers.Default) { runCatching { model.engine.flatten(src, page, 1100).image }.getOrNull() }
        flat = f
        original = f?.let { withContext(Dispatchers.Default) { it.toBitmap().asImageBitmap() } }
    }
    // 2. rendu : recalculé à chaque changement de filtre (avec un court délai pendant qu'un curseur bouge)
    LaunchedEffect(flat, page.filter, page.brightness, page.contrast, page.deshadow, page.sharpen, page.ai) {
        val f = flat ?: return@LaunchedEffect
        delay(60)
        preview = withContext(Dispatchers.Default) { Enhancer.apply(f, page.enhance()).toBitmap().asImageBitmap() }
    }
    // 3. vignettes des filtres
    LaunchedEffect(flat, page.deshadow, page.sharpen, page.ai) {
        val f = flat ?: return@LaunchedEffect
        thumbs = withContext(Dispatchers.Default) {
            val small = f.downscale(170)
            Filter.values().associateWith { Enhancer.apply(small, page.enhance().copy(filter = it, brightness = 0f, contrast = 0f)).toBitmap().asImageBitmap() }
        }
    }

    Box(
        Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp).background(C.Deep).pointerInput(Unit) {
            detectTapGestures(onPress = { showOriginal = true; tryAwaitRelease(); showOriginal = false })
        },
        contentAlignment = Alignment.Center,
    ) {
        val shown = if (showOriginal) original else preview
        if (shown == null) CircularProgressIndicator(color = C.Accent)
        else Image(shown, contentDescription = if (showOriginal) "Page avant amélioration" else "Aperçu de la page", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(12.dp))
        Text(
            if (showOriginal) "Avant amélioration" else "Maintenir : voir l'original", color = C.Text, fontSize = 12.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xE60E1014)).padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(Filter.values().toList()) { f ->
                val sel = page.filter == f
                Column(
                    Modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { onChange(page.copy(filter = f)) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        Modifier.size(62.dp, 78.dp).clip(RoundedCornerShape(10.dp)).background(C.Surface)
                            .border(if (sel) 2.dp else 1.dp, if (sel) C.Accent else C.Line, RoundedCornerShape(10.dp)),
                    ) {
                        thumbs[f]?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    }
                    Text(f.label, color = if (sel) C.Accent else C.Text, fontSize = 12.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium)
                }
            }
        }
        val tunable = page.filter != Filter.ORIGINAL
        SectionLabel("Améliorations · calculées sur l'appareil", Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp))
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Sans ombres", page.deshadow && tunable && page.filter != Filter.PHOTO, { onChange(page.copy(deshadow = !page.deshadow)) }, Modifier.weight(1f))
            Pill(
                if (page.filter == Filter.PHOTO) "Photo nette IA" else "Texte net IA", page.ai && tunable,
                { onChange(page.copy(ai = !page.ai)) }, Modifier.weight(1f),
            )
            Pill("Accentuer", page.sharpen && !page.ai && tunable && page.filter != Filter.BW, { onChange(if (page.ai) page.copy(ai = false, sharpen = true) else page.copy(sharpen = !page.sharpen)) }, Modifier.weight(1f))
        }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Luminosité", color = C.Muted, fontSize = 13.sp, modifier = Modifier.width(84.dp))
                Slider(value = page.brightness, onValueChange = { onChange(page.copy(brightness = it)) }, valueRange = -1f..1f, modifier = Modifier.weight(1f).height(40.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Contraste", color = C.Muted, fontSize = 13.sp, modifier = Modifier.width(84.dp))
                Slider(value = page.contrast, onValueChange = { onChange(page.copy(contrast = it)) }, valueRange = -1f..1f, modifier = Modifier.weight(1f).height(40.dp))
            }
        }
        BigButton(
            if (applied) "Appliqué à toutes les pages" else "Appliquer ce rendu à toutes les pages", { onApplyAll(); applied = true },
            Modifier.fillMaxWidth().padding(horizontal = 20.dp), primary = false, height = 48.dp,
        )
        Spacer(Modifier.height(4.dp))
    }
}
