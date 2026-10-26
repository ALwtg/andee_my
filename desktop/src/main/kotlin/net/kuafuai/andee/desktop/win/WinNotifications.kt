package net.kuafuai.andee.desktop.win

import org.json.JSONArray
import org.json.JSONObject

/**
 * The notification centre, which Windows does not let this process read.
 *
 * There is no public API for it: `UserNotificationListener` is a WinRT contract
 * that needs a restricted capability granted at package level, and the shell's own
 * database (`wpndatabase.db`) is a SQLite file inside the user's `AppData` that
 * Microsoft explicitly does not support reading. Both "solutions" are the kind
 * that work until an update, and they are not worth a feature the user would then
 * trust.
 *
 * So this returns `available: false` with the reason, and **says what can be done
 * instead**: the tray icon already tells the user when Andee itself needs them,
 * and `screen.ui_tree` can read a notification *popup* while it is on screen like
 * any other window. That is less than the Mac, and saying so is the point.
 */
object WinNotifications {

    fun snapshot(sinceMs: Long, limit: Int): JSONObject = JSONObject()
        .put("available", false)
        .put(
            "why",
            "Windows gives this process no way to read the notification centre: the supported API " +
                "(UserNotificationListener) needs a package capability this app cannot have, and the " +
                "shell's database is undocumented and unsupported.",
        )
        .put(
            "instead",
            "If a notification is on screen right now, it is a window like any other — screen.ui_tree " +
                "can read it. And this app's own notices are the tray icon.",
        )
        .put("notifications", JSONArray())
        .put("count", 0)

    /** Whether this platform can answer the question at all — asked before listing the tool. */
    val readable: Boolean = false
}
