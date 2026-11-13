package app.feuillet.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import app.feuillet.AppModel
import app.feuillet.Screen
import app.feuillet.core.Detector
import app.feuillet.core.Outline
import app.feuillet.core.Plane
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

/** Réduit le plan de luminance de la caméra (~240 px de large) et le remet à l'endroit. */
private fun lumaPlane(image: ImageProxy): Plane {
    val plane = image.planes[0]
    val buf = plane.buffer
    val stride = plane.rowStride
    val f = max(1, image.width / 240)
    val w = image.width / f; val h = image.height / f
    val small = FloatArray(w * h)
    for (y in 0 until h) for (x in 0 until w) {
        var s = 0
        // moyenne sur une croix de 4 points du bloc : suffisant pour la détection, très rapide
        val bx = x * f; val by = y * f; val half = f / 2
        s += buf.get(by * stride + bx).toInt() and 255
        s += buf.get(by * stride + bx + half).toInt() and 255
        s += buf.get((by + half) * stride + bx).toInt() and 255
        s += buf.get((by + half) * stride + bx + half).toInt() and 255
        small[y * w + x] = s / 4f
    }
    return when (image.imageInfo.rotationDegrees) {
        90 -> Plane(h, w, FloatArray(w * h) { i -> val nx = i % h; val ny = i / h; small[(h - 1 - nx) * w + ny] })
        180 -> Plane(w, h, FloatArray(w * h) { i -> small[w * h - 1 - i] })
        270 -> Plane(h, w, FloatArray(w * h) { i -> val nx = i % h; val ny = i / h; small[nx * w + (w - 1 - ny)] })
        else -> Plane(w, h, small)
    }
}

/** Plus grand déplacement d'un coin entre deux contours (en fraction de l'image). */
private fun cornerShift(a: Outline, b: Outline): Float {
    var m = 0f
    for (i in 0 until 8) m = max(m, abs(a.corners[i] - b.corners[i]))
    return m
}

