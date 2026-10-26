package net.kuafuai.andee.desktop.win

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import net.kuafuai.andee.util.Log
import java.awt.image.BufferedImage

/**
 * The Windows body's system calls: windows, pixels, clicks, keys, icons, clipboard.
 *
 * **JNA rather than JNI.** The Mac body needed a Swift dylib because its
 * accessibility tree, screen capture and input all live in Objective-C
 * frameworks. Nothing here does: `user32`/`gdi32`/`shell32` are flat C exports,
 * so this build stays a pure Gradle/JVM build — no compiler beyond the JDK, which
 * is also what lets it be built on the machine that runs it.
 *
 * Everything is **best-effort and honest about it**: a capture that fails returns
 * null, one that comes back blank is [looksBlank]-ed by the caller, and
 * [available] is a fact the settings page shows rather than an assumption.
 *
 * Coordinates are **physical pixels of the virtual desktop**, after
 * [enableDpiAwareness]. Getting that wrong does not produce an error — it
 * silently scales every tap by the monitor's factor, which is the kind of bug
 * that reads as "the model aims badly".
 */
object Win32 {

    private const val TAG = "Win32"

    private const val SM_CXSCREEN = 0
    private const val SM_CYSCREEN = 1
    private const val SM_XVIRTUALSCREEN = 76
    private const val SM_YVIRTUALSCREEN = 77
    private const val SM_CXVIRTUALSCREEN = 78
    private const val SM_CYVIRTUALSCREEN = 79

    private const val PW_RENDERFULLCONTENT = 0x00000002
    private const val GWL_EXSTYLE = -20
    private const val WS_EX_TOOLWINDOW = 0x00000080
    private const val SRCCOPY = 0x00CC0020
    private const val DIB_RGB_COLORS = 0

    private const val MOUSEEVENTF_LEFTDOWN = 0x0002
    private const val MOUSEEVENTF_LEFTUP = 0x0004
    private const val KEYEVENTF_KEYUP = 0x0002
    private const val KEYEVENTF_UNICODE = 0x0004
    private const val INPUT_KEYBOARD = 1

    private const val WM_MOUSEWHEEL = 0x020A
    private const val WM_LBUTTONDOWN = 0x0201
    private const val WM_LBUTTONUP = 0x0202
    private const val WM_CHAR = 0x0102
    private const val VK_RETURN = 0x0D

    private const val DI_NORMAL = 0x0003

    private const val CF_UNICODETEXT = 13
    private const val GMEM_MOVEABLE = 0x0002

    private const val SW_RESTORE = 9

    // ---- structures -------------------------------------------------------

    @Structure.FieldOrder("left", "top", "right", "bottom")
    class RECT : Structure() {
        @JvmField var left = 0
        @JvmField var top = 0
        @JvmField var right = 0
        @JvmField var bottom = 0
    }

    @Structure.FieldOrder("x", "y")
    class POINT : Structure() {
        @JvmField var x = 0
        @JvmField var y = 0
    }

    @Structure.FieldOrder(
        "biSize", "biWidth", "biHeight", "biPlanes", "biBitCount", "biCompression",
        "biSizeImage", "biXPelsPerMeter", "biYPelsPerMeter", "biClrUsed", "biClrImportant",
    )
    class BITMAPINFOHEADER : Structure() {
        @JvmField var biSize = 0
        @JvmField var biWidth = 0
        @JvmField var biHeight = 0
        @JvmField var biPlanes = 0
        @JvmField var biBitCount = 0
        @JvmField var biCompression = 0
        @JvmField var biSizeImage = 0
        @JvmField var biXPelsPerMeter = 0
        @JvmField var biYPelsPerMeter = 0
        @JvmField var biClrUsed = 0
        @JvmField var biClrImportant = 0
    }

    @Structure.FieldOrder("bmiHeader", "bmiColors")
    class BITMAPINFO : Structure() {
        @JvmField var bmiHeader = BITMAPINFOHEADER()
        @JvmField var bmiColors = IntArray(3)
    }

