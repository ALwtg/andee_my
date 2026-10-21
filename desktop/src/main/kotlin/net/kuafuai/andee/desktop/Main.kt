package net.kuafuai.andee.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun main() {
    installFileLog()
    net.kuafuai.andee.util.Log.i("Andee", "starting, data in ${AppDirs.root}")
    Assistant.start()
    application {
        var settings by remember { mutableStateOf(false) }
        Window(
            onCloseRequest = ::exitApplication,
            title = "Andee",
            state = rememberWindowState(width = 520.dp, height = 760.dp),
        ) {
            if (settings) SettingsScreen { settings = false } else ChatScreen { settings = true }
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
