package net.kuafuai.andee.asr

import java.util.Locale

/**
 * A small, device-side escape hatch. Match the whole utterance: asking how to
 * exit, cancelling a subscription, or quoting a command must remain a turn for
 * the brain rather than unexpectedly shutting the assistant down.
 */
internal object VoiceExitCommand {
    private fun normalize(text: String): String =
        text.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private val commands = setOf(
        "exit Andee", "close Andee", "quit Andee",
        "Andee exit", "Andee close", "Andee quit",
        "take a break", "Andee take a break", "Hey Andee take a break",
        "退出 Andee", "关闭 Andee", "Andee 退出", "Andee 关闭",
        "退出安迪", "关闭安迪", "安迪退出", "安迪关闭",
        "休息一下", "先休息一下", "你先休息一下",
        "Andee 休息一下", "安迪休息一下", "安迪你先休息一下",
    ).map(::normalize).toSet()

    fun matches(text: String, userInitiated: Boolean = true): Boolean =
        userInitiated && normalize(text) in commands
}