    private interface User32 : com.sun.jna.Library {
        fun GetSystemMetrics(index: Int): Int
        fun SetProcessDpiAwarenessContext(ctx: Pointer?): Int
        fun GetThreadDpiAwarenessContext(): Pointer?
        fun AreDpiAwarenessContextsEqual(a: Pointer?, b: Pointer?): Int
        fun GetDC(hwnd: Long): Long
        fun ReleaseDC(hwnd: Long, dc: Long): Int
        fun EnumWindows(cb: WndEnumProc, data: Pointer?): Int
        fun IsWindowVisible(hwnd: Long): Int
        fun IsWindow(hwnd: Long): Int
        fun IsIconic(hwnd: Long): Int
        fun GetWindowTextLengthW(hwnd: Long): Int
        // Pointer, not CharArray: JNA maps a Java `char[]` to an ANSI `char*`, so
        // the wide call filled it with UTF-16 halves that read back as mojibake.
        // Every string buffer in this file is a Memory the caller reads as wide.
        fun GetWindowTextW(hwnd: Long, buf: Pointer, max: Int): Int
        fun GetClassNameW(hwnd: Long, buf: Pointer, max: Int): Int
        fun GetWindowRect(hwnd: Long, rect: RECT): Int
        fun GetClientRect(hwnd: Long, rect: RECT): Int
        fun ClientToScreen(hwnd: Long, point: POINT): Int
        fun GetWindowThreadProcessId(hwnd: Long, pid: IntByReference): Int
        fun GetWindowLongW(hwnd: Long, index: Int): Int
        fun GetForegroundWindow(): Long
        fun SetForegroundWindow(hwnd: Long): Int
        fun ShowWindow(hwnd: Long, cmd: Int): Int
        fun PrintWindow(hwnd: Long, dc: Long, flags: Int): Int
        fun SetCursorPos(x: Int, y: Int): Int
        fun GetCursorPos(point: POINT): Int
        fun mouse_event(flags: Int, dx: Int, dy: Int, data: Int, extra: Long)
        fun SendInput(count: Int, inputs: Pointer, size: Int): Int
        fun PostMessageW(hwnd: Long, msg: Int, wParam: Long, lParam: Long): Int
        fun OpenClipboard(hwnd: Long): Int
        fun CloseClipboard(): Int
        fun EmptyClipboard(): Int
        fun GetClipboardData(format: Int): Long
        fun SetClipboardData(format: Int, data: Long): Long
        fun MoveWindow(hwnd: Long, x: Int, y: Int, w: Int, h: Int, repaint: Int): Int
        // Both of these belong to user32, not gdi32/shell32 — declaring them in
        // the wrong library produces `Error looking up function`, which reads like
        // a missing Windows API rather than a misplaced line.
        fun DrawIconEx(dc: Long, x: Int, y: Int, icon: Pointer, w: Int, h: Int, step: Int, brush: Long, flags: Int): Int
        fun DestroyIcon(icon: Pointer): Int
    }

    private interface Gdi32 : com.sun.jna.Library {
        fun CreateCompatibleDC(dc: Long): Long
        fun CreateCompatibleBitmap(dc: Long, w: Int, h: Int): Long
        fun SelectObject(dc: Long, obj: Long): Long
        fun DeleteObject(obj: Long): Int
        fun DeleteDC(dc: Long): Int
        fun BitBlt(dst: Long, x: Int, y: Int, w: Int, h: Int, src: Long, sx: Int, sy: Int, rop: Int): Int
        fun CreateDIBSection(
            dc: Long,
            info: Pointer,
            usage: Int,
            bits: com.sun.jna.ptr.PointerByReference,
            section: Pointer?,
            offset: Int,
        ): Long
    }

    private interface Kernel32 : com.sun.jna.Library {
        // kernel32, not user32 — the same class of mistake as `DrawIconEx` was.
        fun GetSystemPowerStatus(status: SYSTEM_POWER_STATUS): Int
        fun CreateMutexW(attr: Pointer?, owner: Int, name: WString): Long
        fun GetLastError(): Int
        fun GlobalAlloc(flags: Int, bytes: Long): Long
        fun GlobalLock(handle: Long): Pointer?
        fun GlobalUnlock(handle: Long): Int
    }

    @Structure.FieldOrder(
        "ACLineStatus", "BatteryFlag", "BatteryLifePercent", "Reserved1",
        "BatteryLifeTime", "BatteryFullLifeTime",
    )
    class SYSTEM_POWER_STATUS : Structure() {
        @JvmField var ACLineStatus: Byte = 0
        @JvmField var BatteryFlag: Byte = 0
        @JvmField var BatteryLifePercent: Byte = 0
        @JvmField var Reserved1: Byte = 0
        @JvmField var BatteryLifeTime: Int = 0
        @JvmField var BatteryFullLifeTime: Int = 0
    }

    /** Power the way the shell reports it: `BatteryLifePercent` is 255 for "unknown". */
    data class Power(val onMains: Boolean, val percent: Int, val minutesLeft: Int)

