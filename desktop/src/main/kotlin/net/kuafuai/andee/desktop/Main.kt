package net.kuafuai.andee.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun main() {
    installFileLog()
    net.kuafuai.andee.util.Log.i("Andee", "starting, data in ${AppDirs.root}")
    Assistant.askApproval = { ApprovalGate.request(it) }
    MacBall.restoreLook()
    Assistant.start()
    application {
        var settings by remember { mutableStateOf(false) }
        var pending by remember { mutableStateOf(ApprovalGate.current) }
        // The chat is the phone's unfolded card: the ball is always there, the chat comes and goes.
        var chat by remember { mutableStateOf(true) }
        ApprovalGate.onChange = { pending = ApprovalGate.current; if (pending != null) chat = true }
        var ballWindow by remember { mutableStateOf<java.awt.Window?>(null) }
        var chatWindow by remember { mutableStateOf<java.awt.Window?>(null) }
        // A click aimed at another app must not land on Andee: both windows step aside for the instant of it.
        // (Native, not Window.opacity: AWT refuses that on the decorated chat window.)
        Assistant.hideWindows = { hide -> net.kuafuai.andee.desktop.mac.Bridge.setOwnAlpha(if (hide) 0.0 else 1.0) }

        FloatingBall(
            onTap = { if (!net.kuafuai.andee.desktop.voice.MacVoice.toggle()) { chat = true; settings = true } },
            onLongPress = { chat = !chat },
            onWindow = { ballWindow = it },
        )

        Window(
            onCloseRequest = { chat = false },
            visible = chat,
            title = "Andee",
            state = rememberWindowState(width = 520.dp, height = 760.dp),
        ) {
            chatWindow = window
            MenuBar {
                Menu("Andee") {
                    Item(if (Strings.lang() == "zh") "显示对话" else "Show chat", onClick = { chat = true })
                    Item(if (Strings.lang() == "zh") "退出 Andee" else "Quit Andee", onClick = ::exitApplication,
                        shortcut = androidx.compose.ui.input.key.KeyShortcut(androidx.compose.ui.input.key.Key.Q, meta = true))
                }
            }
            androidx.compose.foundation.layout.Box {
                if (settings) SettingsScreen { settings = false } else ChatScreen { settings = true }
                pending?.let { ApprovalCard(it) }
            }
        }
    }
}

/**
 * Shared-module logging goes to `andee.log` next to the data, rotated at
 * 2 MB. A packaged app has no terminal, so this file is the only place a
 * failure leaves a trace.
 */
private fun installFileLog() {
    val f = AppDirs.file("andee.log")
    if (f.length() > 2_000_000) f.renameTo(File(f.parentFile, "andee.log.1"))
    val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    net.kuafuai.andee.util.Log.sink = object : net.kuafuai.andee.util.Log.Sink {
        override fun log(level: Char, tag: String, msg: String, t: Throwable?) {
            val line = "${fmt.format(Date())} $level/$tag: $msg" + (t?.let { "\n" + it.stackTraceToString() } ?: "")
            synchronized(this) { runCatching { f.appendText(line + "\n") } }
            if (level == 'W' || level == 'E') System.err.println(line)
        }
    }
}
