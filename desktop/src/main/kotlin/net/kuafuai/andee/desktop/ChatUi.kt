package net.kuafuai.andee.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun t(zh: String, en: String) = if (Strings.lang() == "zh") zh else en

/** An empty chat offers scenes instead of sitting blank — the phone's presets bubbles. */
@Composable
fun SceneOffer(onPick: (ScenePresets.Preset) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(t("还没有对话。说点什么，或选一个情景：", "Nothing here yet. Say something, or pick a scene:"), color = Palette.photoInk, fontSize = 13.sp)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (p in ScenePresets.ALL) {
                Column(
                    Modifier.fillMaxWidth().glassSurface(12).clickable { onPick(p) }.padding(12.dp),
                ) {
                    Text(p.title, color = Palette.text, fontSize = 14.sp)
                    Text(p.summary, color = Palette.secondary, fontSize = 12.sp)
                }
            }
        }
    }
}
