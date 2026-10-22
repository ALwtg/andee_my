package net.kuafuai.andee.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The settings sheet, the phone's card shape: a Glass header, segmented
 * pickers, and a 更多 section that opens the notebook, scenes, the expression
 * card and the self-check — the same four doors the phone's card reaches.
 */
@Composable
fun SettingsScreen(
    onDone: () -> Unit,
    onOpenNotebook: () -> Unit,
    onOpenVault: () -> Unit,
    onOpenScenes: () -> Unit,
    onOpenExpressions: () -> Unit,
    onOpenSelfCheck: () -> Unit,
) {
    val zh = Strings.lang() == "zh"
    fun t(a: String, b: String) = if (zh) a else b
    val v = remember { mutableStateMapOf<String, String>().apply { putAll(Settings.all()) } }
    var lang by remember { mutableStateOf(Strings.lang()) }
    var fullOpenAsk by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        GlassHeader(t("设置", "Settings"), subtitle = t("所有设置都存在本机", "Everything stays on this Mac"), onClose = onDone)

        SectionTitle(t("界面语言", "Language"))
        SegmentedPicker(listOf("zh" to "中", "en" to "EN"), lang) { lang = it; v["lang"] = it }

        SectionTitle(t("大脑", "Backend"))
        SegmentedPicker(listOf("local" to t("本机", "Own key"), "codeflying" to "CodeFlying"), v["brain"].orEmpty().ifEmpty { "local" }) { v["brain"] = it }
        GlassField(t("API Key", "API key"), v["llm_api_key"].orEmpty(), { v["llm_api_key"] = it }, secret = true)
        GlassField(t("接口地址（留空用默认）", "Base URL (blank = default)"), v["llm_base_url"].orEmpty(), { v["llm_base_url"] = it })
        GlassField(t("模型名（留空用默认）", "Model (blank = default)"), v["llm_model"].orEmpty(), { v["llm_model"] = it })
        SectionTitle(t("思考", "Thinking"))
        SegmentedPicker(listOf("disabled" to t("关", "Off"), "enabled" to t("开", "On")), v["llm_thinking"].orEmpty().ifEmpty { "disabled" }) { v["llm_thinking"] = it }
        GlassField(t("每轮最多步数（默认 25）", "Max steps per turn (default 25)"), v["max_steps"].orEmpty(), { v["max_steps"] = it })

        SectionTitle(t("命令行", "Commands"))
        SegmentedPicker(
            listOf("ask" to t("问我", "Ask me"), "auto" to t("自动", "Automatic"), "read_only" to t("只读", "Read-only"), "full" to t("完全开放", "Full")),
            v["shell_mode"].orEmpty().ifEmpty { "ask" },
        ) { picked ->
            if (picked == "full" && v["shell_mode"] != "full") fullOpenAsk = true else v["shell_mode"] = picked
        }
        Text(
            when (v["shell_mode"].orEmpty().ifEmpty { "ask" }) {
                "auto" -> t("在沙箱里，普通命令直接跑；删大量文件、强推这类仍先问你。", "Ordinary commands run in the sandbox; destructive ones still ask first.")
                "read_only" -> t("只允许查看类命令，其它一律拒绝。", "Only read-only commands are allowed.")
                "full" -> t("不进沙箱、不问：Andee 能改动这台 Mac 上的任何文件。", "No sandbox, no asking: Andee can change any file.")
                else -> t("只读命令直接跑，其它每条先问你。", "Read-only commands run; everything else asks first.")
            },
            color = Palette.secondary, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
        )
        GlassField(t("工作文件夹（逗号分隔，留空用 ~/Andee）", "Work folders (comma-separated; blank = ~/Andee)"), v["shell_workdirs"].orEmpty(), { v["shell_workdirs"] = it })
        SectionTitle(t("SSH 钥匙", "SSH keys"))
        SshKeys(zh)
        if (fullOpenAsk) {
            FullOpenConfirm(zh, onYes = { v["shell_mode"] = "full"; fullOpenAsk = false }, onNo = { fullOpenAsk = false })
        }

        SectionTitle(t("通知", "Notifications"))
        SegmentedPicker(listOf("off" to t("关", "Off"), "chat" to t("聊天类", "Chats"), "all" to t("全部", "All")), v["notify"].orEmpty().ifEmpty { "off" }) { v["notify"] = it }
        Text(
            when (net.kuafuai.andee.desktop.mac.MacNotifications.state) {
                net.kuafuai.andee.desktop.mac.MacNotifications.State.NO_ACCESS -> t(
                    "读不到通知：在 系统设置 → 隐私与安全性 → 完全磁盘访问权限 里加上 Andee。",
                    "Can't read notifications: add Andee under System Settings → Privacy & Security → Full Disk Access.")
                else -> t("打开后 Andee 先自己判断：忽略、告诉你，还是动手处理。", "When on, Andee decides for itself: ignore, tell you, or deal with it.")
            },
            color = Palette.secondary, fontSize = 12.sp,
        )

        SectionTitle(t("语音", "Voice"))
        GlassField(t("语音 Key", "Voice key"), v["api_key"].orEmpty(), { v["api_key"] = it }, secret = true)

        SectionTitle(t("更多", "More"))
        MoreRow(t("小本本", "Notebook"), t("它记得的事", "What it remembers"), onOpenNotebook)
        MoreRow(t("保险箱", "Vault"), t("你自己的账号", "Your own accounts"), onOpenVault)
        MoreRow(t("情景", "Scenes"), t("和它一起学会的做事方式", "Ways of working it learned"), onOpenScenes)
        MoreRow(t("形象与表情", "Look & expressions"), t("换一个样子", "Pick a face"), onOpenExpressions)
        MoreRow(t("自检", "Self-check"), t("看看哪里没开", "What is missing"), onOpenSelfCheck)

        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(t("保存", "Save")) {
                Settings.save(v.toMap())
                v["ball"]?.let { MacBall.pickLook(it) }
                Thread { Assistant.ensure() }.start()
                onDone()
            }
            Pill(t("取消", "Cancel")) { onDone() }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            t("数据保存在 ~/Library/Application Support/Andee，笔记本的密钥在钥匙串里。", "Data lives in ~/Library/Application Support/Andee; the notebook key is in your Keychain."),
            color = Palette.secondary, fontSize = 12.sp,
        )
    }
}

