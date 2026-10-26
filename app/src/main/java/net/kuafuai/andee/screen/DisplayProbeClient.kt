package net.kuafuai.andee.screen

import android.content.Context
import android.util.Base64
import android.util.Log
import android.view.Display
import net.kuafuai.andee.device.ShizukuStatus
import java.io.File
import java.util.zip.ZipFile

/**
 * Runs `helper/DisplayProbe` through Shizuku and reads its verdict.
 *
 * **Blocking — worker thread only.** It starts another process, waits for it to
 * create a display, launch an app, read a frame and exit, which is seconds.
 *
 * ## Why the app does not just ask `DisplayManager` itself
 *
 * Because the question is "can a process that owns a `Surface` give us a display
 * of its own", and only a process holding the shell's permissions can answer it.
 * The app can publish a *mirror* (`settings put global overlay_display_devices`)
 * and that answer is already known to be wrong on this device — see
 * `VirtualScreens.MIRROR_REFUSAL`.
 *
 * ## The four steps, in the order that removes the most doubt first
 *
 * 1. **The channel.** `id -u`, an echo, `ls -l` on the classpath, and whether
 *    `app_process` / `dalvikvm` exist. All shell builtins and coreutils: if this
 *    comes back empty, nothing about ART matters yet.
 * 2. **`hello` through `app_process`** with the installed APK as classpath. It
 *    runs no platform code, so its only question is whether a JVM starts and can
 *    load our class.
 * 3. **`hello` again**, with the dex files extracted to the app's *external*
 *    files directory — the one place the shell user can read — for runtimes that
 *    open only the primary dex from an APK.
 * 4. **`hello` once more through `dalvikvm -cp`.** `app_process` is a thin
 *    wrapper around that runtime; when the wrapper is what wedges, this says so
 *    and gives a path that does not go through it.
 *
 * Only after one of those answers does the display probe run at all. On the
 * device, the first version jumped straight to step 5 and produced **no output
 * for 25 seconds** — the same wall this ordering exists to avoid.
 *
 * ## What a good answer looks like, and why the frame is compared
 *
 * `OK display` alone proves nothing: mirror displays also report an id and a
 * name. The decisive fact is **the picture** — the probe's own `Surface` frame
 * against a capture of the default display taken right afterwards.
 *
 *  * Large difference — the app is drawing *there* while the user's screen shows
 *    something else. That is a second screen.
 *  * Near-zero difference — either the display is a copy, or the launch was
 *    redirected and the app is now in front of the user; the same failure from
 *    where the user is sitting.
 *
 * The threshold is `VirtualScreens`'s own, so the diagnostic and the runtime
 * check cannot drift apart.
 */
object DisplayProbeClient {

    private const val TAG = "Body"

    /** What the probe launches. Settings is on every device and harmless to start. */
    const val DEFAULT_PROBE_PKG = "com.android.settings"

    /** The cheap first step: it runs no platform code at all. */
    private const val HELLO_TIMEOUT_MS = 12_000L

    /** `echo` and `ls` either answer at once or never. */
    private const val CHANNEL_TIMEOUT_MS = 8_000L

    /**
     * @param frame the PNG the probe read out of its own Surface, or null.
     * @param log every line that got as far as this side — channel check, each
     *   attempt's own words, and the probe's stages. This is the product of a run
     *   that did *not* succeed, so it is never trimmed to the happy path.
     */
    data class Outcome(
        val displayId: Int?,
        val frame: ByteArray?,
        val log: List<String>,
        val delta: Double?,
        val failure: String?,
    ) {
        val ok: Boolean get() = failure == null && frame != null
        val independent: Boolean
            get() = ok && delta != null && delta > VirtualScreens.mirrorTolerance()
    }

