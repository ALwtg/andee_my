package net.kuafuai.andee.device

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Is the shell channel up — the one capability behind 「优先虚拟屏操作」 that
 * this app cannot grant itself.
 *
 * **Why this is its own object.** A background display needs three things a
 * normal app does not have: permission to create a display that third-party
 * activities can be launched onto, permission to inject touches into it, and
 * permission to capture its frames. All three are `signature`-level and all
 * three are the shell's. Shizuku is the ordinary way to borrow them, and
 * "borrowed" is the right word: it holds until the user reboots, so the state
 * this object reports is *per boot*, not per install. That is exactly why the
 * status is a thing the device checks and shows rather than something the
 * settings card assumes once.
 *
 * **Four answers, and the difference between them is the user's next step**, so
 * they are not collapsed into a boolean: not installed (go install), installed
 * but the server is not running (start it — the app cannot do that for you),
 * running but this app is not authorised (a dialog inside Shizuku), ready.
 *
 * **Nothing here runs on the main thread.** [probe] crosses a binder into
 * another process, and a Shizuku that is installed but wedged would block the
 * caller for as long as that binder takes to fail. So the settings card and the
 * checklist read [cached] — which is free and always answers, possibly with
 * [State.UNKNOWN] — and call [refresh] to fill it in behind the paint. Same
 * shape as `SelfCheck.local()` / `SelfCheck.run()`, and for the same reason.
 */
object ShizukuStatus {

    /** Shizuku's own package. Its absence is the first, cheapest answer. */
    const val PACKAGE = "moe.shizuku.privileged.api"

    /** Where a user with no Shizuku goes. */
    const val INSTALL_URL = "https://shizuku.rikka.app/download/"

    /**
     * How a user without wireless debugging starts the server after a reboot.
     *
     * Shown to be copied, not run: it is an `adb` command, and the app cannot
     * execute it without the very channel the command would provide.
     */
    const val START_COMMAND =
        "adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh"

    /** Ours, not Shizuku's: only the callback needs it. */
    const val REQUEST_CODE = 0x5A12

    enum class State {
        /** Never probed yet — the cheap answer the first paint gets. */
        UNKNOWN,

        /** No Shizuku on this device. */
        NOT_INSTALLED,

        /** Installed, but the server does not answer. Normal right after a reboot. */
        NOT_RUNNING,

        /** Server alive, this app not authorised to use it. */
        NOT_AUTHORIZED,

        /** Alive and authorised: a background display can be created. */
        READY,
    }

    data class Snapshot(
        val state: State = State.UNKNOWN,
        /**
         * Whether USB/无线调试 is on. Not a substitute for Shizuku — it is the
         * difference between "start it again" and "you will have to connect a
         * cable first", which is the sentence the user actually needs.
         */
        val adbEnabled: Boolean = false,
        val at: Long = 0L,
    ) {
        val ready: Boolean get() = state == State.READY
    }

    @Volatile
    private var last = Snapshot()

    /** One probe at a time; a second [refresh] while one is in flight is dropped. */
    private val inFlight = AtomicBoolean(false)

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "shizuku-status").apply { isDaemon = true }
    }

    /** The last answer, free to call from anywhere — including the main thread. */
    fun cached(): Snapshot = last

    /**
     * Probe behind the caller's back, then hand the fresh answer to [then].
     *
     * [then] runs **on the worker thread**: the caller owns the switch back to
     * the UI thread (`Handler(Looper.getMainLooper()).post { … }`), because a
     * card that was closed while the binder was answering must not be touched.
     * A refresh already in flight swallows the new request rather than queueing
     * a second binder round-trip — every caller here is a repaint, and one
     * answer is enough for all of them.
     */
    fun refresh(context: Context, then: ((Snapshot) -> Unit)? = null) {
        val app = context.applicationContext
        if (!inFlight.compareAndSet(false, true)) return
        worker.execute {
            val s = runCatching { probe(app) }.getOrDefault(Snapshot())
            last = s
            inFlight.set(false)
            runCatching { then?.invoke(s) }
        }
    }

    /**
     * The real answer. **Blocking — worker thread only.**
     *
     * `getPackageInfo` first, because "not installed" is the one case that must
     * never be reported as "not running": the two lead to different sentences
     * and different buttons, and an uninstalled Shizuku cannot be started.
     */
    fun probe(context: Context): Snapshot {
        val adb = runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        }.getOrDefault(false)
        val installed = runCatching {
            context.packageManager.getPackageInfo(PACKAGE, 0)
            true
        }.getOrDefault(false)
        val now = System.currentTimeMillis()
        if (!installed) return Snapshot(State.NOT_INSTALLED, adb, now)

        val state = runCatching {
            when {
                // A dead binder is what a reboot looks like from here, and it
                // is not an error: Shizuku's server has to be started again.
                !Shizuku.pingBinder() -> State.NOT_RUNNING
                // Pre-11 Shizuku has no per-app permission model; if the binder
                // answers, the channel is open.
                Shizuku.isPreV11() -> State.READY
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> State.READY
                else -> State.NOT_AUTHORIZED
            }
        }.getOrDefault(State.NOT_RUNNING)
        return Snapshot(state, adb, now)
    }

    /**
     * Ask Shizuku for permission — its own dialog, our request code.
     *
     * Returns false rather than throwing when the binder is gone (the user can
     * tap this on a card that was painted before a reboot) and when the request
     * cannot be raised. The caller says which of those happened; a silent no-op
     * on a permission button is the failure this project keeps writing notes
     * about.
     */
    fun requestPermission(): Boolean = runCatching {
        Shizuku.requestPermission(REQUEST_CODE)
        true
    }.getOrDefault(false)
}
