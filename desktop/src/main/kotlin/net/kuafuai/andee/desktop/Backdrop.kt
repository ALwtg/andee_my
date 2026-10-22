package net.kuafuai.andee.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.kuafuai.andee.util.Log
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.math.max

/**
 * The phone's backdrop: one photograph, blurred by us and darkened by a veil,
 * behind every surface. The cards are translucent glass laid on it — which is
 * what makes them read as glass rather than as grey boxes.
 *
 * Same recipe as the phone's Backdrop.kt, and for the same reasons: a 512-px
 * copy with three box-blur passes (a downscale-upscale smears rather than
 * frosts), and a veil *derived from the image* — `1 − 0.22 / meanLuma`, clamped
 * to 38–75% black — so swapping the file needs no new constants. The veil goes
 * over the image, not under it.
 */
object Backdrop {

    private const val TARGET_W = 512
    private const val BLUR_RADIUS = 8
    private const val BLUR_PASSES = 3
    private const val TARGET_LUMA = 0.22f
    private const val VEIL_MIN = 0.38f
    private const val VEIL_MAX = 0.75f

    class Built(val image: ImageBitmap, val veil: Float)

    @Volatile private var built: Built? = null

    /** Decode, shrink, blur, measure. Off the UI thread; cached after the first call. */
    fun build(): Built? {
        built?.let { return it }
        val src = runCatching {
            Backdrop::class.java.getResourceAsStream("/backdrop.jpg")?.use { ImageIO.read(it) }
        }.getOrNull() ?: run { Log.w("Backdrop", "backdrop.jpg missing; surfaces keep their plain fill"); return null }
        val k = TARGET_W.toDouble() / src.width
        val w = TARGET_W
        val h = max(1, (src.height * k).toInt())
        val small = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        small.createGraphics().apply {
            setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(src, 0, 0, w, h, null); dispose()
        }
        val px = IntArray(w * h)
        small.getRGB(0, 0, w, h, px, 0, w)
        boxBlur(px, w, h)
        small.setRGB(0, 0, w, h, px, 0, w)
        val luma = meanLuma(px)
        val veil = (1f - TARGET_LUMA / max(luma, 0.02f)).coerceIn(VEIL_MIN, VEIL_MAX)
        Log.i("Backdrop", "backdrop ${w}x$h mean luma %.3f → veil %.0f%%".format(luma, veil * 100))
        return Built(small.toComposeImageBitmap(), veil).also { built = it }
    }

    private fun boxBlur(px: IntArray, w: Int, h: Int) {
        val tmp = IntArray(px.size)
        val r = BLUR_RADIUS
        val n = r * 2 + 1
        repeat(BLUR_PASSES) {
            for (y in 0 until h) {
                val row = y * w
                var sr = 0; var sg = 0; var sb = 0
                for (i in -r..r) { val c = px[row + i.coerceIn(0, w - 1)]; sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF }
                for (x in 0 until w) {
                    tmp[row + x] = -0x1000000 or ((sr / n) shl 16) or ((sg / n) shl 8) or (sb / n)
                    val add = px[row + (x + r + 1).coerceIn(0, w - 1)]
                    val sub = px[row + (x - r).coerceIn(0, w - 1)]
                    sr += ((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)
                    sg += ((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF)
                    sb += (add and 0xFF) - (sub and 0xFF)
                }
            }
            for (x in 0 until w) {
                var sr = 0; var sg = 0; var sb = 0
                for (i in -r..r) { val c = tmp[i.coerceIn(0, h - 1) * w + x]; sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF }
                for (y in 0 until h) {
                    px[y * w + x] = -0x1000000 or ((sr / n) shl 16) or ((sg / n) shl 8) or (sb / n)
                    val add = tmp[(y + r + 1).coerceIn(0, h - 1) * w + x]
                    val sub = tmp[(y - r).coerceIn(0, h - 1) * w + x]
                    sr += ((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)
                    sg += ((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF)
                    sb += (add and 0xFF) - (sub and 0xFF)
                }
            }
        }
    }

    private fun meanLuma(px: IntArray): Float {
        var sum = 0.0
        for (c in px) sum += (0.2126 * ((c shr 16) and 0xFF) + 0.7152 * ((c shr 8) and 0xFF) + 0.0722 * (c and 0xFF)) / 255.0
        return (sum / px.size).toFloat()
    }
}

/** The photograph and its veil, filling whatever it is put in. Black until the image is ready. */
@Composable
fun BackdropLayer(modifier: Modifier = Modifier.fillMaxSize()) {
    var b by remember { mutableStateOf(Backdrop.build()) }
    LaunchedEffect(Unit) { if (b == null) b = withContext(Dispatchers.Default) { Backdrop.build() } }
    Box(modifier.background(Palette.base)) {
        b?.let {
            Image(it.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            // The veil goes on top of the image, not under it: a background behind the image darkens nothing.
            Box(Modifier.fillMaxSize().background(Color(0f, 0f, 0f, it.veil)))
        }
    }
}