    private interface Shell32 : com.sun.jna.Library {
        // HICON* out-parameters are pointer-sized, so they are PointerByReference.
        // A hand-rolled Structure here reads as zero and every icon silently
        // vanishes — measured, before this line was written correctly.
        fun ExtractIconExW(
            file: WString,
            index: Int,
            large: com.sun.jna.ptr.PointerByReference,
            small: com.sun.jna.ptr.PointerByReference,
            count: Int,
        ): Int
        fun ShellExecuteW(
            hwnd: Long,
            op: WString?,
            file: WString,
            params: WString?,
            dir: WString?,
            show: Int,
        ): Pointer?
    }

    private interface Ole32 : com.sun.jna.Library {
        fun CoInitializeEx(reserved: Pointer?, flags: Int): Int
        fun CoUninitialize()
    }


    // Loaded without `W32APIOptions`: that class lives in `jna-platform`, and this
    // module does not depend on it. The wide APIs are spelled out in the interface
    // instead — `WString` and `CharArray` map to `wchar_t*` on Windows by JNA's own
    // rules — which is one fewer artifact to ship and one fewer indirection between
    // this file and the Win32 prototype it calls.
    private val user: User32 by lazy { Native.load("user32", User32::class.java) }
    private val gdi: Gdi32 by lazy { Native.load("gdi32", Gdi32::class.java) }
    private val kernel: Kernel32 by lazy { Native.load("kernel32", Kernel32::class.java) }
    private val shell: Shell32 by lazy { Native.load("shell32", Shell32::class.java) }
    private val ole: Ole32 by lazy { Native.load("ole32", Ole32::class.java) }

    fun interface WndEnumProc : com.sun.jna.win32.StdCallLibrary.StdCallCallback {
        fun callback(hwnd: Long, data: Pointer?): Int
    }

    val available: Boolean by lazy {
        runCatching { user.GetSystemMetrics(SM_CXSCREEN) > 0 }
            .onFailure { Log.w(TAG, "user32 is not answering; the Windows body cannot work", it) }
            .getOrDefault(false)
    }

    /**
     * Per-Monitor V2, **before any window exists**.
     *
     * Without it Windows virtualises coordinates: `GetWindowRect` reports scaled
     * values, `PrintWindow` renders at the scaled size, and every 0–1000
     * coordinate lands off by the monitor's factor. It is a process-wide property
     * that can be set once, which is why it is not done inside a window.
     */
    @Volatile private var dpiDone = false

    fun enableDpiAwareness(): Boolean {
        if (dpiDone) return true
        dpiDone = true
        // DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2 is the pseudo-handle (-4).
        val pmv2 = Pointer(-4L)
        val set = runCatching { user.SetProcessDpiAwarenessContext(pmv2) != 0 }
            .onFailure { Log.w(TAG, "could not set per-monitor DPI awareness", it) }
            .getOrDefault(false)
        // A JVM or a launcher may already have set it, in which case the call above
        // fails with ACCESS_DENIED and that is **not** a problem — the question is
        // what the process ended up as, not who asked first. Measured: under
        // `gradlew run` the JVM had already made the process DPI-aware, the set
        // failed, and the honest answer was "already correct".
        val already = runCatching {
            val current = user.GetThreadDpiAwarenessContext()
            user.AreDpiAwarenessContextsEqual(current, pmv2) != 0
        }.getOrDefault(false)
        if (!set && !already) {
            Log.w(TAG, "DPI awareness is not per-monitor v2; coordinates may be scaled")
        }
        return set || already
    }

    /** COM, on the thread that is about to use UI Automation. */
    fun initCom(): Boolean = runCatching {
        ole.CoInitializeEx(null, 0) // COINIT_MULTITHREADED; S_FALSE (already in) is fine
        true
    }.getOrDefault(false)

    fun shutdownCom() {
        runCatching { ole.CoUninitialize() }
    }

    // ---- geometry ---------------------------------------------------------

    fun screenBounds(): IntArray =
        intArrayOf(0, 0, user.GetSystemMetrics(SM_CXSCREEN), user.GetSystemMetrics(SM_CYSCREEN))

    /** The whole desktop across every monitor: x, y, w, h. */
    fun virtualBounds(): IntArray = intArrayOf(
        user.GetSystemMetrics(SM_XVIRTUALSCREEN),
        user.GetSystemMetrics(SM_YVIRTUALSCREEN),
        user.GetSystemMetrics(SM_CXVIRTUALSCREEN),
        user.GetSystemMetrics(SM_CYVIRTUALSCREEN),
    )

    fun windowRect(hwnd: Long): IntArray? {
        val r = RECT()
        if (user.GetWindowRect(hwnd, r) == 0) return null
        return intArrayOf(r.left, r.top, r.right, r.bottom)
    }

    /** The client area — what the app actually draws in — in screen coordinates. */
    fun clientRectOnScreen(hwnd: Long): IntArray? {
        val r = RECT()
        if (user.GetClientRect(hwnd, r) == 0) return null
        val p = POINT()
        if (user.ClientToScreen(hwnd, p) == 0) return null
        return intArrayOf(p.x, p.y, p.x + r.right, p.y + r.bottom)
    }

