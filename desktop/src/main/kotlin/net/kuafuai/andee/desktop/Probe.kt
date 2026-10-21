package net.kuafuai.andee.desktop

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A turn without the window: `./gradlew :desktop:probe --args='…'`. Prints the
 * progress lines and the answer, then exits. For checking the brain, the
 * notebook and the tools on this Mac without clicking through the UI.
 */
fun main(args: Array<String>) {
    net.kuafuai.andee.util.Log.sink = object : net.kuafuai.andee.util.Log.Sink {
        override fun log(level: Char, tag: String, msg: String, t: Throwable?) {
            if (level == 'W' || level == 'E') System.err.println("$level/$tag: $msg")
            t?.printStackTrace()
        }
    }
    for (line in args.ifEmpty { arrayOf("你好") }) {
        val done = CountDownLatch(1)
        Assistant.onProgress = { println("  … $it") }
        Assistant.onFinal = { println("Andee: $it") }
        Assistant.onBusy = { busy -> if (!busy) done.countDown() }
        println("You: $line")
        Assistant.say(line)
        if (!done.await(180, TimeUnit.SECONDS)) println("(no answer within 180 s)")
    }
    System.exit(0)
}
