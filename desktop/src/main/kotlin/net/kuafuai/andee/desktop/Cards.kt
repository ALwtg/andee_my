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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The phone's cards, on the Mac: self-check (✓), the notebook (小本本) and
 * scenes (情景). Same Glass surfaces, same "the person browses and deletes, the
 * model writes" split.
 */

/** The readiness checklist — the phone's ✓ card, one row per thing that can silently break. */
@Composable
fun SelfCheckCard(onDone: () -> Unit, onOpenSettings: () -> Unit) {
    val zh = Strings.lang() == "zh"
    data class CheckRow(val id: String, val name: String, val ok: Boolean, val why: String, val fix: (() -> Unit)?)
    val rows = buildList {
        val ax = net.kuafuai.andee.desktop.mac.Bridge.axTrusted()
        add(CheckRow("ax", if (zh) "辅助功能" else "Accessibility", ax,
            if (ax) if (zh) "能读、能操作别的 app" else "Can read and drive other apps" else if (zh) "读不到屏幕，也无法操作" else "Cannot read or drive the screen",
            { openPane("Privacy_Accessibility") }))
        val rec = net.kuafuai.andee.desktop.mac.Bridge.screenCaptureAllowed()
        add(CheckRow("rec", if (zh) "录屏" else "Screen Recording", rec,
            if (rec) if (zh) "能看见屏幕" else "Can see the screen" else if (zh) "截图拿不到" else "Screenshots are blocked",
            { openPane("Privacy_ScreenCapture") }))
        val fda = net.kuafuai.andee.desktop.mac.MacNotifications.state != net.kuafuai.andee.desktop.mac.MacNotifications.State.NO_ACCESS
        add(CheckRow("fda", if (zh) "完全磁盘访问" else "Full Disk Access", fda,
            if (fda) if (zh) "能读通知" else "Can read notifications" else if (zh) "读不到通知数据库" else "Cannot read the notification store",
            { openPane("Privacy_AllFiles") }))
        val backend = Settings.get("llm_api_key").isNotBlank()
        add(CheckRow("backend", if (zh) "大脑 Key" else "Model key", backend,
            if (backend) if (zh) "已配置" else "Configured" else if (zh) "还没配，回答不了" else "Missing — Andee cannot answer",
            { onOpenSettings() }))
        val voice = net.kuafuai.andee.desktop.voice.MacVoice.configured()
        add(CheckRow("voice", if (zh) "语音 Key" else "Voice key", voice,
            if (voice) if (zh) "能听能说" else "Can hear and speak" else if (zh) "还没配，说不了话" else "Missing — Andee cannot speak",
            { onOpenSettings() }))
    }
    Column(Modifier.fillMaxSize().background(Palette.base).padding(20.dp).verticalScroll(rememberScrollState())) {
        GlassHeader(if (zh) "自检" else "Self-check", if (zh) "每一项都可能悄悄坏掉" else "Each of these can fail silently", onClose = onDone)
        for (r in rows) {
            Row(
                Modifier.fillMaxWidth().glassPanel(14).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (r.ok) "✓" else "!", color = if (r.ok) Palette.ok else Palette.warn, fontSize = 16.sp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.name, color = Palette.label, fontSize = 14.sp)
                    Text(r.why, color = Palette.secondary, fontSize = 11.sp)
                }
                if (!r.ok && r.fix != null) {
                    Text(if (zh) "去处理" else "Fix", color = Palette.accent, fontSize = 13.sp,
                        modifier = Modifier.clickable { r.fix?.invoke() }.padding(6.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun openPane(anchor: String) {
    runCatching { net.kuafuai.andee.desktop.mac.Bridge.open("x-apple.systempreferences:com.apple.preference.security?$anchor") }
}

/** 小本本: what Andee remembers, browsable and deletable — plus the todos it owes. */
@Composable
fun NotebookCard(onDone: () -> Unit) {
    val zh = Strings.lang() == "zh"
    var refresh by remember { mutableStateOf(0) }
    val memories = DesktopNotebook.book.memories().sortedByDescending { it.importance }
    val todos = DesktopNotebook.book.todos("pending").filter { it.status in listOf("active", "paused") }
        .sortedBy { if (it.nextRunAt > 0) it.nextRunAt else Long.MAX_VALUE }
    Column(Modifier.fillMaxSize().background(Palette.base).padding(20.dp).verticalScroll(rememberScrollState())) {
        GlassHeader(if (zh) "小本本" else "Notebook", if (zh) "它记得的事" else "What it remembers", onClose = onDone,
            trailing = {
                Text(if (zh) "复制全部" else "Copy all", color = Palette.accent, fontSize = 13.sp,
                    modifier = Modifier.clickable {
                        val dump = memories.joinToString("\n\n") { "【${it.name}】${it.type}\n${it.description}" }
                        java.awt.Toolkit.getDefaultToolkit().systemClipboard
                            .setContents(java.awt.datatransfer.StringSelection(dump), null)
                    }.padding(6.dp))
            })

        SectionTitle(if (zh) "记忆" else "Memories")
        if (memories.isEmpty()) {
            Text(if (zh) "还没有记住什么。" else "Nothing remembered yet.", color = Palette.muted, fontSize = 13.sp)
        }
        for (m in memories) {
            Column(Modifier.fillMaxWidth().glassPanel(14).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.name, color = Palette.label, fontSize = 14.sp)
                        Text("${m.type} · ${m.description.take(60)}", color = Palette.secondary, fontSize = 11.sp)
                    }
                    Text(if (zh) "删除" else "Delete", color = Palette.danger, fontSize = 12.sp,
                        modifier = Modifier.clickable { DesktopNotebook.book.forget(m.name); refresh++ }.padding(6.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        SectionTitle(if (zh) "待办" else "Todos")
        if (todos.isEmpty()) {
            Text(if (zh) "没有待办。" else "Nothing pending.", color = Palette.muted, fontSize = 13.sp)
        }
        for (t in todos) {
            Row(Modifier.fillMaxWidth().glassPanel(14).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.what, color = Palette.label, fontSize = 13.sp)
                    val whenTxt = if (t.mode == "cron") t.cronExpr else LocalTime.format(t.at)
                    Text(whenTxt + (if (t.status == "paused") "（暂停）" else ""), color = Palette.secondary, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** 情景: the ways of working Andee has learned — enter, leave, read, delete. */
@Composable
fun ScenesCard(onDone: () -> Unit) {
    val zh = Strings.lang() == "zh"
    var refresh by remember { mutableStateOf(0) }
    val scenes = DesktopNotebook.book.scenes().sortedByDescending { it.lastUsedAt }
    val active = Settings.get("scene").ifEmpty { null }
    Column(Modifier.fillMaxSize().background(Palette.base).padding(20.dp).verticalScroll(rememberScrollState())) {
        GlassHeader(if (zh) "情景" else "Scenes", if (zh) "它和你一起学会的做事方式" else "Ways of working it learned", onClose = onDone)
        if (scenes.isEmpty()) {
            Text(if (zh) "还没有情景。空对话里可以选一个预设，或让它学着提一个。" else "No scenes yet. Pick a preset from an empty chat, or let Andee propose one.", color = Palette.muted, fontSize = 13.sp)
        }
        for (s in scenes) {
            var expanded by remember { mutableStateOf(false) }
            val isActive = s.name == active
            Column(Modifier.fillMaxWidth().glassPanel(14).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text((if (isActive) "● " else "") + s.title, color = if (isActive) Palette.accent else Palette.label, fontSize = 14.sp)
                        Text(s.summary + " · ${s.uses} 次", color = Palette.secondary, fontSize = 11.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (isActive) (if (zh) "退出" else "Leave") else (if (zh) "进入" else "Enter"),
                        color = Palette.accent, fontSize = 12.sp,
                        modifier = Modifier.clickable {
                            Settings.save(mapOf("scene" to if (isActive) "" else s.name))
                            refresh++
                        }.padding(vertical = 6.dp))
                    Text(if (zh) "规则" else "Rules", color = Palette.secondary, fontSize = 12.sp,
                        modifier = Modifier.clickable { expanded = !expanded }.padding(vertical = 6.dp))
                    Spacer(Modifier.weight(1f))
                    Text(if (zh) "删除" else "Delete", color = Palette.danger, fontSize = 12.sp,
                        modifier = Modifier.clickable {
                            if (isActive) Settings.save(mapOf("scene" to ""))
                            DesktopNotebook.book.deleteScene(s.name)
                            refresh++
                        }.padding(vertical = 6.dp))
                }
                if (expanded) {
                    Text(s.prompt, color = Palette.label, fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().glassWell(10).padding(10.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
