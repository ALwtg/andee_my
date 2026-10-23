package net.kuafuai.andee.brain

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the seams are, and why they have to be found mechanically.
 *
 * A wrong seam is not a wrong label: the stretch it names gets replaced by a
 * summary in the live context, and a range that lands inside a turn orphans a
 * `tool` message and the API answers 400. So the rules are pinned down here
 * rather than trusted to review.
 *
 * The two locale hazards are covered on purpose — the stamp is rendered in the
 * interface language, so a parser that reads `MM-dd HH:mm` works on this
 * machine and fails on a Chinese tablet, which is exactly the bug that would
 * survive every local test.
 */
class TopicSplitterTest {

    /** A user message carrying the device's own `[now …]` stamp. */
    private fun user(stamp: String, text: String = "做个番茄钟"): JSONObject =
        JSONObject().put("role", "user").put("content", "[now $stamp] $text")

    private fun assistant(text: String): JSONObject =
        JSONObject().put("role", "assistant").put("content", text)

    private fun system(): JSONObject =
        JSONObject().put("role", "system").put("content", "you are andee")

    /** An assistant turn that called a tool, plus the result it got back. */
    private fun toolCall(name: String = "show_html"): List<JSONObject> = listOf(
        JSONObject().put("role", "assistant")
            .put("content", JSONObject.NULL)
            .put(
                "tool_calls",
                JSONArray().put(
                    JSONObject().put("id", "c1").put(
                        "function",
                        JSONObject().put("name", name).put("arguments", "{}"),
                    ),
                ),
            ),
        JSONObject().put("role", "tool").put("tool_call_id", "c1").put("content", "{\"shown\":true}"),
    )

    /** Six messages: a conversation big enough to be worth folding. */
    private fun stretch(stamp: String, tag: String): List<JSONObject> = listOf(
        user(stamp, "帮我$tag"),
        assistant("好"),
        assistant("做完了"),
        user(stamp, "再改一下$tag"),
        assistant("改好了"),
        assistant("还有别的吗"),
    )

    // ---- the turns a topic may start and end on ----

    @Test
    fun `every stamped user message is a turn, oldest first`() {
        val h = listOf(system()) +
            stretch("2026-10-15 周四 10:00", "甲") +
            stretch("2026-10-15 周四 10:01", "乙")
        val turns = TopicSplitter.userTurns(h)
        assertEquals(listOf(1, 4, 7, 10), turns)
        assertTrue(turns.all { h[it].optString("role") == "user" })
    }

    /**
     * The case that looked broken on the tablet: a timer, then the weather a
     * minute later. Two topics, no silence between them — both turns must be
     * offered, so the model can say where one topic ends.
     */
    @Test
    fun `a change of subject with no silence still yields separate turns`() {
        val h = listOf(system()) +
            listOf(user("2026-10-16 周五 10:10", "帮我做一个番茄钟"), assistant("做好了")) +
            listOf(user("2026-10-16 周五 10:10", "做得美观一些"), assistant("改好了")) +
            listOf(user("2026-10-16 周五 10:11", "今天天气怎么样？"), assistant("晴"))
        assertEquals(3, TopicSplitter.userTurns(h).size)
    }

    @Test
    fun `device notes in the user role are not turns`() {
        // A page closed, a transcript arrived: no stamp, no row on the device,
        // and not where the person changed the subject.
        val h = listOf(system()) +
            user("2026-10-15 周四 10:00", "甲") +
            JSONObject().put("role", "user").put("content", "(the user closed the page)") +
            user("2026-10-15 周四 10:02", "乙")
        assertEquals(listOf(1, 3), TopicSplitter.userTurns(h))
    }

    @Test
    fun `the system prompt and tool traffic are never turns`() {
        val h = listOf(system()) + user("2026-10-15 周四 10:00") + toolCall()
        assertEquals(listOf(1), TopicSplitter.userTurns(h))
    }

    @Test
    fun `a long silence is a seam and a short one is not`() {
        val a = user("2026-10-15 周四 10:00")
        assertTrue(TopicSplitter.isSeam(a, user("2026-10-15 周四 14:00")))
        assertFalse(TopicSplitter.isSeam(a, user("2026-10-15 周四 10:05")))
    }

