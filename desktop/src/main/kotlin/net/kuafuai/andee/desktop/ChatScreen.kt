package net.kuafuai.andee.desktop

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The phone's `Glass` palette, so the two apps look like one product. */
/** The phone's `Glass` night palette, exactly — so the two apps read as one product. */
object Palette {
    val base = Color(0xFF000000)
    val cardSolid = Color(0xF2060608)
    val cardFrosted = Color(0x99000000)
    val panel = Color(0x0FFFFFFF)          // 6% white
    val panelEdge = Color(0x17FFFFFF)       // 9% white
    val glass = Color(0x1FFFFFFF)           // 12% white
    val glassEdge = Color(0x38FFFFFF)       // 22% white
    val well = Color(0x59000000)            // 35% black
    val wellEdge = Color(0x1AFFFFFF)        // 10% white
    val thumb = Color(0x2EFFFFFF)           // 18% white
    val thumbEdge = Color(0x40FFFFFF)
    val hairline = Color(0x2BFFFFFF)        // 17% white
    val title = Color(0xFFFFFFFF)
    val label = Color(0xFFDCE1EA)
    val secondary = Color(0xFF8F97A6)
    val muted = Color(0xFF5F6775)
    val accent = Color(0xFF7FA5FF)
    val tint = Color(0xFF4C6FFF)
    val danger = Color(0xFFFF6B6B)
    val ok = Color(0xFF4ADE80)
    val warn = Color(0xFFFBBF24)

    // kept for existing call sites
    val text get() = title
    val accentSoft = Color(0x267FA5FF)
    val user = accent
    val error get() = danger
    val card get() = cardSolid
}

@Composable
fun ChatScreen(onOpenSettings: () -> Unit, onOpenSelfCheck: () -> Unit, onOpenArtifacts: () -> Unit) {
    var rows by remember { mutableStateOf(Scrollback.snapshot()) }
    var progress by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var voice by remember { mutableStateOf(net.kuafuai.andee.desktop.voice.MacVoice.State.IDLE) }
    var heard by remember { mutableStateOf("") }
    var artifacts by remember { mutableStateOf(Pages.artifacts()) }
    var recording by remember { mutableStateOf(Meeting.isActive()) }
    val list = rememberLazyListState()
    val items = displayItems(rows)

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
    LaunchedEffect(items.size) { if (items.isNotEmpty()) list.animateScrollToItem(items.size - 1) }

    // The ball is the phone's way in: a tap talks, a second tap says "done".
    fun toggleMic() {
        if (Meeting.isActive()) { Meeting.stopFromUser(); return }
        if (!net.kuafuai.andee.desktop.voice.MacVoice.toggle()) progress = noVoiceKey()
    }

    fun send() {
        val t = input.trim()
        if (t.isEmpty() || busy) return
        input = ""
        Thread { Assistant.say(t) }.start()
    }

    Column(Modifier.fillMaxSize().background(Palette.base)) {
        Header(
            status = when {
                recording -> if (Strings.lang() == "zh") "● 正在录音" else "● Recording"
                voice == net.kuafuai.andee.desktop.voice.MacVoice.State.LISTENING -> if (Strings.lang() == "zh") "● 正在听" else "● Listening"
                busy -> if (Strings.lang() == "zh") "思考中…" else "Thinking…"
                voice == net.kuafuai.andee.desktop.voice.MacVoice.State.SPEAKING -> if (Strings.lang() == "zh") "正在说" else "Speaking"
                else -> null
            },
            stoppable = recording || busy,
            onStop = {
                if (recording) Meeting.stopFromUser() else if (busy) Assistant.stop()
            },
            onSettings = onOpenSettings,
            onSelfCheck = onOpenSelfCheck,
        )

        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
            if (artifacts.isNotEmpty()) {
                Text(
                    "📄 " + (if (Strings.lang() == "zh") "产物" else "Artifacts") + " (${artifacts.size})",
                    color = Palette.accent, fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().glassPanel(12).clickable { onOpenArtifacts() }.padding(12.dp),
                )
                Spacer(Modifier.height(10.dp))
            }
            LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (items.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
                            SceneOffer { p ->
                                ScenePresets.adoptIfMissing(p.name)
                                Thread { Assistant.say("进入「${p.title}」情景。${p.example}") }.start()
                            }
                        }
                    }
                }
                items(items, key = { itemKey(it) }) { it ->
                    when (it) {
                        is Scrollback.Row -> Bubble(it)
                        is TopicRow -> TopicCard(it.topic, it.count) {
                            Thread { Assistant.say("继续聊「${it.topic.title}」。先 get_topic(id=${it.topic.id}) 读回对话再接着聊。") }.start()
                        }
                    }
                }
            }
        }

        if (heard.isNotEmpty()) {
            Text("🎤 $heard", color = Palette.text, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 4.dp))
        }
        if (progress.isNotEmpty()) {
            Text(progress, color = Palette.secondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 4.dp))
        }

        InputBar(
            input = input,
            onInput = { input = it },
            listening = voice == net.kuafuai.andee.desktop.voice.MacVoice.State.LISTENING,
            canSend = input.isNotBlank() && !busy,
            onMic = { toggleMic() },
            onSend = { send() },
        )
    }
}

