package net.kuafuai.andee.desktop.win

import net.kuafuai.andee.desktop.DeviceHand
import net.kuafuai.andee.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * `device.*` on Windows.
 *
 * **The supported set is smaller than the Mac's, on purpose.** The phone's and the
 * Mac's versions answer calendar, reminders, contacts, location and step counts
 * because those platforms expose them to a normal app. Windows does not: Outlook's
 * calendar is COM, contacts belong to whichever app the user installed, location
 * needs the WinRT consent flow, and there is no pedometer. Every one of those is
 * therefore **absent from [handles]**, which means the tool is not in the model's
 * list at all — a tool that exists and always answers "not supported" is a promise
 * the model makes to the user and breaks, which this project refuses to ship.
 *
 * What is left is what actually works here: launching apps, opening URLs, reading
 * the screen's and the battery's facts, the network, the volume keys, brightness
 * where the hardware has it, and the vault.
 */
class WinDevice : DeviceHand {

    override fun handles(method: String) = method in METHODS

    override fun dispatch(method: String, p: JSONObject, silent: Boolean, untrusted: Boolean): Any? =
        when (method) {
            "device.launch" -> launch(p)
            "device.open_url" -> openUrl(p.optString("url"))
            "device.apps" -> apps()
            "device.battery" -> battery()
            "device.connectivity" -> connectivity()
            "device.volume" -> volume(p)
            "device.brightness" -> brightness(p)
            "device.facts" -> facts()
            "device.permissions" -> permissions()
            "device.notifications" -> WinNotifications.snapshot(
                p.optLong("since_ms", 0L),
                p.optInt("limit", 20).coerceIn(1, 50),
            )

            "device.vault.list" -> WinVault.list()
            "device.vault.get" -> WinVault.get(p.optString("entry"), p.optString("field"))
            "device.vault.fill" -> WinVault.fill(p.optString("entry"))
            else -> throw IllegalArgumentException("unknown method $method")
        }

    // ---- launching --------------------------------------------------------

    /**
     * Start an app by package-ish name, shortcut label, or path.
     *
     * Three attempts in the order that is most likely and least surprising: App
     * Paths (what the Run box uses), then a Start Menu shortcut with that label
     * (what the user actually clicks), then hand it to the shell in case it is a
     * protocol or a UWP app.
     */
    private fun launch(p: JSONObject): JSONObject {
        val pkg = p.optString("pkg").trim()
        val label = p.optString("label").trim()
        val target = pkg.ifEmpty { label }
        require(target.isNotEmpty()) { "launch_app needs pkg or a label" }

        val viaAppPaths = Win32.resolveExe(target)
        if (viaAppPaths != null) {
            val ok = runCatching { ProcessBuilder(viaAppPaths).start() }.isSuccess
            return JSONObject().put("launched", ok).put("via", "app_paths").put("exe", viaAppPaths)
        }
        val shortcut = findShortcut(target)
        if (shortcut != null) {
            // `.lnk` through the shell: resolving the target ourselves would mean
            // parsing the shortcut format, and the shell already does it correctly
            // (including arguments and working directory).
            val ok = Win32.shellOpen(shortcut.absolutePath)
            return JSONObject().put("launched", ok).put("via", "start_menu")
                .put("shortcut", shortcut.name)
        }
        val ok = Win32.shellOpen(target)
        return JSONObject().put("launched", ok).put("via", "shell")
            .put(
                "note",
                if (ok) "Handed to the shell; it may be a protocol handler or a packaged app."
                else "Nothing on this machine answers to \"$target\".",
            )
    }

    private fun findShortcut(label: String): File? {
        val needle = label.lowercase().removeSuffix(".lnk")
        val roots = listOfNotNull(
            System.getenv("APPDATA")?.let { File(it, "Microsoft/Windows/Start Menu/Programs") },
            System.getenv("ProgramData")?.let { File(it, "Microsoft/Windows/Start Menu/Programs") },
        )
        for (root in roots) {
            if (!root.isDirectory) continue
            root.walkTopDown()
                .filter { it.isFile && it.extension.equals("lnk", true) }
                .firstOrNull { it.nameWithoutExtension.lowercase() == needle }
                ?.let { return it }
        }
        // Then a partial match — "wechat" should find "WeChat 微信".
        for (root in roots) {
            if (!root.isDirectory) continue
            root.walkTopDown()
                .filter { it.isFile && it.extension.equals("lnk", true) }
                .firstOrNull { it.nameWithoutExtension.lowercase().contains(needle) }
                ?.let { return it }
        }
        return null
    }

    private fun openUrl(url: String): JSONObject {
        require(url.isNotBlank()) { "open_url needs a url" }
        // http(s), mailto:, ms-settings: — all of it goes to the shell, which is
        // what a double-click does and what respects the user's default apps.
        return JSONObject().put("opened", Win32.shellOpen(url)).put("url", url)
    }

    // ---- reading ----------------------------------------------------------

    private fun apps(): JSONObject {
        val installed = JSONArray()
        val roots = listOfNotNull(
            System.getenv("APPDATA")?.let { File(it, "Microsoft/Windows/Start Menu/Programs") },
            System.getenv("ProgramData")?.let { File(it, "Microsoft/Windows/Start Menu/Programs") },
        )
        val seen = HashSet<String>()
        for (root in roots) {
            if (!root.isDirectory) continue
            root.walkTopDown()
                .filter { it.isFile && it.extension.equals("lnk", true) }
                .take(300)
                .forEach { lnk ->
                    val name = lnk.nameWithoutExtension
                    if (seen.add(name.lowercase())) installed.put(name)
                }
        }
        val running = JSONArray()
        Win32.windows().forEach { w ->
            if (w.title.isNotBlank()) {
                running.put(JSONObject().put("title", w.title).put("hwnd", w.hwnd).put("pid", w.pid))
            }
        }
        return JSONObject()
            .put("installed", installed)
            .put("running", running)
            .put("note", "installed comes from the Start Menu; running is every visible top-level window.")
    }

    private fun battery(): JSONObject {
        val p = Win32.power()
            ?: return JSONObject().put("available", false).put("why", "the shell did not answer GetSystemPowerStatus")
        val hasBattery = p.percent in 0..100
        return JSONObject()
            .put("available", hasBattery)
            .put("on_mains", p.onMains)
            .put("percent", if (hasBattery) p.percent else JSONObject.NULL)
            .put("minutes_left", if (p.minutesLeft >= 0) p.minutesLeft else JSONObject.NULL)
            .put(
                "why",
                if (hasBattery) "reported by the shell"
                else "this machine reports no battery — it is a desktop, so mains power is the whole answer",
            )
    }