@Composable
private fun MoreRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().glassPanel(14).clickable { onClick() }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Palette.label, fontSize = 14.sp)
            Text(subtitle, color = Palette.secondary, fontSize = 11.sp)
        }
        Text("›", color = Palette.secondary, fontSize = 18.sp)
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun FullOpenConfirm(zh: Boolean, onYes: () -> Unit, onNo: () -> Unit) {
    fun t(a: String, b: String) = if (zh) a else b
    var typed by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp).glassPanel(12).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            t("完全开放后，Andee 运行命令不再进沙箱、不再问你。确认的话输入「开启」。", "With full access, commands run without the sandbox or asking. Type \"enable\" to confirm."),
            color = Palette.label, fontSize = 13.sp,
        )
        androidx.compose.foundation.text.BasicTextField(
            value = typed, onValueChange = { typed = it }, singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = Palette.label, fontSize = 14.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Palette.accent),
            modifier = Modifier.fillMaxWidth().glassWell(10).padding(10.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (typed.trim() == "开启" || typed.trim().equals("enable", true)) PrimaryButton(t("开启完全开放", "Enable full access")) { onYes() }
            Pill(t("取消", "Cancel")) { onNo() }
        }
    }
}

@Composable
private fun SshKeys(zh: Boolean) {
    fun t(a: String, b: String) = if (zh) a else b
    var keys by remember { mutableStateOf(runCatching { net.kuafuai.andee.desktop.shell.AndeeAgent.keys() }.getOrDefault(emptyList())) }
    var hosts by remember { mutableStateOf("github.com") }
    var error by remember { mutableStateOf("") }
    for (k in keys) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).glassPanel(10).padding(10.dp)) {
            Text(k.name + "  →  " + k.hosts.ifEmpty { listOf(t("任何主机", "any host")) }.joinToString(), color = Palette.label, fontSize = 13.sp)
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
    androidx.compose.foundation.text.BasicTextField(
        value = hosts, onValueChange = { hosts = it }, singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(color = Palette.label, fontSize = 14.sp),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(Palette.accent),
        modifier = Modifier.fillMaxWidth().glassWell(10).padding(10.dp),
    )
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill(t("生成新钥匙", "Generate a key")) {
            error = runCatching {
                net.kuafuai.andee.desktop.shell.AndeeAgent.generate("andee-" + (keys.size + 1), hostList(hosts)); ""
            }.getOrElse { it.message.orEmpty() }
            keys = runCatching { net.kuafuai.andee.desktop.shell.AndeeAgent.keys() }.getOrDefault(emptyList())
        }
        Pill(t("导入 ~/.ssh/id_ed25519", "Import ~/.ssh/id_ed25519")) {
            error = runCatching {
                val f = java.io.File(System.getProperty("user.home"), ".ssh/id_ed25519")
                net.kuafuai.andee.desktop.shell.AndeeAgent.import("id_ed25519", f.readText(), hostList(hosts)); ""
            }.getOrElse { it.message.orEmpty() }
            keys = runCatching { net.kuafuai.andee.desktop.shell.AndeeAgent.keys() }.getOrDefault(emptyList())
        }
    }
    if (error.isNotEmpty()) Text(error, color = Palette.danger, fontSize = 12.sp)
}

private fun hostList(s: String) = s.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }
