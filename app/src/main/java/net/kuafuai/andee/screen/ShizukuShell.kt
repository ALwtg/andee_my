package net.kuafuai.andee.screen

import android.util.Log
import net.kuafuai.andee.device.ShizukuStatus
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit

/**
 * A shell, borrowed. **Blocking — never call this from the main thread.**
 *
 * The background screen needs four things a normal app cannot do, and all four
 * have a one-line shell equivalent that has worked since Android 4:
 * `settings put` to publish the display, `am start --display` to put an app on
 * it, `input -d` to touch it, `screencap -d` to look at it. Behind Shizuku those
 * run as the shell user, which is the whole reason this object exists rather
 * than a pile of hidden-API reflection: the commands are documented, stable
 * across OEMs, and observable in `adb shell` when something goes wrong.
 *
 * **What it is not.** It is not a general command runner for the brain. Nothing
 * in `ToolSchemas` maps to it, `CommandDispatcher` never takes a command as a
 * parameter, and every caller here passes a literal built in this package from
 * a `pkg` / `displayId` / coordinate the device already validated. That
 * matters: a shell with an interpolated string from a model is an RCE on the
 * user's phone with their own accounts logged in.
 *
 * Failures are values, not exceptions. A missing grant, a Shizuku that was
 * killed by a ROM, a command that is not there on this OEM — all of them come
 * back as [Result.ok] == false with the shell's own words in [Result.out],
 * because those words are the most useful thing anyone can read afterwards.
 */
object ShizukuShell {

    private const val TAG = "Body"

    data class Result(val code: Int, val out: String) {
        val ok: Boolean get() = code == 0
    }

    /**
     * Whether the channel is up right now.
     *
     * Live, not cached: every caller of this is about to run something, and a
     * stale `UNKNOWN` after an install would refuse real work for no reason. See
     * [ShizukuStatus.liveReady].
     */
    fun ready(): Boolean = ShizukuStatus.liveReady()

    /**
     * Run [command] and collect stdout+stderr as text.
     *
     * `2>&1` is folded into the command rather than reading two streams: a shell
     * that fills one pipe while nobody drains it blocks forever, and the
     * difference between "the command failed" and "the command could not be run"
     * lives entirely in stderr here.
     *
     * ## The deadline is on the stream, not on the process
     *
     * **Measured on the device: every command appeared to hang, including
     * `id -u`.** The cause was this side, not the shell — `Shizuku.newProcess`
     * returns a *custom* `Process`, and going anywhere near its
     * `waitFor(long, TimeUnit)` / `exitValue()` implementation is not safe: the
     * default implementation of the timed wait polls `exitValue()`, and one
     * throw from that path was caught by `getOrDefault(false)` and reported as
     * "timed out after Nms" — with the child killed before it had printed a
     * thing. So the completion signal is **EOF on stdout**, an interface every
     * `Process` gets right because the OS provides it. Exit status is read
     * afterwards, and only if it can be read at all.
     */
    fun run(command: String, timeoutMs: Long = 6_000): Result {
        val started = start("$command 2>&1") ?: return Result(-1, "shizuku is not ready")
        val sink = StringBuilder()
        val eof = java.util.concurrent.CountDownLatch(1)
        Thread {
            runCatching {
                started.inputStream.bufferedReader().use { input ->
                    while (true) {
                        val line = input.readLine() ?: break
                        synchronized(sink) { sink.append(line).append('\n') }
                    }
                }
            }
            eof.countDown()
        }.apply { isDaemon = true; name = "shizuku-read" }.start()

        val finished = runCatching { eof.await(timeoutMs, TimeUnit.MILLISECONDS) }
            .getOrDefault(false)
        if (!finished) runCatching { started.destroy() }
        val out = synchronized(sink) { sink.toString().trim() }
        if (!finished) {
            Log.w(TAG, "shell gave no EOF within ${timeoutMs}ms: $command")
            return Result(-1, "timed out after ${timeoutMs}ms: $command\n$out".trim())
        }
        return Result(runCatching { started.exitValue() }.getOrDefault(0), out)
    }