    // ---- windows ----------------------------------------------------------

    data class Win(
        val hwnd: Long,
        val pid: Int,
        val title: String,
        val className: String,
    )

    /**
     * Every top-level window a person could name: visible, titled, not a tool
     * window.
     *
     * Tool windows are excluded because they never answer "which app am I driving"
     * — floating palettes, IME candidates, Andee's own overlays. Listing them makes
     * the answer longer and less true.
     */
    fun windows(): List<Win> {
        val out = ArrayList<Win>()
        val cb = WndEnumProc { hwnd, _ ->
            if (user.IsWindowVisible(hwnd) != 0 && user.GetWindowTextLengthW(hwnd) > 0) {
                if (user.GetWindowLongW(hwnd, GWL_EXSTYLE) and WS_EX_TOOLWINDOW == 0) {
                    val pid = IntByReference()
                    user.GetWindowThreadProcessId(hwnd, pid)
                    out.add(Win(hwnd, pid.value, windowText(hwnd), className(hwnd)))
                }
            }
            1
        }
        user.EnumWindows(cb, null)
        return out
    }

    fun windowText(hwnd: Long): String = runCatching {
        val len = user.GetWindowTextLengthW(hwnd)
        if (len <= 0) return ""
        val buf = Memory((len + 1) * 2L)
        val n = user.GetWindowTextW(hwnd, buf, len + 1)
        if (n <= 0) "" else buf.getWideString(0)
    }.getOrDefault("")

    fun className(hwnd: Long): String = runCatching {
        val buf = Memory(512L)
        val n = user.GetClassNameW(hwnd, buf, 256)
        if (n <= 0) "" else buf.getWideString(0)
    }.getOrDefault("")

    fun foregroundWindow(): Long = user.GetForegroundWindow()

    fun isAlive(hwnd: Long): Boolean = user.IsWindow(hwnd) != 0

    fun bringToFront(hwnd: Long) {
        if (user.IsIconic(hwnd) != 0) user.ShowWindow(hwnd, SW_RESTORE)
        runCatching { user.SetForegroundWindow(hwnd) }
    }

    fun moveWindow(hwnd: Long, x: Int, y: Int, w: Int, h: Int): Boolean =
        runCatching { user.MoveWindow(hwnd, x, y, w, h, 1) != 0 }.getOrDefault(false)

    // ---- pixels -----------------------------------------------------------

    /**
     * One window as a picture, or null.
     *
     * `PrintWindow` with `PW_RENDERFULLCONTENT` asks the window to paint itself
     * into our DC, so it works while the window sits behind others — which is the
     * whole point of the background screen. GPU-composited windows (browsers with
     * hardware acceleration, video, games) are allowed to ignore it and hand back
     * an empty frame; [looksBlank] exists so the caller can say that instead of
     * passing a black rectangle off as an observation.
     */
    fun captureWindow(hwnd: Long): BufferedImage? {
        val rect = windowRect(hwnd) ?: return null
        val w = rect[2] - rect[0]
        val h = rect[3] - rect[1]
        if (w <= 0 || h <= 0) return null
        if (user.IsIconic(hwnd) != 0) {
            user.ShowWindow(hwnd, SW_RESTORE)
            Thread.sleep(140)
        }
        return grab(w, h) { dc -> user.PrintWindow(hwnd, dc, PW_RENDERFULLCONTENT) }
    }

    /** The whole desktop, or null. */
    fun captureScreen(): BufferedImage? {
        val b = virtualBounds()
        if (b[2] <= 0 || b[3] <= 0) return null
        return grab(b[2], b[3]) { dc ->
            val screenDc = user.GetDC(0)
            try {
                gdi.BitBlt(dc, 0, 0, b[2], b[3], screenDc, b[0], b[1], SRCCOPY)
            } finally {
                user.ReleaseDC(0, screenDc)
            }
        }
    }

