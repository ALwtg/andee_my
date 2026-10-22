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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.kuafuai.andee.desktop.shell.RiskClassifier.Tier
import net.kuafuai.andee.desktop.shell.ShellTools.Answer
import net.kuafuai.andee.desktop.shell.ShellTools.Approval
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The approval card. The brain's tool thread blocks in [request] until the
 * user answers or [TIMEOUT_S] passes — and a timeout is a refusal, never a
 * yes (design doc §4.4).
 */
object ApprovalGate {
    private const val TIMEOUT_S = 120L

    data class Pending(val a: Approval, val answer: CompletableFuture<Answer>)

    @Volatile var current: Pending? = null
        private set
    @Volatile var onChange: () -> Unit = {}

    fun request(a: Approval): Answer {
        val p = Pending(a, CompletableFuture())
        synchronized(this) {
            current?.answer?.complete(Answer.DENY)
            current = p
        }
        onChange()
        val r = runCatching { p.answer.get(TIMEOUT_S, TimeUnit.SECONDS) }.getOrDefault(Answer.DENY)
        synchronized(this) { if (current === p) current = null }
        onChange()
        return r
    }

    fun answer(r: Answer) {
        current?.answer?.complete(r)
    }
}

@Composable
fun ApprovalCard(p: ApprovalGate.Pending) {
    val zh = Strings.lang() == "zh"
    fun t(a: String, b: String) = if (zh) a else b
    val a = p.a
    var typed by remember(p) { mutableStateOf("") }
    var confirmed by remember(p) { mutableStateOf(false) }
    val tierColor = when (a.tier) {
        Tier.CRITICAL -> Palette.error
        Tier.HIGH -> Color(0xFFFF9F0A)
        Tier.MEDIUM -> Color(0xFFFFD60A)
        else -> Palette.secondary
    }
    // Just the card: it lives in its own window beside the ball (PromptWindow), not over the chat.
    run {
        Column(
            Modifier.fillMaxWidth()
                .background(Palette.card, RoundedCornerShape(18.dp))
                .border(1.dp, Palette.hairline, RoundedCornerShape(18.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (a.domain != null) {
                Text(t("允许联网访问这个网站吗？", "Allow access to this site?"), color = Palette.text, fontSize = 17.sp)
                Mono(a.domain)
                Text(t("一个命令需要连到这里。允许后本次运行期间都可以访问。", "A command needs to reach it. Allowed for the rest of this session."),
                    color = Palette.secondary, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(t("允许", "Allow")) { ApprovalGate.answer(Answer.ONCE) }
                    Pill(t("拒绝", "Deny")) { ApprovalGate.answer(Answer.DENY) }
                }
                return@Column
            }
            Text(t("Andee 想运行一条命令", "Andee wants to run a command"), color = Palette.text, fontSize = 17.sp)
            Text(a.why, color = Palette.text, fontSize = 14.sp)
            Mono(a.command)
            Text(t("在 ", "in ") + a.cwd, color = Palette.secondary, fontSize = 12.sp)
            Text(
                t("风险：", "Risk: ") + tierName(a.tier, zh) + " — " + a.reason,
                color = tierColor, fontSize = 13.sp,
            )
            if (a.second && !confirmed) {
                Text(
                    t("这条命令删掉或改掉的东西可能找不回来。确认的话，在下面输入「确定」。",
                        "What this removes or changes may not come back. To go ahead, type \"confirm\" below."),
                    color = Palette.error, fontSize = 13.sp,
                )
                BasicTextField(
                    value = typed, onValueChange = { typed = it }, singleLine = true,
                    textStyle = TextStyle(color = Palette.text, fontSize = 14.sp), cursorBrush = SolidColor(Palette.accent),
                    modifier = Modifier.fillMaxWidth().background(Palette.well, RoundedCornerShape(10.dp)).padding(10.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (typed.trim() == "确定" || typed.trim().equals("confirm", true)) {
                        Pill(t("运行", "Run")) { confirmed = true; ApprovalGate.answer(Answer.ONCE) }
                    }
                    Pill(t("拒绝", "Deny")) { ApprovalGate.answer(Answer.DENY) }
                }
                return@Column
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(t("允许一次", "Allow once")) { ApprovalGate.answer(Answer.ONCE) }
                if (a.allowAlways) Pill(t("总是允许这类", "Always allow this kind")) { ApprovalGate.answer(Answer.ALWAYS) }
                Pill(t("拒绝", "Deny")) { ApprovalGate.answer(Answer.DENY) }
            }
            Text(t("2 分钟不回答就当作拒绝。", "No answer in 2 minutes counts as Deny."), color = Palette.secondary, fontSize = 11.sp)
        }
    }
}

private fun tierName(t: Tier, zh: Boolean) = when (t) {
    Tier.SAFE -> if (zh) "只读" else "read-only"
    Tier.LOW -> if (zh) "低" else "low"
    Tier.MEDIUM -> if (zh) "中" else "medium"
    Tier.HIGH -> if (zh) "高" else "high"
    Tier.CRITICAL -> if (zh) "致命" else "critical"
}

@Composable
private fun Mono(text: String) {
    SelectionContainer {
        Text(
            text, color = Palette.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.fillMaxWidth().background(Palette.well, RoundedCornerShape(10.dp)).padding(10.dp),
        )
    }
}
