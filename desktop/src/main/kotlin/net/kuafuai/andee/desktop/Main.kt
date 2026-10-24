package net.kuafuai.andee.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import kotlinx.coroutines.delay
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
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
    // Intel Mac: skiko's Metal renderer crashes (SIGILL in
    // MetalRedrawer.createMetalDevice) on these machines, so fall back to
    // software rendering. Apple Silicon keeps the hardware Metal path. Must run
    // before any SkiaLayer is created, so before `application { }` below.
    if (System.getProperty("os.arch") in setOf("x86_64", "amd64")) {
        System.setProperty("skiko.renderApi", "SOFTWARE")
    }
    installFileLog()
    net.kuafuai.andee.util.Log.i("Andee", "starting, data in ${AppDirs.root}")
    Assistant.askApproval = { ApprovalGate.request(it) }
    MacBall.restoreLook()
    Assistant.start()
    Peers.startIfEnabled()
    application {
        var pending by remember { mutableStateOf(ApprovalGate.current) }
        // The chat window comes and goes (the ball is always there); the nav says what it shows.
        var chat by remember { mutableStateOf(true) }
        val nav = remember { Nav() }
        ApprovalGate.onChange = { pending = ApprovalGate.current }
        var ballWindow by remember { mutableStateOf<java.awt.Window?>(null) }
        var chatWindow by remember { mutableStateOf<java.awt.Window?>(null) }
        var ask by remember { mutableStateOf(AskGate.current) }
        AskGate.onChange = { ask = AskGate.current }
        // A click aimed at another app must not land on Andee: both windows step aside for the instant of it.
        // (Native, not Window.opacity: AWT refuses that on the decorated chat window.)
        // Like the phone folding its card before it drives another app: the chat window would cover
        // that app, and while it is frontmost the brain's keystrokes would land in Andee's own input.
        // The ball stays; a long-press or a Dock click brings the chat back.
        Assistant.yieldScreen = { if (chat) { chat = false; Thread.sleep(220) } }
        Assistant.hideWindows = { hide -> net.kuafuai.andee.desktop.mac.Bridge.setOwnAlpha(if (hide) 0.0 else 1.0) }

        // Bringing the window up is two things: make it visible, then pull the app forward (an
        // accessory-style ball never owns the foreground, so a visible window can still sit behind).
        var frontTick by remember { mutableStateOf(0) }
        fun openChat(screen: String = "chat", typing: Boolean = false) {
            chat = true
            nav.go(screen)
            if (typing) ComposerFocus.pending = true
            frontTick++
        }
        LaunchedEffect(frontTick) {
            if (frontTick == 0) return@LaunchedEffect
            delay(60)
            chatWindow?.let { w ->
                if (w is java.awt.Frame && (w.extendedState and java.awt.Frame.ICONIFIED) != 0) w.extendedState = java.awt.Frame.NORMAL
                w.toFront()
            }
            runCatching {
                val d = java.awt.Desktop.getDesktop()
                if (d.isSupported(java.awt.Desktop.Action.APP_REQUEST_FOREGROUND)) d.requestForeground(true)
            }
        }
        // A click on the Dock icon. With the window closed there is nothing for the system to raise,
        // so without this the icon opened it only when it happened to still be on screen.
        DisposableEffect(Unit) {
            val d = runCatching { java.awt.Desktop.getDesktop() }.getOrNull()
            val l = java.awt.desktop.AppReopenedListener { javax.swing.SwingUtilities.invokeLater { openChat() } }
            runCatching { if (d?.isSupported(java.awt.Desktop.Action.APP_EVENT_REOPENED) == true) d.addAppEventListener(l) }
            onDispose { runCatching { d?.removeAppEventListener(l) } }
        }

        FloatingBall(
            actions = ballActions(onType = { openChat(typing = true) }, onNoVoice = { openChat("settings") })
                .let { a -> BallActions(a.onTap, a.onDoubleTap, a.onTripleTap) { openChat() } },
            onWindow = { ballWindow = it },
        )
        BallCaptionWindow(ballWindow, suppressed = pending != null || ask != null)
        PromptWindow(ballWindow, pending, ask)

        Window(
            onCloseRequest = { chat = false },
            visible = chat,
            title = "Andee",
            // Big enough that the conversation, the ball above it and the sidebar all breathe on a laptop; never
            // past 90% of the screen it opens on.
            state = rememberWindowState(width = defaultChatSize().first, height = defaultChatSize().second),
            resizable = true,
        ) {
            chatWindow = window
            window.minimumSize = java.awt.Dimension(760, 520)
            MenuBar {
                Menu("Andee") {
                    Item(if (Strings.lang() == "zh") "新对话" else "New chat", onClick = { Assistant.newConversation(); chat = true; nav.go("chat") },
                        shortcut = androidx.compose.ui.input.key.KeyShortcut(androidx.compose.ui.input.key.Key.N, meta = true))
                    Item(if (Strings.lang() == "zh") "显示对话" else "Show chat", onClick = { chat = true; nav.go("chat") })
                    Item(if (Strings.lang() == "zh") "设置" else "Settings", onClick = { chat = true; nav.go("settings") },
                        shortcut = androidx.compose.ui.input.key.KeyShortcut(androidx.compose.ui.input.key.Key.Comma, meta = true))
                    Item(if (Strings.lang() == "zh") "退出 Andee" else "Quit Andee", onClick = ::exitApplication,
                        shortcut = androidx.compose.ui.input.key.KeyShortcut(androidx.compose.ui.input.key.Key.Q, meta = true))
                }
            }
            AppRoot(nav)
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

/** The chat window's first size: 1280×960 points, shrunk to 90% of the main screen when that is smaller. */
private fun defaultChatSize(): Pair<androidx.compose.ui.unit.Dp, androidx.compose.ui.unit.Dp> {
    val screen = runCatching { java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds }.getOrNull()
    val w = minOf(1280, ((screen?.width ?: 1280) * 0.9).toInt())
    val h = minOf(960, ((screen?.height ?: 960) * 0.9).toInt())
    return w.dp to h.dp
}

/**
 * The ball's taps, shared by the floating ball and the one above the conversation. [onType] brings the chat up
 * with the cursor in the box; a long press is the caller's (the floating ball opens the window, the chat's own
 * ball has nothing to open).
 */
fun ballActions(onType: () -> Unit, onNoVoice: () -> Unit) = BallActions(
    onTap = {
        when {
            Meeting.isActive() -> Meeting.stopFromUser()
            else -> {
                // The phone's rule: a tap is "listen to me now". Whatever was running or being read out stops
                // first, and the mic opens in the same tap rather than needing a second one.
                if (Assistant.working()) Assistant.stop()
                if (!net.kuafuai.andee.desktop.voice.MacVoice.toggle()) onNoVoice()
            }
        }
    },
    // Typing is a new request too: whatever was running or being read out stops first, as the tap does.
    onDoubleTap = { if (Assistant.working()) Assistant.stop(); onType() },
    onTripleTap = { if (Meeting.isActive()) Meeting.stopFromUser() else Assistant.stop() },
    onLongPress = {},
)
