package net.kuafuai.andee.desktop.win

import net.kuafuai.andee.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The accessibility tree on Windows — UI Automation, through a small bridge.
 *
 * ## Why there is a PowerShell process in the middle
 *
 * UI Automation is COM, and its interfaces are wide: `IUIAutomationElement` alone
 * exposes ~90 property getters whose **vtable slots changed** between Windows
 * versions. Binding that by hand means hardcoding indices into an ABI, where a
 * wrong number is not an exception but a call into the wrong function — a crash
 * with no stack that reads as "the port is unstable". The .NET wrapper
 * (`UIAutomationClient`) is versioned *with* the OS and is present on every
 * Windows 11 machine, so the bridge is a 150-line script that speaks one JSON
 * line per command, and this file is its client.
 *
 * The cost is one process and a few hundred milliseconds on the first call; the
 * process is kept alive, so a tree dump is ~0.3–1 s afterwards rather than a
 * process spawn per action.
 *
 * ## Elements are addressed by **path**, not by handle
 *
 * A path is `"0/2/1"` — child indices from the window's root in the Raw view.
 * An `AutomationElement` cannot be handed across processes, and a raw pointer to
 * one is invalid the moment the app rebuilds its tree; a path can be re-walked,
 * which is exactly the re-locate strategy the Mac body uses for its e-numbers.
 *
 * ## What this layer does **not** do
 *
 * It does not click. `act` only uses UIA *patterns* (`Invoke`, `Value`,
 * `SelectionItem`, `LegacyIAccessible`), which act on a control without taking
 * the foreground — that is the whole point of the background screen. When no
 * pattern answers, the caller decides between a posted message and a real click,
 * and says which one it used.
 */
object WinUia {

    private const val TAG = "WinUia"

    private const val CALL_TIMEOUT_MS = 20_000L
    private const val BOOT_TIMEOUT_MS = 20_000L

    /** One element, as the bridge describes it. */
    data class Node(
        val path: String,
        val depth: Int,
        val type: String,
        val name: String,
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int,
        val enabled: Boolean,
        val patterns: List<String>,
    ) {
        val cx: Int get() = x + w / 2
        val cy: Int get() = y + h / 2
        val clickable: Boolean get() = "invoke" in patterns || "legacy" in patterns || "select" in patterns
    }

    @Volatile private var session: Session? = null
    private val bootLock = Any()

    val available: Boolean
        get() = runCatching { ensure() != null }.getOrDefault(false)

    /**
     * Whether the bridge is up **right now**, without starting it.
     *
     * For the checklist: `available` opens a process, which is fine once and wrong
     * on every repaint of a settings card.
     */
    val bridgeRunning: Boolean get() = session?.alive == true

    // ---- lifecycle --------------------------------------------------------

    /**
     * The live bridge, started on first use and restarted once if it died.
     *
     * A bridge that exits (PowerShell policy, an OOM, the user killing it) must not
     * leave every later call throwing: the second attempt is what turns "the tools
     * stopped working an hour ago" into a restart nobody notices.
     */
    private fun ensure(): Session? = synchronized(bootLock) {
        session?.takeIf { it.alive }?.let { return it }
        session?.close()
        session = null
        val started = Session.start()
        session = started
        if (started == null) Log.w(TAG, "the UI Automation bridge did not start")
        started
    }

    fun shutdown() {
        synchronized(bootLock) {
            session?.close()
            session = null
        }
    }

    // ---- calls ------------------------------------------------------------

