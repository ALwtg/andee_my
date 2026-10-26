package net.kuafuai.andee.helper

import android.content.Context
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Looper
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * The shell-side probe: **can this device give us a display of its own?**
 *
 * Not an app — a `main`, started by the app through Shizuku as the shell user,
 * with this APK on its classpath:
 *
 * ```
 * CLASSPATH=<app.apk> app_process /system/bin --nice-name=andee-probe \
 *     net.kuafuai.andee.helper.DisplayProbe display 1080 1920 320 com.android.settings
 * ```
 *
 * **Why it has to be a separate process.** A display that third-party apps can be
 * launched onto needs `VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY` *and* the
 * permission to create it (`CAPTURE_VIDEO_OUTPUT`), and it renders into a
 * `Surface` held by whoever created it. An ordinary app has neither; the shell
 * user has both. The same division scrcpy uses, for the same reason.
 *
 * ## Two modes, because one failure hides another
 *
 *  * `hello` — print the uid, the sdk, the classpath the runtime actually sees,
 *    and exit. **No platform calls at all.** This separates "the process could
 *    not be started or the class could not be loaded" from "one of the calls
 *    below hung", which are the same blank screen otherwise.
 *  * `display` — create the display, launch one app on it, read one frame out of
 *    the `Surface`, print, release, exit.
 *
 * ## Why every line is printed before the work it describes
 *
 * The first version printed nothing until a system `Context` existed, and on the
 * device it produced **no output at all for 25 seconds** — a blank wall that
 * could mean the class loader, `Looper`, `ActivityThread.systemMain`, or the
 * display service, and no way to tell them apart. So: `OK start` is the first
 * statement, [watchDog] prints the stage it is stuck in and kills the process,
 * and every risky call is preceded by `stage.set(...)`. A diagnostic that can
 * only say "it did not work" is not a diagnostic.
 *
 * Output is a contract, one line per fact:
 *
 *  - `OK start uid=… pid=… sdk=… mode=…`
 *  - `OK classpath <what the JVM thinks it is>`   (`hello` only)
 *  - `OK context` / `OK display id=N name=… state=…` / `OK launch …`
 *  - `OK frame WxH <base64 png>`                  (single line, megabytes)
 *  - `ERR <where> <detail>` / `ERR watchdog stuck at '<stage>' after <ms>ms`
 *  - `OK done`
 */
object DisplayProbe {

    private const val FRAME_WAIT_MS = 4_000L
    private const val POLL_MS = 120L

    /** In `serve`, a frame is wanted now: wait a beat, then hand back the last one. */
    private const val FRAME_QUICK_MS = 1_200L

    /**
     * **Nothing in this file draws.** Not the aim grid, not a caption, not a
     * marker: `Canvas.drawText` here killed the process with SIGABRT (no font
     * manager outside an application), and the display went down with it. The
     * frames leave this process raw and the app annotates them — see
     * `ScreenController.gridOverlay`.
     */

    /**
     * Killed from inside, before the app's own 25 s timeout, so the last stage is
     * always reported. Generous: a cold ART start on an old device plus one
     * activity launch is still well under this.
     */
    private const val WATCHDOG_MS = 20_000L

    private const val LOG_TAG = "AndeeProbe"

    /** What the process is doing right now — read by [watchDog] from another thread. */
    private val stage = AtomicReference("start")

    /** fd 1, opened once. See [say]. */
    private val sink = java.util.concurrent.atomic.AtomicReference<FileOutputStream?>(null)