@Composable
fun CaptureScreen(model: AppModel, docId: String) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val store = model.store
    val doc = store.doc(docId)

    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var torch by remember { mutableStateOf(false) }
    var outline by remember { mutableStateOf<Outline?>(null) }
    var aspect by remember { mutableStateOf(0.75f) }
    var stable by remember { mutableIntStateOf(0) }
    var busy by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var focusAt by remember { mutableStateOf<Offset?>(null) }

    val capture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).setTargetAspectRatio(AspectRatio.RATIO_4_3).build()
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    var camera by remember { mutableStateOf<Camera?>(null) }
    // état du déclenchement automatique, manipulé par le fil d'analyse
    val auto2 = remember { object { var last: Outline? = null; var count = 0; var armed = true; var lastShot: Outline? = null; var shooting = false } }

    /** Quitte le viseur : vers le document en construction s'il a déjà des pages, sinon retour simple. */
    fun finish() {
        val d = store.doc(docId)
        model.pop()
        if (d != null && d.pages.isNotEmpty() && model.stack.last() != Screen.Document(docId)) model.push(Screen.Document(docId))
    }

    fun shoot() {
        if (auto2.shooting || busy > 0) return
        auto2.shooting = true; busy++
        val file = File(context.cacheDir, "capture_${System.nanoTime()}.jpg")
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(), executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    auto2.shooting = false; auto2.lastShot = auto2.last; auto2.armed = false; auto2.count = 0
                    model.scope.launch {
                        // une page à la fois : la photo s'ouvre aussitôt sur l'écran des bords
                        val page = model.engine.preparePage(docId, file, deleteSource = true)
                        busy--
                        if (page == null) message = "Photo illisible, réessayez."
                        else if (model.stack.last() == Screen.Capture(docId)) { model.draft = page; model.replace(Screen.Edit(docId, page.id)) }
                    }
                }

                override fun onError(e: ImageCaptureException) {
                    auto2.shooting = false; busy--
                    message = "La photo n'a pas pu être prise."
                }
            },
        )
    }

    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        for (uri in uris) {
            busy++
            model.scope.launch {
                val input = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
                val page = if (input != null) model.engine.importStream(docId, input, context.cacheDir) else null
                busy--
                if (page == null) message = "Une image n'a pas pu être importée."
                else if (busy == 0 && model.stack.last() == Screen.Capture(docId)) finish()
            }
        }
    }

    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.Close, "Fermer", { finish() }, background = Color(0xFF1C1F26))
            Spacer(Modifier.weight(1f))
            IconBtn(Ic.Image, "Importer depuis la galerie", { pickImages.launch("image/*") }, background = Color(0xFF1C1F26))
            Spacer(Modifier.width(8.dp))
            IconBtn(Ic.Flash, if (torch) "Éteindre la lampe" else "Allumer la lampe", {
                torch = !torch; camera?.cameraControl?.enableTorch(torch)
            }, active = torch, background = Color(0xFF1C1F26))
        }

        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (granted) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val view = PreviewView(ctx).apply {
                            scaleType = PreviewView.ScaleType.FIT_CENTER
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        }
                        // toucher l'aperçu : mise au point et exposition sur ce point
                        view.setOnTouchListener { v, e ->
                            if (e.action == android.view.MotionEvent.ACTION_UP) {
                                val point = view.meteringPointFactory.createPoint(e.x, e.y)
                                runCatching { camera?.cameraControl?.startFocusAndMetering(androidx.camera.core.FocusMeteringAction.Builder(point).build()) }
                                focusAt = Offset(e.x, e.y)
                                v.performClick()
                            }
                            true
                        }
                        val future = ProcessCameraProvider.getInstance(ctx)
                        future.addListener({
                            val provider = future.get()
                            val preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).build()
                            preview.setSurfaceProvider(view.surfaceProvider)
                            val analysis = ImageAnalysis.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3)
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                            analysis.setAnalyzer(executor) { image ->
                                try {
                                    val plane = lumaPlane(image)
                                    val found = Detector.detectPlane(plane)
                                    val prev = auto2.last
                                    auto2.count = if (found != null && found.confidence >= 0.8f && prev != null && cornerShift(found, prev) < 0.02f) auto2.count + 1 else 0
                                    auto2.last = found
                                    // réarme le déclenchement quand la page change (ou disparaît) après une prise
                                    val shot = auto2.lastShot
                                    if (!auto2.armed && (found == null || shot == null || cornerShift(found, shot) > 0.08f)) auto2.armed = true
                                    outline = found; aspect = plane.w.toFloat() / plane.h; stable = auto2.count
                                } catch (_: Throwable) {
                                } finally {
                                    image.close()
                                }
                            }
                            runCatching {
                                provider.unbindAll()
                                camera = provider.bindToLifecycle(activity, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis)
                            }.onFailure { message = "Appareil photo indisponible." }
                        }, ContextCompat.getMainExecutor(ctx))
                        view
                    },
                )
                val o = outline
                Canvas(Modifier.fillMaxSize()) {
                    focusAt?.let { drawCircle(C.Text, 28.dp.toPx(), it, style = Stroke(2.dp.toPx())) }
                    if (o == null) return@Canvas
                    // l'aperçu est centré sans rognage : on retrouve le rectangle réellement occupé par l'image
                    val vw = size.width; val vh = size.height
                    val iw: Float; val ih: Float
                    if (vw / vh > aspect) { ih = vh; iw = vh * aspect } else { iw = vw; ih = vw / aspect }
                    val ox = (vw - iw) / 2; val oy = (vh - ih) / 2
                    val path = Path()
                    for (edge in 0 until 4) {
                        val pts = o.edgePolyline(edge, 16, true, 1f)
                        val ordered = if (edge >= 2) (16 downTo 0) else (0..16)
                        for (i in ordered) {
                            val x = ox + pts[2 * i] * iw; val y = oy + pts[2 * i + 1] * ih
                            if (edge == 0 && i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                    }
                    path.close()
                    drawPath(path, C.Accent.copy(alpha = 0.14f))
                    drawPath(path, C.Accent, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    for (k in 0 until 4) drawCircle(C.Accent, 6.dp.toPx(), Offset(ox + o.corners[2 * k] * iw, oy + o.corners[2 * k + 1] * ih))
                }
                val status = when {
                    busy > 0 -> "Recadrage de la page…"
                    outline == null -> "Cherche la page…"
                    else -> "Page détectée · touchez l'image pour la mise au point"
                }
                Row(
                    Modifier.align(Alignment.TopCenter).padding(top = 16.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xD90E1014)).padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (outline != null) C.Accent else C.Muted))
                    Spacer(Modifier.width(8.dp))
                    Text(status, color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                message?.let {
                    Text(
                        it, color = C.Text, fontSize = 12.sp,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xD90E1014)).padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            } else {
                Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("L'appareil photo n'est pas autorisé", color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text("Feuillet s'en sert uniquement pour photographier vos documents. Vous pouvez aussi importer des images de la galerie.", color = C.Muted, fontSize = 14.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(20.dp))
                    BigButton("Autoriser l'appareil photo", { askPermission.launch(Manifest.permission.CAMERA) })
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 26.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.width(110.dp), verticalAlignment = Alignment.CenterVertically) {
                val last = doc?.pages?.lastOrNull()
                val thumb = rememberBitmap(last?.let { store.thumbFile(docId, it.id) }, 160, last?.rev)
                Box(Modifier.size(44.dp, 60.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF1C1F26))) {
                    if (thumb != null) Image(thumb, contentDescription = "Dernière page scannée", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(8.dp))
                Text("${doc?.pages?.size ?: 0} p.", color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(76.dp).clip(CircleShape).border(4.dp, C.Accent, CircleShape).padding(9.dp).clip(CircleShape)
                    .background(if (granted) C.Text else C.Muted)
                    .clickable(enabled = granted, onClickLabel = "Prendre la page", role = Role.Button) { shoot() },
            )
            Spacer(Modifier.weight(1f))
            Box(Modifier.width(110.dp), contentAlignment = Alignment.CenterEnd) {
                if ((doc?.pages?.size ?: 0) > 0) BigButton(if (busy > 0) "…" else "Document", { finish() }, height = 48.dp, enabled = busy == 0)
            }
        }
    }
}
