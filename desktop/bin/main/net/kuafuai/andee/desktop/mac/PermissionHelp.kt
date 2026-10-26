package net.kuafuai.andee.desktop.mac

import java.io.File

/**
 * What to tell the user when a macOS privacy permission is missing.
 *
 * The usual cause is not that the user never turned it on. macOS remembers the
 * grant against the exact build of an ad-hoc-signed app, so every rebuilt
 * Andee.app is a stranger to it: the switch in System Settings still shows *on*
 * (for the old build) while this one is refused. Telling the user to "turn it on"
 * sends them to a switch that already is — so the text says to remove the entry
 * and add this copy back, and names which copy that is.
 */
object PermissionHelp {

    /** The running Andee.app, for the user to pick in the "+" dialog; null under `gradle run`. */
    fun appPath(): String? = System.getProperty("jpackage.app-path")
        ?.let { File(it).parentFile?.parentFile?.parentFile }   // …/Andee.app/Contents/MacOS/Andee
        ?.takeIf { it.name.endsWith(".app") }?.path

    private fun steps(pane: String): String {
        val app = appPath()
        val which = if (app != null) "this copy of Andee at $app" else "the Andee.app being run"
        return "In System Settings → Privacy & Security → $pane: if Andee is not listed, click + and add $which, then switch it on. " +
            "If Andee is already listed and switched on, that entry belongs to an earlier build (macOS ties the grant to the exact " +
            "build, and every update counts as a new app): select Andee, click − to remove it, then click + and add $which, switch " +
            "it on, and quit and reopen Andee (⌘Q). Do not just tell the user to turn it on — if they say it is already on, this " +
            "remove-and-re-add is the fix. You can open the pane for them with run_command: open \"x-apple.systempreferences:" +
            "com.apple.preference.security?${anchor(pane)}\"."
    }

    private fun anchor(pane: String) = if (pane == "Accessibility") "Privacy_Accessibility" else "Privacy_ScreenCapture"

    val ACCESSIBILITY: String get() =
        "Andee does not have Accessibility permission on this Mac, so it cannot read or operate the screen. " + steps("Accessibility")

    val SCREEN_RECORDING: String get() =
        "Andee does not have Screen Recording permission on this Mac, so it cannot see the screen. " +
            steps("Screen & System Audio Recording")
}
