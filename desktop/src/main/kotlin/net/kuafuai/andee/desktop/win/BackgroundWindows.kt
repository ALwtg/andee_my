package net.kuafuai.andee.desktop.win

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kuafuai.andee.desktop.GlassHeader
import net.kuafuai.andee.desktop.PrimaryButton
import net.kuafuai.andee.desktop.SecondaryButton
import net.kuafuai.andee.desktop.glassWell
import java.awt.Point
import java.awt.Toolkit
import java.util.Base64
import kotlin.math.abs

/**
 * The two windows a background window owns: the ball that says it is running, and
 * the supervision window the ball opens.
 *
 * **Both are Compose windows in this application**, not Swing ones, for one
 * reason: they have to look like the rest of Andee. A JFrame here would be a
 * second visual language for the same product, and the ball is the one piece of
 * this feature the user sees constantly.
 *
 * The pair exists because of an invariant this project keeps everywhere: work
 * that happens where the user cannot see it must be **visible and interruptible**.
 * The ball is the visible half (an always-on-top icon of the app being driven),
 * the supervision window is the interruptible half (live frames, tap-to-act,
 * type, close).
 */
@Composable
fun ApplicationScope.BackgroundSurface() {
    // `WinBackground` is plain Kotlin and knows nothing about Compose; the change
    // notification is the bridge. The callback fires on whatever thread opened or
    // closed the window, so the state write is posted to the EDT rather than
    // trusting it.
    var target by remember { mutableStateOf(WinBackground.state()) }
    DisposableEffect(Unit) {
        val hook: () -> Unit = {
            javax.swing.SwingUtilities.invokeLater { target = WinBackground.state() }
        }
        WinBackground.onChanged = hook
        onDispose { if (WinBackground.onChanged === hook) WinBackground.onChanged = null }
    }

    var supervising by remember { mutableStateOf(false) }

    val live = target
    if (live != null) {
        BackgroundBall(
            target = live,
            onOpen = { supervising = true },
        )
        if (supervising) {
            BackgroundPreview(
                target = live,
                onDismiss = { supervising = false },
            )
        }
    }
}

// ---- the ball ---------------------------------------------------------------

/** Sizes the long-press cycles through, in dp. Same ladder as the phone's ball. */
private val BALL_SIZES = listOf(48, 56, 72)

/** Title of the background ball's window — distinct from the chat ball's. */
const val BACKGROUND_BALL_TITLE = "Andee Background"

/**
 * The ball: the target app's icon, always on top, parked at the nearest edge.
 *
 * Three behaviours, all of them answering "what is this thing and how do I make
 * it stop":
 *
 *  * **click** → supervision;
 *  * **drag** → move it, and on release it snaps to the nearer side;
 *  * **long press** → next size.
 *
 * Plus the one the phone has: after eight idle seconds at an edge it fades to
 * 0.35 and slides half out, so it stops covering anything — and the first touch
 * brings it back before the gesture is interpreted. Without that restore the
 * first click on a dimmed ball would land on a half-hidden window.
 */
@Composable
private fun ApplicationScope.BackgroundBall(target: WinBackground.Target, onOpen: () -> Unit) {
    var sizeDp by remember { mutableStateOf(BALL_SIZES[1]) }
    val state = rememberWindowState(
        size = DpSize(sizeDp.dp, sizeDp.dp),
        position = WindowPosition(Alignment.BottomEnd),
    )
    Window(
        onCloseRequest = { },
        state = state,
        title = BACKGROUND_BALL_TITLE,
        undecorated = true,
        transparent = true,
        resizable = false,
        alwaysOnTop = true,
        focusable = false,
    ) {
        val w = window
        val icon: ImageBitmap? = remember(target.hwnd, target.exe) {
            WinBackground.iconPng(target)
                ?.let { bytes ->
                    runCatching {
                        org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
                    }.getOrNull()
                }
        }
        val screen = remember { Toolkit.getDefaultToolkit().screenSize }
        var dimmed by remember { mutableStateOf(false) }

        // Idle fade. `window.opacity` is honoured by Windows on an undecorated
        // window, which is why this needs no native call.
        LaunchedEffect(dimmed) {
            when {
                !dimmed -> {
                    if (w.opacity != 1f) w.opacity = 1f
                }
                else -> w.opacity = 0.35f
            }
        }
        LaunchedEffect(target.hwnd) {
            while (true) {
                delay(8_000)
                val atEdge = w.x <= 2 || w.x + w.width >= screen.width - 2
                if (atEdge && !dimmed) {
                    dimmed = true
                    // Slip half of itself off the screen, in the direction it is
                    // already leaning, so it takes no space at all.
                    if (w.x <= 2) w.setLocation(-w.width / 2, w.y) else w.setLocation(screen.width - w.width / 2, w.y)
                }
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(target.hwnd) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        // Restore before anything else: the drag is measured against
                        // the window's position, and a restored window has moved.
                        if (dimmed) {
                            dimmed = false
                            w.opacity = 1f
                            w.setLocation(
                                if (w.x < 0) 0 else screen.width - w.width,
                                w.y,
                            )
                        }
                        val originX = w.x
                        val originY = w.y
                        val pointer0 = java.awt.MouseInfo.getPointerInfo()?.location ?: Point()
                        val started = System.currentTimeMillis()
                        var moved = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val now = java.awt.MouseInfo.getPointerInfo()?.location
                            if (now != null) {
                                val dx = now.x - pointer0.x
                                val dy = now.y - pointer0.y
                                if (!moved && abs(dx) + abs(dy) > 4) moved = true
                                if (moved) w.setLocation(originX + dx, originY + dy)
                            }
                            change.consume()
                        }
                        if (!moved) {
                            if (System.currentTimeMillis() - started >= 550) {
                                val next = BALL_SIZES[(BALL_SIZES.indexOf(sizeDp) + 1) % BALL_SIZES.size]
                                sizeDp = next
                            } else {
                                onOpen()
                            }
                        } else {
                            // Park at the nearer side — the phone's habit, and it
                            // keeps the ball out of whatever the user is aiming at.
                            val mid = w.x + w.width / 2
                            w.setLocation(if (mid < screen.width / 2) 0 else screen.width - w.width, w.y)
                        }
                    }
                }
                .background(Color(0xE6191A1D), CircleShape)
                .border(1.dp, Color(0x40FFFFFF), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = target.title,
                    modifier = Modifier.fillMaxSize(0.62f),
                    contentScale = ContentScale.Fit,
                )
            } else {
                // No icon is not a failure worth a dialog: the first letter of what
                // the user calls it says the same thing.
                Text(
                    text = target.title.take(1).ifBlank { "?" },
                    color = Color(0xFFEDEDED),
                )
            }
        }
    }
}

