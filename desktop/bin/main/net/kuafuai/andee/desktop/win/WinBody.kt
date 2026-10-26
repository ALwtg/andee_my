package net.kuafuai.andee.desktop.win

import net.kuafuai.andee.config.Gear
import net.kuafuai.andee.desktop.DesktopBody
import net.kuafuai.andee.desktop.DesktopNotebook
import net.kuafuai.andee.desktop.DeviceHand
import net.kuafuai.andee.desktop.GearHand
import net.kuafuai.andee.desktop.ScreenHand
import org.json.JSONObject

/**
 * The Windows body — **a skeleton, and deliberately a truthful one.**
 *
 * The port lands in pieces: the seam first (this file), then the Win32 layer, the
 * UI Automation tree, `screen.*`, the background screen, voice and packaging.
 * Between those pieces the app has to keep starting, because the alternative is a
 * build nobody can run until the last commit of a multi-round port.
 *
 * So the hands here **claim nothing**: `handles` is false for every method, which
 * is what keeps a tool off the model's list. The failure this avoids is the one
 * this project has paid for before — a tool that appears in the list and answers
 * `unknown method`, which reads as "the feature exists and is broken" rather than
 * "it is not built yet".
 *
 * Anything that does reach [PendingHand.dispatch] (a stale prompt, a hub call
 * from a paired device) gets one sentence naming the missing piece.
 */
class WinBody : DesktopBody {

    override val name: String = "Windows"

    override val screen: ScreenHand = WinScreen(
        // Through Assistant, exactly like the Mac body: `Main` installs the real
        // implementation once the windows exist, and before that they are no-ops —
        // which is correct, because there is nothing yet to step aside.
        hideOwnWindows = { hide -> net.kuafuai.andee.desktop.Assistant.hideWindows(hide) },
        yieldScreen = { net.kuafuai.andee.desktop.Assistant.yieldScreen() },
    )

    override val gear: GearHand = WinGear()
    override val device: DeviceHand = WinDevice()

    // Fading an AWT window is `Window.opacity`, and Windows honours it on
    // undecorated and decorated frames alike — but the window plumbing lands with
    // the tray work, so this stays a no-op rather than a half-implementation that
    // hides the chat window and cannot bring it back.
    override fun hideOwnWindows(hide: Boolean) = Unit

    override fun yieldScreen() = Unit

    override fun shutdown() = Unit

    /**
     * A hand that implements the interfaces and answers honestly.
     *
     * It carries a real [Gear] because the settings card reads `Assistant.gear.gear`
     * to paint the shelf — an empty shelf is the correct picture of "nothing has
     * been scanned yet", and it is cheaper than teaching the card about null.
     */
    private class PendingHand(private val what: String) : ScreenHand, DeviceHand, GearHand {

        override val gear: Gear by lazy { Gear(DesktopNotebook.book) }

        override fun handles(method: String) = false

        override fun dispatch(method: String, p: JSONObject, silent: Boolean, untrusted: Boolean): Any {
            throw IllegalStateException(
                "$what is not built in this build yet, so '$method' cannot run. Nothing was done — " +
                    "say so rather than retrying.",
            )
        }

        override fun scanNow() = Unit

        override fun indexForPrompt(): String = ""
    }
}
