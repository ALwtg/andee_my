package net.kuafuai.andee.desktop.win

import net.kuafuai.andee.util.Log
import java.awt.Color
import java.awt.Graphics2D
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities

/**
 * The tray icon, and the one registry write the app makes.
 *
 * **Why a tray icon at all**: the chat window closes to nothing — the ball is the
 * usual way back — and on Windows a program with no window, no tray icon and no
 * dock is a program the user has to find in Task Manager. The tray is also where
 * a Windows user looks for "what is this thing doing", so the background window's
 * state lives in this menu as well.
 *
 * **Why the menu is rebuilt rather than mutated**: AWT's `MenuItem` label can be
 * changed, but the set of items depends on state (a background window exists or
 * it does not), and a menu that gains and loses items in place is a menu that
 * shows stale entries on some Windows builds. Rebuilding is a few objects and no
 * surprises.
 */
object WinTray {

    private const val TAG = "WinTray"

    private const val RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val RUN_VALUE = "Andee"

    private var tray: SystemTray? = null
    private var icon: TrayIcon? = null

    /** Set once [install] succeeds; null afterwards. */
    private var actions: Actions? = null

    private class Actions(
        val onShow: () -> Unit,
        val onSettings: () -> Unit,
        val onCloseBackground: () -> Unit,
        val onQuit: () -> Unit,
    )

    val installed: Boolean get() = icon != null

    /**
     * Put the icon in the tray, or say why not.
     *
     * `SystemTray.isSupported()` is false on a few configurations (a session
     * without Explorer) and `add` throws when the notification area is full. Both
     * are reported, and the app carries on: without a tray icon the ball still
     * exists, so this is degraded rather than broken.
     */
    fun install(
        onShow: () -> Unit,
        onSettings: () -> Unit,
        onCloseBackground: () -> Unit,
        onQuit: () -> Unit,
    ): Boolean {
        if (icon != null) return true
        if (!SystemTray.isSupported()) {
            Log.w(TAG, "this session has no system tray; the ball is the only door")
            return false
        }
        actions = Actions(onShow, onSettings, onCloseBackground, onQuit)
        return runCatching {
            val t = SystemTray.getSystemTray()
            val i = TrayIcon(image(), "Andee", popup())
            i.isImageAutoSize = true
            // Double-click is the Windows habit for "open it".
            i.addActionListener { onShow() }
            t.add(i)
            tray = t
            icon = i
            // The background window may come and go while the app sits in the
            // tray; refreshing here is what keeps the menu honest.
            WinBackground.onChanged = {
                SwingUtilities.invokeLater { refresh() }
            }
            refresh()
            true
        }.onFailure { Log.w(TAG, "could not install the tray icon", it) }.getOrDefault(false)
    }

    fun remove() {
        runCatching { icon?.let { tray?.remove(it) } }
        icon = null
        tray = null
        actions = null
    }

    /** Rebuild the menu for whatever is true right now. */
    fun refresh() {
        val i = icon ?: return
        val a = actions ?: return
        val bg = WinBackground.state()
        i.toolTip =
            if (bg != null) "Andee · 副屏：${bg.title}" else "Andee"
        i.popupMenu = popup()
    }

    private fun popup(): PopupMenu {
        val a = actions ?: return PopupMenu()
        val menu = PopupMenu()
        val bg = WinBackground.state()
        val status = MenuItem(
            if (bg != null) "副屏：${bg.title.take(28)}" else "副屏：没有在跑",
        )
        status.isEnabled = false
        menu.add(status)
        if (bg != null) {
            menu.add(MenuItem("关闭副屏").apply { addActionListener { a.onCloseBackground() } })
        }
        menu.addSeparator()
        menu.add(MenuItem("打开 Andee").apply { addActionListener { a.onShow() } })
        menu.add(MenuItem("设置").apply { addActionListener { a.onSettings() } })
        menu.addSeparator()
        val auto = MenuItem(if (autostart()) "开机自启：开" else "开机自启：关")
        auto.addActionListener {
            val next = !autostart()
            setAutostart(next)
            auto.label = if (autostart()) "开机自启：开" else "开机自启：关"
        }
        menu.add(auto)
        menu.addSeparator()
        menu.add(MenuItem("退出").apply { addActionListener { a.onQuit() } })
        return menu
    }

    /**
     * A 16/32px icon for the tray: Andee's own, or a drawn fallback.
     *
     * `TrayIcon` scales with `isImageAutoSize`, so one 32×32 image is enough for
     * every DPI.
     */
    private fun image(): BufferedImage {
        val exe = ProcessHandle.current().info().command().orElse(null)
        Win32.iconFor(exe)?.let { return it }
        val size = 32
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g: Graphics2D = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = Color(0x2B2B2F)
            g.fillRoundRect(1, 1, size - 2, size - 2, 10, 10)
            g.color = Color(0xE8E8E8)
            g.font = g.font.deriveFont(18f)
            g.drawString("A", size / 2 - 6, size / 2 + 7)
        } finally {
            g.dispose()
        }
        return img
    }

    // ---- autostart --------------------------------------------------------

    /**
     * Whether Andee starts with this user's session — read from `HKCU\...\Run`.
     *
     * Current user, not machine: the app needs no Administrator rights, and an
     * entry under `HKLM` would both require them and start Andee for accounts that
     * never asked for it.
     */
    fun autostart(): Boolean = runCatching {
        val p = ProcessBuilder("reg", "query", RUN_KEY, "/v", RUN_VALUE)
            .redirectErrorStream(true)
            .start()
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor()
        text.contains(RUN_VALUE) && p.exitValue() == 0
    }.getOrDefault(false)

    fun setAutostart(on: Boolean): Boolean = runCatching {
        val cmd = if (on) {
            val exe = System.getProperty("jpackage.app-path")
                ?: ProcessHandle.current().info().command().orElse(null)
                ?: return false
            listOf("reg", "add", RUN_KEY, "/v", RUN_VALUE, "/t", "REG_SZ", "/d", "\"$exe\"", "/f")
        } else {
            listOf("reg", "delete", RUN_KEY, "/v", RUN_VALUE, "/f")
        }
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText()
        p.waitFor()
        p.exitValue() == 0
    }.getOrDefault(false)
}
