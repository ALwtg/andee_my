package net.kuafuai.andee.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The phone's `Glass` surfaces, in Compose. Same tokens, same hierarchy: a near-
 * black base, everything above it white at low alpha, a top-lit edge that reads
 * as frosted glass. Real compositor blur does not apply here — the Mac window is
 * opaque black rather than an overlay over another app — so the sheen plus the
 * translucency is the whole effect, exactly as the phone's pills approximate it.
 */

/** A card surface: near-black fill, bright hairline, top sheen. */
fun Modifier.glassCard(radius: Int = 18): Modifier = this
    .background(Palette.cardSolid, RoundedCornerShape(radius.dp))
    .border(1.dp, Palette.hairline, RoundedCornerShape(radius.dp))
    .then(sheen(radius))

/** A grouped surface sitting on a card. */
fun Modifier.glassPanel(radius: Int = 14): Modifier = this
    .background(Palette.panel, RoundedCornerShape(radius.dp))
    .border(1.dp, Palette.panelEdge, RoundedCornerShape(radius.dp))

/**
 * A surface sitting directly on the photograph rather than inside a card: 12% white, not
 * the 6% film that is right one level down. 6% on a blurred photograph is a card that has
 * quietly stopped existing — the phone's Glass.GLASS note, and the reason there are two.
 */
fun Modifier.glassSurface(radius: Int = 14): Modifier = this
    .background(Palette.glass, RoundedCornerShape(radius.dp))
    .border(1.dp, Palette.glassEdge, RoundedCornerShape(radius.dp))

/**
 * The frosted card the form screens sit in: 60% black over the photograph, the phone's
 * CARD_FROSTED. Text is chosen against a dark ground; left directly on the photograph it
 * is legible on the dark half and gone on the bright one.
 */
fun Modifier.frostedCard(radius: Int = 20): Modifier = this
    .background(Palette.cardFrosted, RoundedCornerShape(radius.dp))
    .border(1.dp, Palette.hairline, RoundedCornerShape(radius.dp))
    .then(sheen(radius))

/** A recessed surface: text fields and picker tracks. */
fun Modifier.glassWell(radius: Int = 12): Modifier = this
    .background(Palette.well, RoundedCornerShape(radius.dp))
    .border(1.dp, Palette.wellEdge, RoundedCornerShape(radius.dp))

/**
 * The lit top edge — the one piece that reads as glass on a flat backdrop. A gradient
 * *background in the card's own shape*. It returns a fresh [Modifier], not an extension on the
 * receiver: used as `.then(sheen(r))` an extension would re-append the receiver's whole chain
 * (padding included), which painted a second card inset by the padding.
 */
private fun sheen(radius: Int): Modifier = Modifier.background(
    Brush.verticalGradient(0f to Color(0x1FFFFFFF), 0.45f to Color(0x00FFFFFF)),
    RoundedCornerShape(radius.dp),
)

@Composable
fun GlassHeader(title: String, subtitle: String? = null, onClose: () -> Unit, trailing: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text(title, color = Palette.title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, color = Palette.secondary, fontSize = 12.sp)
        }
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
        Text("✕", color = Palette.secondary, fontSize = 18.sp,
            modifier = Modifier.glassPanel(18).clickable { onClose() }.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

@Composable
fun SectionTitle(title: String) {
    Spacer(Modifier.height(16.dp))
    Text(title, color = Palette.secondary, fontSize = 13.sp)
    Spacer(Modifier.height(6.dp))
}

/** The phone's sliding-thumb segmented picker. */
@Composable
fun SegmentedPicker(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    val current = if (options.any { it.first == selected }) selected else options.first().first
    Row(
        Modifier.fillMaxWidth().glassWell(12).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for ((value, label) in options) {
            val on = value == current
            Text(
                label, color = if (on) Palette.title else Palette.secondary, fontSize = 14.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.weight(1f)
                    .background(if (on) Palette.thumb else Color.Transparent, RoundedCornerShape(9.dp))
                    .border(1.dp, if (on) Palette.thumbEdge else Color.Transparent, RoundedCornerShape(9.dp))
                    .clickable { onPick(value) }
                    .padding(vertical = 7.dp),
            )
        }
    }
}

@Composable
fun GlassField(label: String, value: String, onValue: (String) -> Unit, secret: Boolean = false) {
    Spacer(Modifier.height(8.dp))
    Text(label, color = Palette.secondary, fontSize = 12.sp)
    Spacer(Modifier.height(4.dp))
    BasicTextField(
        value = value,
        onValueChange = onValue,
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        textStyle = TextStyle(color = Palette.label, fontSize = 14.sp),
        cursorBrush = SolidColor(Palette.accent),
        modifier = Modifier.fillMaxWidth().glassWell(12).padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

@Composable
fun PrimaryButton(label: String, onClick: () -> Unit) {
    Text(
        label, color = Palette.ON_TINT(), fontSize = 14.sp, fontWeight = FontWeight.Medium,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
            .background(Palette.tint, RoundedCornerShape(14.dp))
            .clickable { onClick() }.padding(vertical = 11.dp),
    )
}

private fun Palette.ON_TINT(): Color = Color(0xFFFFFFFF)