    @JvmStatic
    fun main(args: Array<String>) {
        val mode = args.getOrNull(0)?.trim().orEmpty().ifEmpty { "display" }

        // First statement, before any platform call that could block.
        // `hold` keeps the display alive so it can be experimented on from a
        // laptop: launch commands, `dumpsys`, and a stack move all have to be
        // issued *while* the display exists, and a probe that exits after one
        // frame cannot answer "is this display launchable at all".
        val holdMs = args.getOrNull(5)?.toLongOrNull() ?: 0L
        say(
            "OK start uid=${android.os.Process.myUid()} pid=${android.os.Process.myPid()} " +
                "sdk=${Build.VERSION.SDK_INT} mode=$mode",
        )
        // **`serve` arms no watchdog, and that is not an oversight.** The watchdog
        // exists to stop a one-shot probe that wedged in a platform call; a server
        // is *supposed* to sit there for as long as the screen is up. Measured on
        // the device: with the 20 s watchdog armed, `serve` died mid-session, the
        // display vanished with it, and the app's next tree dump reported "no
        // window on that display yet" — which reads as an accessibility bug and is
        // not one.
        watchDog(
            when (mode) {
                "serve" -> 0L
                "hold" -> holdMs + 15_000L
                else -> WATCHDOG_MS
            },
        )

        if (mode == "hello") {
            // The classpath as the runtime resolved it, not as the shell spelled
            // it: when a class is missing, the difference is the whole answer.
            val cp = runCatching { System.getProperty("java.class.path").orEmpty() }.getOrDefault("")
            say("OK classpath ${cp.take(300)}")
            say("OK hello done")
            return
        }

        val w = args.getOrNull(1)?.toIntOrNull() ?: 1080
        val h = args.getOrNull(2)?.toIntOrNull() ?: 1920
        val dpi = args.getOrNull(3)?.toIntOrNull() ?: 320
        val pkg = args.getOrNull(4)?.trim().orEmpty()

        // A Looper for anything the platform posts back to this thread.
        stage.set("looper")
        val looperFailure = runCatching { Looper.prepareMainLooper() }.exceptionOrNull()
        say(if (looperFailure == null) "OK looper" else "WARN looper ${looperFailure.javaClass.simpleName}")

        stage.set("context")
        val context = systemContext()
        if (context == null) {
            say("ERR context ActivityThread.systemMain() refused — hidden API changed, or no system context here")
            return
        }
        say("OK context")

        stage.set("reader")
        val reader = runCatching { ImageReader.newInstance(w, h, android.graphics.PixelFormat.RGBA_8888, 2) }
            .onFailure { say("ERR reader ${it.javaClass.simpleName}: ${it.message}") }
            .getOrNull() ?: return

        stage.set("displaymanager")
        val dm = displayManager(context)
        if (dm == null) {
            say("ERR displaymanager the system has no DisplayManager service")
            return
        }

        // PUBLIC so apps can be launched onto it at all; OWN_CONTENT_ONLY so it
        // is a screen rather than a copy of the one the user is looking at. Those
        // two flags are the difference between this and the mirror-only displays
        // `settings put global overlay_display_devices` hands out — see
        // `screen/VirtualScreens.MIRROR_REFUSAL`.
        // PUBLIC: apps may be launched onto it at all.
        // OWN_CONTENT_ONLY: it is a screen, not a copy of the user's.
        // OWN_DISPLAY_GROUP (API 33+): the display gets a display *group* of its
        // own. Without it the display joins the default group, and on the device
        // measured here `am start --display N` is then **silently redirected to
        // display 0** — the app ends up in front of the user, which is the exact
        // opposite of the feature. `dumpsys window displays` shows the difference
        // as `mCurrentFocus=null` on our display and the launched activity
        // resumed as `d0`.
        //
        // `OWN_DISPLAY_GROUP` is a **system** constant (`@SystemApi`, added in T),
        // so it has no symbol in the public SDK this app compiles against. Its
        // value is `1 << 5`, and it is part of the flags integer that crosses the
        // binder — a protocol value, not a name that can drift.
        //
        // Measured: `PUBLIC | OWN_DISPLAY_GROUP` is refused outright —
        // `IllegalArgumentException: Public display must not be marked as
        // SHOW_WHEN_LOCKED_INSECURE` — because a display group of its own is
        // "always unlocked" by definition, and that is incompatible with being
        // public. So the group variant runs without PUBLIC: the shell user is the
        // display's owner, which is the identity the launch permission is about.
        val ownDisplayGroup = if (Build.VERSION.SDK_INT >= 33) 1 shl 5 else 0
        val public = if (ownDisplayGroup != 0) 0 else DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
        val flags = public or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            ownDisplayGroup

        stage.set("createVirtualDisplay")
        val display: VirtualDisplay? = runCatching {
            dm.createVirtualDisplay("andee-probe", w, h, dpi, reader.surface, flags)
        }.onFailure {
            say("ERR create ${it.javaClass.simpleName}: ${it.message}")
        }.getOrNull()

        if (display == null) {
            say("ERR display createVirtualDisplay returned null")
            return
        }
        // `VirtualDisplay` itself carries no id: the display it created does, and
        // that is the number `am start --display` and `input -d` want.
        val host: android.view.Display = display.display
        say("OK display id=${host.displayId} name=${host.name} state=${host.state}")

        // ---- serve -----------------------------------------------------------
        //
        // The shape the engine actually needs: one display, one process, one line
        // per command. Everything below is a shell command with a documented
        // interface — which is why this half is the one that can be corrected on a
        // real device without a debugger.
        if (mode == "serve") {
            stage.set("serve")
            say("OK ready display=${host.displayId} state=${host.state}")
            val input = System.`in`.bufferedReader()
            var last: Bitmap? = null
            var running = true
            while (running) {
                val cmd = (input.readLine() ?: break).trim()
                if (cmd.isEmpty()) continue
                val parts = cmd.split(' ', limit = 2)
                when (parts[0]) {
                    "ping" -> say("OK pong")

                    "open" -> {
                        val target = parts.getOrNull(1)?.trim().orEmpty()
                        if (target.isEmpty()) {
                            say("ERR open needs a package")
                        } else {
                            launch(context, target, host.displayId)
                            say("OK open")
                        }
                    }

                    "tap" -> {
                        val a = parts.getOrNull(1).orEmpty().split(' ')
                        if (a.size < 2) say("ERR tap needs x y")
                        else { sh("input -d ${host.displayId} tap ${a[0]} ${a[1]}"); say("OK tap") }
                    }

                    "swipe" -> {
                        val a = parts.getOrNull(1).orEmpty().split(' ')
                        if (a.size < 4) {
                            say("ERR swipe needs x1 y1 x2 y2 [ms]")
                        } else {
                            val ms = (a.getOrNull(4)?.toLongOrNull() ?: 300L).coerceIn(50, 3_000)
                            sh(
                                "input -d ${host.displayId} swipe " +
                                    "${a[0]} ${a[1]} ${a[2]} ${a[3]} $ms",
                            )
                            say("OK swipe")
                        }
                    }

                    "type" -> {
                        // base64 over the wire: the text is model output, and this
                        // line is read by a shell. Decoding here means the only
                        // thing that ever needs escaping is the single quote.
                        val text = runCatching {
                            String(
                                Base64.decode(parts.getOrNull(1).orEmpty(), Base64.NO_WRAP),
                                Charsets.UTF_8,
                            )
                        }.getOrNull()
                        if (text == null) {
                            say("ERR type needs base64 text")
                        } else {
                            val escaped = text.replace("'", "'\\''")
                            sh("input -d ${host.displayId} text '$escaped'")
                            say("OK type")
                        }
                    }

                    "key" -> {
                        val code = parts.getOrNull(1)?.trim()?.toIntOrNull()
                        if (code == null) say("ERR key needs a keycode")
                        else { sh("input -d ${host.displayId} keyevent $code"); say("OK key") }
                    }

                    "frame" -> {
                        // A short wait, then the previous frame: the supervision
                        // page polls this and a blank reply would make it flicker.
                        //
                        // **The raw frame, and no drawing here.** The aim grid is
                        // drawn by the app (`ScreenController.gridOverlay`): measured
                        // on the device, `Canvas.drawText` in this process killed it
                        // with SIGABRT — no font manager in an `app_process` with no
                        // application — and the display died with it. Text never
                        // happens in here again.
                        val fresh = grab(reader, FRAME_QUICK_MS)
                        if (fresh != null) {
                            if (last !== fresh) runCatching { last?.recycle() }
                            last = fresh
                        }
                        val bmp = last
                        if (bmp == null) {
                            say("ERR frame none")
                        } else {
                            if (last !== bmp) runCatching { last?.recycle() }
                            last = bmp
                            val out = ByteArrayOutputStream()
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                            say(
                                "OK frame ${bmp.width}x${bmp.height} " +
                                    Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP),
                            )
                        }
                    }

                    "close" -> {
                        say("OK closing")
                        running = false
                    }

                    else -> say("ERR unknown command ${parts[0]}")
                }
            }
            runCatching { last?.recycle() }
            runCatching { display.release() }
            runCatching { reader.close() }
            say("OK done")
            return
        }

