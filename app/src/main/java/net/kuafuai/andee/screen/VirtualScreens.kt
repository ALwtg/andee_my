package net.kuafuai.andee.screen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * The background screen: a second display an app can be driven on while the
 * person keeps the one in their hands.
 *
 * ## Who owns what
 *
 * The display belongs to a **shell-side helper process**
 * (`helper/DisplayProbe serve`), started through Shizuku. That is not an
 * implementation detail — an ordinary app cannot create a display that
 * third-party activities can be launched onto, and a display renders into a
 * `Surface` its creator holds. So the creator is a process running as `shell`,
 * and this object is the **client**: one line out, one line back.
 *
 * The protocol, in full (every line starts with `OK` / `ERR`):
 *
 * ```
 *   → open <pkg>            ← OK open
 *   → tap <x> <y>           ← OK tap
 *   → swipe <x1> <y1> <x2> <y2> <ms>   ← OK swipe
 *   → type <base64 text>    ← OK type
 *   → key <keycode>         ← OK key
 *   → frame                 ← OK frame <w>x<h> <base64 png>
 *   → close                 ← OK closing … OK done
 * ```
 *
 * ## What it replaced, and why that matters
 *
 * This used to publish `settings put global overlay_display_devices`, which on
 * the device measured here produces a **mirror**: the app it launched came to
 * the front of the user's own screen, which is the exact opposite of the
 * feature. That is why [MIRROR_REFUSAL] is still checked on every start — not
 * against the old mechanism, but against a device whose platform may still hand
 * out a copy instead of a screen.
 *
 * ## One at a time, deliberately
 *
 * The user's own words for the failure mode were 「虚拟屏长时间占用资源」. Two
 * hidden screens is two places nobody is looking and two processes holding
 * surfaces, so [start] refuses while one is up, [state] answers "is anything
 * hidden right now", and [closeAll] is the one button that ends all of it.
 *
 * **Blocking.** Every method crosses a process boundary. Callers are
 * `CommandDispatcher` (already on a worker) and the supervision Activity's
 * poller.
 */
object VirtualScreens {

    private const val TAG = "Body"

    /** The published display's own pixels — the space a tap is aimed in. */
    const val WIDTH = 1080
    const val HEIGHT = 1920
    private const val DPI = 320

    /**
     * The aim grid, borrowed rather than re-declared: one grid, one set of
     * numbers, or "D7" would mean two places depending on the screen the model
     * was reading.
     */
    val GRID_COLS = ScreenController.GRID_COLS
    val GRID_ROWS = ScreenController.GRID_ROWS

    private const val BOOT_TIMEOUT_MS = 25_000L
    private const val OPEN_TIMEOUT_MS = 15_000L
    private const val CMD_TIMEOUT_MS = 6_000L
    private const val FRAME_TIMEOUT_MS = 12_000L

    /** How long the launched app gets to draw before the independence check. */
    private const val DRAW_SETTLE_MS = 1_200L

    /** Mean luma difference (0–255) under which two frames count as one screen. */
    private const val MIRROR_TOLERANCE = 6.0

    private const val THUMB = 64

    /**
     * What the device says when the platform refuses to give us a screen of its
     * own. Kept as a constant because it is a refusal the *model* reads, and the
     * next step belongs to the user rather than to a retry.
     */
    const val MIRROR_REFUSAL =
        "This device's platform only produced a MIRROR display: the new display showed the " +
            "primary screen back, and launching the app there put it in front of the user " +
            "instead. Nothing was left running. Do this task on the screen in front of them " +
            "and tell them why."

    /** Where the engine's remaining edge is. See the class doc. */
    const val ENGINE_NOTE =
        "The background screen runs as a shell-side helper process (app_process + a display " +
            "it owns). It needs Shizuku running and authorised; ⚙ → 高级 → 屏幕与后台 says which " +
            "of those is missing."

    data class VirtualScreen(
        val displayId: Int,
        val pkg: String,
        val label: String,
        val startedAt: Long,
    )