    fun run(
        context: Context,
        pkg: String = DEFAULT_PROBE_PKG,
        timeoutMs: Long = 25_000L,
    ): Outcome {
        if (!ShizukuShell.ready()) {
            return Outcome(null, null, emptyList(), null, "shizuku is not running or not authorised")
        }
        val apk = runCatching { context.applicationInfo.sourceDir }.getOrNull()
            ?: return Outcome(null, null, emptyList(), null, "this build has no source dir to hand the helper")

        val kept = ArrayList<String>()

        // ---- 1. the channel ------------------------------------------------
        val channel = channelCheck(apk)
        kept += channel.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
            .map { "channel: ${it.take(200)}" }.toList()
        if (channel.isBlank()) {
            kept += "channel: (no output at all — the shell itself did not answer)"
        }

        // ---- 2 and 3. hello, with two classpaths ---------------------------
        var classpath = apk
        var hello = ShizukuShell.run(command(classpath, "hello"), HELLO_TIMEOUT_MS)
        var how = "app_process + apk"
        if (!started(hello)) {
            kept += "app_process/apk: ${hello.out.take(300).ifBlank { "(no output)" }}"
            val dex = extractDex(context)
            if (dex != null) {
                classpath = dex
                hello = ShizukuShell.run(command(classpath, "hello"), HELLO_TIMEOUT_MS)
                how = "app_process + dex"
                if (!started(hello)) {
                    kept += "app_process/dex: ${hello.out.take(300).ifBlank { "(no output)" }}"
                }
            } else {
                kept += "dex: could not be extracted for a retry"
            }
        }

        // ---- 4. the other entry point --------------------------------------
        if (!started(hello)) {
            val alt = "dalvikvm -cp '$classpath' net.kuafuai.andee.helper.DisplayProbe hello"
            val viaDalvik = ShizukuShell.run(alt, HELLO_TIMEOUT_MS)
            if (started(viaDalvik)) {
                hello = viaDalvik
                how = "dalvikvm"
            } else {
                kept += "dalvikvm: ${viaDalvik.out.take(300).ifBlank { "(no output)" }}"
            }
        }

        if (!started(hello)) {
            kept += hello.out.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            Log.w(TAG, "display probe: the helper never started. ${kept.joinToString(" | ")}")
            return Outcome(
                null, null, kept, null,
                "the helper process did not start (see the lines below — the channel and each " +
                    "runtime have their own answer)",
            )
        }
        kept += "helper started via $how"

        // ---- 5. the display ------------------------------------------------
        val result = ShizukuShell.run(command(classpath, "display", pkg), timeoutMs)
        val lines = result.out.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

        var displayId: Int? = null
        var frame: ByteArray? = null
        var failure: String? = null

        for (line in lines) {
            when {
                line.startsWith("OK display id=") -> {
                    displayId = line.removePrefix("OK display id=")
                        .substringBefore(' ').toIntOrNull()
                    kept += line
                }

                line.startsWith("OK frame ") -> {
                    // "OK frame 1080x1920 <base64>"
                    val parts = line.split(' ', limit = 4)
                    val b64 = parts.getOrNull(3).orEmpty()
                    frame = runCatching { Base64.decode(b64, Base64.NO_WRAP) }.getOrNull()
                    kept += if (frame == null) "OK frame <undecodable>" else "OK frame ${parts.getOrNull(1)}"
                }

                line.startsWith("ERR ") -> {
                    failure = line.removePrefix("ERR ")
                    kept += line
                }

                else -> kept += line.take(400)
            }
        }

        if (failure == null && frame == null) {
            failure = if (result.ok) "the helper said nothing about a frame" else result.out.take(300)
        }
        if (failure == null && displayId == null) failure = "the helper never reported a display"

        // Taken after the helper is gone on purpose: in the mirror case the app it
        // launched is still in front, so this capture is what the user is looking
        // at, and comparing the two answers the real question.
        val delta = if (frame != null) {
            val host = ShizukuShell.runBytes("screencap -p")
            if (host == null) null else VirtualScreens.framesDiffer(frame, host)
        } else {
            null
        }

        Log.i(
            TAG,
            "display probe: display=$displayId frame=${frame?.size ?: 0}B delta=$delta failure=$failure",
        )
        return Outcome(displayId, frame, kept, delta, failure)
    }

    /**
     * The channel check alone, as JSON — what `debug.shizuku` returns.
     *
     * No display is created and nothing is launched: this answers "does a command
     * started through Shizuku ever come back", which is the question that took
     * several rounds to isolate because the helper itself works perfectly from
     * `adb shell`.
     */
    fun channelReport(
        context: Context,
        display: Boolean = false,
        screen: Boolean = false,
        shot: Boolean = false,
        pkg: String? = null,
    ): org.json.JSONObject {
        val apk = runCatching { context.applicationInfo.sourceDir }.getOrNull()
            ?: return org.json.JSONObject().put("error", "no source dir to hand the helper")
        val raw = channelCheck(apk)
        // …and the step that actually matters: can this process start the helper,
        // and does the helper's own first line come back? `hello` runs no platform
        // code, so a failure here is about the transport and nothing else.
        val hello = ShizukuShell.run(command(apk, "hello"), HELLO_TIMEOUT_MS)
        val report = org.json.JSONObject()
            .put("shizuku", ShizukuStatus.cached().state.name)
            .put("ready", ShizukuShell.ready())
            .put("raw", raw)
            .put("lines", raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList())
            .put("hello_ok", started(hello))
            .put("hello", hello.out.take(600))

        // Optional third half: the **engine**, not the probe — the helper in its
        // long-lived `serve` form, exactly as `screen.background` drives it. This
        // bypasses nothing but the keyguard guard in `CommandDispatcher`, which is
        // there for the model's sake; a loopback caller with adb is already past
        // anything that guard protects.
        if (screen) {
            val target = pkg ?: DEFAULT_PROBE_PKG
            val started = VirtualScreens.start(context, target, target)
            report
                .put("screen_ok", started.isSuccess)
                .put("screen_id", started.getOrNull()?.displayId ?: -1)
                .put("screen_error", started.exceptionOrNull()?.message.orEmpty())
            if (started.isSuccess && shot) {
                val png = VirtualScreens.shotPng(started.getOrNull()!!.displayId)
                report
                    .put("shot_bytes", png?.length ?: 0)
                    .put("shot_head", png?.take(40).orEmpty())
            }
        }

        // Optional second half: the whole display probe, so a laptop can ask the
        // one question the button asks without a finger on the glass.
        if (display) {
            val outcome = run(context)
            report
                .put("display_ok", outcome.ok)
                .put("display_independent", outcome.independent)
                .put("display_id", outcome.displayId ?: -1)
                .put("display_delta", outcome.delta ?: -1.0)
                .put("display_failure", outcome.failure.orEmpty())
                .put("display_log", outcome.log.takeLast(12))
        }
        return report
    }

