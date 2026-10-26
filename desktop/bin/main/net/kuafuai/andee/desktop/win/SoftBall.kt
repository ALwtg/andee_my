package net.kuafuai.andee.desktop.win

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.kuafuai.andee.desktop.MacBall
import net.kuafuai.andee.ui.ball.Mood

/**
 * The ball on Windows, painted by Compose instead of by an OpenGL renderer.
 *
 * **Why not the orb renderer.** Its frames come from an offscreen OpenGL context
 * created through **CGL**, which is a macOS API: on Windows the first frame threw
 * `A required function is missing: CGLGetCurrentContext` on the render thread and
 * left a zero-sized window behind. A WGL context is the faithful fix and is worth
 * doing one day; a ball that exists, drags, taps and changes colour with the
 * conversation is worth doing now.
 *
 * **What this is not.** It is not the character art: the look comes from
 * [Mood], not from the chosen `BallLooks` persona. The face is a lamp — calm,
 * thinking, listening, speaking — and the tray icon is where the app says who it
 * is. Calling it the orb would be the kind of small lie this port keeps paying
 * for.
 */
@Composable
fun SoftBall(size: Dp, modifier: Modifier = Modifier) {
    // Nothing pushes mood changes to us (the renderer's callback is a mac thing), so
    // we ask. Four times a second is invisible to the eye and costs a few reads.
    var mood by remember { mutableStateOf(MacBall.currentMood()) }
    LaunchedEffect(Unit) {
        while (true) {
            mood = MacBall.currentMood()
            delay(250)
        }
    }

    val tint = when (mood) {
        Mood.CALM -> Color(0xFF5B8CFF)
        Mood.THINKING -> Color(0xFFFFB74D)
        Mood.LISTENING -> Color(0xFF4DD0E1)
        Mood.SPEAKING -> Color(0xFFB388FF)
        else -> Color(0xFF5B8CFF)
    }

    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2f
        val c = Offset(this.size.width / 2f, this.size.height / 2f)
        // Body: a radial gradient so it reads as a sphere rather than a disc.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(tint.copy(alpha = 0.98f), tint.copy(alpha = 0.45f)),
                center = Offset(c.x - r * 0.25f, c.y - r * 0.3f),
                radius = r * 1.6f,
            ),
            radius = r,
            center = c,
        )
        // Rim, so the ball still reads against a bright wallpaper.
        drawCircle(
            color = Color.White.copy(alpha = 0.30f),
            radius = r,
            center = c,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = r * 0.06f),
        )
        // Two eyes: the smallest thing that turns a gradient into a someone.
        val eyeR = r * 0.13f
        val dx = r * 0.30f
        val dy = -r * 0.06f
        drawCircle(Color(0xFF12131A).copy(alpha = 0.85f), eyeR, Offset(c.x - dx, c.y + dy))
        drawCircle(Color(0xFF12131A).copy(alpha = 0.85f), eyeR, Offset(c.x + dx, c.y + dy))
        // Specular highlight, top-left, the way every cartoon sphere has one.
        drawCircle(
            color = Color.White.copy(alpha = 0.35f),
            radius = r * 0.18f,
            center = Offset(c.x - r * 0.42f, c.y - r * 0.42f),
        )
    }
}
