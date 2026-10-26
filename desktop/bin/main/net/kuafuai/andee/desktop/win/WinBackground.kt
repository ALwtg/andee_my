package net.kuafuai.andee.desktop.win

import net.kuafuai.andee.net.SurfacePolicy
import net.kuafuai.andee.util.Log
import org.json.JSONObject
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

/**
 * The background screen on Windows — which on Windows means **a window, not a
 * display**.
 *
 * ## What the feature is here
 *
 * Android could give the app a display of its own; Windows has no such thing for
 * a normal user-mode process (a virtual monitor is a kernel driver). What Windows
 * does have is better for the actual goal: UI Automation can read and *act on*
 * controls of a window that is **not in front**, so "work on this app while the
 * user does something else" does not need a second screen at all.
 *
 * So the contract is the phone's, translated:
 *
 * | phone | here |
 * |---|---|
 * | a display the user cannot see | a window the user is not looking at |
 * | `am start --display N` | bind to the app's existing window (launch it if it is not running) |
 * | `input -d N` | UIA patterns first, posted messages second, real clicks last |
 * | `screencap -d N` | `PrintWindow` on that window |
 * | the ball + supervision page | the same ball, the same supervision window |
 *
 * ## The three ways to act, and why the reply names one
 *
 * 1. **UIA pattern** (`Invoke`, `SelectionItem`, `LegacyIAccessible`) — acts on the
 *    control itself. No cursor movement, no focus change. This is the good path.
 * 2. **Posted message** (`WM_LBUTTONDOWN/UP` with client coordinates) — the window
 *    handles it as if the mouse were over it. Works for most Win32 apps, ignored
 *    by many WPF/UWP ones.
 * 3. **Real click** — always works, and moves the user's pointer and foreground
 *    window. It is a fallback, and the reply says `click` so nobody believes the
 *    background promise when it did not hold.
 *
 * ## What it refuses, and why the refusal is not negotiable here
 *
 * `SurfacePolicy` — the user's black and white lists plus the shipped categories
 * (finance, payment, games, flash sales) — lives in `core` and is checked on every
 * open. The reason is the same as on the phone and is not a technical one: those
 * are the apps whose risk engines react to exactly this shape of automation, and
 * forcing it trades the user's account for a feature.
 */
object WinBackground {

    private const val TAG = "WinBackground"

    /** A bound window plus what the ball and the status row need to describe it. */
    data class Target(
        val hwnd: Long,
        val pkg: String,
        val title: String,
        val exe: String,
        val startedAt: Long,
    )

    @Volatile private var current: Target? = null

    /** Told whenever a target appears or goes away — the ball is driven from here. */
    @Volatile var onChanged: (() -> Unit)? = null

    fun state(): Target? = current

    private fun changed() {
        runCatching { onChanged?.invoke() }
    }

    // ---- lifecycle --------------------------------------------------------

    /**
     * Bind to [pkg] — a process name (`Weixin.exe`), a title fragment, or an
     * executable path.
     *
     * **Launching is the honest part.** If the app is not running, it is started,
     * and Windows brings a freshly started application to the front — there is no
     * `--display` to send it to. The reply says `launched: true` so the caller can
     * tell the user their screen was used once, at the start, rather than
     * discovering it when the app appears over their work.
     */
    fun open(pkg: String, label: String = pkg, rules: SurfacePolicy.Rules = SurfacePolicy.Rules()): Result<Target> {
        current?.let {
            return Result.failure(
                IllegalStateException(
                    "a background window is already bound (${it.title}); close it first — one at a time " +
                        "is the rule on every platform",
                ),
            )
        }
        val verdict = SurfacePolicy.verdict(pkg, rules)
        if (!verdict.allowed) {
            return Result.failure(IllegalStateException("the background screen is closed for this app: ${verdict.reason}"))
        }

        val found = findWindow(pkg)
        val launched = found == null
        val win = found ?: launch(pkg) ?: return Result.failure(
            IllegalStateException("no window and nothing to launch for '$pkg'"),
        )
        val target = Target(
            hwnd = win.hwnd,
            pkg = pkg,
            title = win.title.ifEmpty { label },
            exe = Win32.exeOfPid(win.pid).orEmpty(),
            startedAt = System.currentTimeMillis(),
        )
        current = target
        Log.i(TAG, "bound hwnd=${target.hwnd} \"${target.title}\" launched=$launched")
        changed()
        return Result.success(target)
    }