    @Test
    fun `a change of day is a seam even without a long silence`() {
        assertTrue(TopicSplitter.isSeam(user("2026-10-15 周四 23:55"), user("2026-10-16 周五 00:05")))
    }

    @Test
    fun `two unknown clocks are not a seam`() {
        // Zero means "unknown", and two unknowns must not read as a huge gap.
        assertFalse(TopicSplitter.isSeam(user(""), user("")))
    }

    // ---- the stamp, in both interface languages ----

    @Test
    fun `reads a Chinese stamp`() {
        val t = TopicSplitter.stampOfText("[now 2026-10-15 周四 18:30] 做个番茄钟")
        assertTrue("expected a real instant, got $t", t > 0)
    }

    @Test
    fun `reads an English stamp`() {
        val zh = TopicSplitter.stampOfText("[now 2026-10-15 周四 18:30] x")
        val en = TopicSplitter.stampOfText("[now 2026-10-15 Thu 18:30] x")
        // Same instant either way — this is the whole point of not parsing with
        // one locale's pattern.
        assertEquals(zh, en)
    }

    @Test
    fun `reads the stamp when the language tag follows it`() {
        val plain = TopicSplitter.stampOfText("[now 2026-10-15 Thu 18:30] hi")
        val tagged = TopicSplitter.stampOfText("[now 2026-10-15 Thu 18:30 · ui-language en] hi")
        assertEquals(plain, tagged)
    }

    @Test
    fun `a message with no stamp reads as no time, not as 1970`() {
        assertEquals(0L, TopicSplitter.stampOfText("(the user sent 2 photo(s) with no words)"))
        assertEquals(0L, TopicSplitter.stampOfText(null))
        assertEquals(0L, TopicSplitter.stampOfText(""))
    }

    // ---- dangling calls ----

    @Test
    fun `an answered tool call is not dangling`() {
        val h = listOf(system()) + toolCall()
        assertFalse(TopicSplitter.hasDanglingCall(h, 1, h.size))
    }

    @Test
    fun `an unanswered tool call is`() {
        // The turn was interrupted mid-call. Folding this would bury the one
        // thing the next turn has to see.
        val h = listOf(system()) +
            JSONObject().put("role", "assistant")
                .put("content", JSONObject.NULL)
                .put(
                    "tool_calls",
                    JSONArray().put(
                        JSONObject().put("id", "c9")
                            .put("function", JSONObject().put("name", "show_html").put("arguments", "{}")),
                    ),
                )
        assertTrue(TopicSplitter.hasDanglingCall(h, 1, h.size))
    }

    @Test
    fun `the range given is the range checked`() {
        val h = listOf(system()) + toolCall() + toolCall()
        // Only the first call is inside this window.
        assertFalse(TopicSplitter.hasDanglingCall(h, 1, 3))
    }

    // ---- rendering the text the brain reads back ----

    @Test
    fun `the rendered text is the words, without the stamps`() {
        val h = listOf(system()) + stretch("2026-10-15 周四 10:00", "番茄钟")
        val text = renderTopicText(h, 1, h.size)
        assertTrue(text.contains("帮我番茄钟"))
        assertFalse("a stamp is device chrome, not something anyone said", text.contains("[now "))
        assertFalse(text.contains("you are andee"))
    }

    @Test
    fun `the rendered text leaves out the index sections the device appended`() {
        // Caught on the tablet: get_topic handed back the notebook index as if
        // the user had said it.
        val h = listOf(system()) +
            JSONObject().put("role", "user").put(
                "content",
                "[now 2026-10-16 周五 10:50] 帮我做一个番茄钟的应用\n\n" +
                    "(what your notebook holds, for reference; use recall for detail)\n- 位置：常驻北京",
            )
        val text = renderTopicText(h, 1, h.size)
        assertTrue(text.contains("帮我做一个番茄钟的应用"))
        assertFalse(text.contains("notebook"))
        assertFalse(text.contains("常驻北京"))
    }

    @Test
    fun `tool traffic is noted by name, not pasted in`() {
        val h = listOf(system()) + toolCall("show_html")
        val text = renderTopicText(h, 1, h.size)
        assertTrue(text.contains("show_html"))
        // The result body is device chatter and can be 24 000 characters.
        assertFalse(text.contains("shown"))
    }
}
