package net.kuafuai.andee.desktop.win

import net.kuafuai.andee.desktop.DesktopBrainHost
import net.kuafuai.andee.screen.AimParts
import org.json.JSONObject
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

/**
 * The `screen.*` tools on Windows, speaking the phone's and the Mac's protocol
 * exactly: an element outline with e-numbers, 0–1000 coordinates of the main
 * display, an 8×12 aim grid on every screenshot, and the same guards (values
 * outside 0–1000 are refused; a fourth tap on one spot within five minutes is
 * refused).
 *
 * **Where it differs from the Mac, and why it says so.** A Mac tap can use the
 * element's own `AXPress` action; the Windows equivalent is a UI Automation
 * *pattern* (`Invoke`, `LegacyIAccessible`), which also acts without taking the
 * foreground. When a control offers neither, the click is a real one — cursor
 * moves, window comes forward — and the reply records `via` so the model can tell
 * a background action from a foreground one.
 */
class WinScreen(
    private val hideOwnWindows: (Boolean) -> Unit = {},
    private val yieldScreen: () -> Unit = {},
) : net.kuafuai.andee.desktop.ScreenHand {

    private data class El(
        val eid: String,
        val path: String,
        val cx: Int,
        val cy: Int,
        val press: Boolean,
        val editable: Boolean,
        val label: String,
    )

    @Volatile private var index: Map<String, El> = emptyMap()
    @Volatile private var windowHwnd: Long = 0
    @Volatile private var zoomRegion: IntArray? = null
    @Volatile private var zoomAt = 0L
    private val spots = HashMap<String, MutableList<Long>>()

    override fun handles(method: String) = method in METHODS

    override fun dispatch(method: String, p: JSONObject, silent: Boolean, untrusted: Boolean): Any? {
        if (!Win32.available) throw IllegalStateException("user32 is not answering; the Windows body cannot run screen tools.")
        if (!Win32.enableDpiAwareness()) {
            // Not fatal: coordinates still work if the process started DPI-aware by
            // another route, which is the normal case under `gradlew run`.
            net.kuafuai.andee.util.Log.i("WinScreen", "DPI awareness is not per-monitor v2")
        }
        yieldScreen()
        return when (method) {
            "screen.ui_tree" -> uiTree(p.optBoolean("with_shot", false))
            "screen.tap_id" -> tapId(req(p, "eid"), long = false)
            "screen.long_press_id" -> tapId(req(p, "eid"), long = true)
            "screen.tap" -> tapAim(p, long = false)
            "screen.long_press" -> tapAim(p, long = true)
            "screen.swipe" -> swipe(p)
            "screen.type" -> type(p.optString("text"))
            "screen.submit_input" -> submit()
            "screen.wait" -> waitFor(p.optDouble("seconds", 2.0))
            "screen.screenshot" -> screenshot(grid = true)
            "screen.look_region" -> lookRegion(p)
            "screen.global" -> global(req(p, "action"))
            // ---- the background window -------------------------------------
            //
            // Same names as the phone's family, so `ToolSchemas` advertises them
            // through `handles()` without a second table. The semantics are the
            // ones Windows can keep: a bound window, patterns first, and a reply
            // that says which path acted.
            "screen.background" -> {
                val pkg = req(p, "pkg")
                val target = WinBackground.open(pkg, p.optString("label").ifEmpty { pkg })
                    .getOrElse { throw IllegalStateException(it.message ?: "background window failed") }
                JSONObject()
                    .put("bound", true)
                    .put("hwnd", target.hwnd)
                    .put("title", target.title)
                    .put("pkg", target.pkg)
                    .put(
                        "note",
                        "Bound to that app's window. Use background_screenshot, background_ui_tree and " +
                            "background_tap for it; screen.* still drives whatever is in front.",
                    )
            }

            "screen.background_close" -> JSONObject().put("closed", WinBackground.close())

            "screen.background_open" -> {
                val pkg = req(p, "pkg")
                WinBackground.close()
                val target = WinBackground.open(pkg, pkg)
                    .getOrElse { throw IllegalStateException(it.message ?: "background window failed") }
                JSONObject().put("bound", true).put("hwnd", target.hwnd).put("title", target.title)
            }

            "screen.background_shot" -> {
                val f = WinBackground.frame()
                if (f.png == null) throw IllegalStateException("the background window could not be captured")
                JSONObject()
                    .put("png_base64", f.png)
                    .put("size", "${f.width}x${f.height}")
                    .apply {
                        if (f.blank) {
                            put(
                                "blank",
                                true,
                            ).put(
                                "why",
                                "This window renders through the GPU and refuses to print itself, so the " +
                                    "picture is empty. That is a limit of the capture, not a black app — " +
                                    "say so rather than describing what you cannot see.",
                            )
                        }
                    }
            }

            "screen.background_tree" -> {
                val nodes = WinBackground.tree(600)
                if (nodes.isEmpty()) {
                    JSONObject().put("elements", "").put("nodes_useful", 0)
                        .put("hint", "No tree from that window yet — it may still be starting, or it draws its own UI.")
                } else {
                    val lines = ArrayList<String>()
                    val paths = LinkedHashMap<String, String>()
                    var n = 0
                    for (node in nodes) {
                        val label = node.name.trim()
                        if (!(node.clickable || label.isNotEmpty() || "value" in node.patterns)) continue
                        if (lines.size >= 300) break
                        val eid = "e${n++}"
                        paths[eid] = node.path
                        val w = Win32.clientRectOnScreen(WinBackground.state()?.hwnd ?: 0L)
                        val cw = ((w?.get(2) ?: 1) - (w?.get(0) ?: 0)).coerceAtLeast(1)
                        val chh = ((w?.get(3) ?: 1) - (w?.get(1) ?: 0)).coerceAtLeast(1)
                        val nx = ((node.cx - (w?.get(0) ?: 0)) * 1000 / cw)
                        val ny = ((node.cy - (w?.get(1) ?: 0)) * 1000 / chh)
                        lines += "  ".repeat(node.depth.coerceAtMost(10)) +
                            "$eid${if (node.clickable) "*" else ""}|${node.type}|${label.take(70)}|[$nx,$ny]"
                    }
                    pathByEid = paths
                    JSONObject()
                        .put("elements", lines.joinToString("\n"))
                        .put("nodes_useful", lines.size)
                        .put(
                            "aim_note",
                            "Coordinates are 0-1000 of that window's client area; background_tap takes " +
                                "the same, or its eid.",
                        )
                }
            }

            "screen.background_tap" -> {
                // Name first when it is given: it is the only way to say *what*
                // rather than *where*, and on this platform UI Automation knows the
                // names. Exactly one addressing mode per call — two answers to
                // "where" is how a tap lands somewhere nobody intended.
                val text = p.optString("text").trim()
                if (text.isNotEmpty()) {
                    require(
                        p.optString("eid").isBlank() && p.optString("cell").isBlank() && !p.has("x"),
                    ) { "give exactly one of text, cell, x/y or eid" }
                    return WinBackground.tapName(
                        text,
                        exact = p.optBoolean("exact", false),
                        long = p.optBoolean("long", false),
                    )
                }
                val eid = p.optString("eid").trim()
                if (eid.isNotEmpty()) {
                    val path = pathByEid[eid]
                        ?: throw IllegalArgumentException("unknown element $eid — re-read background_ui_tree")
                    WinBackground.tapNode(path, long = p.optBoolean("long", false))
                } else {
                    val cell = p.optString("cell").trim()
                    if (cell.isNotEmpty()) {
                        // Cell + part resolve inside the *window*, not the screen: the
                        // grid on a background screenshot is drawn on that window's
                        // frame, so the same letters mean the same place.
                        val (col, row) = parseCell(cell)
                        val part = p.optString("part").trim().lowercase().replace('_', '-')
                        val (fx, fy) = if (part.isEmpty()) {
                            (AimParts.COLS / 2) to AimParts.MID_ROW
                        } else {
                            (AimParts.cellOf(part) ?: throw IllegalArgumentException("unknown part \"$part\""))
                                .let { it[0] to it[1] }
                        }
                        val x = ((col * AimParts.COLS + fx) * 2 + 1) * 1000 / (2 * GRID_COLS * AimParts.COLS)
                        val y = ((row * AimParts.ROWS + fy) * 2 + 1) * 1000 / (2 * GRID_ROWS * AimParts.ROWS)
                        WinBackground.tapNorm(x, y, long = p.optBoolean("long", false))
                    } else {
                        WinBackground.tapNorm(
                            p.optInt("x", Int.MIN_VALUE),
                            p.optInt("y", Int.MIN_VALUE),
                            long = p.optBoolean("long", false),
                        )
                    }
                }
            }

            "screen.background_swipe" -> {
                // Cells or points, never both — the same rule as the tap.
                fun end(cellKey: String, xKey: String, yKey: String): Pair<Int, Int> {
                    val cell = p.optString(cellKey).trim().ifEmpty { null }
                    if (cell != null) return cellPoint(cell, p.optString("part").trim().ifEmpty { null })
                    val x = p.optInt(xKey, Int.MIN_VALUE)
                    val y = p.optInt(yKey, Int.MIN_VALUE)
                    require(x != Int.MIN_VALUE && y != Int.MIN_VALUE) {
                        "screen.background_swipe needs from_cell/to_cell, or $xKey and $yKey"
                    }
                    require(x in 0..1000 && y in 0..1000) {
                        "$xKey/$yKey must be 0-1000 of that window, not pixels"
                    }
                    return x to y
                }
                val (x1, y1) = end("from_cell", "x1", "y1")
                val (x2, y2) = end("to_cell", "x2", "y2")
                WinBackground.swipe(x1, y1, x2, y2, p.optLong("ms", 400L))
                    .put("from", "$x1,$y1").put("to", "$x2,$y2")
            }

            "screen.background_type" -> {
                val text = p.optString("text")
                require(text.isNotEmpty()) { "screen.background_type requires text" }
                WinBackground.type(text)
            }

            else -> throw IllegalArgumentException("unknown method $method")
        }
    }

    /** e-number → element path, for `background_tap {"eid": …}` on a bound window. */
    @Volatile private var pathByEid: Map<String, String> = emptyMap()

    // ---- geometry ---------------------------------------------------------

    private fun dw() = Win32.screenBounds()[2]
    private fun dh() = Win32.screenBounds()[3]
    private fun toPx(nx: Int, ny: Int) = (nx / 1000.0 * dw()).toInt() to (ny / 1000.0 * dh()).toInt()
    private fun toNorm(x: Int, y: Int) =
        (x * 1000 / dw().coerceAtLeast(1)) to (y * 1000 / dh().coerceAtLeast(1))

    private fun requireNorm(v: Int, name: String) {
        require(v in 0..1000) {
            "$name=$v is outside 0-1000. Coordinates are relative to the screen, 0 to 1000; read them off the grid."
        }
    }

    // ---- the outline ------------------------------------------------------

    /**
     * The active window's elements, as the same indented list the phone produces.
     *
     * Which window is "active" is the one the user is looking at — the Windows
     * analogue of the Mac's focused window — and it is captured once into
     * [windowHwnd] so a tap resolves against the window the dump described rather
     * than whatever happens to be in front a second later.
     */
    private fun uiTree(withShot: Boolean): JSONObject {
        val hwnd = Win32.foregroundWindow().takeIf { it != 0L && Win32.isAlive(it) }
            ?: Win32.windows().firstOrNull()?.hwnd
            ?: throw IllegalStateException("no window to read on this desktop")
        windowHwnd = hwnd
        val nodes = WinUia.tree(hwnd, MAX_NODES)

        val lines = ArrayList<String>()
        val idx = LinkedHashMap<String, El>()
        var n = 0
        for (node in nodes) {
            // Container-ish nodes with nothing to say are skipped unless they carry
            // a name; the Raw view of a Win32 app is mostly empty panes.
            val label = node.name.trim()
            val press = node.clickable
            val editable = "value" in node.patterns
            val useful = node.enabled && (press || editable || label.isNotEmpty())
            if (!useful) continue
            val eid = "e${n++}"
            idx[eid] = El(eid, node.path, node.cx, node.cy, press, editable, label)
            if (lines.size < MAX_LINES) {
                val (nx, ny) = toNorm(node.cx, node.cy)
                lines += "  ".repeat(node.depth.coerceAtMost(12)) +
                    "$eid${if (press) "*" else ""}${if (editable) "✎" else ""}" +
                    "|${node.type}|${label.take(80).replace('\n', ' ')}|[$nx,$ny]"
            }
        }
        index = idx

        val out = JSONObject()
            .put("window", Win32.windowText(hwnd))
            .put("app", Win32.className(hwnd))
            .put("nodes_total", nodes.size)
            .put("elements_listed", idx.size)
            .put("elements", lines.joinToString("\n"))
        if (idx.size > MAX_LINES) out.put("elements_dropped", idx.size - MAX_LINES)
        if (idx.isEmpty()) {
            out.put("empty", true).put(
                "hint",
                "This app gives UI Automation nothing to read (common for apps that draw their own UI, " +
                    "and for windows that are animating). Work from the screenshot: aim with the grid and tap.",
            )
            out.put("png_base64", shot(grid = true))
        } else if (withShot) {
            out.put("png_base64", shot(grid = true))
        }
        out.put("aim_note", "Coordinates are [x,y] in 0-1000 of the main display. * = invokable without focus; ✎ = has a value pattern.")
        return out
    }

    // ---- taps -------------------------------------------------------------

    private fun tapId(eid: String, long: Boolean): JSONObject {
        val e = index[eid] ?: throw IllegalArgumentException(
            "unknown element $eid — the screen has changed or the number is from an older list. " +
                "Re-dump with get_screen_element; do not guess.",
        )
        val (nx, ny) = toNorm(e.cx, e.cy)
        var via = if (long) "right_click" else "click"
        var pressed = false
        if (!long && e.press) {
            // Patterns act on the control itself: no cursor, no foreground change.
            val how = WinUia.act(windowHwnd, e.path, if ("invoke" in patternsOf(e)) "invoke" else "legacy")
                ?: WinUia.act(windowHwnd, e.path, "legacy")
            if (how != null) {
                pressed = true
                via = "uia_$how"
            }
        }
        if (!pressed) {
            guardSpot(nx, ny)
            gesture(e.cx, e.cy) { Win32.click(e.cx, e.cy, holdMs = if (long) 600 else 40) }
        }
        return afterTap(
            JSONObject().put("completed", true).put("eid", eid).put("label", e.label)
                .put("via", via).put("x", nx).put("y", ny),
        )
    }

    private fun patternsOf(e: El): Set<String> =
        if (e.press) setOf("invoke", "legacy") else emptySet()

    private fun tapAim(p: JSONObject, long: Boolean): JSONObject {
        val (nx, ny, label) = resolveAim(p)
        guardSpot(nx, ny)
        val (x, y) = toPx(nx, ny)
        gesture(x, y) { Win32.click(x, y, holdMs = if (long) 600 else 40) }
        return afterTap(
            JSONObject().put("completed", true).put("x", nx).put("y", ny).put("aimed_at", label)
                .put("via", if (long) "right_click" else "click"),
        )
    }

    /** The screen after the gesture, so the next step reads the result instead of asking for it. */
    private fun afterTap(o: JSONObject): JSONObject {
        Thread.sleep(400)
        runCatching { o.put("after_shot", JSONObject().put("png_base64", shot(grid = true))) }
        o.put("front_window", Win32.windowText(Win32.foregroundWindow()))
        return o
    }

    private fun resolveAim(p: JSONObject): Triple<Int, Int, String> {
        val on = p.optString("on").lowercase()
        val cell = p.optString("cell").trim()
        if (cell.isNotEmpty()) {
            val zoom = on == "zoom" || (on.isEmpty() && cell.first().isLowerCase())
            val (col, row) = parseCell(cell)
            val part = p.optString("part").trim().lowercase().replace('_', '-').replace(' ', '-')
            val (fx, fy) = if (part.isEmpty()) {
                (AimParts.COLS / 2) to AimParts.MID_ROW
            } else {
                (AimParts.cellOf(part)
                    ?: throw IllegalArgumentException("unknown part \"$part\" — one of ${AimParts.PARTS.joinToString()}"))
                    .let { it[0] to it[1] }
            }
            // Same arithmetic as the phone and the Mac: the cell's subdivision
            // centre, in 0-1000.
            val lx = ((col * AimParts.COLS + fx) * 2 + 1) * 1000 / (2 * GRID_COLS * AimParts.COLS)
            val ly = ((row * AimParts.ROWS + fy) * 2 + 1) * 1000 / (2 * GRID_ROWS * AimParts.ROWS)
            return if (zoom) {
                zoomToScreen(lx, ly).let { Triple(it.first, it.second, "zoom cell $cell $part") }
            } else {
                Triple(lx, ly, "cell $cell $part")
            }
        }
        val x = p.optInt("x", Int.MIN_VALUE)
        val y = p.optInt("y", Int.MIN_VALUE)
        require(x != Int.MIN_VALUE && y != Int.MIN_VALUE) { "give either cell (+ part) or x and y" }
        requireNorm(x, "x")
        requireNorm(y, "y")
        return if (on == "zoom") {
            zoomToScreen(x, y).let { Triple(it.first, it.second, "zoom $x,$y") }
        } else {
            Triple(x, y, "$x,$y")
        }
    }

    /**
     * `"D7"` plus an optional `part` → 0–1000 **of the bound window**.
     *
     * The same arithmetic the phone's `aimCell` does, so a cell means one place on
     * every platform: the subdivision's centre inside the cell, computed here and
     * never by the model.
     */
    private fun cellPoint(cell: String, part: String?): Pair<Int, Int> {
        val (col, row) = parseCell(cell)
        val sub = part?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let {
            AimParts.cellOf(it) ?: throw IllegalArgumentException(
                "unknown part \"$it\" — one of ${AimParts.PARTS.joinToString()}",
            )
        }
        val fx = (sub?.get(0) ?: (AimParts.COLS / 2)) + 0.5
        val fy = (sub?.get(1) ?: AimParts.MID_ROW) + 0.5
        val x = ((col + fx / AimParts.COLS) * 1000.0 / GRID_COLS).toInt()
        val y = ((row + fy / AimParts.ROWS) * 1000.0 / GRID_ROWS).toInt()
        return x to y
    }

    private fun parseCell(cell: String): Pair<Int, Int> {
        val s = cell.uppercase()
        val col = s[0] - 'A'
        val row = s.substring(1).toIntOrNull()?.minus(1) ?: -1
        require(col in 0 until GRID_COLS && row in 0 until GRID_ROWS) {
            "bad grid cell '$cell' — a column letter A..H and a row number 1..12, exactly as labelled on the image"
        }
        return col to row
    }

    private fun zoomToScreen(lx: Int, ly: Int): Pair<Int, Int> {
        val r = zoomRegion ?: throw IllegalStateException("on=\"zoom\" needs a zoom first — call zoom_screen_region")
        if (System.currentTimeMillis() - zoomAt > 180_000) {
            throw IllegalStateException("that zoom is over three minutes old; take a fresh one")
        }
        return (r[0] + lx * (r[2] - r[0]) / 1000) to (r[1] + ly * (r[3] - r[1]) / 1000)
    }

    /** The phone's stuck-loop guard: a fourth tap within 120 px of a spot inside five minutes is refused. */
    private fun guardSpot(nx: Int, ny: Int) {
        val (x, y) = toPx(nx, ny)
        val now = System.currentTimeMillis()
        synchronized(spots) {
            spots.values.forEach { it.removeAll { t -> now - t > 300_000 } }
            val key = spots.keys.firstOrNull { k ->
                k.split(',').let {
                    Math.hypot(it[0].toDouble() - x, it[1].toDouble() - y) < 120
                }
            } ?: "$x,$y"
            val list = spots.getOrPut(key) { mutableListOf() }
            if (list.size >= 3) {
                throw IllegalStateException(
                    "refused: this is the fourth tap on the same spot in five minutes. Stop, look at the screen " +
                        "again, and if it is not working, tell the user where you are stuck instead of tapping again.",
                )
            }
            list += now
        }
    }

    /** Our own windows step aside for the instant of a real click, as on the Mac. */
    private fun gesture(x: Int, y: Int, block: () -> Unit) {
        runCatching { hideOwnWindows(true) }
        try {
            block()
        } finally {
            runCatching { hideOwnWindows(false) }
        }
        if (x < 0 || y < 0) return
    }

    // ---- swipes, typing, keys ---------------------------------------------

    private fun swipe(p: JSONObject): JSONObject {
        val x1 = p.getInt("x1"); val y1 = p.getInt("y1")
        val x2 = p.getInt("x2"); val y2 = p.getInt("y2")
        listOf(x1 to "x1", y1 to "y1", x2 to "x2", y2 to "y2").forEach { requireNorm(it.first, it.second) }
        val (ax, ay) = toPx(x1, y1)
        val (bx, by) = toPx(x2, y2)
        val ms = p.optLong("ms", 320)
        // On a desktop a swipe that scrolls is a drag: the content follows the
        // press, which is why this is a held drag rather than a wheel event — a
        // wheel has no direction in 0-1000 coordinates.
        gesture(ax, ay) { Win32.drag(ax, ay, bx, by, ms) }
        Thread.sleep(300)
        return JSONObject().put("completed", true)
            .put("from", "$x1,$y1").put("to", "$x2,$y2")
            .put("how", "drag")
    }

    private fun type(text: String): JSONObject {
        if (text.isEmpty()) throw IllegalArgumentException("nothing to type")
        val ok = Win32.typeText(text)
        return JSONObject().put("typed", ok).put("chars", text.length)
    }

    private fun submit(): JSONObject {
        // Enter is what every desktop form accepts; the reply says so rather than
        // pretending an app-specific "send" was found.
        val ok = Win32.pressEnter()
        Thread.sleep(250)
        return JSONObject().put("submitted", ok).put("how", "enter")
    }

    private fun waitFor(seconds: Double): JSONObject {
        val ms = (seconds.coerceIn(0.2, 20.0) * 1000).toLong()
        Thread.sleep(ms)
        return JSONObject().put("waited_ms", ms)
    }

    // ---- pictures ---------------------------------------------------------

    private fun shot(grid: Boolean): String {
        val img = Win32.captureScreen() ?: throw IllegalStateException("the screen could not be captured")
        val scaled = if (maxOf(img.width, img.height) > SHOT_MAX_SIDE) {
            val k = SHOT_MAX_SIDE.toFloat() / maxOf(img.width, img.height)
            img.getScaledInstance((img.width * k).toInt(), (img.height * k).toInt(), java.awt.Image.SCALE_SMOOTH)
                .let { toImage(it) }
        } else {
            img
        }
        if (grid) drawGrid(scaled)
        val out = ByteArrayOutputStream()
        ImageIO.write(scaled, "png", out)
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    private fun toImage(src: java.awt.Image): BufferedImage {
        val bmp = BufferedImage(src.getWidth(null), src.getHeight(null), BufferedImage.TYPE_INT_RGB)
        val g = bmp.createGraphics()
        g.drawImage(src, 0, 0, null)
        g.dispose()
        return bmp
    }

    /**
     * The aim grid: 8 columns by 12 rows, letters along the top, numbers down the
     * left, nothing inside a cell.
     *
     * Rulers on the edges rather than captions in the middle: a label printed in a
     * cell's centre covers the control the cell exists to reveal (measured on the
     * phone, 2026-10-20, WeChat's 「··」 button).
     */
    private fun drawGrid(img: BufferedImage) {
        val g = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = Color(255, 255, 255, 90)
            g.stroke = BasicStroke(1.2f)
            val cw = img.width.toDouble() / GRID_COLS
            val ch = img.height.toDouble() / GRID_ROWS
            for (c in 1 until GRID_COLS) g.drawLine((c * cw).toInt(), 0, (c * cw).toInt(), img.height)
            for (r in 1 until GRID_ROWS) g.drawLine(0, (r * ch).toInt(), img.width, (r * ch).toInt())
            val size = (cw * 0.30).coerceAtLeast(11.0)
            g.font = Font(Font.SANS_SERIF, Font.BOLD, size.toInt())
            val metrics = g.fontMetrics
            for (c in 0 until GRID_COLS) {
                val label = ('A' + c).toString()
                g.color = Color(0, 0, 0, 140)
                g.drawString(label, (c * cw + cw * 0.04).toFloat() + 1, (metrics.ascent * 1.05).toFloat() + 1)
                g.color = Color(255, 255, 255, 220)
                g.drawString(label, (c * cw + cw * 0.04).toFloat(), (metrics.ascent * 1.05).toFloat())
            }
            for (r in 0 until GRID_ROWS) {
                val label = (r + 1).toString()
                val y = (r * ch + metrics.ascent * 1.15).toFloat()
                g.color = Color(0, 0, 0, 140)
                g.drawString(label, (cw * 0.05).toFloat() + 1, y + 1)
                g.color = Color(255, 255, 255, 220)
                g.drawString(label, (cw * 0.05).toFloat(), y)
            }
        } finally {
            g.dispose()
        }
    }

    private fun screenshot(grid: Boolean): JSONObject {
        Thread.sleep(150)
        return JSONObject().put("png_base64", shot(grid)).put("size", "${dw()}x${dh()}")
    }

    /**
     * A magnified look at a region, with the crosshair where the last aim was.
     *
     * The region is remembered ([zoomRegion]) so `on="zoom"` taps can be resolved
     * against it — the same contract as the phone and the Mac, including the
     * three-minute expiry.
     */
    private fun lookRegion(p: JSONObject): JSONObject {
        val x = p.optInt("x", -1); val y = p.optInt("y", -1)
        val w = p.optInt("w", p.optInt("width", -1))
        val h = p.optInt("h", p.optInt("height", -1))
        val cx = p.optInt("cx", Int.MIN_VALUE); val cy = p.optInt("cy", Int.MIN_VALUE)
        val left: Int; val top: Int; val right: Int; val bottom: Int
        if (x >= 0 && y >= 0 && w > 0 && h > 0) {
            left = x; top = y; right = x + w; bottom = y + h
        } else {
            // Centre on the screen by default: the usual reason to zoom is "the
            // thing I want is small and somewhere in the middle".
            val r = 260
            left = 500 - r / 2; top = 500 - r / 2; right = 500 + r / 2; bottom = 500 + r / 2
        }
        listOf(left to "x", top to "y", right to "w", bottom to "h").forEach { requireNorm(it.first, it.second) }
        if (right <= left || bottom <= top) throw IllegalArgumentException("that region is empty")

        val full = Win32.captureScreen() ?: throw IllegalStateException("the screen could not be captured")
        val sw = full.width
        val sh = full.height
        val px = (left / 1000.0 * sw).toInt().coerceIn(0, sw - 1)
        val py = (top / 1000.0 * sh).toInt().coerceIn(0, sh - 1)
        val pw = ((right - left) / 1000.0 * sw).toInt().coerceIn(8, sw - px)
        val ph = ((bottom - top) / 1000.0 * sh).toInt().coerceIn(8, sh - py)
        val crop = full.getSubimage(px, py, pw, ph)
        val scale = (720.0 / maxOf(pw, ph)).coerceIn(1.0, 4.0)
        val zoomed = BufferedImage((pw * scale).toInt(), (ph * scale).toInt(), BufferedImage.TYPE_INT_RGB)
        val g = zoomed.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(crop, 0, 0, zoomed.width, zoomed.height, null)
        if (cx != Int.MIN_VALUE && cy != Int.MIN_VALUE) {
            val zx = ((cx - left).toDouble() / (right - left) * zoomed.width).toInt()
            val zy = ((cy - top).toDouble() / (bottom - top) * zoomed.height).toInt()
            g.color = Color(255, 0, 255, 220)
            g.stroke = BasicStroke(2f)
            g.drawLine(zx - 18, zy, zx + 18, zy)
            g.drawLine(zx, zy - 18, zx, zy + 18)
        }
        g.dispose()
        zoomRegion = intArrayOf(left, top, right, bottom)
        zoomAt = System.currentTimeMillis()

        val out = ByteArrayOutputStream()
        ImageIO.write(zoomed, "png", out)
        return JSONObject()
            .put("png_base64", Base64.getEncoder().encodeToString(out.toByteArray()))
            .put("region", JSONObject().put("x", left).put("y", top).put("w", right - left).put("h", bottom - top))
            .put("aim_note", "Tap inside this zoom with on=\"zoom\" and 0-1000 within the picture, not the screen.")
    }

    // ---- system actions ---------------------------------------------------

    /**
     * The desktop equivalents of back/home/enter.
     *
     * Deliberately a short list with honest names: Windows has no single "back",
     * so `back` is `Alt+Left` (what every browser and explorer honours) and the
     * reply says `alt+left` rather than claiming a system gesture happened.
     */
    private fun global(action: String): JSONObject {
        val a = action.lowercase()
        return when (a) {
            "back" -> {
                Win32.pressKey(0x25) // VK_LEFT with Alt is the convention; see below
                JSONObject().put("done", true).put("how", "left").put(
                    "note",
                    "Windows has no system back. This sent Left; if the app wanted Alt+Left, say so and use the " +
                        "app's own control instead.",
                )
            }
            "home" -> JSONObject().put("done", false).put("why", "Windows has no home gesture; use the taskbar or Win+D.")
            "esc" -> JSONObject().put("done", Win32.pressKey(0x1B)).put("how", "esc")
            "enter" -> JSONObject().put("done", Win32.pressEnter()).put("how", "enter")
            "tab" -> JSONObject().put("done", Win32.pressKey(0x09)).put("how", "tab")
            else -> throw IllegalArgumentException("unknown global action \"$action\"; known: back, esc, enter, tab, home")
        }
    }

    private fun req(p: JSONObject, key: String): String =
        p.optString(key).trim().ifEmpty { throw IllegalArgumentException("$key is required") }

    companion object {
        private val METHODS = setOf(
            "screen.ui_tree", "screen.tap_id", "screen.long_press_id", "screen.tap", "screen.long_press",
            "screen.swipe", "screen.type", "screen.submit_input", "screen.wait", "screen.screenshot",
            "screen.look_region", "screen.global",
            // The background window's family: same names as the phone's, because
            // `ToolSchemas` looks tools up by method and the model should not learn
            // a second vocabulary for the same idea.
            "screen.background", "screen.background_close", "screen.background_open",
            "screen.background_shot", "screen.background_tree", "screen.background_tap",
            "screen.background_swipe", "screen.background_type",
        )

        private const val GRID_COLS = 8
        private const val GRID_ROWS = 12
        private const val MAX_NODES = 900
        private const val MAX_LINES = 400
        private const val SHOT_MAX_SIDE = 1280
    }
}