    /**
     * The same, but for bytes — `screencap -p` writes a PNG to stdout.
     *
     * Null means "no frame", which is a different statement from an empty one:
     * callers show the last frame they had rather than a black rectangle that
     * looks like the app crashed.
     */
    fun runBytes(command: String, timeoutMs: Long = 8_000): ByteArray? {
        val started = start(command) ?: return null
        val sink = java.io.ByteArrayOutputStream()
        val eof = java.util.concurrent.CountDownLatch(1)
        Thread {
            runCatching {
                val input = started.inputStream
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(chunk)
                    if (n <= 0) break
                    synchronized(sink) { sink.write(chunk, 0, n) }
                }
            }
            eof.countDown()
        }.apply { isDaemon = true; name = "shizuku-read-bytes" }.start()

        // Same rule as [run]: EOF is the completion signal, never `waitFor`.
        val finished = runCatching { eof.await(timeoutMs, TimeUnit.MILLISECONDS) }
            .getOrDefault(false)
        if (!finished) {
            runCatching { started.destroy() }
            Log.w(TAG, "shell gave no EOF within ${timeoutMs}ms: $command")
        }
        return synchronized(sink) { sink.toByteArray() }.takeIf { finished && it.isNotEmpty() }
    }

    /**
     * Start a command and hand back a live [Session] — stdin stays open.
     *
     * Null means the channel is down (dead binder, revoked grant), which the
     * caller reports as "the background screen could not be started" rather than
     * as a timeout.
     */
    fun open(command: String): Session? = start(command)?.let { Session(it) }

    /**
     * A **long-lived** child: a helper that keeps a display open and answers one
     * line per command.
     *
     * The one-shot [run] is the wrong shape for the background screen — every
     * command would pay for a process, and the display would die with it. This
     * keeps stdin open for commands and turns stdout into a queue, with the same
     * discipline as [run]: **EOF is the completion signal**, never `waitFor`.
     */
    class Session internal constructor(private val process: Process) {

        private val lines = java.util.concurrent.LinkedBlockingQueue<String>()
        private val ended = java.util.concurrent.CountDownLatch(1)
        private val writer = runCatching { process.outputStream.bufferedWriter() }.getOrNull()

        init {
            Thread {
                runCatching {
                    process.inputStream.bufferedReader().use { input ->
                        while (true) {
                            val line = input.readLine() ?: break
                            lines.put(line)
                        }
                    }
                }
                ended.countDown()
            }.apply { isDaemon = true; name = "shizuku-session" }.start()
        }

        /** True while the child is still there. Cheap: no binder call. */
        val alive: Boolean get() = ended.count == 1L

        fun send(line: String): Boolean = runCatching {
            val w = writer ?: return@runCatching false
            synchronized(w) {
                w.write(line)
                w.write("\n")
                w.flush()
            }
            true
        }.getOrDefault(false)

        /** The next line, or null on timeout / EOF. */
        fun readLine(timeoutMs: Long): String? =
            runCatching { lines.poll(timeoutMs, TimeUnit.MILLISECONDS) }.getOrNull()

        /** Everything already queued, without waiting — for a failure report. */
        fun drain(): List<String> {
            val out = ArrayList<String>()
            while (true) out += lines.poll() ?: break
            return out
        }

        fun close() {
            runCatching { send("close") }
            runCatching { writer?.close() }
            runCatching { process.destroy() }
        }
    }

    /**
     * Start `sh` as the shell user — the reason any of this works.
     *
     * **Reflection, and the one place in this file where that is acceptable.**
     * `Shizuku.newProcess` is the library's own entry point for exactly this and
     * is marked private in the 13.x API, so calling it means reaching for it
     * reflectively. The alternative is the binder-wrapper route: wrap the hidden
     * `IActivityManager` / `IInputManager` stubs, which means shipping our own
     * copies of their AIDL transaction codes and hoping they match the ROM —
     * version-fragile in a way a single method lookup is not.
     *
     * Everything it can fail with (a dead binder, a revoked grant, a ROM that
     * forbids `sh`, a library that renamed the method) lands in the same place:
     * null, and the caller reports that the channel is down instead of guessing
     * why.
     */
    private fun start(command: String): Process? =
        runCatching {
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            )
            m.isAccessible = true
            m.invoke(null, arrayOf("sh", "-c", command), null, null) as? Process
        }
            .onFailure { Log.w(TAG, "shizuku would not start a process", it) }
            .getOrNull()
}
