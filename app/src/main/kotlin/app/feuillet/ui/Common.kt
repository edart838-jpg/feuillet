package app.feuillet.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/** Palette : fond encre, accent jaune-vert (lisible sur le papier comme sur le fond sombre). */
object C {
    val Ink = Color(0xFF0E1014)
    val Deep = Color(0xFF08090B)
    val Surface = Color(0xFF181B21)
    val Line = Color(0xFF2A2E37)
    val Text = Color(0xFFF2F3EE)
    val Muted = Color(0xFFA3A9B4)
    val Accent = Color(0xFFD7F64A)
    val OnAccent = Color(0xFF14170A)
    val AccentDim = Color(0xFF252A1A)
    val Danger = Color(0xFFFF8A7A)
}

@Composable
fun FeuilletTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.Accent, onPrimary = C.OnAccent, secondary = C.Accent, onSecondary = C.OnAccent,
            background = C.Ink, onBackground = C.Text, surface = C.Surface, onSurface = C.Text,
            surfaceVariant = C.Surface, onSurfaceVariant = C.Muted, outline = C.Line, error = C.Danger,
        ),
        content = content,
    )
}

/** Icônes au trait (grille 24), dessinées à partir de chemins SVG. */
object Ic {
    private fun make(vararg paths: String): ImageVector {
        val b = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        for (d in paths) {
            b.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                stroke = SolidColor(Color.White), strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    val Back = make("M15 5l-7 7 7 7")
    val Close = make("M6 6l12 12M18 6L6 18")
    val Search = make("M4 11a7 7 0 1 0 14 0a7 7 0 1 0 -14 0", "M20 20l-3.5-3.5")
    val Folder = make("M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z")
    val Settings = make("M5 6h9M18 6h1M5 12h2M11 12h8M5 18h7M16 18h3", "M14 6a2 2 0 1 0 4 0a2 2 0 1 0 -4 0", "M7 12a2 2 0 1 0 4 0a2 2 0 1 0 -4 0", "M12 18a2 2 0 1 0 4 0a2 2 0 1 0 -4 0")
    val Scan = make("M4 8V5a1 1 0 0 1 1-1h3M16 4h3a1 1 0 0 1 1 1v3M20 16v3a1 1 0 0 1-1 1h-3M8 20H5a1 1 0 0 1-1-1v-3M12 9v6M9 12h6")
    val Flash = make("M13 3L5 14h6l-1 7 8-11h-6z")
    val Curve = make("M3 8c5-4 13-4 18 0M3 17h18M12 9v5M9.5 12l2.5 2.5 2.5-2.5")
    val Detect = make("M4 9V5h4M20 9V5h-4M4 15v4h4M20 15v4h-4", "M10 12a2 2 0 1 0 4 0a2 2 0 1 0 -4 0")
    val Rotate = make("M20 12a8 8 0 1 1-3-6.2M20 4v5h-5")
    val Plus = make("M12 5v14M5 12h14")
    val Check = make("M5 12.5l4.5 4.5L19 7.5")
    val Share = make("M12 15V4M8 8l4-4 4 4M5 13v5a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-5")
    val Trash = make("M5 7h14M10 7V5h4v2M7 7l1 12h8l1-12")
    val More = make("M12 6v.01M12 12v.01M12 18v.01")
    val Image = make("M4 6a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2z", "M4 16l4.5-4.5 4 4 3-3L20 17", "M14 8.5a1 1 0 1 0 2 0a1 1 0 1 0 -2 0")
    val Edit = make("M4 20h4L19 9l-4-4L4 16z")
    val Download = make("M12 4v11M8 11l4 4 4-4M5 20h14")
    val Up = make("M12 19V5M6 11l6-6 6 6")
    val Down = make("M12 5v14M6 13l6 6 6-6")
    val Tag = make("M4 4h7l9 9-7 7-9-9z", "M8 8v.01")
}

@Composable
fun IconBtn(
    icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    size: Dp = 44.dp, radius: Dp = 22.dp, active: Boolean = false, background: Color = C.Surface, outlined: Boolean = false,
) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(radius))
            .background(if (active) C.Accent else background)
            .then(if (outlined && !active) Modifier.border(1.dp, C.Line, RoundedCornerShape(radius)) else Modifier)
            .clickable(onClickLabel = description, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = if (active) C.OnAccent else C.Text, modifier = Modifier.size(22.dp))
    }
}

/** Pastille de choix (dossier, format, filtre…). */
@Composable
fun Pill(
    text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, solid: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(22.dp)
    val bg = when { selected && solid -> C.Accent; selected -> C.AccentDim; else -> C.Surface }
    val fg = when { selected && solid -> C.OnAccent; selected -> C.Accent; else -> C.Text }
    Box(
        modifier.defaultMinSize(minHeight = 44.dp).clip(shape).background(bg)
            .border(if (selected && !solid) 2.dp else 1.dp, if (selected) C.Accent else C.Line, shape)
            .combinedClickable(role = Role.Button, onLongClick = onLongClick, onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = fg, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
    }
}

@Composable
fun BigButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = true,
    icon: ImageVector? = null, enabled: Boolean = true, height: Dp = 56.dp,
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier.height(height).alpha(if (enabled) 1f else 0.45f).clip(shape)
            .background(if (primary) C.Accent else C.Surface)
            .then(if (primary) Modifier else Modifier.border(1.dp, C.Line, shape))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = if (primary) C.OnAccent else C.Text, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, color = if (primary) C.OnAccent else C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier = modifier, color = C.Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.2.sp)
}

/** Barre de titre : retour, titre, action éventuelle à droite. */
@Composable
fun TopBar(title: String, onBack: () -> Unit, action: @Composable () -> Unit = {}) {
    Row(
        Modifier.padding(PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBtn(Ic.Back, "Retour", onBack)
        Text(
            title, color = C.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
        )
        action()
    }
}

/** Charge une image depuis un fichier, réduite à [maxSide]. [key] force le rechargement quand le fichier change. */
@Composable
fun rememberBitmap(file: File?, maxSide: Int, key: Any? = null): ImageBitmap? {
    val state = produceState<ImageBitmap?>(null, file?.path, maxSide, key) {
        value = if (file == null) null else withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                var sample = 1
                while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
    }
    return state.value
}
