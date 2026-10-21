package net.kuafuai.andee.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The settings sheet. Same keys as the phone's, and the same rule for the
 * enum-like ones: a picker, whose leftmost option is what an unknown saved
 * value reads as.
 */
@Composable
fun SettingsScreen(onDone: () -> Unit) {
    val zh = Strings.lang() == "zh"
    fun t(a: String, b: String) = if (zh) a else b
    val v = remember { mutableStateMapOf<String, String>().apply { putAll(Settings.all()) } }
    var lang by remember { mutableStateOf(Strings.lang()) }

    Column(Modifier.fillMaxSize().background(Palette.base).padding(20.dp).verticalScroll(rememberScrollState())) {
        Text(t("设置", "Settings"), color = Palette.text, fontSize = 22.sp)
        Spacer(Modifier.height(16.dp))

        Section(t("界面语言", "Language"))
        Picker(listOf("zh" to "中", "en" to "EN"), lang) { lang = it; v["lang"] = it }

        Section(t("后端", "Backend"))
        Picker(
            listOf("local" to t("本机（自己的 Key）", "Own key"), "codeflying" to "CodeFlying"),
            v["brain"].orEmpty().ifEmpty { "local" },
        ) { v["brain"] = it }
        Field(t("API Key", "API key"), v, "llm_api_key", secret = true)
        Field(t("接口地址（留空用默认）", "Base URL (blank = default)"), v, "llm_base_url")
        Field(t("模型名（留空用默认）", "Model (blank = default)"), v, "llm_model")
        Section(t("思考", "Thinking"))
        Picker(listOf("disabled" to t("关", "Off"), "enabled" to t("开", "On")), v["llm_thinking"].orEmpty().ifEmpty { "disabled" }) {
            v["llm_thinking"] = it
        }
        Field(t("每轮最多步数（默认 25）", "Max steps per turn (default 25)"), v, "max_steps")

        Section(t("语音（火山）", "Voice (Volcengine)"))
        Field(t("火山 Key", "Volcengine key"), v, "api_key", secret = true)

        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill(t("保存", "Save")) {
                Settings.save(v.toMap())
                Thread { Assistant.ensure() }.start()
                onDone()
            }
            Pill(t("取消", "Cancel")) { onDone() }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            t("数据保存在 ~/Library/Application Support/Andee，笔记本的密钥在钥匙串里。",
                "Data lives in ~/Library/Application Support/Andee; the notebook's key is in your Keychain."),
            color = Palette.secondary, fontSize = 12.sp,
        )
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(14.dp))
    Text(title, color = Palette.secondary, fontSize = 13.sp)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun Picker(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    val current = if (options.any { it.first == selected }) selected else options.first().first
    Row(
        Modifier.background(Palette.well, RoundedCornerShape(10.dp)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for ((value, label) in options) {
            val on = value == current
            Text(
                label, color = Palette.text, fontSize = 14.sp,
                modifier = Modifier
                    .background(if (on) Palette.panel else Palette.well.copy(alpha = 0f), RoundedCornerShape(8.dp))
                    .border(1.dp, if (on) Palette.hairline else Palette.hairline.copy(alpha = 0f), RoundedCornerShape(8.dp))
                    .clickable { onPick(value) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun Field(label: String, v: MutableMap<String, String>, key: String, secret: Boolean = false) {
    Spacer(Modifier.height(8.dp))
    Text(label, color = Palette.secondary, fontSize = 12.sp)
    Spacer(Modifier.height(4.dp))
    BasicTextField(
        value = v[key].orEmpty(),
        onValueChange = { v[key] = it },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        textStyle = TextStyle(color = Palette.text, fontSize = 14.sp),
        cursorBrush = SolidColor(Palette.accent),
        modifier = Modifier.fillMaxWidth().background(Palette.well, RoundedCornerShape(10.dp))
            .border(1.dp, Palette.hairline, RoundedCornerShape(10.dp)).padding(10.dp),
    )
}