    private fun connectivity(): JSONObject {
        val ifaces = JSONArray()
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .forEach { ni ->
                    val v4 = ni.inetAddresses.toList().filterIsInstance<Inet4Address>().firstOrNull()
                    ifaces.put(
                        JSONObject()
                            .put("name", ni.name)
                            // Virtual adapters are noise here; say which is which rather than filter.
                            .put("virtual", ni.isVirtual)
                            .put("address", v4?.hostAddress.orEmpty())
                            .put("mac", runCatching { ni.hardwareAddress?.joinToString(":") { b -> "%02x".format(b) } }
                                .getOrNull().orEmpty()),
                    )
                }
        }.onFailure { Log.w("WinDevice", "could not read network interfaces", it) }
        return JSONObject().put("interfaces", ifaces).put("count", ifaces.length())
    }

    /**
     * Volume, by the media keys — the only path Windows offers a normal process.
     *
     * There is no absolute volume API here (CoreAudio's `ISimpleAudioVolume` is
     * COM and per-session; the endpoint volume needs the same). So `level` is
     * refused rather than approximated: "set to 40%" answered by pressing "volume
     * up" five times is a lie the user hears.
     */
    private fun volume(p: JSONObject): JSONObject {
        val mute = if (p.has("mute")) p.optBoolean("mute") else null
        val action = p.optString("action").lowercase()
        val level = if (p.has("level")) p.optInt("level", -1) else -1
        when {
            level >= 0 -> return JSONObject().put("supported", false).put(
                "why",
                "Windows gives this process no absolute volume control. Use action=\"up\"/\"down\" a few " +
                    "times, or ask the user to set it.",
            )
            mute != null -> {
                // VK_VOLUME_MUTE is a toggle; the key cannot be told a target state,
                // so the reply says "toggled", not "muted".
                Win32.pressKey(0xAD)
                return JSONObject().put("toggled", true).put("asked_for_mute", mute)
            }
            action == "up" || action == "down" -> {
                val vk = if (action == "up") 0xAF else 0xAE
                repeat(5) { Win32.pressKey(vk) }
                return JSONObject().put("applied", action).put("steps", 5)
            }
            else -> return JSONObject().put("supported", true).put(
                "note",
                "Give mute=true, or action=\"up\"/\"down\". Level is not settable on Windows.",
            )
        }
    }

    /**
     * Brightness through WMI, which works on laptops and honestly fails on
     * desktops (external monitors are not controlled by `WmiSetBrightness`).
     */
    private fun brightness(p: JSONObject): JSONObject {
        val want = if (p.has("brightness")) p.optInt("brightness", -1) else -1
        val script = if (want < 0) {
            "(Get-CimInstance -Namespace root/WMI -ClassName WmiMonitorBrightness).CurrentBrightness"
        } else {
            // The API takes 0-100; the tool speaks 1-255 like the phone's.
            val percent = ((want.coerceIn(1, 255) - 1) * 100 / 254).coerceIn(0, 100)
            "(Get-CimInstance -Namespace root/WMI -ClassName WmiMonitorBrightnessMethods)" +
                ".WmiSetBrightness(1, $percent); $percent"
        }
        val out = runCatching {
            val pr = ProcessBuilder(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script,
            ).redirectErrorStream(true).start()
            val text = pr.inputStream.bufferedReader().readText().trim()
            pr.waitFor()
            text
        }.getOrNull().orEmpty()
        val value = out.lineSequence().mapNotNull { it.trim().toIntOrNull() }.lastOrNull()
        if (value == null) {
            return JSONObject().put("available", false).put(
                "why",
                "this display does not answer the WMI brightness interface — an external monitor is " +
                    "controlled by its own hardware, not by Windows",
            )
        }
        return JSONObject().put("available", true)
            .put("percent", value)
            .put("scale", "1..100 (the tool's 1..255 is converted)")
    }

    private fun facts(): JSONObject {
        val rt = Runtime.getRuntime()
        return JSONObject()
            .put("os", System.getProperty("os.name"))
            .put("version", System.getProperty("os.version"))
            .put("arch", System.getProperty("os.arch"))
            .put("java", System.getProperty("java.version"))
            .put("user", System.getProperty("user.name"))
            .put("cores", rt.availableProcessors())
            .put("ram_total_mb", rt.totalMemory() / 1024 / 1024)
            .put("ram_free_mb", rt.freeMemory() / 1024 / 1024)
            .put("screen", JSONObject().put("w", Win32.screenBounds()[2]).put("h", Win32.screenBounds()[3]))
            .put("dpi_aware", Win32.enableDpiAwareness())
            .put("data_dir", net.kuafuai.andee.desktop.AppDirs.root.absolutePath)
    }

    /**
     * The two ceilings, plus what is up. Windows has no grants to ask for, so this
     * is the honest replacement for `device.permissions` on a Mac.
     */
    private fun permissions(): JSONObject = JSONObject()
        .put("uia", WinUia.bridgeRunning)
        .put("tray", java.awt.SystemTray.isSupported())
        .put("dpi_aware", Win32.enableDpiAwareness())
        .put("background", WinBackground.state()?.title ?: JSONObject.NULL)
        .put(
            "limits",
            JSONArray()
                .put("a window running as Administrator cannot be controlled by this process (UIPI)")
                .put("a GPU-composited window may capture as a blank picture")
                .put("the notification centre cannot be read by a normal process"),
        )

    companion object {
        /**
         * Exactly what this body implements. Anything absent is absent from the
         * model's tool list — never listed and then refused.
         */
        private val METHODS = setOf(
            "device.launch", "device.open_url", "device.apps", "device.battery", "device.connectivity",
            "device.volume", "device.brightness", "device.facts", "device.permissions",
            "device.notifications", "device.vault.list", "device.vault.get", "device.vault.fill",
        )
    }
}
