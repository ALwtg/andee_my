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
    // Optional: `lang=en` and `theme=day`, set in the throwaway home before anything draws.
    for (a in args.drop(5)) a.split('=', limit = 2).takeIf { it.size == 2 }?.let { (k, v) ->
        if (k == "lang") Settings.save(mapOf("lang" to v))
        if (k == "theme") Palette.setNight(v == "night")
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
