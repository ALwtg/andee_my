package net.kuafuai.andee.desktop

import net.kuafuai.andee.desktop.win.Win32

/**
 * The Windows body, checked against the machine it is running on.
 *
 *   ./gradlew :desktop:winsmoke
 *
 * A port like this fails in ways that are hard to see from the chat window: a
 * missing export, a structure at the wrong offset, DPI virtualisation quietly
 * scaling every coordinate. Printing the four facts that everything else is built
 * on — the display bounds, the windows that answer, whether a window prints, and
 * whether an icon rasterises — turns "the tools do not work" into a line that says
 * which one does not.
 *
 * It is a main, not a test: it needs a desktop session with windows in it, and a
 * test that skips when there is nothing to look at would pass forever without
 * ever having checked anything.
 */
fun main() {
    println("os         = ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
    println("dpi aware  = ${Win32.enableDpiAwareness()}")
    println("user32     = ${Win32.available}")
    println("screen     = ${Win32.screenBounds().toList()}")
    println("virtual    = ${Win32.virtualBounds().toList()}")

    val all = runCatching { Win32.windows() }.getOrElse { emptyList() }
    println("windows    = ${all.size}")
    all.take(8).forEach { w ->
        println("   hwnd=${w.hwnd} pid=${w.pid} class=${w.className} title=\"${w.title.take(48)}\"")
    }

    val fg = Win32.foregroundWindow()
    // The console's code page mangles Chinese on the way out; the first code point
    // says whether the *read* was right (a CJK code point) or the buffer was filled
    // with halves (replacement characters).
    all.firstOrNull { it.title.isNotEmpty() }?.let { w ->
        println("title cp   = ${w.title.take(3).map { it.code }} \"${w.title.take(6)}\"")
    }
    println("foreground = $fg \"${Win32.windowText(fg).take(48)}\"")
    println("fg client  = ${Win32.clientRectOnScreen(fg)?.toList()}")

    val shot = Win32.captureWindow(fg) ?: Win32.captureScreen()
    println("capture fg = ${shot?.width}x${shot?.height} blank=${shot?.let { Win32.looksBlank(it) }}")

    // A titled window, because the foreground one is often a shell surface that
    // prints nothing — and "which window did you try" is half of a capture bug.
    val target = all.firstOrNull { it.title.isNotEmpty() }
    if (target != null) {
        println("rect       = ${Win32.windowRect(target.hwnd)?.toList()}")
        println("client     = ${Win32.clientRectOnScreen(target.hwnd)?.toList()}")
        val w = Win32.captureWindow(target.hwnd)
        println("capture win= ${w?.width}x${w?.height} blank=${w?.let { Win32.looksBlank(it) }} (${target.title.take(24)})")
    }
    val desktop = Win32.captureScreen()
    println("capture all= ${desktop?.width}x${desktop?.height} blank=${desktop?.let { Win32.looksBlank(it) }}")

    val exe = System.getProperty("java.home") + "\\bin\\java.exe"
    println("icon       = ${Win32.iconFor(exe)?.let { "${it.width}x${it.height}" } ?: "none"}")

    println("clipboard  = ${Win32.clipboardText()?.take(24)?.replace("\n", " ") ?: "empty"}")
    println("single     = ${Win32.singleInstance("andee-smoke-${System.currentTimeMillis()}")}")

    // The settings card's rows, so a row that throws is found here and not on the
    // card. Nothing in here may start the UIA bridge — that is the whole point of
    // `bridgeRunning`.
    // device/gear: everything that can be checked without a user in the loop.
    runCatching {
        val dev = net.kuafuai.andee.desktop.win.WinDevice()
        fun call(m: String, p: org.json.JSONObject = org.json.JSONObject()): org.json.JSONObject =
            dev.dispatch(m, p, false, false) as org.json.JSONObject
        println("device     = launch=${dev.handles("device.launch")} calendar=${dev.handles("device.calendar")}")
        println("   battery  = ${call("device.battery").toString().take(140)}")
        println("   facts    = ${call("device.facts").optString("os")} cores=${call("device.facts").optInt("cores")}")
        println("   net      = ${call("device.connectivity").optInt("count")} interfaces")
        println("   volume   = ${call("device.volume", org.json.JSONObject().put("level", 40)).optString("why").take(70)}")
        println("   notify   = available=${call("device.notifications").optBoolean("available")}")
        println("   apps     = ${call("device.apps").optJSONArray("installed")?.length() ?: -1} installed")
        println("   vault    = ${call("device.vault.list").optInt("count")} entries")
    }.onFailure { println("device     = FAILED: ${it.javaClass.simpleName}: ${it.message}") }

    runCatching {
        val g = net.kuafuai.andee.desktop.win.WinGear()
        g.scanNow()
        val list = g.dispatch("gear.list", org.json.JSONObject(), false, false) as org.json.JSONObject
        println("gear       = keys=${list.keys().asSequence().toList()} chars=${list.toString().length}")
        println("   count    = ${list.optInt("count")} apps=${list.optJSONArray("apps")?.length() ?: -1}")
        println("   first    = ${list.optJSONArray("apps")?.optJSONObject(0)?.toString()?.take(120)}")
        println("   note     = ${list.optString("note").take(100)}")
        println("   index    = ${g.indexForPrompt().take(120).replace("\n", " | ")}")
    }.onFailure { println("gear       = FAILED: ${it.javaClass.simpleName}: ${it.message}") }

    // ---- the terminal hand (T1/T4) ----------------------------------------
    //
    // Registered on both platforms now, and exercised here for real: a `pwsh`
    // invocation through the same job table the tool uses. If this line is missing
    // from the output, the shell family is not wired.
    runCatching {
        val shellTools = net.kuafuai.andee.desktop.shell.ShellTools(
            ask = { net.kuafuai.andee.desktop.shell.ShellTools.Answer.DENY },
            secrets = { emptyList() },
            record = { },
        )
        println(
            "shell      = handles(shell.run)=${shellTools.handles("shell.run")} " +
                "windows=${net.kuafuai.andee.desktop.shell.ShellRunner.isWindows} " +
                "extra=${shellTools.extraTools().length()}",
        )
        val cwd = java.io.File(System.getProperty("user.home"), "Andee").apply { mkdirs() }
        val jobs = net.kuafuai.andee.desktop.shell.JobTable(
            java.io.File(System.getProperty("java.io.tmpdir"), "andee-smoke-jobs"),
        )
        val t0 = System.currentTimeMillis()
        val job = jobs.start(
            "\$PSVersionTable.PSVersion.ToString(); Write-Output andee-shell-ok",
            cwd,
            emptyMap(),
            null,
        )
        val done = job.waitFor(20_000)
        println(
            "   run      = done=$done exit=${job.exitCode} in ${System.currentTimeMillis() - t0}ms " +
                "out=[${job.output().trim().replace("\n", " / ").take(80)}]",
        )
    }.onFailure { println("shell      = FAILED: ${it.javaClass.simpleName}: ${it.message}") }

    // ---- conda sandboxes: what is here already -----------------------------
    runCatching {
        val cs = net.kuafuai.andee.desktop.shell.CondaSandboxes
        val envs = cs.list()
        println("conda      = available=${cs.available()} conda=${cs.conda() ?: "not found"}")
        println(
            "   envs     = ${envs.size}: " +
                envs.take(8).joinToString { "${it.name}${if (it.managed) "(ours)" else ""}" },
        )
    }.onFailure { println("conda      = FAILED: ${it.message}") }

    // ---- the prompt a Windows model would actually read --------------------
    //
    // A gate, not a report: this throws, so `:desktop:winsmoke` fails if the
    // Windows prompt ever starts describing a Mac again.
    runCatching {
        val caps = net.kuafuai.andee.desktop.MacPrompt.Capabilities(
            screen = true, voice = false, shell = true, pages = true,
        )
        val prompt = net.kuafuai.andee.desktop.DesktopPrompt.text(caps)
        // Word boundaries matter: "this machine" contains "this mac", and the first
        // version of this gate failed on exactly that. A gate that cries wolf gets
        // switched off, which is worse than no gate.
        val macWords = listOf(
            Regex("""\bzsh\b""", RegexOption.IGNORE_CASE),
            Regex("""\bmdfind\b""", RegexOption.IGNORE_CASE),
            Regex("""\bopen -a\b""", RegexOption.IGNORE_CASE),
            Regex("""\bthis Mac\b"""),
            Regex("""com\.apple"""),
            Regex("""⌘\["""),
            Regex("""\bDock\b"""),
        )
        val leaks = macWords.map { it.pattern }.filter { re -> Regex(re).containsMatchIn(prompt) }
        println("prompt     = ${prompt.length} chars, mac-words=${if (leaks.isEmpty()) "none" else leaks}")
        println("   head    = " + prompt.lineSequence().take(3).joinToString(" | ").take(160))
        check(leaks.isEmpty()) { "the Windows prompt still speaks Mac: $leaks" }
        check(!prompt.contains("show_html is not available")) {
            "the prompt says show_html is missing, but UiTools registers it (caps.pages must be true)"
        }
    }.onFailure { println("prompt     = FAILED: ${it.message}") }

    // The Mac branch must still produce the Mac text — this runs on Windows
    // because it is pure strings, and that is the point: the refactor's promise is
    // that the Mac prompt did not change, and a Windows machine can check it.
    runCatching {
        val caps = net.kuafuai.andee.desktop.MacPrompt.Capabilities(
            screen = true, voice = true, shell = true, pages = true,
        )
        val mac = net.kuafuai.andee.desktop.MacPrompt.text(caps)
        val mustHave = listOf("⌘[", "open -a", "this Mac's own", "you are a Mac app")
        val missing = mustHave.filterNot { mac.contains(it) }
        println("mac prompt = ${mac.length} chars, missing=${if (missing.isEmpty()) "none" else missing}")
        check(missing.isEmpty()) { "the Mac prompt lost its own sentences: $missing" }
    }.onFailure { println("mac prompt = FAILED: ${it.message}") }

    // LAN discovery is what the phone connection rides on; the Mac-named class is
    // plain Java underneath, and this is the one fact that decides whether it can
    // work here at all.
    runCatching {
        val addr = net.kuafuai.andee.desktop.PeerDiscoveryMac.lanAddress()
        println("lan        = ${addr.hostAddress} site-local=${addr.isSiteLocalAddress}")
    }.onFailure { println("lan        = FAILED: ${it.message}") }

    // Real sandbox round-trip, only when asked: it creates a conda env and deletes
    // it again, which has no place in a smoke run somebody else triggers.
    //   $env:ANDEE_SANDBOX_TEST='1'; ./gradlew :desktop:winsmoke
    if (System.getenv("ANDEE_SANDBOX_TEST") == "1") {
        runCatching {
            val cs = net.kuafuai.andee.desktop.shell.CondaSandboxes
            val made = cs.create("smoke").getOrThrow()
            println("sandbox    = created ${made.name} managed=${made.managed} at ${made.prefix}")
            val env = cs.envFor(made) ?: error("the new sandbox has no environment")
            val jobs = net.kuafuai.andee.desktop.shell.JobTable(
                java.io.File(System.getProperty("java.io.tmpdir"), "andee-sandbox-jobs"),
            )
            val job = jobs.start(
                "Write-Output \"env=\$env:CONDA_DEFAULT_ENV\"; " +
                    "Write-Output \"python=\$((Get-Command python -ErrorAction SilentlyContinue).Source)\"",
                java.io.File(System.getProperty("user.home"), "Andee").apply { mkdirs() },
                env,
                null,
            )
            val done = job.waitFor(60_000)
            println("   inside   = done=$done exit=${job.exitCode} out=[${job.output().trim().replace("\n", " / ").take(160)}]")
            println("   listed   = ${cs.list().count { it.managed }} managed of ${cs.list().size}")
            println("   removed  = ${cs.remove(made.name)}")
            println("   after    = ${cs.list().count { it.managed }} managed of ${cs.list().size}")
        }.onFailure { println("sandbox    = FAILED: ${it.message}") }
    }

    // Full "tap by name" against a UIA-rich app, only when asked — it moves the
    // selection inside Windows Settings, which a smoke run should not do.
    //   $env:ANDEE_NAME_TEST='1'; ./gradlew :desktop:winsmoke
    if (System.getenv("ANDEE_NAME_TEST") == "1") {
        runCatching {
            val bg = net.kuafuai.andee.desktop.win.WinBackground
            // Title first (what a person says), then the process name — Settings'
            // window title changes with the page it is on, and the process name does
            // not.
            val t = bg.open("设置", "设置").getOrElse { bg.open("SystemSettings", "设置").getOrThrow() }
            println("name test  = bound \"${t.title}\" hwnd=${t.hwnd}")
            val out = bg.tapName("蓝牙")
            println(
                "   tapName  = tapped=${out.optBoolean("tapped")} " +
                    "how=${out.optString("how")} matched=${out.optJSONObject("matched")?.optString("name")} " +
                    "score=${out.optJSONObject("matched")?.optDouble("score", -1.0)}",
            )
            val amb = bg.tapName("系统")
            println("   ambiguous= ${amb.optString("reason")} matches=${amb.optJSONArray("matches")?.length() ?: 0}")
            bg.close()
        }.onFailure { println("name test  = FAILED: ${it.message}") }
    }

    // ---- by-name matching (Windows: UIA names) ----------------------------
    //
    // The ranking is pure, so it is tested here with hand-written nodes rather than
    // against whatever happens to be on screen: the rules (exact wins, two equal
    // labels tie, whitespace and full-width forms normalise) are what can break
    // silently.
    runCatching {
        val bg = net.kuafuai.andee.desktop.win.WinBackground
        fun node(name: String, x: Int, y: Int, w: Int, h: Int, press: Boolean) =
            net.kuafuai.andee.desktop.win.WinUia.Node(
                path = "0/1", depth = 1, type = "Button", name = name,
                x = x, y = y, w = w, h = h, enabled = true,
                patterns = if (press) listOf("invoke") else emptyList(),
            )
        val nodes = listOf(
            node("发送", 100, 100, 80, 30, true),
            node("发送到手机", 200, 400, 120, 30, false),
            node("确定", 10, 10, 60, 24, true),
            node("确定", 300, 10, 60, 24, true),
            node("取消", 400, 10, 60, 24, true),
        )
        val top = bg.rank(nodes, "发送")
        println("   rank     = ${top.map { "${it.node.name}@${it.score}" }.take(2)}")
        check(top.first().node.name == "发送" && top.first().score == 1.0) { "the exact label must win" }
        val amb = bg.rank(nodes, "确定")
        check(amb.size == 2 && amb[0].score == amb[1].score) { "two 确定 must tie, so the tap refuses" }
        // A Chinese IME produces full-width forms and stray spaces; the button is
        // the same button.
        check(bg.rank(nodes, "发 送").firstOrNull()?.score == 1.0) { "whitespace must normalise away" }
        check(bg.rank(nodes, "发送").size == 2) { "contains-match must also find 发送到手机" }
        println("   names    = exact wins, two 确定 tie, whitespace normalised")
    }.onFailure { println("   rank     = FAILED: ${it.message}") }

    // And the live path: does this machine's UI Automation actually hand over names?
    runCatching {
        val wins = Win32.windows().filter { it.title.isNotBlank() }
        val pick = wins.firstOrNull { it.className !in setOf("Chrome_WidgetWin_1") } ?: wins.firstOrNull()
        if (pick == null) {
            println("   find     = no window to ask")
        } else {
            val hits = net.kuafuai.andee.desktop.win.WinUia.find(pick.hwnd, "文件", exact = false) +
                net.kuafuai.andee.desktop.win.WinUia.find(pick.hwnd, "File", exact = false)
            println(
                "   find     = ${hits.size} match(es) for 文件/File in \"${pick.title.take(28)}\" " +
                    hits.take(3).joinToString { it.name.take(12) },
            )
        }
    }.onFailure { println("   find     = FAILED: ${it.message}") }

    println("capability rows:")
    net.kuafuai.andee.desktop.win.WinCapabilities.facts().forEach { f ->
        println("   ${if (f.ok) "ok " else "-- "} ${f.label}: ${f.detail}")
    }

    // UI Automation: the tree every screen tool depends on.
    println("uia        = ${net.kuafuai.andee.desktop.win.WinUia.available}")
    val uiaWindows = net.kuafuai.andee.desktop.win.WinUia.windows()
    println("uia wins   = ${uiaWindows.size}")
    val pick = uiaWindows.firstOrNull { it.title.isNotEmpty() && it.hwnd != 0L }
    if (pick != null) {
        val started = System.currentTimeMillis()
        val tree = net.kuafuai.andee.desktop.win.WinUia.tree(pick.hwnd, 200)
        val ms = System.currentTimeMillis() - started
        println("uia tree   = ${tree.size} nodes in ${ms}ms from \"${pick.title.take(24)}\"")
        tree.take(6).forEach { n ->
            println("   ${n.path} ${n.type} \"${n.name.take(28)}\" [${n.cx},${n.cy}] pats=${n.patterns}")
        }
        println("uia useful = ${tree.count { it.name.isNotEmpty() || it.clickable }}")
    }

    // The tool as the brain sees it: the outline plus an aim-gridded picture.
    runCatching {
        val screen = net.kuafuai.andee.desktop.win.WinScreen()
        val started = System.currentTimeMillis()
        val out = screen.dispatch("screen.ui_tree", org.json.JSONObject().put("with_shot", true), false, false)
            as org.json.JSONObject
        val ms = System.currentTimeMillis() - started
        println("ui_tree    = ${out.optInt("elements_listed")} elements / ${out.optInt("nodes_total")} nodes in ${ms}ms")
        println("   window  = \"${out.optString("window").take(30)}\"")
        out.optString("elements").lineSequence().take(4).forEach { println("   $it") }
        println("   shot    = ${out.optString("png_base64").length} base64 chars")
    }.onFailure { println("ui_tree    = failed: ${it.javaClass.simpleName}: ${it.message}") }

    // The background window: bind to something already running, read it, look at it.
    runCatching {
        val bg = net.kuafuai.andee.desktop.win.WinBackground
        // Bind to whatever is on screen rather than to a fixed app: this has to
        // pass on any machine, and "Taskmgr" only exists while Task Manager is open.
        val subject = Win32.windows().firstOrNull { it.title.isNotBlank() && it.hwnd != 0L }
            ?: throw IllegalStateException("nothing on screen to bind to")
        val target = bg.open(subject.title).getOrElse { err -> throw err }
        println("background = hwnd=${target.hwnd} \"${target.title.take(24)}\" pkg=${target.pkg}")
        println("   alive   = ${bg.alive()}")
        println("   tree    = ${bg.tree(200).size} nodes")
        val f = bg.frame()
        println("   frame   = ${f.width}x${f.height} blank=${f.blank} chars=${f.png?.length ?: 0}")
        println("   icon    = ${bg.iconPng()?.size ?: 0} bytes")
        // The three ways to act, exercised through the same entry point the
        // supervision window uses. A tap in the middle of Task Manager changes
        // nothing, and the reply says which path answered.
        val tap = bg.tapNorm(500, 500)
        println("   tap     = how=${tap.optString("how")} done=${tap.optBoolean("done")}")
        // A vertical swipe: wheel first (pointer stays put), drag only if the
        // window ignored it. `changed` is the measured frame difference, so the
        // line below is evidence rather than a claim.
        val swipe = bg.swipe(500, 800, 500, 300, 400)
        println(
            "   swipe    = how=${swipe.optString("how")} done=${swipe.optBoolean("done")} " +
                "changed=${swipe.optDouble("changed", -1.0)}",
        )
        // The stuck-loop guard, proven rather than described: the tap above was the
        // first gesture at this spot, so the fourth must be refused.
        var refusal = ""
        repeat(3) {
            runCatching { bg.tapNorm(500, 500) }.onFailure { refusal = it.message.orEmpty() }
        }
        println("   guard    = ${if (refusal.isBlank()) "NOT ENFORCED" else "refused the fourth gesture"}")
        check(refusal.isNotBlank()) { "the background stuck-loop guard did not fire" }
        println("   closed  = ${bg.close()}")
    }.onFailure { println("background = failed: ${it.javaClass.simpleName}: ${it.message}") }
}
