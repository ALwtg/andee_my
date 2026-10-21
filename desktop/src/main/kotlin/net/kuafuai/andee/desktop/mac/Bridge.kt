package net.kuafuai.andee.desktop.mac

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import net.kuafuai.andee.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The native bridge (desktop/native/AndeeMac.swift), loaded through JNA.
 * Strings that come back are malloc'd JSON; [Bridge] frees them.
 */
object Bridge {

    private interface Lib : Library {
        fun andee_free(p: Pointer?)
        fun andee_ax_trusted(prompt: Int): Int
        fun andee_screen_capture_allowed(request: Int): Int
        fun andee_main_display(): Pointer
        fun andee_frontmost(): Pointer
        fun andee_running_apps(): Pointer
        fun andee_ax_tree(maxNodes: Int): Pointer
        fun andee_ax_press_at(x: Double, y: Double): Int
        fun andee_ax_set_focused_value(text: String): Int
        fun andee_ax_focused_value(): Pointer
        fun andee_click(x: Double, y: Double, button: Int, count: Int, holdMs: Int)
        fun andee_drag(x1: Double, y1: Double, x2: Double, y2: Double, ms: Int)
        fun andee_scroll(x: Double, y: Double, dx: Int, dy: Int)
        fun andee_type_text(text: String)
        fun andee_key(keyCode: Int, flags: Int)
        fun andee_screenshot(path: String, maxSide: Int): Int
        fun andee_open(target: String): Int
    }

    private val lib: Lib? by lazy {
        val f = locate()
        if (f == null) {
            Log.e("Bridge", "libandeemac.dylib not found; screen tools are unavailable")
            null
        } else {
            runCatching { Native.load(f.path, Lib::class.java) }
                .onFailure { Log.e("Bridge", "could not load ${f.path}", it) }.getOrNull()
        }
    }

    val available: Boolean get() = lib != null

    private fun locate(): File? {
        System.getProperty("compose.application.resources.dir")?.let { File(it, "libandeemac.dylib") }
            ?.takeIf { it.exists() }?.let { return it }
        val cached = File(System.getProperty("user.home"), "Library/Andee-shell/libandeemac.dylib")
        val res = Bridge::class.java.getResourceAsStream("/libandeemac.dylib") ?: return cached.takeIf { it.exists() }
        res.use { input -> cached.parentFile.mkdirs(); cached.outputStream().use { input.copyTo(it) } }
        return cached
    }

    private fun need(): Lib = lib ?: throw IllegalStateException(
        "The Mac screen bridge is not loaded on this install, so screen tools cannot run. Say so to the user.",
    )

    private fun str(p: Pointer): String = try { p.getString(0, "UTF-8") } finally { need().andee_free(p) }

    fun axTrusted(prompt: Boolean = false) = lib?.andee_ax_trusted(if (prompt) 1 else 0) == 1
    fun screenCaptureAllowed(request: Boolean = false) = lib?.andee_screen_capture_allowed(if (request) 1 else 0) == 1
    fun display(): JSONObject = JSONObject(str(need().andee_main_display()))
    fun frontmost(): JSONObject = JSONObject(str(need().andee_frontmost()))
    fun runningApps(): JSONArray = JSONArray(str(need().andee_running_apps()))
    fun axTree(maxNodes: Int): JSONObject = JSONObject(str(need().andee_ax_tree(maxNodes)))
    fun axPressAt(x: Double, y: Double) = need().andee_ax_press_at(x, y) == 1
    fun setFocusedValue(text: String) = need().andee_ax_set_focused_value(text) == 1
    fun focusedValue(): JSONObject = JSONObject(str(need().andee_ax_focused_value()))
    fun click(x: Double, y: Double, right: Boolean = false, count: Int = 1, holdMs: Int = 40) =
        need().andee_click(x, y, if (right) 1 else 0, count, holdMs)
    fun drag(x1: Double, y1: Double, x2: Double, y2: Double, ms: Int) = need().andee_drag(x1, y1, x2, y2, ms)
    fun scroll(x: Double, y: Double, dx: Int, dy: Int) = need().andee_scroll(x, y, dx, dy)
    fun typeText(text: String) = need().andee_type_text(text)
    fun key(code: Int, flags: Int = 0) = need().andee_key(code, flags)
    fun screenshot(path: String, maxSide: Int) = need().andee_screenshot(path, maxSide) == 1
    fun open(target: String) = need().andee_open(target) == 1

    /** macOS virtual key codes the tools use. */
    object Keys {
        const val RETURN = 36
        const val TAB = 48
        const val ESCAPE = 53
        const val DELETE = 51
        const val LEFT_BRACKET = 33
        const val A = 0
        const val V = 9
        const val SPACE = 49
        const val F3 = 160
        const val UP = 126
        const val DOWN = 125
        const val CMD = 1
        const val SHIFT = 2
        const val OPTION = 4
        const val CONTROL = 8
    }
}
