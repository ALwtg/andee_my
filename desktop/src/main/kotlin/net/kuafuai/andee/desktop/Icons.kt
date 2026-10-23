package net.kuafuai.andee.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The app's icons, as SVG path data on a 24-unit grid, drawn in the text colour.
 * The outlines are from Lucide (https://lucide.dev, ISC licence), copied as path
 * data; STOP is our own filled square.
 * An emoji is a colour bitmap from whatever font the Mac has, so it ignores the
 * palette and looks different on every machine; a path is the same everywhere
 * and takes the colour it is given.
 *
 * Strokes are 2 units, round-capped and round-joined, as Lucide draws them.
 */
enum class AndeeIcon(val d: String, val filled: Boolean = false) {
    CHAT("M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"),
    DOCUMENT("M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z M14 2v4a2 2 0 0 0 2 2h4 M10 9H8 M16 13H8 M16 17H8"),
    NOTEBOOK("M2 6h4 M2 10h4 M2 14h4 M2 18h4 M6 2h12a2 2 0 0 1 2 2v16a2 2 0 0 1 -2 2h-12a2 2 0 0 1 -2 -2v-16a2 2 0 0 1 2 -2z M9.5 8h5 M9.5 12H16 M9.5 16H14"),
    SPARKLE("M9.937 15.5A2 2 0 0 0 8.5 14.063l-6.135-1.582a.5.5 0 0 1 0-.962L8.5 9.936A2 2 0 0 0 9.937 8.5l1.582-6.135a.5.5 0 0 1 .963 0L14.063 8.5A2 2 0 0 0 15.5 9.937l6.135 1.581a.5.5 0 0 1 0 .964L15.5 14.063a2 2 0 0 0-1.437 1.437l-1.582 6.135a.5.5 0 0 1-.963 0z M20 3v4 M22 5h-4 M4 17v2 M5 18H3"),
    LOCK("M5 11h14a2 2 0 0 1 2 2v7a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-7a2 2 0 0 1 2 -2z M7 11V7a5 5 0 0 1 10 0v4"),
    FACE("M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0 M8 14s1.5 2 4 2 4-2 4-2 M9 9L9.01 9 M15 9L15.01 9"),
    CHECK_CIRCLE("M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0 M0 0m9 12 2 2 4-4"),
    ALERT_CIRCLE("M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0 M12 8L12 12 M12 16L12.01 16"),
    SLIDERS("M21 4L14 4 M10 4L3 4 M21 12L12 12 M8 12L3 12 M21 20L16 20 M12 20L3 20 M14 2L14 6 M8 10L8 14 M16 18L16 22"),
    GRID("M4 4h6a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z M14 4h6a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1h-6a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z M4 13h6a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1v-5a1 1 0 0 1 1-1z M14 13h6a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1h-6a1 1 0 0 1-1-1v-5a1 1 0 0 1 1-1z"),
    LINK("M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71 M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"),
    /** 朋友: two people, the nearer one larger. */
    FRIENDS("M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2 M9 3a4 4 0 1 0 0 8a4 4 0 1 0 0-8z M22 21v-2a4 4 0 0 0-3-3.87 M16 3.13a4 4 0 0 1 0 7.75"),
    PLUS("M5 12h14 M12 5v14"),
    MIC("M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3Z M19 10v2a7 7 0 0 1-14 0v-2 M12 19L12 22"),
    SEND("m5 12 7-7 7 7 M12 19V5"),
    PAPERCLIP("M13.234 20.252 21 12.3 M16 6l-8.414 8.586a2 2 0 0 0 0 2.828 2 2 0 0 0 2.828 0l8.414-8.586a4 4 0 0 0 0-5.656 4 4 0 0 0-5.656 0l-8.415 8.585a6 6 0 1 0 8.486 8.486"),
    IMAGE("M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z M9 7a2 2 0 1 0 0 4a2 2 0 1 0 0-4z M21 15l-3.086-3.086a2 2 0 0 0-2.828 0L6 21"),
    FILE("M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7z M14 2v4a2 2 0 0 0 2 2h4"),
    VOLUME("M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298z M16 9a5 5 0 0 1 0 6 M19.364 18.364a9 9 0 0 0 0-12.728"),
    VOLUME_OFF("M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298z M22 9L16 15 M16 9l6 6"),
    STOP("M8 6h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z", filled = true),
    CLOSE("M18 6 6 18 M0 0m6 6 12 12"),
    CHEVRON("m9 18 6-6-6-6"),
    CHECK("M20 6 9 17l-5-5"),
    ;
}

@Composable
fun Icon(icon: AndeeIcon, tint: Color, size: Dp = 18.dp, modifier: Modifier = Modifier) {
    val path = remember(icon) { PathParser().parsePathString(icon.d).toPath() }
    Canvas(modifier.size(size)) {
        val k = this.size.width / 24f
        withTransform({ scale(k, k, pivot = Offset.Zero) }) {
            if (icon.filled) drawPath(path, tint, style = Fill)
            else drawPath(path, tint, style = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
