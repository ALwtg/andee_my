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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.kuafuai.andee.brain.SpeechMood
import net.kuafuai.andee.desktop.voice.MacVoice
import net.kuafuai.andee.net.ToolSchemas
import net.kuafuai.andee.util.Log
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * `ui.ask` / `ui.alert` on the Mac: a card with tappable buttons, read aloud.
 *
 * Mirrors the phone's CardUi.ask/alert, with two deliberate differences.
 * The `heard` (voice answer) half is not here yet: the phone reads the question
 * then opens its microphone, which is safe there because the audio path has
 * echo cancellation — the Mac's does not (that is why the desktop is push-to-
 * talk), so an open mic would transcribe Andee's own voice reading the question.
 * And the card sits in the chat window rather than anchored to the ball.
 */
class UiTools : DesktopBrainHost.ToolProvider {

    override fun handles(method: String) = method == "ui.ask" || method == "ui.alert"

    override fun dispatch(method: String, p: JSONObject, silent: Boolean, untrusted: Boolean): Any? = when (method) {
        "ui.ask" -> {
            val question = p.optString("question").trim().ifEmpty { throw IllegalArgumentException("ui.ask requires question") }
            // Caller-supplied buttons pass through untouched — those are the model's
            // words. None sent: our own pair in the interface language.
            val buttons = p.optJSONArray("buttons")?.let { a -> (0 until a.length()).map { a.optString(it) } }
                ?: listOf(if (Strings.lang() == "zh") "是" else "Yes", if (Strings.lang() == "zh") "否" else "No")
            val timeoutMs = p.optLong("timeout_ms", 30_000L).coerceIn(5_000, 120_000)
            val r = AskGate.ask(question, buttons, timeoutMs)
            // The question and the answer are part of the conversation, as on the
            // phone — otherwise the card comes and goes and the log shows the
            // brain acting on a choice nobody saw being made.
            Scrollback.add(Scrollback.Role.ASSISTANT, SpeechMood.parse(question).text)
            val reply = r.button ?: r.heard
            if (!reply.isNullOrBlank()) Scrollback.add(Scrollback.Role.USER, reply)
            JSONObject()
                .put("button", r.button)
                .put("how", r.how)
                .put("answered", r.button != null || r.heard != null)
                .apply { r.heard?.let { put("heard", it) } }
        }
        "ui.alert" -> {
            val text = p.optString("text").trim().ifEmpty { throw IllegalArgumentException("ui.alert requires text") }
            val timeoutMs = p.optLong("timeout_ms", 30_000L).coerceIn(5_000, 120_000)
            val r = AskGate.alert(text, timeoutMs)
            JSONObject().put("how", r.how).put("read", true)
        }
        else -> throw IllegalArgumentException("unknown method $method")
    }
}

/**
 * The card gate. The brain's tool thread blocks in [ask]/[alert] until the user
 * answers or the timeout passes — a timeout is "no answer", never a guess.
 */
object AskGate {

    data class Result(val button: String?, val heard: String?, val how: String)

    class Pending(
        val question: String?,
        val buttons: List<String>,
        val answer: CompletableFuture<Result>,
        val deadline: Long,
    )

    @Volatile var current: Pending? = null
        private set
    @Volatile var onChange: () -> Unit = {}

    fun ask(question: String, buttons: List<String>, timeoutMs: Long): Result =
        run(question, buttons, timeoutMs)

    fun alert(text: String, timeoutMs: Long): Result =
        run(text, emptyList(), timeoutMs)

    private fun run(question: String, buttons: List<String>, timeoutMs: Long): Result {
        val p = Pending(question, buttons, CompletableFuture(), System.currentTimeMillis() + timeoutMs)
        synchronized(this) {
            current?.answer?.complete(Result(null, null, "superseded"))
            current = p
        }
        onChange()
        // Voice-first: the question is read out, exactly as the phone says it.
        MacVoice.speak(question)
        val r = runCatching { p.answer.get(timeoutMs, TimeUnit.MILLISECONDS) }
            .getOrDefault(Result(null, null, "timeout"))
        synchronized(this) { if (current === p) current = null }
        onChange()
        return r
    }

    fun answer(button: String?) {
        val p = current ?: return
        p.answer.complete(Result(button, null, "button"))
    }

    fun dismiss() {
        current?.answer?.complete(Result(null, null, "tap_outside"))
    }
}

/** The card: [Pending.question] null means an alert (one 知道了 control). */
@Composable
fun AskCard(p: AskGate.Pending) {
    val zh = Strings.lang() == "zh"
    fun t(a: String, b: String) = if (zh) a else b
    val isAlert = p.buttons.isEmpty()
    Box(Modifier.fillMaxSize().background(Color(0xB3000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 440.dp).fillMaxWidth().padding(20.dp)
                .background(Palette.card, RoundedCornerShape(18.dp))
                .border(1.dp, Palette.hairline, RoundedCornerShape(18.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(p.question ?: "", color = Palette.text, fontSize = 16.sp)
            if (isAlert) {
                Row(Modifier.align(Alignment.End)) {
                    Pill(t("知道了", "OK")) { AskGate.answer(null) }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    p.buttons.forEachIndexed { i, b ->
                        val primary = i == 0
                        Text(
                            b, color = if (primary) Color(0xFF0A2540) else Palette.text, fontSize = 15.sp,
                            modifier = Modifier.fillMaxWidth()
                                .background(if (primary) Palette.accent else Palette.panel, RoundedCornerShape(12.dp))
                                .border(1.dp, if (primary) Color.Transparent else Palette.hairline, RoundedCornerShape(12.dp))
                                .clickable { AskGate.answer(b) }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        }
    }
}
