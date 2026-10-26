package net.kuafuai.andee.desktop

/**
 * The parts of the platform prompt that are true of one machine and false of the
 * other.
 *
 * [MacPrompt] is 95% platform truth — the notebook, the scenes, the honest-failure
 * rules — and 5% machine. That 5% used to be written into the text, so a Windows
 * build told the model it had `⌘[`, a menu bar and `mdfind`, and `DesktopPrompt`
 * tried to fix it with `.replace("user's Mac", "user's Windows PC")` — which
 * cannot rewrite a sentence about a Dock.
 *
 * So the varying paragraphs live here, one implementation per platform, and
 * [MacPrompt] asks for them. **The Mac strings are byte-for-byte what they were**,
 * which is why the Mac build cannot regress from this refactor: only their
 * location changed.
 */
interface PlatformNotes {

    /** How the machine is named mid-sentence: "Mac", "Windows PC". */
    val name: String

    /** `caps.screen = true`: what "see and operate the screen" means here. */
    fun screenParagraph(): String

    /** `caps.shell`: whether commands can run, and under what rules. */
    fun shellParagraph(hasShell: Boolean): String

    /** Whose notebook this is. */
    fun notebookParagraph(): String

    companion object {

        val mac: PlatformNotes = object : PlatformNotes {
            override val name = "Mac"

            override fun screenParagraph() =
                "- You can see and operate the Mac's screen with the same tools and the same grid and 0-1000 " +
                    "coordinates. On a Mac: a tap is a mouse click; long_press is a right-click (the context " +
                    "menu); swipe scrolls what is under its start point by that distance; system_action back is " +
                    "⌘[ (Back in browsers and Finder — many apps have none, prefer a visible button or Esc), home " +
                    "shows the desktop. launch_app takes a bundle id (com.apple.Safari) or an app name. Prefer " +
                    "keyboard shortcuts and the menu bar (it is in the element list) over hunting for small icons. " +
                    "Anything a command can do (open -a, open URL, mdfind) is usually faster than clicking.\n"

            override fun shellParagraph(hasShell: Boolean) = if (hasShell) {
                "- You can run command-line tools on this Mac with run_command, under the rules its description " +
                    "gives. This replaces §1's ban on shell and terminal: on this device the terminal is one of " +
                    "your hands.\n"
            } else {
                "- You cannot run commands on this Mac yet.\n"
            }

            override fun notebookParagraph() =
                "- You do have your notebook, todos and scenes (§2–§5, §11) — they work the same here, and they " +
                    "are this Mac's own: what you remembered on the phone is not here, and the reverse.\n"
        }

        /**
         * Windows. Every sentence below is something a tool here actually does, and
         * the ones that would be wrong are named as limits rather than left out —
         * a model that does not know about UIPI explains its own failure as a bug.
         */
        val windows: PlatformNotes = object : PlatformNotes {
            override val name = "Windows PC"

            override fun screenParagraph() =
                "- You can see and operate this PC's screen with the same tools and the same 0-1000 coordinates. " +
                    "A tap is a real click (the pointer moves, the window comes forward); long_press is a " +
                    "right-click; swipe is a held drag, which is how a desktop scrolls. `system_action` is small " +
                    "here: Windows has no system \"back\", so back sends the Left key and says so. launch_app takes " +
                    "a Start Menu name (\"微信\", \"Visual Studio Code\") or a path, not a bundle id.\n" +
                    "- Elements come from UI Automation. A control with an invoke/value/select pattern can be driven " +
                    "**without moving the pointer or stealing focus** — the reply says `uia_invoke` when that " +
                    "happened, `post_message` for a click that was delivered without focus change, and `click` when " +
                    "the pointer really moved. Apps that draw their own UI (Electron, games) hand over almost no " +
                    "tree: then read the picture and its 8x12 grid.\n" +
                    "- **Two limits no permission fixes:** a window running as Administrator cannot be touched by " +
                    "this process (UIPI), and a GPU-composited window (browser, video, game) may capture as a " +
                    "completely blank picture. Both are reported, never faked.\n"

            override fun shellParagraph(hasShell: Boolean) = if (hasShell) {
                "- You can run command lines here with run_command, through PowerShell with no profile and no " +
                    "terminal. This replaces §1's ban on shell and terminal. There is **no kernel sandbox on " +
                    "Windows**: a command can write anywhere the user can, and a delete really deletes — so " +
                    "destructive commands deserve a second look before you propose them. `sandbox` puts a command " +
                    "inside a conda environment when a task wants its own toolchain.\n"
            } else {
                "- You cannot run commands on this PC.\n"
            }

            override fun notebookParagraph() =
                "- You do have your notebook, todos and scenes (§2–§5, §11) — they work the same here, and they " +
                    "are this PC's own: what you remembered on the phone is not here, and the reverse.\n"
        }
    }
}
