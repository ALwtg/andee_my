package net.kuafuai.andee.desktop

import net.kuafuai.andee.ui.ball.BallLooks
import net.kuafuai.andee.ui.ball.Mood
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.system.exitProcess

/**
 * One frame of the ball to a PNG, for checking the desktop renderer without the window:
 * `./gradlew :desktop:ballshot --args='HAPPY 336 /tmp/ball.png andee'`
 */
fun main(args: Array<String>) {
    net.kuafuai.andee.util.Log.sink = object : net.kuafuai.andee.util.Log.Sink {
        override fun log(level: Char, tag: String, msg: String, t: Throwable?) {
            System.err.println("$level/$tag: $msg"); t?.printStackTrace()
        }
    }
    val mood = Mood.valueOf(args.getOrElse(0) { "CALM" })
    val size = args.getOrElse(1) { "336" }.toInt()
    val out = File(args.getOrElse(2) { "/tmp/ball.png" })
    args.getOrNull(3)?.let { BallLooks.requestByName(it) }
    val r = BallRenderer(size, size)
    if (System.getenv("BALL_BENCH") != null) {
        // How much CPU the renderer alone costs, without any window: ten seconds of frames.
        val os = java.lang.management.ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
        Thread.sleep(2000)
        val c0 = os.processCpuTime; val t0 = System.nanoTime()
        Thread.sleep(10_000)
        println("renderer alone: %.1f%% of one core".format(100.0 * (os.processCpuTime - c0) / (System.nanoTime() - t0)))
        exitProcess(0)
    }
    r.setState(mood, listening = mood == Mood.LISTENING, emotion = null)
    // Wait for the first frame, then let the face settle into the mood for a second and a half.
    val deadline = System.currentTimeMillis() + 10_000
    while (r.failure == null && r.frame == null && System.currentTimeMillis() < deadline) Thread.sleep(50)
    if (r.frame != null) Thread.sleep(1500)
    r.failure?.let { System.err.println("failed: $it"); exitProcess(1) }
    val px = r.frame ?: run { System.err.println("no frame in 10 s"); exitProcess(1) }
    val argb = IntArray(size * size) { i ->
        val j = i * 4
        ((px[j + 3].toInt() and 0xff) shl 24) or ((px[j + 2].toInt() and 0xff) shl 16) or
            ((px[j + 1].toInt() and 0xff) shl 8) or (px[j].toInt() and 0xff)
    }
    val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB_PRE)
    img.raster.setDataElements(0, 0, size, size, argb)
    ImageIO.write(img, "png", out)
    println("wrote $out, ${argb.count { (it ushr 24) != 0 }} of ${argb.size} pixels drawn")
    r.stop()
    exitProcess(0)
}