    /** The screen that is up, or null. */
    @Volatile
    private var current: VirtualScreen? = null

    private var session: ShizukuShell.Session? = null

    /** Serialises command/response pairs: one session, one line at a time. */
    private val lock = Any()

    private val starting = AtomicReference(false)

    /**
     * Told whenever a screen appears or goes away, on the caller's thread.
     *
     * Set by `BodyCoreService`, which owns the ball — and the ball is the only
     * visible sign that something is running where the user cannot see. A screen
     * that opens without one is the failure this exists to avoid, so the
     * notification comes from here rather than from whichever caller acted.
     */
    @Volatile
    var onChanged: (() -> Unit)? = null

    fun state(): VirtualScreen? = current

    private fun changed() {
        runCatching { onChanged?.invoke() }
    }

    // ---- lifecycle -------------------------------------------------------

    /**
     * Open a display and put [pkg] on it.
     *
     * Refuses rather than queueing when one is already up: two hidden screens is
     * the resource leak the user named, and silently reusing the existing one
     * would put the second app on a display the first one owns.
     */
    fun start(context: Context, pkg: String, label: String): Result<VirtualScreen> {
        if (!ShizukuShell.ready()) {
            return Result.failure(
                IllegalStateException(
                    "Shizuku is not running or not authorised — the background screen needs it. " +
                        "⚙ → 高级 → 屏幕与后台 shows which of the two it is.",
                ),
            )
        }
        if (!starting.compareAndSet(false, true)) {
            return Result.failure(IllegalStateException("a background screen is already being opened"))
        }
        try {
            synchronized(lock) {
                current?.let {
                    return Result.failure(
                        IllegalStateException(
                            "a background screen is already up (${it.label} on display " +
                                "${it.displayId}); close it first",
                        ),
                    )
                }
                val apk = runCatching { context.applicationInfo.sourceDir }.getOrNull()
                    ?: return Result.failure(IllegalStateException("this build has no source dir"))
                val command = "CLASSPATH='$apk' app_process /system/bin --nice-name=andee-bg " +
                    "net.kuafuai.andee.helper.DisplayProbe serve $WIDTH $HEIGHT $DPI"
                val s = ShizukuShell.open(command)
                    ?: return Result.failure(
                        IllegalStateException("the helper process could not be started. $ENGINE_NOTE"),
                    )
                session = s

                val boot = await(s, "OK ready ", BOOT_TIMEOUT_MS)
                val displayId = boot.lines.firstOrNull { it.startsWith("OK ready display=") }
                    ?.removePrefix("OK ready display=")
                    ?.substringBefore(' ')
                    ?.toIntOrNull()
                if (displayId == null) {
                    shutdownLocked()
                    return Result.failure(
                        IllegalStateException(
                            "the helper did not open a display: ${boot.log.ifBlank { "no output" }}",
                        ),
                    )
                }

                s.send("open $pkg")
                val opened = await(s, "OK open", OPEN_TIMEOUT_MS)
                if (!opened.ok) {
                    shutdownLocked()
                    return Result.failure(
                        IllegalStateException(
                            "the app would not start on display $displayId: ${opened.log}",
                        ),
                    )
                }

                // Let the app draw once. `am start` answers as soon as the task is
                // created, and a frame taken in that gap is the platform's
                // background, not the app — which would make the check below
                // compare two unrelated pictures and call it "independent".
                try {
                    Thread.sleep(DRAW_SETTLE_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }

                // Is this a screen of its own, or the user's screen twice? Asked
                // here, at the only moment the answer is true, and it is the same
                // measurement the diagnostic reports.
                val guest = frameLocked(s)
                val host = ShizukuShell.runBytes("screencap -p")
                if (guest != null && host != null) {
                    val delta = framesDiffer(guest, host) ?: 0.0
                    Log.i(TAG, "background screen $displayId: frame delta $delta")
                    if (delta <= MIRROR_TOLERANCE) {
                        shutdownLocked()
                        return Result.failure(IllegalStateException(MIRROR_REFUSAL))
                    }
                }

                val screen = VirtualScreen(displayId, pkg, label, System.currentTimeMillis())
                current = screen
                Log.i(TAG, "background screen ${screen.displayId} for $pkg")
                changed()
                return Result.success(screen)
            }
        } finally {
            starting.set(false)
        }
    }

    /**
     * Close every hidden screen — the whole of 「关闭所有虚拟屏」.
     *
     * Also clears the platform setting the *old* engine wrote, because a device
     * that upgraded while a mirror was published would otherwise keep one: this
     * is the one place that has to be able to undo both.
     */
    fun closeAll(context: Context): Boolean {
        synchronized(lock) {
            shutdownLocked()
            val legacy = ShizukuShell.run("settings put global overlay_display_devices \"\"")
            if (!legacy.ok) Log.w(TAG, "could not clear overlay displays: ${legacy.out}")
            changed()
            return true
        }
    }

    /** Send `close`, drop the session, forget the screen. Idempotent. */
    private fun shutdownLocked() {
        current = null
        val s = session
        session = null
        if (s != null) {
            runCatching { s.send("close") }
            // Give the helper a beat to release the display and say so; its own
            // exit is what actually frees the Surface.
            runCatching { s.readLine(1_500) }
            runCatching { s.close() }
        }
    }

    // ---- hand ------------------------------------------------------------

    /**
     * Put **another** app on the screen that is already up.
     *
     * Without this the model has exactly two moves on a running background
     * screen — drive the app it opened, or tear the screen down and start over —
     * and the second one is what it did: `open_background_screen` refuses while
     * one is up, so "now open the chat list" read as "the tool chain is
     * incomplete". The display is the resource; the app on it is a detail.
     */
    fun open(pkg: String): Boolean {
        if (state() == null) return false
        return command("open $pkg", "OK open")
    }

    /** A tap on the hidden screen, in that display's own pixels. */
    fun tap(displayId: Int, x: Int, y: Int): Boolean =
        command("tap $x $y", "OK tap")

    /**
     * A tap in **0–1000 relative units**, the space every other `screen.*` tool
     * speaks.
     *
     * The model must not be handed a pixel grid it has to divide by 1080 — that
     * arithmetic is what the aim grid removes on the default display, and there
     * is no element list here to read a coordinate off. Out of range is refused,
     * never clamped: a silent clamp is a mis-tap nobody can see happening.
     */
    fun tapNorm(displayId: Int, x: Int, y: Int): Boolean {
        require(x in 0..1000 && y in 0..1000) {
            "background tap coordinates are 0-1000 relative, got $x,$y"
        }
        guardSpot(x, y)
        return tap(displayId, x * WIDTH / 1000, y * HEIGHT / 1000)
    }

    // ---- stuck-loop guard -------------------------------------------------

    /**
     * The foreground's guard, brought to the screen that needs it most.
     *
     * On the display in front of the user there is an element list, so a blind
     * repeat is rare. Here there is **none** (this platform does not publish a
     * second display's windows to accessibility), which makes "tap the same spot
     * again" the most likely wrong move a model can make — and the least visible,
     * because nobody is looking at that screen.
     *
     * Same numbers as the foreground: four gestures within 120 px of each other
     * inside five minutes is refused, and the refusal tells the model to look again
     * rather than to try harder.
     */
    private val spots = HashMap<String, MutableList<Long>>()

    private fun guardSpot(normX: Int, normY: Int) {
        val x = normX * WIDTH / 1000
        val y = normY * HEIGHT / 1000
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
                    "Refused: this is the fourth gesture on the same spot in five minutes. Nothing is " +
                        "changing there — take a fresh background_screenshot, check whether the screen is " +
                        "still on the app you think it is, and if it still will not move, tell the user " +
                        "where you are stuck instead of tapping again.",
                )
            }
            list += now
        }
    }

    /**
     * `"D7"` — plus an optional `part` — turned into 0–1000 coordinates.
     *
     * **This is the aiming story on this screen, and the grid is drawn onto the
     * frame** so the model reads a ruler instead of measuring pixels off a
     * downscaled PNG. Null when the cell does not name one: refused, never
     * clamped, for the same reason every other coordinate here is.
     *
     * The arithmetic lives here rather than in the model, which is the rule the
     * whole coordinate protocol exists for.
     */
    fun aimCell(cell: String, part: String?): Pair<Int, Int>? {
        val c = cell.trim().uppercase()
        if (c.length < 2) return null
        val col = c[0] - 'A'
        val row = c.substring(1).toIntOrNull()?.minus(1) ?: return null
        if (col !in 0 until GRID_COLS || row !in 0 until GRID_ROWS) return null
        val sub = part?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { AimParts.cellOf(it) }
        // Centre of the named subdivision: `+ 0.5` inside both axes, so omitting
        // `part` aims at the middle row of the cell rather than its top edge.
        val fx = (sub?.get(0) ?: (AimParts.COLS / 2)) + 0.5
        val fy = (sub?.get(1) ?: AimParts.MID_ROW) + 0.5
        val x = ((col + fx / AimParts.COLS) * 1000.0 / GRID_COLS).toInt()
        val y = ((row + fy / AimParts.ROWS) * 1000.0 / GRID_ROWS).toInt()
        return x to y
    }

    fun swipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, ms: Long): Boolean {
        require(x1 in 0..1000 && y1 in 0..1000 && x2 in 0..1000 && y2 in 0..1000) {
            "background swipe coordinates are 0-1000 relative, got ($x1,$y1) → ($x2,$y2)"
        }
        // Guarded at the start point: a swipe that begins where the last three
        // gestures began is the same stuck loop wearing a different name.
        guardSpot(x1, y1)
        return command("swipe $x1 $y1 $x2 $y2 ${ms.coerceIn(50, 3_000)}", "OK swipe")
    }

    /** System keys: 4 is back, 3 is home. */
    fun key(displayId: Int, code: Int): Boolean = command("key $code", "OK key")

    /**
     * Type into whatever has focus on the hidden screen.
     *
     * The text goes over the wire as base64 so the shell never sees it raw; the
     * helper decodes it and escapes the one character `input text` cares about.
     * A failure is reported rather than retried through a second channel that
     * would type it twice.
     */
    fun type(displayId: Int, text: String): Boolean {
        val b64 = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return command("type $b64", "OK type")
    }

    /**
     * One PNG frame of the hidden screen, or null.
     *
     * From the helper's own `Surface`, not `screencap`: the display is not the
     * internal one, and `screencap -d` rejects logical ids on the device measured
     * here (`Display Id '0' is not valid`). The surface is also the creator's,
     * which is the whole reason the helper exists.
     */
    fun frame(displayId: Int): ByteArray? = synchronized(lock) {
        val s = session ?: return null
        frameLocked(s)
    }

    /**
     * A base64 PNG for the brain, downscaled to the same
     * [ScreenCapture.SHOT_MAX_SIDE] long side as every other screenshot, so the
     * model does not have to learn two sizes for one concept.
     */
    fun shotPng(displayId: Int): String? {
        val bytes = shotBytes(displayId) ?: return null
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /**
     * The same PNG, before base64 — for the caller that still has to draw on it.
     *
     * The aim grid is added by the **app** (`ScreenController.gridOverlay`), not
     * by the helper: `Canvas.drawText` in the helper's `app_process` aborted with
     * SIGABRT and took the display down. Downscaled here to the same long side as
     * every other screenshot, so one picture size means one thing.
     */
    fun shotBytes(displayId: Int): ByteArray? {
        val bytes = frame(displayId) ?: return null
        val decoded = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            .getOrNull() ?: return null
        val long = maxOf(decoded.width, decoded.height)
        val scaled = if (long > ScreenCapture.SHOT_MAX_SIDE) {
            val k = ScreenCapture.SHOT_MAX_SIDE.toFloat() / long
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * k).toInt().coerceAtLeast(1),
                (decoded.height * k).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            decoded
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        return out.toByteArray()
    }

    // ---- protocol --------------------------------------------------------

    /** Send a command and wait for its ack. False when either half fails. */
    private fun command(line: String, expect: String): Boolean = synchronized(lock) {
        val s = session ?: return false
        if (!s.send(line)) return false
        return await(s, expect, CMD_TIMEOUT_MS).ok
    }

    /** `frame` round trip inside an existing lock. */
    private fun frameLocked(s: ShizukuShell.Session): ByteArray? {
        if (!s.send("frame")) return null
        val reply = await(s, "OK frame ", FRAME_TIMEOUT_MS)
        if (!reply.ok) return null
        val b64 = reply.lines.firstOrNull { it.startsWith("OK frame ") }
            ?.split(' ', limit = 4)
            ?.getOrNull(3)
            .orEmpty()
        return runCatching { Base64.decode(b64, Base64.NO_WRAP) }.getOrNull()
    }

    /**
     * One response, with everything that arrived before it.
     *
     * Lines are collected rather than discarded: when an ack never comes, the
     * preceding lines are usually the reason (`ERR …`, a stack trace from the
     * helper, `OK launch …` for a launch that was redirected). A deadline that
     * returned only "timed out" is what made this feature take several rounds to
     * debug.
     */
    private class Reply(val ok: Boolean, val lines: List<String>) {
        val log: String get() = lines.joinToString(" | ").take(600)
    }

    private fun await(s: ShizukuShell.Session, expect: String, timeoutMs: Long): Reply {
        val collected = ArrayList<String>()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val left = deadline - System.currentTimeMillis()
            val line = s.readLine(left.coerceAtLeast(50)) ?: break
            collected += line
            if (line.startsWith(expect)) return Reply(true, collected)
            if (line.startsWith("ERR ")) return Reply(false, collected)
        }
        return Reply(false, collected)
    }

    // ---- measurement -----------------------------------------------------

    /**
     * Mean absolute luma difference between two PNG captures, or null when either
     * will not decode.
     *
     * The same measure, the same threshold, as the diagnostic in
     * `DisplayProbeClient` — so a probe that says "independent" and a runtime
     * check that says "mirror" cannot both be true.
     */
    internal fun framesDiffer(a: ByteArray, b: ByteArray): Double? {
        val ta = lumaThumb(a) ?: return null
        val tb = lumaThumb(b) ?: return null
        var sum = 0L
        for (i in ta.indices) sum += kotlin.math.abs(ta[i] - tb[i])
        return sum.toDouble() / ta.size
    }

    internal fun mirrorTolerance(): Double = MIRROR_TOLERANCE

    /**
     * 64×64 luma of a PNG, or null if it will not decode.
     *
     * Not byte equality: our own ball animates and the clock ticks, so two
     * captures of the *same* screen are never identical files, while two captures
     * of *different* screens differ far more. Deliberately biased toward "not a
     * mirror": a false "different" costs one wasted session, a false "mirror"
     * would refuse a working feature on a good device.
     */
    private fun lumaThumb(png: ByteArray): IntArray? {
        val bmp = runCatching { BitmapFactory.decodeByteArray(png, 0, png.size) }.getOrNull()
            ?: return null
        val small = Bitmap.createScaledBitmap(bmp, THUMB, THUMB, true)
        val out = IntArray(THUMB * THUMB)
        val row = IntArray(THUMB)
        for (y in 0 until THUMB) {
            // One getPixels per row, not per pixel: 64 array hand-offs instead of
            // 4096.
            small.getPixels(row, 0, THUMB, 0, y, THUMB, 1)
            for (x in 0 until THUMB) {
                val c = row[x]
                out[y * THUMB + x] =
                    ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
            }
        }
        if (small !== bmp) small.recycle()
        bmp.recycle()
        return out
    }
}
