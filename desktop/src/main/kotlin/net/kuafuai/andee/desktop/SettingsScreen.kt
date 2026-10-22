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
import net.kuafuai.andee.config.BrainConfig

/** A string from the phone's own resources (generated into strings_*.properties), so the wording is the phone's. */
private fun st(key: String, vararg a: Any): String = Strings.get("s_$key").let { if (a.isEmpty()) it else String.format(Strings.locale(), it, *a) }

/**
 * The settings card, laid out as the phone's: 后端 (with the voice provider inside it), 外观, 小本本,
 * 消息提醒, 保险箱, 单次任务最多步数, and a 高级 fold carrying 显示云端选项, 诊断 and the destructive
 * buttons. Wording comes from the phone's string resources, so a label cannot drift from its twin.
 *
 * What differs is only what the Mac is: a 命令行 and SSH keys section (the Mac has a shell), no wake
 * word, no typing-IME row (neither exists here), and no 形象与表情 / 自检 doors, which the sidebar
 * already is.
 */
@Composable
fun SettingsScreen(
    onDone: () -> Unit,
) {
    val zh = Strings.lang() == "zh"
    fun t(a: String, b: String) = if (zh) a else b
    val v = remember { mutableStateMapOf<String, String>().apply { putAll(Settings.all()) } }
    var fullOpenAsk by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf("") }   // "", "chat", "notes", "factory"
    val brain = v["brain"].orEmpty().ifEmpty { "local" }

    // The voice provider the card shows is the one in force: a saved pick, else by language.
    val provider = when (v["voice_provider"].orEmpty().trim().lowercase()) {
        "google" -> "google"
        "huoshan" -> "huoshan"
        else -> if (Strings.lang() == "en") "google" else "huoshan"
    }
    val en = Strings.lang() == "en"
    val llmModelHint = if (en) "gpt-4o-mini" else "deepseek-flash"
    val llmUrlHint = if (en) "https://api.openai.com/v1" else "https://api.deepseek.com"

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        GlassHeader(st("settings_title"), subtitle = t("所有设置都存在本机", "Everything stays on this Mac"), onClose = onDone)

        // ---- language: written the instant it is tapped, like the phone ----
        SectionTitle(t("界面语言", "Language"))
        SegmentedPicker(listOf("zh" to "中", "en" to "EN"), Strings.lang()) {
            Settings.save(mapOf("lang" to it))
            v["lang"] = it
            Strings.changed()
        }

        // ---- 后端 ----
        SectionTitle(st("settings_section_brain"))
        SegmentedPicker(
            buildList {
                add("local" to st("settings_brain_local"))
                add("codeflying" to st("settings_brain_codeflying"))
                if (v["show_hub"] == "on") add("hub" to st("settings_brain_cloud"))
            },
            brain,
        ) { v["brain"] = it }
        when (brain) {
            "hub" -> {
                GlassField(st("settings_hub_url"), v["hub_url"].orEmpty(), { v["hub_url"] = it }, hint = st("settings_hub_url_hint"))
                GlassField(st("settings_device_name"), v["device_name"].orEmpty(), { v["device_name"] = it }, hint = st("settings_device_name_hint"))
                Note(st("settings_brain_cloud_note"))
            }
            "codeflying" -> Note(st("settings_brain_codeflying_unconfigured"))
            else -> {
                GlassField(st("settings_llm_api_key"), v["llm_api_key"].orEmpty(), { v["llm_api_key"] = it }, secret = true, hint = "sk-…")
                GlassField(st("settings_llm_model"), v["llm_model"].orEmpty(), { v["llm_model"] = it }, hint = llmModelHint)
                GlassField(st("settings_llm_base_url"), v["llm_base_url"].orEmpty(), { v["llm_base_url"] = it }, hint = llmUrlHint)
                RowLabel(st("settings_llm_thinking"))
                val thinking = v["llm_thinking"].orEmpty().ifEmpty { "enabled" }
                SegmentedPicker(listOf("enabled" to st("common_on"), "disabled" to st("common_off")), thinking) { v["llm_thinking"] = it }
                if (thinking != "disabled") {
                    RowLabel(st("settings_llm_effort"))
                    SegmentedPicker(
                        listOf("high" to st("common_high"), "medium" to st("common_medium"), "low" to st("common_low")),
                        v["llm_reasoning_effort"].orEmpty().ifEmpty { "high" },
                    ) { v["llm_reasoning_effort"] = it }
                }
                Note(st("settings_brain_local_note"))
            }
        }

        // ---- who hears and speaks (not drawn under CodeFlying, which brings its own voice) ----
        if (brain != "codeflying") {
            RowLabel(st("settings_voice_provider"))
            SegmentedPicker(
                listOf("huoshan" to st("settings_voice_provider_huoshan"), "google" to st("settings_voice_provider_google")),
                provider,
            ) { v["voice_provider"] = it }
            if (provider == "google") {
                GlassField(st("settings_google_api_key"), v["google_api_key"].orEmpty(), { v["google_api_key"] = it }, secret = true, hint = st("settings_google_api_key_hint"))
                GlassField(st("settings_google_project"), v["google_project"].orEmpty(), { v["google_project"] = it }, hint = st("settings_google_project_hint"))
                GlassField(st("settings_google_location"), v["google_location"].orEmpty(), { v["google_location"] = it }, hint = "asia-northeast1")
                GlassField(st("settings_google_asr_model"), v["google_asr_model"].orEmpty(), { v["google_asr_model"] = it }, hint = "gemini-live-2.5-flash-preview-native-audio")
                GlassField(st("settings_google_tts_model"), v["google_tts_model"].orEmpty(), { v["google_tts_model"] = it }, hint = "gemini-2.5-flash-preview-tts")
                GlassField(st("settings_google_base_url"), v["google_base_url"].orEmpty(), { v["google_base_url"] = it }, hint = "https://aiplatform.googleapis.com")
                GlassField(st("settings_google_language"), v["google_language"].orEmpty(), { v["google_language"] = it }, hint = st("settings_google_language_hint"))
                Note(st("settings_voice_google_note"))
            } else {
                GlassField(st("settings_voice_api_key"), v["api_key"].orEmpty(), { v["api_key"] = it }, secret = true, hint = st("settings_voice_api_key_hint"))
                GlassField(st("settings_voice_asr_endpoint"), v["asr_endpoint"].orEmpty(), { v["asr_endpoint"] = it }, hint = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async")
                GlassField(st("settings_voice_asr_resource_id"), v["asr_resource_id"].orEmpty(), { v["asr_resource_id"] = it }, hint = "volc.bigasr.sauc.duration")
                GlassField(st("settings_voice_tts_endpoint"), v["tts_endpoint"].orEmpty(), { v["tts_endpoint"] = it }, hint = "wss://openspeech.bytedance.com/api/v3/tts/bidirection")
                GlassField(st("settings_voice_tts_resource_id"), v["tts_resource_id"].orEmpty(), { v["tts_resource_id"] = it }, hint = "seed-tts-2.0")
                GlassField(st("settings_voice_tts_sample_rate"), v["tts_sample_rate"].orEmpty(), { v["tts_sample_rate"] = it }, hint = "24000")
            }
        }

        // ---- 外观: eager, the redraw is the confirmation ----
        SectionTitle(st("settings_section_theme"))
        Note(st("settings_theme_note"))
        SegmentedPicker(
            listOf("day" to st("settings_theme_day"), "night" to st("settings_theme_night")),
            if (Palette.night) "night" else "day",
        ) { Palette.setNight(it == "night") }

        // ---- 小本本 ----
        SectionTitle(st("settings_section_notebook"))
        SegmentedPicker(listOf("on" to st("common_on"), "off" to st("common_off")), v["sweep"].orEmpty().ifEmpty { "on" }) { v["sweep"] = it }
        Note(st("settings_notebook_sweep_note"))
        GlassField(st("settings_notebook_quiet_minutes"), v["sweep_quiet_minutes"].orEmpty(), { v["sweep_quiet_minutes"] = it }, hint = "20")
        GlassField(st("settings_notebook_daily_cap"), v["sweep_daily_cap"].orEmpty(), { v["sweep_daily_cap"] = it }, hint = "12")
        Note(
            st("settings_notebook_today") + "：" + st(
                "settings_notebook_stats", Sweep.countToday(), Sweep.tokensToday(),
                DesktopNotebook.book.memories().size, DesktopNotebook.book.todos("pending").size,
            ),
        )

        // ---- 消息提醒 ----
        SectionTitle(st("settings_section_notify"))
        SegmentedPicker(
            listOf("off" to st("common_off"), "chat" to st("settings_notify_chat"), "all" to st("settings_notify_all")),
            v["notify"].orEmpty().ifEmpty { "off" },
        ) { v["notify"] = it }
        Note(st("settings_notify_note"))
        if (net.kuafuai.andee.desktop.mac.MacNotifications.state == net.kuafuai.andee.desktop.mac.MacNotifications.State.NO_ACCESS) {
            Text(
                t("读不到通知：在 系统设置 → 隐私与安全性 → 完全磁盘访问权限 里加上 Andee。",
                    "Can't read notifications: add Andee under System Settings → Privacy & Security → Full Disk Access."),
                color = Palette.warn, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
            )
        }

        // ---- 保险箱: a summary here, the card is in the sidebar ----
        SectionTitle(st("settings_section_vault"))
        val vaultN = remember { runCatching { net.kuafuai.andee.desktop.mac.MacVault.entries().size }.getOrDefault(0) }
        Note(if (vaultN == 0) st("settings_vault_empty_note") else st("settings_vault_count_note", vaultN))

        // ---- 单次任务最多步数 ----
        SectionTitle(st("settings_max_steps"))
        Note(st("settings_max_steps_note"))
        GlassField(st("settings_max_steps"), v["max_steps"].orEmpty(), { v["max_steps"] = it }, hint = "25")

        // ---- Mac only: the shell ----
        SectionTitle(t("命令行", "Commands"))
        SegmentedPicker(
            listOf("ask" to t("问我", "Ask me"), "auto" to t("自动", "Automatic"), "read_only" to t("只读", "Read-only"), "full" to t("完全开放", "Full")),
            v["shell_mode"].orEmpty().ifEmpty { "ask" },
        ) { picked ->
            if (picked == "full" && v["shell_mode"] != "full") fullOpenAsk = true else v["shell_mode"] = picked
        }
        Note(
            when (v["shell_mode"].orEmpty().ifEmpty { "ask" }) {
                "auto" -> t("在沙箱里，普通命令直接跑；删大量文件、强推这类仍先问你。", "Ordinary commands run in the sandbox; destructive ones still ask first.")
                "read_only" -> t("只允许查看类命令，其它一律拒绝。", "Only read-only commands are allowed.")
                "full" -> t("不进沙箱、不问：Andee 能改动这台 Mac 上的任何文件。", "No sandbox, no asking: Andee can change any file.")
                else -> t("只读命令直接跑，其它每条先问你。", "Read-only commands run; everything else asks first.")
            },
        )
        GlassField(t("工作文件夹（逗号分隔，留空用 ~/Andee）", "Work folders (comma-separated; blank = ~/Andee)"), v["shell_workdirs"].orEmpty(), { v["shell_workdirs"] = it })
        SectionTitle(t("SSH 钥匙", "SSH keys"))
        SshKeys(zh)
        if (fullOpenAsk) {
            FullOpenConfirm(zh, onYes = { v["shell_mode"] = "full"; fullOpenAsk = false }, onNo = { fullOpenAsk = false })
        }

        // ---- 高级: folded, with the note saying what is behind it ----
        SectionTitle(st("settings_section_advanced"))
        Row(
            Modifier.fillMaxWidth().glassPanel(12).clickable { advanced = !advanced }.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(st("settings_advanced_note"), color = Palette.secondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(if (advanced) "⌃" else "⌄", color = Palette.secondary, fontSize = 16.sp)
        }
        if (advanced) {
            SectionTitle(st("settings_show_hub"))
            SegmentedPicker(listOf("off" to st("common_off"), "on" to st("common_on")), v["show_hub"].orEmpty().ifEmpty { "off" }) { v["show_hub"] = it }
            Note(st("settings_show_hub_note"))

            SectionTitle(st("settings_section_diagnostics"))
            RowLabel(st("settings_grounding"))
            SegmentedPicker(listOf("off" to st("common_off"), "on" to st("common_on")), v["grounding"].orEmpty().ifEmpty { "off" }) { v["grounding"] = it }
            Note(st("settings_grounding_note"))
            Note(st("settings_device_id") + "：" + Settings.uid())
            Spacer(Modifier.height(12.dp))
            when (confirm) {
                "chat" -> ConfirmBlock(st("settings_clear_chat_confirm_title"), st("settings_clear_chat_confirm_body"), st("settings_clear_chat_confirm"),
                    onNo = { confirm = "" }) { Assistant.stop(); Scrollback.wipe(); Pages.wipe(); confirm = "" }
                "notes" -> ConfirmBlock(st("settings_clear_notes_confirm_title"), st("settings_clear_notes_confirm_body"), st("settings_clear_notes_confirm"),
                    onNo = { confirm = "" }) { DesktopNotebook.book.wipe(); confirm = "" }
                "factory" -> ConfirmBlock(
                    st("settings_factory_reset_confirm_title"),
                    st("settings_factory_reset_list_intro") + "\n" + factoryItems(zh).joinToString("\n") { "· $it" } + "\n" + st("settings_factory_reset_after"),
                    st("settings_factory_reset_confirm"), onNo = { confirm = "" },
                ) { factoryReset(); confirm = ""; onDone() }
                else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DangerButton(st("settings_clear_chat")) { confirm = "chat" }
                    DangerButton(st("settings_clear_notes")) { confirm = "notes" }
                    DangerButton(st("settings_factory_reset")) { confirm = "factory" }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        // Equal halves, cancel on the left and the main action on the right.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(st("common_cancel"), Modifier.weight(1f)) { onDone() }
            PrimaryButton(st("common_save"), Modifier.weight(1f)) {
                Settings.save(v.toMap())
                v["ball"]?.let { MacBall.pickLook(it) }
                Thread { Assistant.ensure() }.start()
                onDone()
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            t("数据保存在 ~/Library/Application Support/Andee，笔记本的密钥在钥匙串里。", "Data lives in ~/Library/Application Support/Andee; the notebook key is in your Keychain."),
            color = Palette.secondary, fontSize = 12.sp,
        )
    }
}

@Composable
private fun Note(text: String) {
    Text(text, color = Palette.secondary, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun RowLabel(text: String) {
    Text(text, color = Palette.secondary, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun DangerButton(label: String, onClick: () -> Unit) {
    Text(
        label, color = Palette.danger, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier.fillMaxWidth().glassPanel(14).clickable { onClick() }.padding(vertical = 11.dp),
    )
}

@Composable
private fun ConfirmBlock(title: String, body: String, confirmLabel: String, onNo: () -> Unit, onYes: () -> Unit) {
    Column(Modifier.fillMaxWidth().glassPanel(12).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = Palette.danger, fontSize = 14.sp)
        Text(body, color = Palette.label, fontSize = 13.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(st("common_cancel"), Modifier.weight(1f)) { onNo() }
            DangerButton(confirmLabel, onYes)
        }
    }
}

/** What 恢复出厂设置 removes on the Mac, in the order the confirmation lists it. */
private fun factoryItems(zh: Boolean): List<String> =
    if (zh) listOf("所有设置和 Key", "小本本（记忆、提醒、情景）", "保险箱里的账号", "聊天记录和它生成过的页面", "SSH 钥匙和命令白名单")
    else listOf("All settings and keys", "The notebook (memories, reminders, scenes)", "The vault accounts", "The chat history and pages it made", "SSH keys and the command allow-list")

/** Wipes everything the card promised to. Irreversible; the confirmation lists it first. */
private fun factoryReset() {
    Assistant.stop()
    runCatching { Scrollback.wipe() }
    runCatching { Pages.wipe() }
    runCatching { DesktopNotebook.book.wipe() }
    runCatching { net.kuafuai.andee.desktop.mac.MacVault.wipe() }
    runCatching { net.kuafuai.andee.desktop.shell.AndeeAgent.keys().forEach { net.kuafuai.andee.desktop.shell.AndeeAgent.delete(it.name) } }
    runCatching { AppDirs.file("shell_always_allow.json").delete() }
    runCatching { AppDirs.file("settings.properties").delete() }
    Strings.changed()
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(t("取消", "Cancel"), Modifier.weight(1f)) { onNo() }
            if (typed.trim() == "开启" || typed.trim().equals("enable", true)) PrimaryButton(t("开启完全开放", "Enable full access"), Modifier.weight(1f)) { onYes() }
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