        if (mode == "hold") {
            // Report the state the platform settled on, then sit still. Anything
            // that happens from here is the laptop's doing, and the frame that
            // matters is the one it captures afterwards.
            stage.set("hold")
            say("OK hold display=${host.displayId} state=${host.state} for ${holdMs}ms")
            try {
                Thread.sleep(holdMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            val after = runCatching { host.state }.getOrDefault(-1)
            say("OK hold end state=$after")
            // Capture at the end on purpose: whatever the laptop launched in the
            // meantime is what this frame should show, and that is the proof the
            // whole chain works.
            stage.set("hold-frame")
            grab(reader)?.let { bmp ->
                say("OK compare mean=${"%.1f".format(meanLuma(bmp))}")
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                val png = out.toByteArray()
                say("OK frame ${bmp.width}x${bmp.height} ${Base64.encodeToString(png, Base64.NO_WRAP)}")
                bmp.recycle()
            } ?: say("ERR frame nothing arrived in hold mode")
            runCatching { display.release() }
            runCatching { reader.close() }
            say("OK done")
            return
        }

        if (pkg.isNotEmpty()) {
            stage.set("launch")
            launch(context, pkg, host.displayId)
        }

        // One frame is the whole point: a display that renders nothing is
        // indistinguishable from one that does not work, and this test has to fail
        // loudly rather than hand back a black rectangle.
        stage.set("frame")
        val bitmap = grab(reader)
        if (bitmap == null) {
            say("ERR frame nothing arrived on the Surface within ${FRAME_WAIT_MS}ms")
        } else {
            // The question the whole probe exists for: is this a screen of its
            // own, or a copy of the one in the user's hand? Answered **here**
            // rather than by the caller, because the comparison has to be taken
            // while the display is alive and the app is where it was put — a
            // capture taken a second later is a different experiment.
            // Which display has focus, and does the new display own a window at
            // all? A mirror has no windows of its own — its content belongs to
            // the display it copies — so this is the structural half of the same
            // question the pixels answer.
            say("OK windows ${displayFocusSummary()}")

            val main = captureDefault()
            if (main == null) {
                say("WARN compare could not capture the default display")
            } else {
                say("OK compare delta=${"%.2f".format(meanDelta(bitmap, main))} mean=${"%.1f".format(meanLuma(bitmap))}")
                main.recycle()
            }

            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            val png = out.toByteArray()
            say("OK frame ${bitmap.width}x${bitmap.height} ${Base64.encodeToString(png, Base64.NO_WRAP)}")
            bitmap.recycle()
        }

        stage.set("release")
        runCatching { display.release() }
        runCatching { reader.close() }
        say("OK done")
    }

    /**
     * Print what we are stuck on and die, from inside.
     *
     * `halt` rather than `exit`: shutdown hooks are not worth waiting for in a
     * process whose reason for dying is that something never returned.
     */
    private fun watchDog(ms: Long) {
        if (ms <= 0L) return
        Thread {
            runCatching { Thread.sleep(ms) }
            say("ERR watchdog stuck at '${stage.get()}' after ${ms}ms")
            Runtime.getRuntime().halt(9)
        }.apply {
            isDaemon = true
            name = "probe-watchdog"
        }.start()
    }

    /**
     * A system `Context` in a process that has no Application.
     *
     * `ActivityThread.systemMain()` is the only public-shaped door: it attaches a
     * thread as the *system* one and builds the context system services use, which
     * is why it can hand out a `DISPLAY_SERVICE` that a bare `app_process` cannot
     * reach. Hidden API, so reflective — and the point most likely to break on a
     * future platform, which is why it gets its own stage and its own sentence.
     */
    private fun systemContext(): Context? = runCatching {
        val threadClass = Class.forName("android.app.ActivityThread")
        val systemMain = threadClass.getDeclaredMethod("systemMain")
        systemMain.isAccessible = true
        val thread = systemMain.invoke(null) ?: return@runCatching null
        val getter = threadClass.getDeclaredMethod("getSystemContext")
        getter.isAccessible = true
        getter.invoke(thread) as? Context
    }.getOrElse {
        say("ERR context ${it.javaClass.simpleName}: ${it.message}")
        null
    }

    /**
     * A `DisplayManager` whose **op package name matches our uid**.
     *
     * **Measured on the device (vivo, Android 16):** the system `Context` reports
     * `android` as its package, we run as `shell` (uid 2000), and
     * `DisplayManagerService` refuses the call outright —
     * `SecurityException: packageName must match the calling uid`. It is not a
     * permission problem and no grant would have fixed it: the service compares
     * the string against the caller's uid before it looks at permissions at all.
     *
     * So the call is made through a context created *for* `com.android.shell`,
     * which is the package uid 2000 actually owns. The system context stays as
     * the fallback, and which one was used is printed — the same class of refusal
     * should not need a second debugging round.
     */
    private fun displayManager(system: Context): DisplayManager? {
        val shell = runCatching { system.createPackageContext("com.android.shell", 0) }
            .onFailure { say("WARN shellcontext ${it.javaClass.simpleName}: ${it.message}") }
            .getOrNull()
        for ((label, ctx) in listOf("shell" to shell, "system" to system)) {
            if (ctx == null) continue
            val dm = runCatching { ctx.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager }
                .getOrNull() ?: continue
            val op = runCatching { ctx.opPackageName }.getOrDefault("?")
            say("OK displaymanager via=$label op=$op")
            return dm
        }
        return null
    }

    /**
     * Ask the platform to start [pkg] on display [displayId], and say what `am` said.
     *
     * Through `am` rather than through a binder on purpose: this probe is about the
     * *display*, and mixing in a second unproven mechanism would make a failure
     * ambiguous. The command is the one `adb shell` would use.
     */
    private fun launch(context: Context, pkg: String, displayId: Int) {
        val component = resolveComponent(context, pkg)
        if (component == null) {
            say("ERR launch no launchable activity for $pkg")
            return
        }
        val out = runCatching {
            // **`-f 0x18000000` is load-bearing.** `am start --display N` alone
            // reuses the top-most instance of the app if one already exists, and
            // that instance lives on the display the user is looking at — so the
            // app never moves, `am` reports a warning instead of an error, and
            // the whole thing looks like "the display is not launchable". With
            // NEW_TASK|MULTIPLE_TASK a task is created on the target display;
            // measured on the device: `topResumedActivity=… d11`.
            val p = ProcessBuilder(
                "sh", "-c",
                "am start --display $displayId -f 0x18000000 -n $component",
            )
                .redirectErrorStream(true)
                .start()
            val text = p.inputStream.bufferedReader().readText().trim()
            val code = p.waitFor()
            "exit=$code $text"
        }.getOrElse { "exit=exception ${it.message}" }
        say("OK launch $out")
    }

    /**
     * One shell command, output drained, exit code returned.
     *
     * Drained rather than ignored: a child whose pipe fills blocks forever, and
     * the caller here is a live server that would then stop answering.
     */
    private fun sh(command: String): Int = runCatching {
        val p = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
    }.getOrDefault(-1)

    private fun resolveComponent(context: Context, pkg: String): String? = runCatching {
        context.packageManager.getLaunchIntentForPackage(pkg)?.component?.flattenToShortString()
    }.getOrNull()

    /** One frame off the Surface, polled — no listener, so no Looper dependency. */
    private fun grab(reader: ImageReader, waitMs: Long = FRAME_WAIT_MS): Bitmap? {
        val deadline = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < deadline) {
            val image = runCatching { reader.acquireLatestImage() }.getOrNull()
            if (image != null) {
                return runCatching { image.use { toBitmap(it) } }
                    .onFailure { say("ERR image ${it.javaClass.simpleName}: ${it.message}") }
                    .getOrNull()
            }
            try {
                Thread.sleep(POLL_MS)
            } catch (_: InterruptedException) {
                return null
            }
        }
        return null
    }

