package net.kuafuai.andee.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.system.exitProcess

/**
 * The real UI, drawn offscreen to a PNG, for checking layout and the glass without a
 * window on screen: `./gradlew :desktop:uishot --args='chat 1020 820 /tmp/ui.png'`.
 * Draws [AppRoot] itself — the same function the app draws — against a throwaway data
 * directory seeded with a short conversation and two topics, so nothing of the user's
 * is read or shown.
 *
 * screen: chat | settings | notebook | scenes | vault | selfcheck | expressions | artifacts | topic:1
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    val screen = args.getOrElse(0) { "chat" }
    val w = args.getOrElse(1) { "1020" }.toInt()
    val h = args.getOrElse(2) { "820" }.toInt()
    val out = File(args.getOrElse(3) { "/tmp/ui.png" })
    System.setProperty("andee.home", File.createTempFile("andee-shot", "").also { it.delete(); it.mkdirs() }.path)
    // Optional: `lang=en`, `theme=day` and `brain=codeflying`, set in the throwaway
    // home before anything draws — the last one because which backend tab is open
    // decides what half of the settings card looks like.
    for (a in args.drop(5)) a.split('=', limit = 2).takeIf { it.size == 2 }?.let { (k, v) ->
        if (k == "lang") Settings.save(mapOf("lang" to v))
        if (k == "theme") Palette.setNight(v == "night")
        if (k == "brain") Settings.save(mapOf("brain" to v))
    }
    if (args.getOrElse(4) { "" } == "empty") Scrollback.wipe() else seed()
    @Suppress("UNUSED_VARIABLE") val unused = 0
    if (args.getOrElse(4) { "" } == "pending") {
        Scrollback.add(Scrollback.Role.ASSISTANT, "这一版改的是手感和反馈：\n\n" + "金豆出现了，每吃 5 个普通豆随机长出一颗金色豆子，6.5 秒后消失，最后一秒会闪，吃到 3 分。撞死时棋盘会抖一下、蛇头位置炸开一层红粒子。".repeat(4) + "\n\n要是你原本想改的是别的东西，跟我说一句我就接着改。")
        Pages.add("贪吃蛇", "<html>", null)?.let { Assistant.beginEdit(it) }
    }
    if (args.getOrElse(4) { "" } == "attach") {
        val img = java.awt.image.BufferedImage(600, 400, java.awt.image.BufferedImage.TYPE_INT_RGB).also { b ->
            b.createGraphics().apply { paint = java.awt.GradientPaint(0f, 0f, java.awt.Color(0x2D6CDF), 600f, 400f, java.awt.Color(0xF2A33A)); fillRect(0, 0, 600, 400); dispose() }
        }
        val png = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(img, "png", it) }.toByteArray()
        val doc = File(System.getProperty("andee.home"), "季度复盘-final.pdf").also { it.writeText("%PDF") }
        ChatPreview.attachments = listOfNotNull(Attachments.fromBytes("a.png", png), Attachments.fromBytes("b.png", png), Attachments.fromFile(doc))
    }
    if (screen == "gear") {
        DesktopNotebook.storeOverride = object : net.kuafuai.andee.config.NotebookStore {
            var s: String? = null
            override fun read() = s
            override fun write(plain: String): Boolean { s = plain; return true }
            override fun wipe() { s = null }
        }
        seedGear()
    }
    if (screen == "peers") {
        Peers.storeOverride = object : net.kuafuai.andee.config.NotebookStore {
            var s: String? = null
            override fun read() = s
            override fun write(plain: String): Boolean { s = plain; return true }
            override fun wipe() { s = null }
        }
        // An invented paired phone, so the card has a row to show. Nothing real.
        Peers.store.add(net.kuafuai.andee.peer.PeerProtocol.Peer("phone-demo", "My phone", net.kuafuai.andee.peer.PeerCrypto.publicBytes(net.kuafuai.andee.peer.PeerCrypto.newKeyPair().public)))
        Peers.count.value = Peers.store.all().size
    }
    val nav = Nav().also { if (screen != "chat") it.go(screen) }
    val density = Density(2f)
    val scene = ImageComposeScene(width = w * 2, height = h * 2, density = density) { AppRoot(nav) }
    // Let the backdrop (decoded off the main thread) and the layout settle.
    repeat(12) { scene.render(it * 50_000_000L); Thread.sleep(60) }
    val img = scene.render(700_000_000L)
    out.writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    println("wrote $out (${w}x$h @2x, screen=$screen)")
    scene.close()
    exitProcess(0)
}

private fun seed() {
    Scrollback.wipe()
    Scrollback.add(Scrollback.Role.USER, "帮我把桌面上的截图整理一下")
    Scrollback.add(Scrollback.Role.ASSISTANT, "好的，我先看看桌面上有多少张截图。一共 14 张，按日期分了三组，要我放进「截图」文件夹吗？")
    Scrollback.add(Scrollback.Role.USER, "放吧")
    Scrollback.add(Scrollback.Role.ASSISTANT, "已经放好了：~/Desktop/截图/ 下面按月份分了三个子文件夹。")
    val a = Scrollback.snapshot().map { it.id }
    Scrollback.fold("整理桌面截图", "把 14 张截图按月份归档", a, "User: 帮我把桌面上的截图整理一下\n\nAndee: 好的……")
    Scrollback.add(Scrollback.Role.USER, "明天上午十点提醒我开周会")
    Scrollback.add(Scrollback.Role.ASSISTANT, "好，已经设好了明天 10:00 的提醒。")
    val b = Scrollback.snapshot().filter { it.topicId == null }.map { it.id }
    Scrollback.fold("周会提醒", "明天 10:00 开周会", b, "User: 明天上午十点提醒我开周会\n\nAndee: 好……")
    Scrollback.add(Scrollback.Role.USER, "看看今天有什么新闻")
    Scrollback.add(Scrollback.Role.ASSISTANT, "我先打开浏览器搜一下，稍等。")
}

/** A few invented apps, in the throwaway home, so the gear card has something to show. Nothing real. */
private fun seedGear() {
    val g = Assistant.gear.gear
    val now = System.currentTimeMillis(); val day = 86_400_000L
    g.applyScan(listOf(
        net.kuafuai.andee.config.Gear.Item("com.google.Chrome", "Google Chrome", "155.0", now - 600_000, 7742),
        net.kuafuai.andee.config.Gear.Item("com.tencent.workbuddy.mac", "WorkBuddy", "2.1", now - 3 * 3_600_000, 336),
        net.kuafuai.andee.config.Gear.Item("com.anthropic.claude-code", "Claude Code", "1.0", now - day, 120),
        net.kuafuai.andee.config.Gear.Item("com.tencent.xinWeChat", "微信", "4.0", now - 2 * day, 1932),
        net.kuafuai.andee.config.Gear.Item("com.eg.android.Stocks", "股市", "9.1", now - 9 * day, 14),
        net.kuafuai.andee.config.Gear.Item("com.example.adultvideo", "Adult Video Player", "1.0", now - 5 * day, 40),
        net.kuafuai.andee.config.Gear.Item("com.apple.Notes", "备忘录", "4.12"),
    ))
    g.note("WorkBuddy", "深度调研 / 写作 agent", "做深度调研并写成带来源的报告", "yes", "一次最多约 20 分钟", null)
    g.note("Claude Code", "命令行写代码", "改代码、跑测试、看 diff", "yes", null, null)
    g.note("Google Chrome", "浏览器", "打开网页、登录的网站", "yes", null, null)
    g.edit("微信", "聊天", "读消息、代你回复（需要我同意）")
}
