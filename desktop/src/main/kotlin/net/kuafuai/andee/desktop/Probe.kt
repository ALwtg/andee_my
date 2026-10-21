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
    // No window here: approvals are answered from ANDEE_PROBE_APPROVE (once|always|deny), default deny.
    val auto = System.getenv("ANDEE_PROBE_APPROVE").orEmpty()
    Assistant.askApproval = { a ->
        println("  [approval] ${a.domain ?: a.command}  (${a.tier}: ${a.reason}) → ${auto.ifEmpty { "deny" }}")
        when (auto) {
            "once" -> net.kuafuai.andee.desktop.shell.ShellTools.Answer.ONCE
            "always" -> net.kuafuai.andee.desktop.shell.ShellTools.Answer.ALWAYS
            else -> net.kuafuai.andee.desktop.shell.ShellTools.Answer.DENY
        }
    }
    // Turns are separated by `|` so a sentence with spaces stays one turn.
    val turns = args.joinToString(" ").split('|').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("你好") }
    for (line in turns) {
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
