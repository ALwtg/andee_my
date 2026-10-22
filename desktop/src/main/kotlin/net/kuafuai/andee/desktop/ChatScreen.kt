package net.kuafuai.andee.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable

/** The phone's `Glass` palette, so the two apps look like one product. */
object Palette {
    val base = Color(0xFF000000)
    val card = Color(0xF20A0A0C)
    val panel = Color(0x0FFFFFFF)
    val well = Color(0x14FFFFFF)
    val hairline = Color(0x1AFFFFFF)
    val text = Color(0xFFF2F2F7)
    val secondary = Color(0x99EBEBF5)
    val accent = Color(0xFF0A84FF)
    val user = Color(0xFF0A84FF)
    val error = Color(0xFFFF453A)
}

@Composable
fun ChatScreen(onOpenSettings: () -> Unit) {
    var rows by remember { mutableStateOf(Scrollback.snapshot()) }
    var progress by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var voice by remember { mutableStateOf(net.kuafuai.andee.desktop.voice.MacVoice.State.IDLE) }
    var heard by remember { mutableStateOf("") }
    var artifacts by remember { mutableStateOf(Pages.artifacts()) }
    var recording by remember { mutableStateOf(Meeting.isActive()) }
    val list = rememberLazyListState()

    LaunchedEffect(Unit) {
        Scrollback.addListener { rows = Scrollback.snapshot() }
        Pages.onChange = { artifacts = Pages.artifacts() }
        Meeting.onRecordingChanged = { recording = it }
        Assistant.onProgress = { progress = it }
        Assistant.onFinal = { progress = "" }
        Assistant.onBusy = { b -> busy = b; if (!b) progress = "" }
        net.kuafuai.andee.desktop.voice.MacVoice.onState = { voice = it }
        net.kuafuai.andee.desktop.voice.MacVoice.onPartial = { heard = it }
        net.kuafuai.andee.desktop.voice.MacVoice.onFinal = { said ->
            heard = ""
            Thread { Assistant.say(said, spoken = true) }.start()
        }
    }
    LaunchedEffect(rows.size) { if (rows.isNotEmpty()) list.animateScrollToItem(rows.size - 1) }

    // The ball is the phone's way in: a tap talks, a second tap says "done".
    fun toggleMic() {
        if (!net.kuafuai.andee.desktop.voice.MacVoice.toggle()) progress = noVoiceKey()
    }

    fun send() {
        val t = input.trim()
        if (t.isEmpty() || busy) return
        input = ""
        Thread { Assistant.say(t) }.start()
    }

    Column(Modifier.fillMaxSize().background(Palette.base).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Andee", color = Palette.text, fontSize = 20.sp)
            Spacer(Modifier.weight(1f))
            if (recording) {
                Pill(if (Strings.lang() == "zh") "■ 停止录音" else "■ Stop recording") { Meeting.stopFromUser() }
                Spacer(Modifier.width(8.dp))
            } else if (busy) {
                Pill(if (Strings.lang() == "zh") "■ 停止" else "■ Stop") { Assistant.stop() }
                Spacer(Modifier.width(8.dp))
            }
            val listening = voice == net.kuafuai.andee.desktop.voice.MacVoice.State.LISTENING
            Pill(if (listening) (if (Strings.lang() == "zh") "● 说完了" else "● Done") else "🎤") { toggleMic() }
            Spacer(Modifier.width(8.dp))
            Pill("⚙") { onOpenSettings() }
        }
        Spacer(Modifier.height(12.dp))
        if (artifacts.isNotEmpty()) {
            ArtifactsPanel(
                artifacts,
                onOpen = { a -> runCatching { net.kuafuai.andee.desktop.mac.Bridge.open(a.file!!.absolutePath) } },
                onRevise = { a -> Thread { Assistant.say("继续修改「${a.title}」。先 get_page(id=${a.id}) 读回源码，改好后用 show_html 重画，不要新建产物。") }.start() },
                onRollback = { v ->
                    Pages.restoreVersion(v.id)
                    Scrollback.add(Scrollback.Role.ASSISTANT, "↩️ 已把「${v.title}」回退到 v${v.version}")
                },
            )
            Spacer(Modifier.height(8.dp))
        }
        LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (rows.isEmpty()) {
                item {
                    SceneOffer { p ->
                        ScenePresets.adoptIfMissing(p.name)
                        Thread { Assistant.say("进入「${p.title}」情景。${p.example}") }.start()
                    }
                }
            }
            items(displayItems(rows), key = { itemKey(it) }) { it ->
                when (it) {
                    is Scrollback.Row -> Bubble(it)
                    is TopicRow -> TopicCard(it.topic, it.count) {
                        Thread { Assistant.say("继续聊「${it.topic.title}」。先 get_topic(id=${it.topic.id}) 读回对话再接着聊。") }.start()
                    }
                }
            }
        }
        if (heard.isNotEmpty()) {
            Text("🎤 $heard", color = Palette.text, fontSize = 14.sp, modifier = Modifier.padding(vertical = 6.dp))
        }
        if (progress.isNotEmpty()) {
            Text(progress, color = Palette.secondary, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
        }
        Box(
            Modifier.fillMaxWidth().background(Palette.well, RoundedCornerShape(14.dp))
                .border(1.dp, Palette.hairline, RoundedCornerShape(14.dp)).padding(12.dp),
        ) {
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                textStyle = TextStyle(color = Palette.text, fontSize = 15.sp),
                cursorBrush = SolidColor(Palette.accent),
                modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Enter && !e.isShiftPressed) {
                        send(); true
                    } else false
                },
            )
            if (input.isEmpty()) {
                Text(
                    if (Strings.lang() == "zh") "输入后按回车发送（Shift+回车换行）" else "Type and press Return (Shift+Return for a new line)",
                    color = Palette.secondary, fontSize = 15.sp,
                )
            }
        }
    }
}

/** One folded stretch rendered as a single card, at the position of its first row. */
private data class TopicRow(val topic: Scrollback.Topic, val count: Int)

private fun displayItems(rows: List<Scrollback.Row>): List<Any> {
    val topics = Scrollback.topics().associateBy { it.id }
    val seen = mutableSetOf<Long>()
    val out = mutableListOf<Any>()
    for (r in rows) {
        val tid = r.topicId
        if (tid == null) { out += r; continue }
        if (seen.add(tid)) {
            topics[tid]?.let { out += TopicRow(it, rows.count { x -> x.topicId == tid }) }
        }
    }
    return out
}

private fun itemKey(it: Any): Any = when (it) {
    is Scrollback.Row -> "r${it.id}"
    is TopicRow -> "t${it.topic.id}"
    else -> it
}

fun noVoiceKey() =
    if (Strings.lang() == "zh") "还没配语音 Key：在 ⚙ 设置的「语音」里填上。" else "No voice key yet: add it under Voice in ⚙ Settings."

@Composable
private fun Bubble(r: Scrollback.Row) {
    val mine = r.role == Scrollback.Role.USER
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        SelectionContainer {
            Text(
                r.text,
                color = if (r.role == Scrollback.Role.ERROR) Palette.error else Palette.text,
                fontSize = 15.sp,
                modifier = Modifier.widthIn(max = 560.dp)
                    .background(if (mine) Palette.user.copy(alpha = 0.35f) else Palette.panel, RoundedCornerShape(14.dp))
                    .border(1.dp, Palette.hairline, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
fun Pill(label: String, onClick: () -> Unit) {
    Text(
        label, color = Palette.text, fontSize = 14.sp,
        modifier = Modifier.background(Palette.panel, RoundedCornerShape(16.dp))
            .border(1.dp, Palette.hairline, RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
