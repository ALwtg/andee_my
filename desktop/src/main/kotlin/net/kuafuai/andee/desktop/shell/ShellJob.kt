package net.kuafuai.andee.desktop.shell

import net.kuafuai.andee.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * One command, running. Started through `/usr/bin/script`, which gives it a
 * real terminal (so tools behave as they do for a person: colours, progress
 * bars, no "not a tty" detours) with no extra library. Its stdin is closed:
 * the model cannot type into a running program, and a program that waits for
 * input is reported as waiting rather than hanging the turn.
 *
 * Every job leads its own process group (`script` calls setsid), so stopping
 * it — by the model, by a triple-click on the ball, or because Andee is
 * quitting — ends everything it started.
 */
class ShellJob(
    val id: String,
    val command: String,
    val cwd: File,
    private val process: Process,
    private val logFile: File,
) {
    private val buf = StringBuilder()
    @Volatile private var readUpTo = 0
    @Volatile var lastOutputAt = System.currentTimeMillis(); private set
    val startedAt = System.currentTimeMillis()
    @Volatile var stoppedBy: String? = null

    init {
        Thread({
            val r = process.inputStream.reader(Charsets.UTF_8)
            val chunk = CharArray(4096)
            logFile.parentFile.mkdirs()
            logFile.bufferedWriter().use { log ->
                while (true) {
                    val n = runCatching { r.read(chunk) }.getOrDefault(-1)
                    if (n < 0) break
                    val s = String(chunk, 0, n)
                    synchronized(buf) { buf.append(s) }
                    log.write(s); log.flush()
                    lastOutputAt = System.currentTimeMillis()
                }
            }
        }, "job-$id").apply { isDaemon = true; start() }
    }

    val running: Boolean get() = process.isAlive
    val exitCode: Int? get() = if (process.isAlive) null else process.exitValue()

    fun waitFor(ms: Long): Boolean = process.waitFor(ms, TimeUnit.MILLISECONDS)

    /** Everything so far, raw. */
    fun output(): String = synchronized(buf) { buf.toString() }

    /** What arrived since the last call. */
    fun takeNew(): String = synchronized(buf) {
        val s = buf.substring(readUpTo.coerceAtMost(buf.length))
        readUpTo = buf.length
        s
    }

    fun markAllRead() = synchronized(buf) { readUpTo = buf.length }

    val log: File get() = logFile

    /**
     * End it and everything it started: SIGTERM to the whole group, then
     * SIGKILL after three seconds for whatever ignored it.
     */
    fun stop(by: String) {
        if (!process.isAlive) return
        stoppedBy = by
        // Per platform, and not for tidiness: `script` put this in its own process
        // group so a negative pid reaches everything, and Windows has no such
        // thing — `taskkill /T` walks the tree instead. See [ShellRunner.stop].
        ShellRunner.stop(process)
    }
}

/** Starts and keeps track of [ShellJob]s. */
class JobTable(private val logDir: File) {

    private val jobs = ConcurrentHashMap<String, ShellJob>()
    private val seq = AtomicInteger(0)

    init {
        Runtime.getRuntime().addShutdownHook(Thread { stopAll("andee_quit") })
    }

    fun start(
        command: String,
        cwd: File,
        env: Map<String, String>,
        sandboxProfile: String?,
    ): ShellJob {
        val id = "j${seq.incrementAndGet()}"
        // The SBPL profile only exists on macOS; on Windows the caller passes null
        // and isolation comes from the environment instead (see CondaSandboxes).
        val prof = sandboxProfile?.let { text ->
            File(logDir, "$id.sb").apply { parentFile.mkdirs(); writeText(text) }
        }
        val pb = ProcessBuilder(ShellRunner.argv(command, prof)).directory(cwd).redirectErrorStream(true)
        pb.environment().apply {
            if (ShellRunner.isWindows) {
                // **Merged, not cleared.** PowerShell needs SystemRoot, TEMP and
                // PATHEXT from the real environment; a cleared environment cannot
                // even load the runtime, and the failure reads as "the command
                // produced no output".
                putAll(env)
            } else {
                clear()
                putAll(env)
            }
        }
        pb.redirectInput(ProcessBuilder.Redirect.from(File(if (ShellRunner.isWindows) "NUL" else "/dev/null")))
        val p = pb.start()
        val job = ShellJob(id, command, cwd, p, File(logDir, "$id.log"))
        jobs[id] = job
        Log.i(TAG, "$id started in ${cwd.path}: ${command.take(120)}")
        return job
    }

    operator fun get(id: String): ShellJob? = jobs[id]

    fun running(): List<ShellJob> = jobs.values.filter { it.running }

    fun stopAll(by: String) {
        for (j in jobs.values) if (j.running) j.stop(by)
    }

    private companion object {
        const val TAG = "Shell"
    }
}

/** Turns a terminal's raw output into text a model can read. */
object TerminalText {
    private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)|\u001B[()][0-9A-Za-z]|\u001B[=>]")

    fun clean(raw: String): String {
        val noAnsi = ANSI.replace(raw, "")
        // `script` echoes a ^D when stdin closes; drop it.
        val noCtl = noAnsi.replace("^D\b\b", "").replace("\u0004", "")
        // A carriage return without a newline redraws the line (progress bars): keep the last frame.
        return noCtl.split("\n").joinToString("\n") { line ->
            line.trimEnd('\r').substringAfterLast('\r')
        }
    }

    /** First [head] and last [tail] characters, with how much was cut. */
    fun truncate(s: String, head: Int = 2_000, tail: Int = 6_000): Pair<String, Boolean> {
        if (s.length <= head + tail) return s to false
        val cut = s.substring(head, s.length - tail)
        val lines = cut.count { it == '\n' }
        return (s.take(head) + "\n…(${lines} lines left out)…\n" + s.takeLast(tail)) to true
    }
}
