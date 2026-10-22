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

/** A folded conversation, collapsed to one card: 继续话题 / 对话记录. */
@Composable
fun TopicCard(topic: Scrollback.Topic, count: Int, onContinue: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)
    Column(
        Modifier.fillMaxWidth().background(Palette.panel, RoundedCornerShape(14.dp))
            .border(1.dp, Palette.hairline, RoundedCornerShape(14.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("🗂 " + topic.title, color = Palette.text, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            Text("${fmt.format(Date(topic.endedAt))} · $count msgs", color = Palette.secondary, fontSize = 11.sp)
        }
        if (topic.summary.isNotBlank()) {
            Text(topic.summary, color = Palette.secondary, fontSize = 12.sp)
        }
        if (expanded) {
            SelectionContainer {
                Text(
                    Scrollback.topicText(topic.id) ?: "（记录已不在）",
                    color = Palette.text, fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().background(Palette.well, RoundedCornerShape(10.dp)).padding(10.dp),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(t("继续话题", "Continue")) { onContinue() }
            Pill(if (expanded) t("收起记录", "Hide transcript") else t("对话记录", "Transcript")) { expanded = !expanded }
        }
    }
}

/** The 产物 shelf: one card per artifact, with open / continue / versions. */
@Composable
fun ArtifactsPanel(artifacts: List<Pages.Page>, onOpen: (Pages.Page) -> Unit, onRevise: (Pages.Page) -> Unit, onRollback: (Pages.Page) -> Unit) {
    if (artifacts.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(t("产物", "Artifacts"), color = Palette.secondary, fontSize = 13.sp)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (a in artifacts) {
                ArtifactCard(a, onOpen, onRevise, onRollback)
            }
        }
    }
}

@Composable
private fun ArtifactCard(a: Pages.Page, onOpen: (Pages.Page) -> Unit, onRevise: (Pages.Page) -> Unit, onRollback: (Pages.Page) -> Unit) {
    var showVersions by remember { mutableStateOf(false) }
    Column(
        Modifier.width(200.dp).background(Palette.panel, RoundedCornerShape(12.dp))
            .border(1.dp, Palette.hairline, RoundedCornerShape(12.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("📄 " + a.title, color = Palette.text, fontSize = 13.sp)
        Text("v${a.version}", color = Palette.secondary, fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill(t("打开", "Open")) { onOpen(a) }
            Pill(t("继续", "Edit")) { onRevise(a) }
            Pill(t("版本", "Versions")) { showVersions = !showVersions }
        }
        if (showVersions) {
            for (v in Pages.versionsOf(a.groupId)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("v${v.version}${if (v.file == null) "（没了）" else ""}", color = if (v.id == a.id) Palette.text else Palette.secondary, fontSize = 11.sp)
                    Spacer(Modifier.weight(1f))
                    if (v.id != a.id && v.file != null) {
                        Text(t("回退", "Roll back"), color = Palette.accent, fontSize = 11.sp, modifier = Modifier.clickable { onRollback(v) })
                    }
                }
            }
        }
    }
}

private fun t(zh: String, en: String) = if (Strings.lang() == "zh") zh else en

/** An empty chat offers scenes instead of sitting blank — the phone's presets bubbles. */
@Composable
fun SceneOffer(onPick: (ScenePresets.Preset) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(t("还没有对话。说点什么，或选一个情景：", "Nothing here yet. Say something, or pick a scene:"), color = Palette.secondary, fontSize = 13.sp)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (p in ScenePresets.ALL) {
                Column(
                    Modifier.fillMaxWidth().background(Palette.panel, RoundedCornerShape(12.dp))
                        .border(1.dp, Palette.hairline, RoundedCornerShape(12.dp))
                        .clickable { onPick(p) }.padding(12.dp),
                ) {
                    Text(p.title, color = Palette.text, fontSize = 14.sp)
                    Text(p.summary, color = Palette.secondary, fontSize = 12.sp)
                }
            }
        }
    }
}
