package net.kuafuai.andee.desktop

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import java.awt.GraphicsEnvironment

/** The ball's window title; the native side finds the window by it. */
const val BALL_TITLE = "Andee Ball"

private val BALL_WINDOW = 112.dp

/**
 * The ball, floating above every app the way the phone's overlay floats above
 * every app: borderless, transparent, never takes focus, on every Space and
 * over full-screen apps (that last part is the native `floatWindow`, because a
 * JVM's "always on top" is only the floating level on the current Space).
 *
 * While a task is running or being read out, any press stops it (see `working` / `onStop`).
 *
 * The phone's three gestures: tap talks, double-tap types (the chat window comes
 * up with the cursor in the input box), long-press opens the window. Quitting is
 * ⌘Q, since closing the chat only hides it. Drag moves it, and where it was left
 * is remembered. A tap waits out the double-tap interval before it fires, which
 * is what lets the two be told apart.
 */
@Composable
fun ApplicationScope.FloatingBall(
    onTap: () -> Unit, onDoubleTap: () -> Unit, onLongPress: () -> Unit, onWindow: (java.awt.Window) -> Unit,
    /** True while a task is running or being read out; a press then stops it at once. */
    working: () -> Boolean = { false }, onStop: () -> Unit = {},
) {
    val state = rememberWindowState(size = DpSize(BALL_WINDOW, BALL_WINDOW), position = savedPosition())
    Window(
        onCloseRequest = ::exitApplication,
        state = state,
        title = BALL_TITLE,
        undecorated = true,
        transparent = true,
        resizable = false,
        alwaysOnTop = true,
        focusable = false,
    ) {
        val w = window
        onWindow(w)
        LaunchedEffect(Unit) {
            // The native window exists once AWT has shown it; ask until it has.
            repeat(20) {
                if (w.isShowing && net.kuafuai.andee.desktop.mac.Bridge.floatWindow(BALL_TITLE)) return@LaunchedEffect
                delay(100)
            }
        }
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    // Follow the mouse on the *screen*, not the pointer's offset inside
                    // the window: the window moves under the pointer with every step, so
                    // window-relative deltas half-cancel each other and the ball shudders.
                    var grab = java.awt.Point()
                    var origin = java.awt.Point()
                    detectDragGestures(
                        onDragStart = {
                            grab = java.awt.MouseInfo.getPointerInfo().location
                            origin = w.location
                        },
                        onDragEnd = { Settings.save(mapOf("ball_pos" to "${w.x},${w.y}")) },
                    ) { change, _ ->
                        change.consume()
                        val now = java.awt.MouseInfo.getPointerInfo().location
                        w.setLocation(origin.x + now.x - grab.x, origin.y + now.y - grab.y)
                    }
                }
                .pointerInput(Unit) {
                    // A tap waits out the double-tap interval before it fires, which is half a second of
                    // "nothing happened" when the user is trying to make it stop. So while it is working
                    // the stop happens on the press itself, and the rest of that gesture is ignored.
                    var stopped = false
                    detectTapGestures(
                        onPress = { stopped = working(); if (stopped) onStop() },
                        onTap = { if (!stopped) onTap() },
                        onDoubleTap = { if (!stopped) onDoubleTap() },
                        onLongPress = { if (!stopped) onLongPress() },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            BallView(BALL_WINDOW, onClick = null)
        }
    }
}

/** Where the user left it; bottom-right of the main screen the first time. */
private fun savedPosition(): WindowPosition {
    Settings.get("ball_pos").split(',').mapNotNull { it.trim().toIntOrNull() }.takeIf { it.size == 2 }?.let { (x, y) ->
        val onScreen = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
            .any { it.defaultConfiguration.bounds.contains(x + 40, y + 40) }
        if (onScreen) return WindowPosition(x.dp, y.dp)
    }
    val b = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    return WindowPosition((b.x + b.width - 112 - 24).dp, (b.y + b.height - 112 - 24).dp)
}