    /**
     * Shared DIB dance: a **DIB section** of the right size, [paint] into it, read
     * its memory.
     *
     * `CreateDIBSection` rather than a compatible bitmap + `GetDIBits`: measured on
     * the target machine, `GetDIBits` returned 0 for every size — window, desktop,
     * 32bpp, both bitmap heights — and a capture that fails silently is worse than
     * no capture at all. A DIB section hands back a pointer to the pixels, so
     * there is no second call left to fail.
     */
    private fun grab(w: Int, h: Int, paint: (Long) -> Int): BufferedImage? {
        val screenDc = user.GetDC(0)
        val memDc = gdi.CreateCompatibleDC(screenDc)
        // The header is written by hand rather than through the [BITMAPINFO]
        // structure: `CreateDIBSection` returned 0 for every size when the JNA
        // structure was passed, and a 40-byte header is short enough to write
        // byte-for-byte instead of guessing which part of the marshalling it
        // disliked.
        val info = Memory(64).apply {
            clear()
            setInt(0, 40)      // biSize
            setInt(4, w)       // biWidth
            setInt(8, -h)      // biHeight, negative = top-down rows
            setShort(12, 1)    // biPlanes
            setShort(14, 32)   // biBitCount
            setInt(16, 0)      // BI_RGB
        }
        val bits = com.sun.jna.ptr.PointerByReference()
        val bmp = gdi.CreateDIBSection(screenDc, info, DIB_RGB_COLORS, bits, null, 0)
        if (bmp == 0L) {
            Log.w(TAG, "CreateDIBSection failed for ${w}x$h")
            gdi.DeleteDC(memDc)
            user.ReleaseDC(0, screenDc)
            return null
        }
        val old = gdi.SelectObject(memDc, bmp)
        return try {
            paint(memDc)
            val mem = bits.value
            if (mem == null) {
                Log.w(TAG, "DIB section has no pixels for ${w}x$h")
                null
            } else {
                val bytes = mem.getByteArray(0, w * h * 4)
                val pixels = IntArray(w * h)
                for (i in 0 until w * h) {
                    val b0 = bytes[i * 4].toInt() and 0xFF
                    val b1 = bytes[i * 4 + 1].toInt() and 0xFF
                    val b2 = bytes[i * 4 + 2].toInt() and 0xFF
                    // A DIB section's alpha byte is only meaningful if something
                    // drew it; Windows composites BGRA with the alpha ignored, so
                    // the frame is made opaque here rather than left ghostly.
                    pixels[i] = (0xFF shl 24) or (b2 shl 16) or (b1 shl 8) or b0
                }
                BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).also {
                    it.setRGB(0, 0, w, h, pixels, 0, w)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "capture failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        } finally {
            runCatching { gdi.SelectObject(memDc, old) }
            gdi.DeleteObject(bmp)
            gdi.DeleteDC(memDc)
            user.ReleaseDC(0, screenDc)
        }
    }

    /**
     * Whether a frame carries no information — the signature of a GPU-composited
     * window that refused to print.
     *
     * Sampled on a grid, not per pixel: the question is "is there anything here",
     * and a 64×36 probe answers it in microseconds.
     */
    fun looksBlank(img: BufferedImage): Boolean {
        var dark = 0
        var total = 0
        val stepX = (img.width / 64).coerceAtLeast(1)
        val stepY = (img.height / 36).coerceAtLeast(1)
        var y = 0
        while (y < img.height) {
            var x = 0
            while (x < img.width) {
                val rgb = img.getRGB(x, y)
                val luma = ((rgb shr 16 and 0xFF) * 299 + (rgb shr 8 and 0xFF) * 587 + (rgb and 0xFF) * 114) / 1000
                if (luma < 8) dark++
                total++
                x += stepX
            }
            y += stepY
        }
        return total > 0 && dark.toDouble() / total > 0.98
    }

    // ---- input ------------------------------------------------------------

    /**
     * A real left click at a screen point.
     *
     * `SetCursorPos` + `mouse_event` rather than `SendInput`'s absolute mouse
     * record: the absolute form quantises through 0–65535 and both are equally
     * "real" input. The pointer moving is not a side effect to avoid — a click
     * whose target the user cannot see the pointer on is the thing to avoid.
     */
    fun click(x: Int, y: Int, holdMs: Long = 40): Boolean = runCatching {
        user.SetCursorPos(x, y)
        user.mouse_event(MOUSEEVENTF_LEFTDOWN, 0, 0, 0, 0)
        Thread.sleep(holdMs.coerceIn(10, 800))
        user.mouse_event(MOUSEEVENTF_LEFTUP, 0, 0, 0, 0)
        true
    }.getOrDefault(false)

    fun cursorPosition(): Pair<Int, Int> {
        val p = POINT()
        user.GetCursorPos(p)
        return p.x to p.y
    }

    /**
     * A click delivered **without focusing the window**.
     *
     * The message carries client-area coordinates and the window handles it as if
     * the mouse were over it, so the user's foreground window never changes. It is
     * also the fragile path: Win32 apps honour it, WPF and UWP frequently do not,
     * because they read the real cursor state instead of the message. The caller
     * therefore reports "posted", not "clicked" — see `WinBackground`.
     */
    fun postClick(hwnd: Long, clientX: Int, clientY: Int): Boolean = runCatching {
        val lp = ((clientY.toLong() and 0xFFFF) shl 16) or (clientX.toLong() and 0xFFFF)
        user.PostMessageW(hwnd, WM_LBUTTONDOWN, 1L /* MK_LBUTTON */, lp)
        Thread.sleep(30)
        user.PostMessageW(hwnd, WM_LBUTTONUP, 0L, lp)
        true
    }.getOrDefault(false)

    fun postChar(hwnd: Long, ch: Char): Boolean =
        runCatching { user.PostMessageW(hwnd, WM_CHAR, ch.code.toLong(), 0L) != 0 }.getOrDefault(false)

    /**
     * The `INPUT` array `SendInput` wants, **built as raw bytes**.
     *
     * JNA's `Union` support needs the active member's type set *before* every
     * write, and getting it subtly wrong produces `ERROR_INVALID_PARAMETER` with
     * no further explanation. This structure is 40 bytes on x64 (28 on x86) and
     * its layout is documented and frozen, so writing the fields at their offsets
     * is both shorter and harder to get wrong than modelling the union.
     */
    private fun sendUnicode(text: String): Boolean = runCatching {
        val header = if ((Native.POINTER_SIZE == 8)) 8 else 4   // type + padding before the union
        val extra = if ((Native.POINTER_SIZE == 8)) 8 else 4    // ULONG_PTR
        val size = header + 16 + extra                  // union: 2+2+4+4 (+padding) + pointer
        val events = ArrayList<ByteArray>(text.length * 2)
        for (ch in text) {
            events.add(unicodeEvent(ch, up = false, header, extra, size))
            events.add(unicodeEvent(ch, up = true, header, extra, size))
        }
        val mem = Memory((size * events.size).toLong())
        events.forEachIndexed { i, bytes -> mem.write((i * size).toLong(), bytes, 0, bytes.size) }
        val sent = user.SendInput(events.size, mem, size)
        sent == events.size
    }.onFailure { Log.w(TAG, "SendInput(unicode) failed", it) }.getOrDefault(false)

    private fun unicodeEvent(ch: Char, up: Boolean, header: Int, extra: Int, size: Int): ByteArray {
        val buf = ByteArray(size)
        val bb = java.nio.ByteBuffer.wrap(buf).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.putInt(0, INPUT_KEYBOARD)
        var o = header
        bb.putShort(o, 0)               // wVk
        bb.putShort(o + 2, ch.code.toShort()) // wScan, as a UTF-16 code unit
        bb.putInt(o + 4, KEYEVENTF_UNICODE or (if (up) KEYEVENTF_KEYUP else 0))
        bb.putInt(o + 8, 0)             // time
        if (extra == 8) bb.putLong(o + 16, 0) else bb.putInt(o + 12, 0)
        return buf
    }

    /** Type text as Unicode key events — layout-independent, no IME involved. */
    fun typeText(text: String): Boolean {
        if (text.isEmpty()) return true
        var i = 0
        while (i < text.length) {
            val n = minOf(32, text.length - i)
            if (!sendUnicode(text.substring(i, i + n))) return false
            i += n
            Thread.sleep(20)
        }
        return true
    }

    /** A single virtual key, e.g. 0x0D Enter, 0x1B Escape. */
    fun pressKey(vk: Int): Boolean = runCatching {
        val header = if ((Native.POINTER_SIZE == 8)) 8 else 4
        val extra = if ((Native.POINTER_SIZE == 8)) 8 else 4
        val size = header + 16 + extra
        fun one(flags: Int): ByteArray {
            val buf = ByteArray(size)
            val bb = java.nio.ByteBuffer.wrap(buf).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            bb.putInt(0, INPUT_KEYBOARD)
            bb.putShort(header, vk.toShort())
            bb.putShort(header + 2, 0)
            bb.putInt(header + 4, flags)
            if (extra == 8) bb.putLong(header + 16, 0) else bb.putInt(header + 12, 0)
            return buf
        }
        val down = one(0)
        val up = one(KEYEVENTF_KEYUP)
        val mem = Memory((size * 2).toLong())
        mem.write(0, down, 0, size)
        mem.write(size.toLong(), up, 0, size)
        user.SendInput(2, mem, size) == 2
    }.getOrDefault(false)

    fun pressEnter(): Boolean = pressKey(VK_RETURN)

    /**
     * Press and hold, move, release — a scroll or a drag.
     *
     * `mouse_event` with `MOUSEEVENTF_MOVE` steps rather than `SetCursorPos` alone:
     * applications that track a drag (list views, sliders, canvas apps) only start
     * scrolling after a few move events with the button down, so a jump from A to B
     * is a click that happens to end elsewhere.
     */
    fun drag(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long): Boolean = runCatching {
        val steps = 12
        user.SetCursorPos(x1, y1)
        user.mouse_event(MOUSEEVENTF_LEFTDOWN, 0, 0, 0, 0)
        try {
            val hold = (ms.coerceIn(80, 2_000) / steps)
            for (i in 1..steps) {
                val x = x1 + (x2 - x1) * i / steps
                val y = y1 + (y2 - y1) * i / steps
                user.SetCursorPos(x, y)
                Thread.sleep(hold)
            }
        } finally {
            user.mouse_event(MOUSEEVENTF_LEFTUP, 0, 0, 0, 0)
        }
        true
    }.getOrDefault(false)

    // ---- icons ------------------------------------------------------------

    /**
     * The icon of an executable, 32×32 — what the background screen's ball wears
     * so the user can see *which* app is running where they cannot look.
     *
     * `ExtractIconExW` rather than `SHGetFileInfoW`: the latter returns its answer
     * inside a 700-byte structure whose field offsets differ between SDK headers,
     * and a wrong offset reads a plausible-looking garbage handle. Two out
     * parameters are unambiguous.
     */
    fun iconFor(exePath: String): BufferedImage? = runCatching {
        val large = com.sun.jna.ptr.PointerByReference()
        val small = com.sun.jna.ptr.PointerByReference()
        val n = shell.ExtractIconExW(WString(exePath), 0, large, small, 1)
        val icon = large.value
        if (n <= 0 || icon == null) {
            Log.w(TAG, "ExtractIconEx returned $n for $exePath")
            return null
        }
        try {
            rasteriseIcon(icon)
        } finally {
            user.DestroyIcon(icon)
            small.value?.let { user.DestroyIcon(it) }
        }
    }.onFailure { Log.w(TAG, "no icon for $exePath: ${it.javaClass.simpleName}: ${it.message}") }
        .getOrNull()

    /** The same DIB path the captures use — one way to get pixels out of GDI. */
    private fun rasteriseIcon(hIcon: Pointer): BufferedImage? =
        grab(32, 32) { dc -> user.DrawIconEx(dc, 0, 0, hIcon, 32, 32, 0, 0, DI_NORMAL) }

    // ---- clipboard --------------------------------------------------------

    fun clipboardText(): String? = runCatching {
        if (user.OpenClipboard(0) == 0) return null
        try {
            val handle = user.GetClipboardData(CF_UNICODETEXT)
            if (handle == 0L) return null
            val ptr = kernel.GlobalLock(handle) ?: return null
            try {
                ptr.getWideString(0)
            } finally {
                kernel.GlobalUnlock(handle)
            }
        } finally {
            user.CloseClipboard()
        }
    }.getOrNull()

    fun setClipboardText(text: String): Boolean = runCatching {
        if (user.OpenClipboard(0) == 0) return false
        try {
            user.EmptyClipboard()
            val handle = kernel.GlobalAlloc(GMEM_MOVEABLE, (text.length + 1) * 2L)
            if (handle == 0L) return false
            val ptr = kernel.GlobalLock(handle) ?: return false
            try {
                ptr.setWideString(0, text)
            } finally {
                kernel.GlobalUnlock(handle)
            }
            user.SetClipboardData(CF_UNICODETEXT, handle) != 0L
        } finally {
            user.CloseClipboard()
        }
    }.getOrDefault(false)

    /**
     * A wheel message, delivered to the window without touching the pointer.
     *
     * This is the Windows half of "scroll the background window": the alternative
     * is a real drag, which moves the user's cursor and (for most windows) brings
     * them forward. The message carries **screen** coordinates in `lParam` and the
     * delta in the high word of `wParam`, 120 per notch — a detail that silently
     * does nothing when it is wrong, which is why the caller checks the frame
     * afterwards instead of trusting the return value.
     */
    fun postWheel(hwnd: Long, screenX: Int, screenY: Int, notches: Int): Boolean = runCatching {
        val wp = ((notches * 120) shl 16).toLong() and 0xFFFF_FFFFL
        val lp = ((screenY.toLong() and 0xFFFF) shl 16) or (screenX.toLong() and 0xFFFF)
        user.PostMessageW(hwnd, WM_MOUSEWHEEL, wp, lp) != 0
    }.getOrDefault(false)

    /**
     * Mean absolute luma difference of two frames, on a 32×32 grid.
     *
     * The same measure the phone and the Mac use for "did anything change", with a
     * much smaller sample: here it only has to answer "did the scroll do anything
     * at all", and it runs between two captures of the same window.
     */
    fun frameDelta(a: BufferedImage, b: BufferedImage): Double {
        if (a.width != b.width || a.height != b.height) return Double.MAX_VALUE
        val sx = (a.width / 32).coerceAtLeast(1)
        val sy = (a.height / 32).coerceAtLeast(1)
        var sum = 0L
        var n = 0
        var y = 0
        while (y < a.height) {
            var x = 0
            while (x < a.width) {
                sum += kotlin.math.abs(luma(a.getRGB(x, y)) - luma(b.getRGB(x, y))).toLong()
                n++
                x += sx
            }
            y += sy
        }
        return if (n == 0) 0.0 else sum.toDouble() / n
    }

    private fun luma(rgb: Int): Int =
        ((rgb shr 16 and 0xFF) * 299 + (rgb shr 8 and 0xFF) * 587 + (rgb and 0xFF) * 114) / 1000

    // ---- power, launching, opening ----------------------------------------

    /**
     * Battery and mains, straight from the shell.
     *
     * `GetSystemPowerStatus` rather than WMI: it is one call with no process and
     * no COM, and it is what the taskbar's own icon is drawn from. A desktop
     * reports `BatteryFlag = 128` (no battery) and percent 255 (unknown), which is
     * a fact worth passing on rather than turning into `0%`.
     */
    fun power(): Power? = runCatching {
        val s = SYSTEM_POWER_STATUS()
        if (kernel.GetSystemPowerStatus(s) == 0) return null
        Power(
            onMains = s.ACLineStatus.toInt() == 1,
            percent = s.BatteryLifePercent.toInt() and 0xFF,
            minutesLeft = s.BatteryLifeTime,
        )
    }.getOrNull()

    /**
     * Hand a file, URL or protocol to the shell — what double-clicking does.
     *
     * `ShellExecuteW` and not a hardcoded program: it respects the user's default
     * browser, their PDF reader, `mailto:` handlers. Anything else is this app
     * deciding on their behalf, and being wrong about it.
     */
    fun shellOpen(target: String): Boolean = runCatching {
        val r = shell.ShellExecuteW(0, WString("open"), WString(target), null, null, 1)
        r != null && Pointer.nativeValue(r) > 32
    }.onFailure { Log.w(TAG, "could not open $target", it) }.getOrDefault(false)

    /**
     * Find an executable by full path, or by bare name through **App Paths**.
     *
     * Not a search of `Program Files`: guessing a path is how an assistant starts
     * the wrong binary, and `App Paths` is the same authority the Run box uses.
     */
    fun resolveExe(nameOrPath: String): String? {
        if (nameOrPath.endsWith(".exe", ignoreCase = true) && java.io.File(nameOrPath).isFile) return nameOrPath
        val name = if (nameOrPath.endsWith(".exe", true)) nameOrPath else "$nameOrPath.exe"
        val roots = listOf(
            "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\$name",
            "HKCU\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\$name",
        )
        for (root in roots) {
            val found = runCatching {
                val p = ProcessBuilder("reg", "query", root, "/ve").redirectErrorStream(true).start()
                val text = p.inputStream.bufferedReader().readText()
                p.waitFor()
                Regex("REG_SZ\\s+(.+)").find(text)?.groupValues?.get(1)?.trim()?.trim('"')
            }.getOrNull()
            if (!found.isNullOrBlank() && java.io.File(found).isFile) return found
        }
        return null
    }

    /** Start an app by name or path; false when there is nothing to start. */
    fun startExe(nameOrPath: String): Boolean {
        val exe = resolveExe(nameOrPath)
        if (exe != null) {
            return runCatching { ProcessBuilder(exe).start() }.isSuccess
        }
        // Not in App Paths: let the shell decide — a UWP app, a protocol handler,
        // or something whose path this process would only be guessing.
        return shellOpen(nameOrPath)
    }

    /** The executable behind a process id, via the shell's own view of it. */
    fun exeOfPid(pid: Int): String? = runCatching {
        val p = ProcessBuilder(
            "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
            "(Get-Process -Id $pid -ErrorAction Stop).Path",
        ).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        text.ifBlank { null }
    }.getOrNull()

    // ---- process ----------------------------------------------------------

    /**
     * Whether this is the only copy — `ERROR_ALREADY_EXISTS` after `CreateMutexW`.
     *
     * The name is per-user (`Local\`) rather than per-machine (`Global\`): two
     * accounts on one PC may each run their own Andee, and a global mutex would
     * make the second one exit for no reason the user could see.
     */
    fun singleInstance(name: String): Boolean = runCatching {
        kernel.CreateMutexW(null, 0, WString("Local\\$name"))
        kernel.GetLastError() != 183 // ERROR_ALREADY_EXISTS
    }.getOrDefault(true)
}
