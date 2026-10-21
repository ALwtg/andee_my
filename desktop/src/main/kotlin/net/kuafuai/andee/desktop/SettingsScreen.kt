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
    var fullOpenAsk by remember { mutableStateOf(false) }

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

        Section(t("命令行", "Commands"))
        Picker(
            listOf(
                "ask" to t("问我", "Ask me"),
                "auto" to t("自动", "Automatic"),
                "read_only" to t("只读", "Read-only"),
                "full" to t("完全开放", "Full access"),
            ),
            v["shell_mode"].orEmpty().ifEmpty { "ask" },
        ) { picked ->
            if (picked == "full" && v["shell_mode"] != "full") fullOpenAsk = true else v["shell_mode"] = picked
        }
        Text(
            when (v["shell_mode"].orEmpty().ifEmpty { "ask" }) {
                "auto" -> t("在沙箱里，普通命令直接跑；删除大量文件、强推这类仍然先问你。",
                    "Runs ordinary commands inside the sandbox; destructive ones are still shown to you first.")
                "read_only" -> t("只允许查看类命令，其它一律拒绝。", "Only commands that read are allowed.")
                "full" -> t("不进沙箱、不问：Andee 能改动这台 Mac 上的任何文件。陌生人消息触发的回合仍然不能执行命令。",
                    "No sandbox, no asking: Andee can change any file on this Mac. A turn started by someone else's message still cannot run commands.")
                else -> t("只读命令直接跑，其它每条先问你。", "Read-only commands run; everything else is shown to you first.")
            },
            color = Palette.secondary, fontSize = 12.sp,
        )
        Field(t("工作文件夹（逗号分隔，留空用 ~/Andee；桌面、文稿整个目录不能用）", "Work folders (comma-separated; blank = ~/Andee; not all of Desktop or Documents)"), v, "shell_workdirs")
        Section(t("SSH 钥匙", "SSH keys"))
        SshKeys(zh)
        if (fullOpenAsk) {
            FullOpenConfirm(zh, onYes = { v["shell_mode"] = "full"; fullOpenAsk = false }, onNo = { fullOpenAsk = false })
        }

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

/** Full access needs the word typed, not a click (design doc §4.2). */
@Composable
private fun FullOpenConfirm(zh: Boolean, onYes: () -> Unit, onNo: () -> Unit) {
    fun t(a: String, b: String) = if (zh) a else b
    var typed by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp).background(Palette.panel, RoundedCornerShape(12.dp))
            .border(1.dp, Palette.error, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            t("完全开放后，Andee 运行命令不再进沙箱、不再问你，能删除和修改这台 Mac 上的任何文件。确认的话输入「开启」。",
                "With full access, Andee's commands run outside the sandbox without asking, and can delete or change any file on this Mac. Type \"enable\" to confirm."),
            color = Palette.text, fontSize = 13.sp,
        )
        BasicTextField(
            value = typed, onValueChange = { typed = it }, singleLine = true,
            textStyle = TextStyle(color = Palette.text, fontSize = 14.sp), cursorBrush = SolidColor(Palette.accent),
            modifier = Modifier.fillMaxWidth().background(Palette.well, RoundedCornerShape(10.dp)).padding(10.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (typed.trim() == "开启" || typed.trim().equals("enable", true)) Pill(t("开启完全开放", "Enable full access")) { onYes() }
            Pill(t("取消", "Cancel")) { onNo() }
        }
    }
}

/** Keys Andee signs with as an ssh-agent; the private half never leaves it. */
@Composable
private fun SshKeys(zh: Boolean) {
    fun t(a: String, b: String) = if (zh) a else b
    var keys by remember { mutableStateOf(runCatching { net.kuafuai.andee.desktop.shell.AndeeAgent.keys() }.getOrDefault(emptyList())) }
    var hosts by remember { mutableStateOf("github.com") }
    var error by remember { mutableStateOf("") }
    for (k in keys) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).background(Palette.panel, RoundedCornerShape(10.dp)).padding(10.dp)) {
            Text(k.name + "  →  " + k.hosts.ifEmpty { listOf(t("任何主机", "any host")) }.joinToString(), color = Palette.text, fontSize = 13.sp)
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(k.openSsh(), color = Palette.secondary, fontSize = 11.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(t("删除", "Delete")) {
                    runCatching { net.kuafuai.andee.desktop.shell.AndeeAgent.delete(k.name) }
                    keys = runCatching { net.kuafuai.andee.desktop.shell.AndeeAgent.keys() }.getOrDefault(emptyList())
                }
            }
        }
    }
    Text(t("允许用于这些主机（逗号分隔）", "Allowed hosts (comma-separated)"), color = Palette.secondary, fontSize = 12.sp)
    BasicTextField(
        value = hosts, onValueChange = { hosts = it }, singleLine = true,
        textStyle = TextStyle(color = Palette.text, fontSize = 14.sp), cursorBrush = SolidColor(Palette.accent),
        modifier = Modifier.fillMaxWidth().background(Palette.well, RoundedCornerShape(10.dp)).padding(10.dp),
    )
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill(t("生成新钥匙", "Generate a key")) {
            error = runCatching {
                net.kuafuai.andee.desktop.shell.AndeeAgent.generate("andee-" + (keys.size + 1), hostList(hosts))
                ""
            }.getOrElse { it.message.orEmpty() }
            keys = runCatching { net.kuafuai.andee.desktop.shell.AndeeAgent.keys() }.getOrDefault(emptyList())
        }
        Pill(t("导入 ~/.ssh/id_ed25519", "Import ~/.ssh/id_ed25519")) {
            error = runCatching {
                val f = java.io.File(System.getProperty("user.home"), ".ssh/id_ed25519")
                net.kuafuai.andee.desktop.shell.AndeeAgent.import("id_ed25519", f.readText(), hostList(hosts))
                ""
            }.getOrElse { it.message.orEmpty() }
            keys = runCatching { net.kuafuai.andee.desktop.shell.AndeeAgent.keys() }.getOrDefault(emptyList())
        }
    }
    Text(
        t("生成后把上面那行公钥贴到 GitHub → Settings → SSH keys。命令里的 git push 会请 Andee 签名，私钥本身拿不到。",
            "Paste the public line above into GitHub → Settings → SSH keys. git push in a command asks Andee to sign; the private key is never handed over."),
        color = Palette.secondary, fontSize = 11.sp,
    )
    if (error.isNotEmpty()) Text(error, color = Palette.error, fontSize = 12.sp)
}

private fun hostList(s: String) = s.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }
