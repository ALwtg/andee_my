package net.kuafuai.andee.desktop

import net.kuafuai.andee.config.Gear

/**
 * The hands the brain dispatches to, and the body that owns them.
 *
 * Everything above this file — the window, the cards, the scrollback, the
 * notebook, the scenes, the ball, the peer link — is the same Kotlin on every
 * operating system. What differs is the *body*: how a tree is read, how a click
 * is injected, how the screen is captured, which apps exist, whether a
 * "background screen" can exist at all.
 *
 * **Why interfaces instead of a `when (os)` inside each hand.** A per-method
 * branch would let one platform rot quietly: a method added to the Mac body
 * would simply not exist on Windows, and the symptom would be a model calling a
 * tool that answers "unknown method" — the failure this project likes least,
 * because it looks like a missing feature rather than a missing implementation.
 * With an interface the other platform fails to *compile*, which is the only
 * version of that bug worth having.
 *
 * The Mac implementations keep their behaviour exactly: this seam sits in front
 * of them, it does not rewrite them.
 */
interface ScreenHand : DesktopBrainHost.ToolProvider

interface DeviceHand : DesktopBrainHost.ToolProvider

interface GearHand : DesktopBrainHost.ToolProvider {

    /** The user's shelf: what is installed, and what they said each thing is for. */
    val gear: Gear

    /** Rescan the installed set now — the settings card has a button for it. */
    fun scanNow()

    /** One line per known app for the prompt, most used first. */
    fun indexForPrompt(): String
}

/**
 * Everything that is **not** shared between platforms.
 *
 * [hideOwnWindows] and [yieldScreen] are the two window-level contracts the
 * screen hand needs and cannot implement itself: before a gesture or a capture,
 * Andee's own windows have to get out of the picture, and before a screen tool
 * runs the chat window has to step back. On the Mac that is a CoreGraphics alpha
 * change; on Windows it will be a Z-order move. Both live behind these two
 * calls so the screen hand does not have to know which one it is talking to.
 */
interface DesktopBody {

    /** Human-facing platform name — the prompt and the checklist both say it. */
    val name: String

    val screen: ScreenHand
    val gear: GearHand
    val device: DeviceHand

    /** Get our own windows out of the picture (`true`) or put them back. */
    fun hideOwnWindows(hide: Boolean)

    /** Get the chat window off the screen before a screen tool runs. */
    fun yieldScreen()

    /**
     * Release whatever the body holds — displays, hooks, threads, native handles.
     *
     * Called on exit, and defaulted because a body that owns nothing (the Mac's
     * does not) should not have to say so.
     */
    fun shutdown() {}
}

/**
 * The one place that reads `os.name`.
 *
 * Lazy, so that loading [Assistant] does not build a body — the `probe`,
 * `ballshot` and `uishot` tasks run without a window and would otherwise pay for
 * one, and on a machine whose body is not finished yet they would fail before
 * printing anything.
 *
 * Windows is matched explicitly; everything else keeps the Mac body, which is
 * what this program has always been. A Linux build would therefore fail at the
 * Mac bridge rather than here — honest, and one line from being a third branch
 * when somebody wants one.
 */
object Body {

    private val impl: DesktopBody by lazy { create() }

    private fun create(): DesktopBody {
        val os = System.getProperty("os.name").orEmpty()
        return if (os.startsWith("Windows", ignoreCase = true)) {
            net.kuafuai.andee.desktop.win.WinBody()
        } else {
            net.kuafuai.andee.desktop.mac.MacBody()
        }
    }

    /** True on the platform whose native bridge this build was given. */
    val isMac: Boolean
        get() = System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)

    /** True where the tray, the Win32 body and the UI Automation bridge exist. */
    val isWindows: Boolean
        get() = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    val name: String get() = impl.name
    val screen: ScreenHand get() = impl.screen
    val gear: GearHand get() = impl.gear
    val device: DeviceHand get() = impl.device

    fun hideOwnWindows(hide: Boolean) = impl.hideOwnWindows(hide)
    fun yieldScreen() = impl.yieldScreen()
    fun shutdown() = impl.shutdown()
}