    /** The top-level windows UIA knows about — the same set as [Win32.windows], as UIA sees it. */
    fun windows(): List<Win32.Win> {
        val reply = call(JSONObject().put("op", "windows")) ?: return emptyList()
        val arr = reply.optJSONArray("windows") ?: return emptyList()
        val out = ArrayList<Win32.Win>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                Win32.Win(
                    hwnd = o.optLong("hwnd"),
                    pid = o.optInt("pid"),
                    title = o.optString("name"),
                    className = o.optString("type"),
                ),
            )
        }
        return out
    }

    /**
     * The tree under [hwnd], depth-first, capped at [max] nodes.
     *
     * Depth comes from the path rather than from the bridge: the script's own depth
     * counter was wrong on the first run (every node reported 0), and a path is the
     * one thing that cannot disagree with itself.
     */
    fun tree(hwnd: Long, max: Int = 600): List<Node> {
        val reply = call(JSONObject().put("op", "tree").put("hwnd", hwnd).put("max", max))
            ?: return emptyList()
        if (!reply.optBoolean("ok", false)) return emptyList()
        return nodes(reply.optJSONArray("nodes"))
    }

    /**
     * Elements of [hwnd] whose name — or automation id — matches [text].
     *
     * **This is what makes "tap 发送" possible on Windows at all.** UI Automation
     * carries a `Name` per control, so the answer is exact and cross-process, with
     * no pixels involved and no OCR. The bridge does an exact `FindAll` first
     * (one query, tens of milliseconds) and only falls back to a bounded walk for
     * a contains-match.
     *
     * Empty means either "nothing is called that" or "this app names nothing"
     * (Electron, games) — the caller distinguishes the two by what comes back, and
     * says which one it is rather than guessing.
     */
    fun find(hwnd: Long, text: String, exact: Boolean = false): List<Node> {
        val needle = text.trim()
        if (needle.isEmpty()) return emptyList()
        val reply = call(
            JSONObject().put("op", "find").put("hwnd", hwnd).put("text", needle).put("exact", exact),
        ) ?: return emptyList()
        if (!reply.optBoolean("ok", false)) return emptyList()
        return nodes(reply.optJSONArray("nodes"))
    }

    fun nodes(arr: JSONArray?): List<Node> {
        if (arr == null) return emptyList()
        val out = ArrayList<Node>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val path = o.optString("path")
            val pats = ArrayList<String>()
            o.optJSONArray("patterns")?.let { p ->
                for (k in 0 until p.length()) pats.add(p.optString(k))
            }
            out.add(
                Node(
                    path = path,
                    depth = path.count { it == '/' },
                    type = o.optString("type"),
                    name = o.optString("name"),
                    x = o.optInt("x"),
                    y = o.optInt("y"),
                    w = o.optInt("w"),
                    h = o.optInt("h"),
                    enabled = o.optBoolean("enabled", true),
                    patterns = pats,
                ),
            )
        }
        return out
    }

    /**
     * Act on an element with a UIA pattern — no focus change, no cursor movement.
     *
     * Returns the pattern that answered (`invoke`, `value`, `select`, `legacy`,
     * `focus`), or null when none did. The caller must treat null as "this control
     * does not accept background input", **not** as "click it harder": a
     * `ValuePattern`-less edit box is a control whose text can only be typed into
     * once it has focus, and pretending otherwise is how a background screen
     * silently stops doing what it claims.
     */
    fun act(hwnd: Long, path: String, action: String, value: String = ""): String? {
        val reply = call(
            JSONObject()
                .put("op", "act")
                .put("hwnd", hwnd)
                .put("path", path)
                .put("action", action)
                .put("value", value),
        ) ?: return null
        if (!reply.optBoolean("ok", false)) {
            Log.i(TAG, "uia act $action at $path refused: ${reply.optString("error")}")
            return null
        }
        return reply.optString("how").ifEmpty { action }
    }

    // ---- the pipe ---------------------------------------------------------

    private fun call(request: JSONObject): JSONObject? {
        val s = ensure() ?: return null
        val reply = s.call(request.toString())
        if (reply == null) Log.w(TAG, "no answer to ${request.optString("op")}")
        return reply
    }

    /**
     * The bridge process: one JSON line out, one back, one caller at a time.
     *
     * Serialised with a lock because the protocol is a pipe: two tools running at
     * once would read each other's answers, and the mismatch would look like the
     * tree being wrong rather than like a race.
     */
    private class Session private constructor(private val process: Process) {

        private val lines = LinkedBlockingQueue<String>()
        private val ended = java.util.concurrent.CountDownLatch(1)
        // **Charsets.UTF_8 on both directions, explicitly.** The default is the
        // platform charset, so on a Chinese Windows this wrote GBK while the script
        // read UTF-8 (or the reverse) and every non-ASCII label — which is most of
        // the labels this feature exists for — arrived as garbage and matched
        // nothing. ASCII commands were unaffected, so nothing looked wrong.
        private val writer = java.io.BufferedWriter(
            java.io.OutputStreamWriter(process.outputStream, Charsets.UTF_8),
        )
        private val lock = Any()

        init {
            Thread {
                runCatching {
                    java.io.BufferedReader(
                        java.io.InputStreamReader(process.inputStream, Charsets.UTF_8),
                    ).use { input ->
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isNotBlank()) lines.put(line)
                        }
                    }
                }
                ended.countDown()
            }.apply { isDaemon = true; name = "uia-bridge-read" }.start()
        }

        val alive: Boolean get() = ended.count == 1L

        fun call(json: String): JSONObject? = synchronized(lock) {
            if (!alive) return null
            if (!runCatching { writer.write(json); writer.write("\n"); writer.flush() }.isSuccess) {
                return null
            }
            val deadline = System.currentTimeMillis() + CALL_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val line = runCatching { lines.poll(200, TimeUnit.MILLISECONDS) }.getOrNull()
                    ?: continue
                val parsed = runCatching { JSONObject(line) }.getOrNull() ?: continue
                // The bridge answers one line per command, so the first JSON line
                // after a request is the answer to it.
                return parsed
            }
            Log.w(TAG, "uia bridge timed out on ${json.take(80)}")
            return null
        }

        fun close() {
            runCatching { writer.write("{\"op\":\"quit\"}\n"); writer.flush() }
            runCatching { writer.close() }
            runCatching { process.destroy() }
        }

        companion object {

            /**
             * Write the script out and start PowerShell on it.
             *
             * The script ships as a resource rather than as a string constant so it
             * can be read, linted and edited as PowerShell — and it is copied to
             * `%TEMP%` because `-File` needs a real path (a resource inside a jar is
             * not one, and `-Command` would put the whole script on a command line
             * with its quoting).
             */
            fun start(): Session? = runCatching {
                val script = materialise() ?: return null
                val pb = ProcessBuilder(
                    "powershell.exe",
                    "-NoProfile",
                    "-NonInteractive",
                    "-ExecutionPolicy", "Bypass",
                    "-File", script.absolutePath,
                )
                pb.redirectErrorStream(true)
                val process = pb.start()
                val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
                // The hello line is the handshake: without it, the first real call
                // would read a startup error as an answer.
                val hello = withTimeout(BOOT_TIMEOUT_MS) { reader.readLine() }.orEmpty()
                if (!hello.contains("uia-bridge")) {
                    Log.w(TAG, "bridge said: ${hello.take(200)}")
                    runCatching { process.destroy() }
                    return null
                }
                Session(process)
            }.onFailure { Log.w(TAG, "could not start the UI Automation bridge", it) }.getOrNull()

            private fun withTimeout(ms: Long, block: () -> String?): String? {
                val box = java.util.concurrent.FutureTask(block)
                Thread(box).apply { isDaemon = true }.start()
                return runCatching { box.get(ms, TimeUnit.MILLISECONDS) }.getOrNull()
            }

            private fun materialise(): File? {
                val bytes = WinUia::class.java.getResourceAsStream("/uia-bridge.ps1")
                    ?.use { it.readBytes() } ?: return null
                val target = File(System.getProperty("java.io.tmpdir"), "andee-uia-bridge.ps1")
                // Rewritten only when it differs, so the file keeps its timestamp and
                // a running bridge is never edited underneath itself.
                if (!target.isFile || target.length() != bytes.size.toLong()) {
                    target.writeBytes(bytes)
                }
                return target
            }
        }
    }
}