/** Title plus a one-line status, with stop and settings on the right — the phone's control bar. */
@Composable
private fun Header(status: String?, stoppable: Boolean, onStop: () -> Unit, onSettings: () -> Unit, onSelfCheck: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text("Andee", color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            if (status != null) {
                Text(status, color = if (status.startsWith("●")) Palette.accent else Palette.secondary, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.weight(1f))
        if (stoppable) {
            Pill(if (Strings.lang() == "zh") "■ 停止" else "■ Stop") { onStop() }
            Spacer(Modifier.width(8.dp))
        }
        Pill("✓") { onSelfCheck() }
        Spacer(Modifier.width(8.dp))
        Pill("⚙") { onSettings() }
    }
}

/** The mic and send live with the text field, as a chat app's composer does. */
@Composable
private fun InputBar(input: String, onInput: (String) -> Unit, listening: Boolean, canSend: Boolean, onMic: () -> Unit, onSend: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RoundButton(listening, onMic) { if (listening) "◼" else "🎤" }
        Box(
            Modifier.weight(1f).background(Palette.well, RoundedCornerShape(18.dp))
                .border(1.dp, Palette.hairline, RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            BasicTextField(
                value = input,
                onValueChange = onInput,
                textStyle = TextStyle(color = Palette.text, fontSize = 15.sp),
                cursorBrush = SolidColor(Palette.accent),
                modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Enter && !e.isShiftPressed) {
                        if (canSend) onSend(); true
                    } else false
                },
            )
            if (input.isEmpty()) {
                Text(
                    if (Strings.lang() == "zh") "说点什么，或输入后回车发送" else "Say something, or type and press Return",
                    color = Palette.secondary, fontSize = 14.sp,
                )
            }
        }
        RoundButton(canSend, onSend) { "↑" }
    }
}

@Composable
private fun RoundButton(enabled: Boolean, onClick: () -> Unit, glyph: () -> String) {
    Box(
        Modifier.size(40.dp)
            .background(if (enabled) Palette.accent else Palette.well, CircleShape)
            .border(1.dp, if (enabled) Color.Transparent else Palette.hairline, CircleShape)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph(), color = if (enabled) Color(0xFF0A2540) else Palette.secondary, fontSize = 17.sp)
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
    val error = r.role == Scrollback.Role.ERROR
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        SelectionContainer {
            Text(
                r.text,
                color = when { error -> Palette.error; mine -> Palette.text; else -> Palette.text },
                fontSize = 15.sp,
                lineHeight = 21.sp,
                modifier = Modifier.widthIn(max = 560.dp)
                    .background(
                        when {
                            error -> Palette.error.copy(alpha = 0.12f)
                            mine -> Palette.accentSoft
                            else -> Palette.panel
                        },
                        RoundedCornerShape(if (mine) 16.dp else 16.dp),
                    )
                    .border(1.dp, if (error) Palette.error.copy(alpha = 0.4f) else Palette.hairline, RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
    }
}

@Composable
fun Pill(label: String, onClick: () -> Unit) {
    Text(
        label, color = Palette.text, fontSize = 13.sp,
        modifier = Modifier.background(Palette.panel, RoundedCornerShape(15.dp))
            .border(1.dp, Palette.hairline, RoundedCornerShape(15.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
