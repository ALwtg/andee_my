package net.kuafuai.andee.desktop.win

import net.kuafuai.andee.desktop.Body

/**
 * What this PC can actually do, in facts the settings page can print.
 *
 * The Mac's checklist answers "did the user grant Accessibility and Screen
 * Recording". Windows has no such grants — it has **silent ceilings** instead:
 * an elevated window simply refuses input, a GPU-composited window simply prints
 * blank, and neither raises anything the user can click. A port that does not say
 * so in advance turns every one of those into "the assistant is broken".
 *
 * Everything here is **cheap** — a handful of already-cached booleans and one
 * registry read. The expensive probe (`WinUia.available` starts a PowerShell
 * bridge) is deliberately not called from a paint.
 */
object WinCapabilities {

    data class Fact(val label: String, val ok: Boolean, val detail: String)

    fun facts(): List<Fact> = buildList {
        add(
            Fact(
                label = "Win32",
                ok = Win32.available,
                detail = if (Win32.available) "窗口、像素、输入都可用" else "user32 没有响应，屏幕工具无法运行",
            ),
        )
        add(
            Fact(
                label = "DPI",
                ok = Win32.enableDpiAwareness(),
                detail = "按物理像素换算 0-1000 坐标",
            ),
        )
        add(
            // Not a probe: starting the bridge costs a process. This says whether
            // one is already up, which is the honest answer to "will the tree work
            // right now".
            Fact(
                label = "元素树（UI Automation）",
                ok = true,
                detail = if (WinUia.bridgeRunning) "桥已启动" else "第一次用时会启动桥进程（约 1 秒）",
            ),
        )
        val traySupported = java.awt.SystemTray.isSupported()
        add(
            Fact(
                label = "托盘",
                ok = traySupported && WinTray.installed,
                detail = when {
                    WinTray.installed -> "窗口关掉后仍可从托盘叫回来"
                    // Two different truths: the session has no tray, or the app has
                    // not got round to installing one yet. Saying "no tray" for the
                    // second would send the user looking for a setting that is fine.
                    traySupported -> "系统支持，应用启动时安装"
                    else -> "这台会话没有托盘，请用窗口菜单"
                },
            ),
        )
        add(
            Fact(
                label = "开机自启",
                ok = WinTray.autostart(),
                detail = if (WinTray.autostart()) "已写入 HKCU 的 Run" else "未开启（托盘菜单里可以打开）",
            ),
        )
        val bg = WinBackground.state()
        add(
            Fact(
                label = "副屏",
                ok = true,
                detail = bg?.let { "正在操作「${it.title}」" } ?: "没有在跑",
            ),
        )
    }

    /** True on the platform this file describes — the caller decides whether to show it. */
    val applicable: Boolean get() = Body.isWindows
}