    // ---- process ---------------------------------------------------------

    private fun command(classpath: String, mode: String, pkg: String = ""): String = buildString {
        append("CLASSPATH='").append(classpath).append("' ")
        append("app_process /system/bin --nice-name=andee-probe ")
        append("net.kuafuai.andee.helper.DisplayProbe ").append(mode)
        if (mode == "display") {
            append(' ').append(VirtualScreens.WIDTH).append(' ').append(VirtualScreens.HEIGHT)
            append(" 320 ").append(pkg)
        }
    }

    /**
     * The four facts that decide whether anything else is worth trying:
     * the uid we actually run as, that output comes back at all, that the shell
     * can read the classpath, and that the runtime binaries exist.
     *
     * `app_process` with no arguments is deliberately last and `head`-bounded: on
     * a healthy device it prints one usage line and exits, and on a device where
     * the binary itself wedges it is the thing that eats the timeout — which is
     * also an answer, and it comes after the data we can still use.
     */
    private fun channelCheck(classpath: String): String =
        ShizukuShell.run(
            // Only things that answer or fail immediately. An `app_process` probe
            // used to be the last item here and was the reason this check could
            // never report anything: it can sit forever, and the deadline then
            // swallowed the facts that had already come back.
            "id -u; echo probe-channel-ok; ls -l '$classpath' 2>&1 | head -2; " +
                "command -v app_process; command -v dalvikvm",
            CHANNEL_TIMEOUT_MS,
        ).out

    /** Whether the helper ran to the end of `hello` — the only proof it started. */
    private fun started(result: ShizukuShell.Result): Boolean =
        result.ok && result.out.contains("OK hello done")

    /**
     * Every `classesN.dex` in the APK, in the app's external files directory, as a
     * `:`-joined classpath — or null when it cannot be produced.
     *
     * External rather than internal on purpose: the shell user cannot read
     * `/data/data/<pkg>/`, and a classpath the helper process cannot open is worse
     * than no retry at all, because it fails the same way twice.
     */
    private fun extractDex(context: Context): String? = runCatching {
        val apk = File(context.applicationInfo.sourceDir)
        val dir = File(context.getExternalFilesDir(null) ?: return@runCatching null, "probe")
        val marker = File(dir, "stamp")
        val stamp = "${apk.length()}-${apk.lastModified()}"
        val dexName = Regex("classes\\d*\\.dex")

        val present = dir.listFiles { f -> dexName.matches(f.name) }.orEmpty()
        // A cache hit has to prove it is *this* build's dex, or an upgrade would
        // silently run the previous version's helper.
        if (marker.isFile && present.isNotEmpty() &&
            runCatching { marker.readText().trim() }.getOrNull() == stamp
        ) {
            return@runCatching present.sortedBy { it.name }
                .joinToString(":") { it.absolutePath }
        }

        dir.mkdirs()
        present.forEach { runCatching { it.delete() } }
        ZipFile(apk).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && dexName.matches(it.name) }
                .forEach { entry ->
                    val out = File(dir, entry.name)
                    zip.getInputStream(entry).use { input ->
                        out.outputStream().use { output -> input.copyTo(output) }
                    }
                }
        }
        marker.writeText(stamp)
        dir.listFiles { f -> dexName.matches(f.name) }.orEmpty()
            .sortedBy { it.name }
            .joinToString(":") { it.absolutePath }
    }.onFailure { Log.w(TAG, "display probe: dex extraction failed", it) }.getOrNull()
}