    /**
     * The primary display, as a bitmap — through `screencap`, which is what the
     * shell has and what the app itself uses for the same comparison.
     *
     * `/data/local/tmp` rather than a pipe: this process is already shell, and a
     * file is the one channel that cannot be confused with our own stdout, which
     * is carrying base64 by then.
     */
    private fun captureDefault(): Bitmap? = runCatching {
        val file = java.io.File("/data/local/tmp/andee-main.png")
        runCatching { file.delete() }
        // **No `-d 0`.** `screencap -d` takes a *physical* display id, and on the
        // device measured here logical 0 is rejected outright: `Display Id '0' is
        // not valid` — written to a zero-byte file, which is why the first
        // version reported "could not capture the default display". No `-d` means
        // the internal display, which is what we want.
        val p = ProcessBuilder("sh", "-c", "screencap -p > ${file.absolutePath}")
            .redirectErrorStream(true)
            .start()
        p.waitFor()
        if (!file.isFile) return@runCatching null
        val bytes = file.readBytes()
        runCatching { file.delete() }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    /**
     * `Display: mDisplayId=N` and `mCurrentFocus=…` for every display, on one line.
     *
     * Read from `dumpsys window displays` because that is the platform's own
     * account of where a window ended up. If our display has no focus line of its
     * own, the app was not put there — which is the difference between "the
     * display works" and "the display exists and the launch was redirected".
     */
    private fun displayFocusSummary(): String = runCatching {
        val p = ProcessBuilder(
            "sh", "-c",
            "dumpsys window displays | grep -E 'Display: mDisplayId|mCurrentFocus'",
        ).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor()
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" | ")
            .take(400)
    }.getOrElse { "dumpsys failed: ${it.message}" }