// ---- supervision ------------------------------------------------------------

/**
 * Supervision: watch the hidden window, and take over when watching is not
 * enough.
 *
 * Four controls, each of which is the desktop answer to something the phone's
 * supervision page has: the live picture (tap it and the tap goes through),
 * 返回键 (Escape — the closest thing Windows has), a text field, and a close
 * button. Clicking the picture is a **real** click on a **real** screen point:
 * the frame is fitted to the window's aspect ratio so the mapping is linear, and
 * the result line says which path acted (`uia_invoke` never moves the pointer,
 * `click` always does).
 */
@Composable
private fun ApplicationScope.BackgroundPreview(
    target: WinBackground.Target,
    onDismiss: () -> Unit,
) {
    val state = rememberWindowState(size = DpSize(430.dp, 860.dp))
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var aspect by remember { mutableStateOf(1080f / 1920f) }
    var blank by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var typed by remember { mutableStateOf("") }
    var boxSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    // Taps are dispatched off the UI thread: `tapNorm` can post a message and sleep,
    // and a supervision window that freezes while its own tap is being delivered is
    // indistinguishable from a hung target.
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Window(
        onCloseRequest = onDismiss,
        state = state,
        title = "副屏 · ${target.title}",
        alwaysOnTop = true,
    ) {
        LaunchedEffect(target.hwnd) {
            while (true) {
                val shot = withContext(Dispatchers.IO) { WinBackground.frame() }
                blank = shot.blank
                if (shot.png != null) {
                    val decoded = runCatching {
                        val bytes = Base64.getDecoder().decode(shot.png)
                        org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
                    }.getOrNull()
                    if (decoded != null) {
                        frame = decoded
                        if (shot.height > 0) aspect = shot.width.toFloat() / shot.height
                    }
                }
                if (!WinBackground.alive()) {
                    note = "目标窗口已经关掉了"
                    onDismiss()
                    break
                }
                delay(700)
            }
        }

        Column(
            Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlassHeader(
                title = "副屏 · ${target.title}",
                subtitle = if (blank) "这个窗口不让抓图（GPU 合成），但点按仍然有效" else null,
                onClose = onDismiss,
            )

            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .glassWell()
                    .onSizeChanged { boxSize = it }
                    .pointerInput(target.hwnd) {
                        detectTapGestures { off ->
                            if (boxSize.width == 0 || boxSize.height == 0) return@detectTapGestures
                            // The picture is laid out at the window's aspect ratio, so
                            // a proportion of the box is a proportion of the frame.
                            val nx = (off.x / boxSize.width * 1000).toInt().coerceIn(0, 1000)
                            val ny = (off.y / boxSize.height * 1000).toInt().coerceIn(0, 1000)
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { WinBackground.tapNorm(nx, ny) }.getOrNull()
                                }
                                note = result?.let { r ->
                                    val how = r.optString("how")
                                    "点 ($nx,$ny) → $how"
                                } ?: "点 ($nx,$ny) 失败"
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                val f = frame
                if (f != null) {
                    Image(
                        bitmap = f,
                        contentDescription = target.title,
                        modifier = Modifier.fillMaxWidth().aspectRatio(aspect),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Text("正在读取窗口画面…", color = Color(0xFFBDBDBD))
                }
            }

            Text(
                text = note.ifBlank { "点画面 = 在那一处点一下；下面可以打字或发返回键" },
                color = Color(0xFFB9B9B9),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("返回键") {
                    val ok = Win32.pressKey(0x1B) // VK_ESCAPE
                    note = if (ok) "已发 Esc" else "Esc 没发出去"
                }
                SecondaryButton("刷新") {
                    note = "重新读取…"
                }
                SecondaryButton("关闭副屏") {
                    WinBackground.close()
                    onDismiss()
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    androidx.compose.material.TextField(
                        value = typed,
                        onValueChange = { typed = it },
                        singleLine = true,
                        label = { Text("打字给副屏…") },
                    )
                }
                PrimaryButton("发送") {
                    val payload = typed
                    if (payload.isNotEmpty()) {
                        typed = ""
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                runCatching { WinBackground.type(payload) }.getOrNull()
                            }
                            note = r?.let {
                                "已输入 ${it.optInt("chars", payload.length)} 字（${it.optString("how")}）"
                            } ?: "输入失败"
                        }
                    }
                }
            }
        }
    }
}