    fun close(): Boolean {
        current = null
        changed()
        return true
    }

    /**
     * Whether the bound window still exists.
     *
     * A window can go away for reasons nobody told us about — the user closed it,
     * the app crashed, Windows restarted the shell. Reporting that honestly is the
     * difference between "the background work stopped" and "the background work is
     * silently doing nothing".
     */
    fun alive(): Boolean {
        val t = current ?: return false
        if (Win32.isAlive(t.hwnd)) return true
        Log.i(TAG, "the bound window is gone (hwnd=${t.hwnd})")
        close()
        return false
    }

    // ---- finding and launching -------------------------------------------

    private fun findWindow(pkg: String): Win32.Win? {
        val needle = pkg.lowercase().removeSuffix(".exe")
        val windows = Win32.windows()
        // Process name first: exact, and what a caller means by "Weixin".
        windows.firstOrNull { w ->
            Win32.exeOfPid(w.pid)?.substringAfterLast('\\')?.lowercase()?.removeSuffix(".exe") == needle
        }?.let { return it }
        // Then the title, which is what a person says out loud.
        return windows.firstOrNull { it.title.lowercase().contains(needle) }
    }

    private fun launch(pkg: String): Win32.Win? {
        // One launcher, in Win32: `device.launch` and this must resolve App Paths
        // the same way, or "the app would not start" would be true in one place and
        // false in the other for the same name.
        if (!Win32.startExe(pkg)) {
            Log.w(TAG, "could not start $pkg")
            return null
        }
        // Wait for a window to appear: an app that has just started has none, and
        // returning the old list would bind to whatever happened to be in front.
        val deadline = System.currentTimeMillis() + 12_000
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(400)
            findWindow(pkg)?.let { return it }
        }
        return null
    }

    // Resolving an executable and reading a process's path both moved to
    // [Win32]: `device.launch` needs the same App Paths rule, and two copies of
    // "where is this program" is how one of them ends up wrong.

    // ---- reading ----------------------------------------------------------

    /** The bound window's tree, in the same shape `screen.ui_tree` produces. */
    fun tree(max: Int = 600): List<WinUia.Node> {
        val t = current ?: return emptyList()
        if (!alive()) return emptyList()
        return WinUia.tree(t.hwnd, max)
    }

    /**
     * The bound app's icon as PNG bytes, for the ball.
     *
     * PNG rather than the `BufferedImage` itself because the ball lives in
     * Compose, which wants a skia image — and encoding once when the window is
     * bound is cheaper than converting on every recomposition.
     */
    fun iconPng(t: Target? = current): ByteArray? {
        val target = t ?: return null
        val exe = target.exe.ifEmpty { target.pkg }
        // Two spellings: `exe` is what the process reported, and the second
        // attempt covers a pkg given without its extension.
        val img = Win32.iconFor(exe) ?: Win32.iconFor("$exe.exe") ?: return null
        val out = ByteArrayOutputStream()
        return runCatching {
            ImageIO.write(img, "png", out)
            out.toByteArray()
        }.getOrNull()
    }

    /**
     * One picture of the bound window.
     *
     * A GPU-composited window prints as nothing, and this is where that gets said:
     * [blank] is not "the app is black", it is "this window will not let us see
     * it", and the caller must report that rather than describe an empty screen.
     */
    class Frame(val png: String?, val blank: Boolean, val width: Int, val height: Int)

    fun frame(): Frame {
        val t = current ?: return Frame(null, blank = false, width = 0, height = 0)
        val img: BufferedImage = Win32.captureWindow(t.hwnd)
            ?: return Frame(null, blank = false, width = 0, height = 0)
        val blank = Win32.looksBlank(img)
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return Frame(
            Base64.getEncoder().encodeToString(out.toByteArray()),
            blank,
            img.width,
            img.height,
        )
    }

    // ---- acting -----------------------------------------------------------

    /**
     * Act on an element inside the bound window, preferring the way that does not
     * touch the user's screen.
     *
     * @return the JSON the tool layer returns: `how` names the path that worked.
     */
    fun tapNode(path: String, long: Boolean = false): JSONObject {
        val t = current ?: throw IllegalStateException("no background window is bound")
        if (!alive()) throw IllegalStateException("the bound window has closed; open one again")

        if (!long) {
            // Patterns act on the control itself. `invoke` first, then `select` —
            // a "tap by name" lands on list rows and nav items more often than on
            // buttons, and those answer SelectionItemPattern, not Invoke — then the
            // legacy default action, which is what older Win32 controls answer.
            // Measured: `find "蓝牙"` in Windows Settings returns a ListItem with
            // exactly one pattern, `select`, so the first two attempts miss it.
            for (action in listOf("invoke", "select", "legacy")) {
                WinUia.act(t.hwnd, path, action)?.let {
                    return JSONObject().put("done", true).put("how", "uia_$it")
                }
            }
        }

        val node = tree().firstOrNull { it.path == path }
            ?: throw IllegalArgumentException("that element is no longer in the window; re-read the tree")
        val client = Win32.clientRectOnScreen(t.hwnd)
            ?: throw IllegalStateException("the window has no client area to aim at")
        val cx = node.cx - client[0]
        val cy = node.cy - client[1]
        if (Win32.postClick(t.hwnd, cx, cy)) {
            return JSONObject().put("done", true).put("how", "post_message")
                .put(
                    "note",
                    "The click was posted, not simulated: this app may or may not honour it. " +
                        "Check the next frame before assuming it worked.",
                )
        }
        Win32.click(node.cx, node.cy, holdMs = if (long) 600 else 40)
        return JSONObject().put("done", true).put("how", "click")
            .put("note", "This was a real click: the user's pointer moved and the window came forward.")
    }

    /**
     * The phone's stuck-loop guard, on the screen that needs it most.
     *
     * Four gestures within 120 px of each other inside five minutes is refused.
     * The background window has a tree (so a blind repeat is less likely than on a
     * phone), but it is also the surface nobody is watching: a model grinding on
     * one spot there produces nothing the user can see, which is exactly the case
     * this guard exists for.
     */
    private val spots = HashMap<String, MutableList<Long>>()

    private fun guardSpot(px: Int, py: Int) {
        val now = System.currentTimeMillis()
        synchronized(spots) {
            spots.values.forEach { it.removeAll { t -> now - t > 300_000 } }
            val key = spots.keys.firstOrNull { k ->
                k.split(',').let { Math.hypot(it[0].toDouble() - px, it[1].toDouble() - py) < 120 }
            } ?: "$px,$py"
            val list = spots.getOrPut(key) { mutableListOf() }
            if (list.size >= 3) {
                throw IllegalStateException(
                    "Refused: this is the fourth gesture on the same spot in five minutes. Nothing is " +
                        "changing there — take a fresh background_screenshot, check the window is still " +
                        "showing what you think it is, and if it still will not move, tell the user where " +
                        "you are stuck instead of clicking again.",
                )
            }
            list += now
        }
    }

    // ---- by name ----------------------------------------------------------

    /** A match, with the score that put it where it is. */
    data class Hit(val node: WinUia.Node, val score: Double)

    /**
     * A label as people actually type one.
     *
     * NFKC turns full-width `Ａ`/`？`/`：` into their ASCII twins — a Chinese IME
     * produces those constantly, and a model copying a label out of a screenshot
     * has no way to know which form it got. Whitespace goes entirely: `发送 ` and
     * `发 送` are the same button to a user, and UI Automation is inconsistent
     * about trailing spaces.
     */
    internal fun normalize(s: String): String =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC)
            .lowercase()
            .replace(Regex("\\s+"), "")

    /**
     * Rank [nodes] against [text], best first. **Pure**, so it is tested without a
     * window: the ranking rules are the part that can be wrong silently.
     *
     * Order: exact name beats prefix beats contains; then anything invokable beats
     * anything not; then the smaller control, because a button called "发送" is
     * usually nested inside a panel called "发送".
     */
    internal fun rank(nodes: List<WinUia.Node>, text: String): List<Hit> {
        val needle = normalize(text)
        if (needle.isEmpty()) return emptyList()
        return nodes.mapNotNull { n ->
            if (!n.enabled || n.w <= 0 || n.h <= 0) return@mapNotNull null
            val name = normalize(n.name)
            val score = when {
                name == needle -> 1.0
                name.startsWith(needle) -> 0.85
                name.contains(needle) -> 0.7
                else -> return@mapNotNull null
            }
            Hit(n, score)
        }.sortedWith(
            compareByDescending<Hit> { it.score }
                .thenByDescending { it.node.clickable }
                .thenBy { it.node.w.toLong() * it.node.h },
        )
    }

    /**
     * Tap the control the user would name.
     *
     * Refuses, rather than guessing, when two equally good candidates are both
     * actionable — two buttons called "确定" is the normal case in a dialog, and
     * picking the first is how the wrong one gets pressed.
     */
    fun tapName(text: String, exact: Boolean = false, long: Boolean = false): JSONObject {
        val t = current ?: throw IllegalStateException("no background window is bound")
        if (!alive()) throw IllegalStateException("the bound window has closed; open one again")
        val hits = rank(WinUia.find(t.hwnd, text, exact), text)
        if (hits.isEmpty()) {
            return JSONObject().put("tapped", false).put("reason", "no_match").put("text", text)
                .put(
                    "hint",
                    "Nothing in this window is named \"$text\". Some apps (Electron, games) name almost " +
                        "nothing — check background_ui_tree, and aim with a grid cell if the list is empty.",
                )
        }
        val best = hits.first()
        val rival = hits.getOrNull(1)
        if (rival != null && rival.score == best.score && best.node.clickable && rival.node.clickable) {
            val arr = org.json.JSONArray()
            hits.take(5).forEach { h ->
                arr.put(
                    JSONObject().put("name", h.node.name).put("type", h.node.type)
                        .put("cell", cellOf(h.node)).put("x", h.node.cx).put("y", h.node.cy)
                        .put("score", h.score),
                )
            }
            return JSONObject().put("tapped", false).put("reason", "ambiguous").put("matches", arr)
                .put(
                    "hint",
                    "More than one control carries that name. Pick one by its cell with background_tap " +
                        "{\"cell\":…}, or ask the user which.",
                )
        }
        // `tapped` is the word every other background_tap reply uses, so a model
        // reading two replies does not have to learn two spellings of "yes".
        val out = tapNode(best.node.path, long)
        return out.put("tapped", out.optBoolean("done")).put(
            "matched",
            JSONObject().put("name", best.node.name).put("type", best.node.type)
                .put("score", best.score).put("source", "uia_name"),
        )
    }

    /** Which grid cell a node's centre falls in, so a refusal is actionable. */
    private fun cellOf(n: WinUia.Node): String {
        val c = Win32.clientRectOnScreen(current?.hwnd ?: 0L) ?: return ""
        val w = (c[2] - c[0]).coerceAtLeast(1)
        val h = (c[3] - c[1]).coerceAtLeast(1)
        val nx = (n.cx - c[0]) * 1000 / w
        val ny = (n.cy - c[1]) * 1000 / h
        // The same 8x12 the screenshots are drawn with; mirrored here so the reply
        // can name a cell the model can actually use.
        val col = (nx * 8 / 1000).coerceIn(0, 7)
        val row = (ny * 12 / 1000).coerceIn(0, 11)
        return "${('A' + col)}${row + 1}"
    }

    /** A tap in 0–1000 of the bound window's client area. */
    fun tapNorm(x: Int, y: Int, long: Boolean = false): JSONObject {
        require(x in 0..1000 && y in 0..1000) { "coordinates are 0-1000, got $x,$y" }
        val t = current ?: throw IllegalStateException("no background window is bound")
        if (!alive()) throw IllegalStateException("the bound window has closed; open one again")
        val client = Win32.clientRectOnScreen(t.hwnd)
            ?: throw IllegalStateException("the window has no client area to aim at")
        val w = client[2] - client[0]
        val h = client[3] - client[1]
        val px = client[0] + x * w / 1000
        val py = client[1] + y * h / 1000
        guardSpot(px, py)
        if (!long && Win32.postClick(t.hwnd, px - client[0], py - client[1])) {
            return JSONObject().put("done", true).put("how", "post_message").put("px", "$px,$py")
        }
        Win32.click(px, py, holdMs = if (long) 600 else 40)
        return JSONObject().put("done", true).put("how", "click").put("px", "$px,$py")
    }

    /**
     * Scroll or drag the bound window, in 0–1000 of its client area.
     *
     * **Wheel first, drag second, and the frame decides.** A wheel message scrolls
     * without moving the user's pointer, which is what this whole surface is for;
     * but plenty of windows ignore it (anything not based on a standard scroll
     * container). So the wheel is posted, the window is captured again, and only if
     * the pixels did not move does it fall back to a real held drag — which works
     * everywhere and moves the pointer.
     *
     * The reply names the path that actually happened. `post_wheel` with a
     * `changed` delta is evidence, not a guess; a drag says so.
     */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long = 400): JSONObject {
        require(x1 in 0..1000 && y1 in 0..1000 && x2 in 0..1000 && y2 in 0..1000) {
            "coordinates are 0-1000 of the window's client area, got ($x1,$y1) → ($x2,$y2)"
        }
        val t = current ?: throw IllegalStateException("no background window is bound")
        if (!alive()) throw IllegalStateException("the bound window has closed; open one again")
        val client = Win32.clientRectOnScreen(t.hwnd)
            ?: throw IllegalStateException("the window has no client area to aim at")
        val w = (client[2] - client[0]).coerceAtLeast(1)
        val h = (client[3] - client[1]).coerceAtLeast(1)
        fun pxX(n: Int) = client[0] + n * w / 1000
        fun pxY(n: Int) = client[1] + n * h / 1000
        val ax = pxX(x1)
        val ay = pxY(y1)
        val bx = pxX(x2)
        val by = pxY(y2)
        // Guarded at the start point: a swipe that begins where the last three
        // gestures began is the same stuck loop wearing a different name.
        guardSpot(ax, ay)

        val vertical = kotlin.math.abs(y2 - y1) >= kotlin.math.abs(x2 - x1)
        if (vertical) {
            // 20 notches ≈ one screen of text; the requested distance is in 0-1000,
            // so a 600-unit swipe asks for about twelve.
            val asked = ((y2 - y1) / 1000.0 * 20).toInt()
            val notches = if (asked == 0) (if (y2 > y1) 1 else -1) else asked
            val before = Win32.captureWindow(t.hwnd)
            if (Win32.postWheel(t.hwnd, (ax + bx) / 2, (ay + by) / 2, notches)) {
                Thread.sleep(320)
                val after = Win32.captureWindow(t.hwnd)
                val delta = if (before != null && after != null) Win32.frameDelta(before, after) else 0.0
                if (delta > 2.0) {
                    return JSONObject().put("done", true).put("how", "post_wheel")
                        .put("notches", notches).put("changed", kotlin.math.round(delta * 10) / 10.0)
                        .put("note", "Scrolled with a wheel message: the pointer did not move.")
                }
                // No pixels moved: the window does not honour the wheel. Fall
                // through to a drag rather than reporting a scroll that did not
                // happen.
            }
        }

        val dragged = Win32.drag(ax, ay, bx, by, ms)
        return JSONObject().put("done", dragged).put("how", "drag")
            .put(
                "note",
                if (dragged) {
                    "This was a real held drag: the user's pointer moved from the start point to the end."
                } else {
                    "The drag could not be delivered."
                },
            )
    }

    /**
     * Type into the bound window.
     *
     * Two paths again, and the difference matters: `WM_CHAR` posted to the window
     * types without focus; `SendInput` types into whatever has focus, so it is only
     * used after the window has been brought forward. A background window that
     * cannot be focused cannot be typed into by the second path, and the reply
     * says which happened.
     */
    fun type(text: String): JSONObject {
        val t = current ?: throw IllegalStateException("no background window is bound")
        if (!alive()) throw IllegalStateException("the bound window has closed; open one again")
        var posted = 0
        for (ch in text) if (Win32.postChar(t.hwnd, ch)) posted++
        if (posted == text.length) {
            return JSONObject().put("typed", true).put("how", "wm_char").put("chars", text.length)
        }
        // The message path did not take the text; the honest fallback changes the
        // user's focus, and says so.
        Win32.bringToFront(t.hwnd)
        Thread.sleep(120)
        val ok = Win32.typeText(text)
        return JSONObject().put("typed", ok).put("how", "send_input")
            .put("note", "This needed the window in front, so the user's screen was used for a moment.")
    }
}
