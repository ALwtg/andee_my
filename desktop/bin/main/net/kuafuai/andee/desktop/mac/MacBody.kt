package net.kuafuai.andee.desktop.mac

import net.kuafuai.andee.desktop.DesktopBody
import net.kuafuai.andee.desktop.DeviceHand
import net.kuafuai.andee.desktop.GearHand
import net.kuafuai.andee.desktop.ScreenHand

/**
 * The macOS body: the same three hands this program has always had, reached
 * through [DesktopBody] so the Windows build can have its own without copying
 * them.
 *
 * Two behaviours are preserved on purpose, and both are the kind that would not
 * fail loudly if they were lost in a refactor:
 *
 *  * the screen hand is built with the **window hooks**, and it calls them
 *    through [net.kuafuai.andee.desktop.Assistant] rather than calling
 *    `Bridge` directly — that indirection is what lets `Main` install the real
 *    implementations once the windows exist (before that they are no-ops, which
 *    is correct: there are no windows to move yet);
 *  * [MacVault.entries] is called at construction, because that is what installs
 *    the vault as a secret source. Skipping it would not raise anything; it would
 *    quietly stop redacting stored passwords out of logs and prompts.
 */
class MacBody : DesktopBody {

    override val name: String = "Mac"

    override val screen: ScreenHand = MacScreen(
        hideOwnWindows = { hide -> net.kuafuai.andee.desktop.Assistant.hideWindows(hide) },
        yieldScreen = { net.kuafuai.andee.desktop.Assistant.yieldScreen() },
    )

    override val gear: GearHand = MacGear()

    override val device: DeviceHand = MacDevice().also { MacVault.entries() }

    override fun hideOwnWindows(hide: Boolean) {
        // Straight to the bridge, **not** through `Assistant.hideWindows`: `Main`
        // points that hook at this very method, and routing back would be a loop.
        runCatching { Bridge.setOwnAlpha(if (hide) 0.0 else 1.0) }
    }

    override fun yieldScreen() = net.kuafuai.andee.desktop.Assistant.yieldScreen()

    // Nothing native is held open by the Mac body: the bridge is a dylib the
    // process unloads by exiting, and the terminal hand owns its own children.
    override fun shutdown() = Unit
}
