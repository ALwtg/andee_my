package net.kuafuai.andee.asr

import java.util.Locale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceExitCommandTest {
    @Test
    fun `automatic follow-up speech cannot request exit`() {
        for (text in listOf("Exit Andee", "Take a break", "退出安迪", "休息一下")) {
            assertFalse(text, VoiceExitCommand.matches(text, userInitiated = false))
            assertTrue(text, VoiceExitCommand.matches(text, userInitiated = true))
        }
    }

    @Test
    fun `recognizes the requested English exit phrases`() {
        assertTrue(VoiceExitCommand.matches("Exit Andee"))
        assertTrue(VoiceExitCommand.matches("Take a break"))
        assertTrue(VoiceExitCommand.matches("Andee, take a break."))
    }

    @Test
    fun `recognizes Chinese and mixed-language speech`() {
        assertTrue(VoiceExitCommand.matches("退出安迪。"))
        assertTrue(VoiceExitCommand.matches("关闭 Andee！"))
        assertTrue(VoiceExitCommand.matches("休息一下"))
        assertTrue(VoiceExitCommand.matches("安迪，你先休息一下。"))
    }

    @Test
    fun `tolerates ASR capitalization spacing and punctuation`() {
        assertTrue(VoiceExitCommand.matches("  EXIT   ANDEE!  "))
        assertTrue(VoiceExitCommand.matches("Take\na\tbreak…"))
        assertTrue(VoiceExitCommand.matches("安迪，休息一下。"))
    }

    @Test
    fun `questions negations and app-specific exit requests remain ordinary turns`() {
        for (text in listOf(
            "How do I exit Andee?",
            "Don't exit Andee",
            "Say exit Andee",
            "What does take a break mean?",
            "Remind me to take a break",
            "Exit WeChat",
            "Close the subscription page",
            "不要退出安迪",
            "怎么退出 Andee？",
            "帮我退出微信",
            "先休息一下，然后帮我回复消息",
            "", "  ", "…",
        )) {
            assertFalse(text, VoiceExitCommand.matches(text))
        }
    }

    @Test
    fun `matching does not depend on the device locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale("tr", "TR"))
            assertTrue(VoiceExitCommand.matches("EXIT ANDEE"))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
