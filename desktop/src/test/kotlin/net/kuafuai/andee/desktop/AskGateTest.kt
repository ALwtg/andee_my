package net.kuafuai.andee.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AskGateTest {
    private fun ask(buttons: List<String>): Pair<Thread, Array<AskGate.Result?>> {
        val out = arrayOfNulls<AskGate.Result>(1)
        val t = Thread { out[0] = AskGate.ask("选哪个?", buttons, 20_000) }.also { it.start() }
        val deadline = System.currentTimeMillis() + 3000
        while (AskGate.current == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
        return t to out
    }

    @Test fun nothingWaitingMeansTheWordsAreAMessage() {
        assertFalse(AskGate.tryAnswer("否"))
    }

    @Test fun aButtonLabelPressesIt() {
        val (t, out) = ask(listOf("是", "否"))
        assertTrue(AskGate.tryAnswer("否。"))
        t.join(3000)
        assertEquals("否", out[0]?.button)
        assertEquals("voice", out[0]?.how)
    }

    @Test fun anyOtherSentenceIsTheAnswerAndReturnsToTheModel() {
        val (t, out) = ask(listOf("是", "否"))
        assertTrue(AskGate.tryAnswer("不用管了"))
        t.join(3000)
        assertEquals(null, out[0]?.button)
        assertEquals("不用管了", out[0]?.heard)
        assertEquals("voice_other", out[0]?.how)
    }

    @Test fun aSentenceThatOnlyStartsWithALabelIsNotThatButton() {
        val (t, out) = ask(listOf("是", "否"))
        AskGate.tryAnswer("否则帮我查一下天气")
        t.join(3000)
        assertEquals(null, out[0]?.button)
        assertEquals("voice_other", out[0]?.how)
    }

    @Test fun anAlertTakesNoAnswer() {
        val t = Thread { AskGate.alert("好了", 20_000) }.also { it.start() }
        val deadline = System.currentTimeMillis() + 3000
        while (AskGate.current == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertFalse(AskGate.tryAnswer("知道了"))
        AskGate.answer(null)
        t.join(3000)
    }
}