    /** Mean luma, 0–255: a black rectangle is the signature of "nothing rendered here". */
    private fun meanLuma(bitmap: Bitmap): Double {
        val t = thumb(bitmap) ?: return -1.0
        return t.sum().toDouble() / t.size
    }

    /**
     * Mean absolute luma difference of two 64×64 thumbnails — the same measure,
     * the same threshold, as `VirtualScreens`.
     *
     * Not byte equality: our own ball animates and the clock ticks, so two
     * captures of the *same* screen are never identical files, while two
     * captures of *different* screens differ far more.
     */
    private fun meanDelta(a: Bitmap, b: Bitmap): Double {
        val ta = thumb(a) ?: return -1.0
        val tb = thumb(b) ?: return -1.0
        var sum = 0L
        for (i in ta.indices) sum += kotlin.math.abs(ta[i] - tb[i])
        return sum.toDouble() / ta.size
    }

    private fun thumb(bitmap: Bitmap): IntArray? = runCatching {
        val small = Bitmap.createScaledBitmap(bitmap, 64, 64, true)
        val out = IntArray(64 * 64)
        val row = IntArray(64)
        for (y in 0 until 64) {
            small.getPixels(row, 0, 64, 0, y, 64, 1)
            for (x in 0 until 64) {
                val c = row[x]
                out[y * 64 + x] =
                    ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
            }
        }
        small.recycle()
        out
    }.getOrNull()

