package net.kuafuai.andee.desktop

/**
 * The platform prompt, per platform.
 *
 * [MacPrompt] was written for one machine and says so in its first sentence — "you
 * are a Mac app". On Windows that sentence is wrong in the one place a wrong
 * sentence is expensive: the first paragraph the model reads, which colours every
 * decision after it. A model that believes it is on a Mac reaches for the Dock,
 * the menu bar and AppleScript, and explains its failures in those terms.
 *
 * **Why the machine-specific paragraphs moved out instead of being patched.**
 * The first version of this file ran `base.replace("user's Mac", "user's Windows
 * PC")` over the text. That cannot rewrite a sentence about the menu bar, and it
 * silently leaves every sentence it does not match. Now the varying paragraphs
 * come from [PlatformNotes] — one implementation per platform, chosen here — and
 * what is appended below is only what neither paragraph covers.
 */
object DesktopPrompt {

    fun text(caps: MacPrompt.Capabilities): String {
        val notes = if (Body.isMac) PlatformNotes.mac else PlatformNotes.windows
        val base = MacPrompt.text(caps, notes)
        return if (Body.isMac) base else base + "\n\n" + BACKGROUND
    }

    /**
     * What only Windows has to explain: the background screen, and where the app
     * lives when no window is open.
     *
     * Deliberately short. The screen paragraph already says what UI Automation can
     * and cannot do; repeating it here would make the prompt longer without making
     * anything clearer.
     */
    private val BACKGROUND = """
### 0b. The second screen on this machine

- **There is no second display here.** `open_background_screen` binds one *window* of the app you
  name, and every background tool works on that window: its tree, its picture, its 0-1000
  coordinates. Do not look for a display id, and do not expect the user's screen to be free of it —
  it is a normal window that they can also see.
- It is shown as a small always-on-top ball carrying that app's icon. It is how the user knows
  something is being driven while they are elsewhere, and clicking it opens the supervision window
  where they can watch, tap, type and close. The same state is in the notification-area menu.
- `launch_app` starts a Start Menu app by name; `open_url` hands a URL to whatever the user has set as
  their browser. Neither takes a bundle identifier, and there is no scripting bridge on this platform.
""".trimIndent()
}
