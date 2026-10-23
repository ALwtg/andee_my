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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.kuafuai.andee.config.Ages
import net.kuafuai.andee.config.Categories
import net.kuafuai.andee.config.Gear

/**
 * 装备: what is installed on this Mac and what Andee has learned each thing is for.
 *
 * It is the user's view of exactly what the model is told. The device reads the facts (installed, last used,
 * how often) and the brain writes the meaning; here the person can read both, correct the meaning, mute one
 * app, forget what was learned, rescan, and decide which *kinds* of app the model hears about. Everything
 * hidden from the model is still listed here, flagged, because the person is entitled to see what was kept
 * out and to change it.
 */
/** The phone's own strings (generated into the tables), so a label cannot differ from its twin. */
private fun g(key: String, vararg a: Any): String = Strings.get("s_gear_$key").let { if (a.isEmpty()) it else String.format(Strings.locale(), it, *a) }

@Composable
fun GearCard(onDone: () -> Unit) {
    val zh = Strings.lang() == "zh"
    fun t(a: String, b: String) = if (zh) a else b
    val shelf = Assistant.gear
    val gear = shelf.gear
    var tick by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    // Reading `tick` is what makes every mutation below redraw this card.
    val rows = remember(tick, query) {
        gear.forCard().filter { (i, _) ->
            query.isBlank() || i.label.contains(query, true) || i.id.contains(query, true) ||
                i.does.contains(query, true) || i.canDo.contains(query, true)
        }
    }
    val total = remember(tick) { gear.forCard().size }
    val scanned = remember(tick) { gear.scannedAt() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        GlassHeader(g("title"), g("subtitle"), onClose = onDone)

        // ---- which kinds the model is told about ----
        SectionTitle(g("kinds_title"))
        Text(g("kinds_note"), color = Palette.secondary, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        val kinds = listOf(
            Categories.ADULT to g("kind_adult"), Categories.FINANCE to g("kind_finance"),
            Categories.HEALTH to g("kind_health"), Categories.DATING to g("kind_dating"), Categories.OTHER to g("kind_other"),
        )
        for ((cat, label) in kinds) {
            val on = remember(tick) { gear.shown(cat) }
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = Palette.label, fontSize = 13.sp, modifier = Modifier.width(110.dp))
                Box(Modifier.weight(1f)) {
                    SegmentedPicker(listOf("off" to g("hide"), "on" to g("tell")), if (on) "on" else "off") {
                        gear.setShown(cat, it == "on"); tick++
                    }
                }
            }
        }

        // ---- the list ----
        SectionTitle(g("installed", total))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (scanned > 0) g("scanned_ago", Ages.ago(System.currentTimeMillis() - scanned, zh)) else g("never_scanned"),
                color = Palette.secondary, fontSize = 12.sp, modifier = Modifier.weight(1f),
            )
            Text(
                if (scanning) g("scanning") else g("rescan"),
                color = Palette.accent, fontSize = 13.sp,
                modifier = Modifier.clickable(enabled = !scanning) {
                    scanning = true
                    Thread { shelf.scanNow(); scanning = false; tick++ }.apply { isDaemon = true }.start()
                }.padding(6.dp),
            )
        }
        Text(g("scan_note"), color = Palette.secondary, fontSize = 11.sp)
        GlassField(g("search"), query, { query = it })
        Spacer(Modifier.height(8.dp))

        for ((i, hidden) in rows) {
            GearRow(i, hidden, zh, editing == i.id,
                onEdit = { editing = if (editing == i.id) null else i.id },
                onChange = { tick++ },
                gear = gear,
            )
            Spacer(Modifier.height(8.dp))
        }
        if (rows.isEmpty()) {
            Text(g("none_match"), color = Palette.photoInk, fontSize = 13.sp)
        }
    }
}

@Composable
private fun GearRow(i: Gear.Item, hidden: Boolean, zh: Boolean, editing: Boolean, onEdit: () -> Unit, onChange: () -> Unit, gear: Gear) {
    fun t(a: String, b: String) = if (zh) a else b
    val now = System.currentTimeMillis()
    var does by remember(i.id, editing) { mutableStateOf(i.does) }
    var canDo by remember(i.id, editing) { mutableStateOf(i.canDo) }
    var confirmForget by remember(i.id) { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().glassPanel(14).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (hidden) { Box(Modifier.size(7.dp).background(Palette.muted, CircleShape)); Spacer(Modifier.width(7.dp)) }
            Text(i.label, color = if (hidden) Palette.secondary else Palette.label, fontSize = 14.sp, modifier = Modifier.weight(1f))
            if (hidden) Text(g("hidden_from_it"), color = Palette.muted, fontSize = 11.sp)
        }
        val meta = buildList {
            if (i.lastUsedAt > 0) add(g("last_used", Ages.ago(now - i.lastUsedAt, zh)))
            if (i.uses > 0) add(g("uses", i.uses))
            if (i.signedIn == "yes") add(g("signed_in") + " (" + Ages.ago(now - i.signedInAt, zh) + ")")
            if (i.signedIn == "no") add(g("signed_out") + " (" + Ages.ago(now - i.signedInAt, zh) + ")")
            if (i.limit.isNotBlank()) add(i.limit)
        }.joinToString(" · ")
        if (meta.isNotEmpty()) Text(meta, color = Palette.secondary, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        if (i.does.isNotBlank() || i.canDo.isNotBlank()) {
            Text(
                listOfNotNull(i.does.takeIf { it.isNotBlank() }, i.canDo.takeIf { it.isNotBlank() }?.let { g("can_do", it) }).joinToString("\n"),
                color = Palette.label, fontSize = 12.sp,
            )
            if (i.byUser) Text(g("by_user"), color = Palette.muted, fontSize = 11.sp)
        } else {
            Text(g("unlearned"), color = Palette.muted, fontSize = 12.sp)
        }

        if (editing) {
            GlassField(g("field_does"), does, { does = it })
            GlassField(g("field_can_do"), canDo, { canDo = it })
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(Strings.get("s_common_cancel"), Modifier.weight(1f)) { onEdit() }
                PrimaryButton(Strings.get("s_common_save"), Modifier.weight(1f)) { gear.edit(i.id, does, canDo); onEdit(); onChange() }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(g("edit"), color = Palette.accent, fontSize = 12.sp, modifier = Modifier.clickable { onEdit() }.padding(vertical = 6.dp))
            Text(
                if (i.ignored) g("unignore") else g("ignore"),
                color = Palette.secondary, fontSize = 12.sp,
                modifier = Modifier.clickable { gear.setIgnored(i.id, !i.ignored); onChange() }.padding(vertical = 6.dp),
            )
            if (i.does.isNotBlank() || i.canDo.isNotBlank() || i.signedIn.isNotBlank() || i.limit.isNotBlank()) {
                Text(
                    if (confirmForget) g("forget_confirm") else g("forget"),
                    color = Palette.danger, fontSize = 12.sp,
                    modifier = Modifier.clickable {
                        if (confirmForget) { gear.forget(i.id); confirmForget = false; onChange() } else confirmForget = true
                    }.padding(vertical = 6.dp),
                )
            }
        }
    }
}