    /**
     * `Image` → bitmap, with the row-padding handling copied from
     * `screen/ScreenCapture.toBitmap` on purpose: the producer pads rows, and
     * copying straight into `w x h` shears the picture diagonally — which still
     * looks like a screenshot until someone reads text off it.
     */
    private fun toBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val w = image.width
        val h = image.height
        val rowPadding = rowStride - pixelStride * w
        val padded = Bitmap.createBitmap(
            w + rowPadding / pixelStride,
            h,
            Bitmap.Config.ARGB_8888,
        )
        padded.copyPixelsFromBuffer(plane.buffer)
        if (padded.width == w) return padded
        val exact = Bitmap.createBitmap(padded, 0, 0, w, h)
        padded.recycle()
        return exact
    }

    /**
     * One line, on **two channels**: logcat (`AndeeProbe`) and fd 1.
     *
     * Logcat first, and not as a courtesy: the pipe back to the app is the thing
     * that was silent on the device, and `adb logcat -s AndeeProbe` reads this
     * process's own words even when nothing arrives on stdout. A diagnostic with
     * one output channel cannot tell "it never got there" from "the channel is
     * broken", which is exactly the wall this version is built to get past.
     *
     * The stream is opened once and kept open. The first version opened one per
     * line and closed it — which closes fd 1 for the whole process, so line two
     * onwards would have vanished even on a healthy pipe.
     */
    private fun say(line: String) {
        runCatching { android.util.Log.i(LOG_TAG, line.take(300)) }
        runCatching {
            val stream = sink.get() ?: FileOutputStream(FileDescriptor.out).also { sink.set(it) }
            synchronized(stream) {
                stream.write((line + "\n").toByteArray(Charsets.UTF_8))
                stream.flush()
            }
        }
    }
}
