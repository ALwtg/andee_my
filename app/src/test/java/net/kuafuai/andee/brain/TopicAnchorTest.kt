package net.kuafuai.andee.brain

import net.kuafuai.andee.ui.ChatHistory
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Matching a brain message to the device row it corresponds to.
 *
 * The two records of one conversation do **not** share an id, a length, or a
 * clock: the brain's history carries tool traffic the device never saw, and its
 * timestamp rides in the message text as `[now …]` to the minute while a row
 * carries milliseconds. The first version of the fold matched the two on
 * timestamps and silently found nothing — the fold reported success and no
 * conversation was ever put away.
 *
 * These tests pin down the anchor that does work: **the words**.
 */
class TopicAnchorTest {

    /**
     * The real implementation, not a copy. It lives in [TopicSplitter] as a
     * public pure function for exactly this reason — the assertions below are
     * about the production behaviour, and a duplicated version would pass while
     * the shipped one drifted.
     */
    private fun anchor(raw: String?): String = TopicSplitter.anchorText(raw)

    /** What a device row holds, for contrast: the sentence and nothing else. */
    private fun deviceRowText(said: String) = said

    @Test
    fun `a plain user message reduces to the sentence`() {
        assertEquals("帮我做个番茄钟", anchor("[now 2026-10-15 周四 18:54] 帮我做个番茄钟"))
    }

    @Test
    fun `it matches what the device row holds`() {
        // The property the fold depends on: strip the chrome and the two records
        // agree on the text.
        val said = "帮我做个番茄钟"
        assertEquals(deviceRowText(said), anchor("[now 2026-10-15 周四 18:54] $said"))
    }

    @Test
    fun `index sections are not part of the sentence`() {
        val brain = "[now 2026-10-15 周四 18:54] 改成深蓝色\n\n" +
            "(what your notebook holds, for reference; use recall for detail)\n- 咖啡：不加糖"
        assertEquals("改成深蓝色", anchor(brain))
    }

    @Test
    fun `all four index sections are cut, not just the first`() {
        val brain = "[now 2026-10-15 周四 18:54] 再加个铃声\n\n" +
            "(scenes you can enter — see §11)\n- x\n\n" +
            "(pages you have made on this device — §7)\n- #1 y\n\n" +
            "(conversations you have finished and put away)\n- #2 z"
        assertEquals("再加个铃声", anchor(brain))
    }

    @Test
    fun `the language tag does not leak into the anchor`() {
        val withTag = anchor("[now 2026-10-15 周四 18:54 · ui-language zh] 好了谢谢")
        val without = anchor("[now 2026-10-15 周四 18:54] 好了谢谢")
        assertEquals(without, withTag)
    }

    @Test
    fun `a message with no stamp still yields its sentence`() {
        assertEquals("你好，能听见吗？", anchor("你好，能听见吗？"))
    }

    @Test
    fun `an assistant message is its own answer`() {
        // Assistant rows carry no `[now …]`; the device row holds the same text.
        assertEquals("好的。", anchor("好的。"))
    }

    @Test
    fun `tool traffic has no words and is therefore skippable`() {
        // A tool message carries a JSON object as its content, not a String —
        // the fold must step over these rather than anchor on them.
        val toolMsg = JSONObject().put("role", "tool")
            .put("content", JSONObject().put("shown", true))
        assertEquals("", TopicSplitter.anchorText(toolMsg))
    }

    // ---- locating a stretch among the device's rows ----

    private val MIN = 60_000L

    /** A row as the matcher sees it. `at` is minutes from an arbitrary epoch. */
    private fun user(text: String, at: Long) = TopicSplitter.RowKey(true, text, at * MIN)
    private fun bot(text: String, at: Long) = TopicSplitter.RowKey(false, text, at * MIN)

    /**
     * The shape the bug lived in: the stretch's last words are 「好的。」, which
     * the assistant also says at the end of every later stretch. Anchoring on the
     * last spoken line matched the *final* 「好的。」 in the whole conversation and
     * folded the live rows into the topic.
     */
    @Test
    fun `a stretch ending in a common reply does not swallow the later ones`() {
        val rows = listOf(
            user("帮我做个番茄钟", 0), bot("好的。", 1),
            user("改成深蓝色", 2), bot("好的。", 3),
            // — a long gap: the next stretch —
            user("帮我比比三款扫地机器人", 60), bot("好的。", 61),
            user("还有别的吗", 62), bot("好的。", 63),
        )
        val range = TopicSplitter.locateRows(
            rows,
            firstSaid = "帮我做个番茄钟", firstAt = 0,
            nextSaid = "帮我比比三款扫地机器人", nextAt = 60 * MIN,
        )
        assertEquals(0..3, range)
    }

    @Test
    fun `tool traffic and replies between the two anchors belong to the stretch`() {
        val rows = listOf(
            user("a 做个页面", 0), bot("我看一下", 0), bot("好了", 1), user("b 加个按钮", 2), bot("加好了", 3),
            user("c 新话题", 90),
        )
        assertEquals(0..4, TopicSplitter.locateRows(rows, "a 做个页面", 0, "c 新话题", 90 * MIN))
    }

    @Test
    fun `a repeated opener is told apart by the clock`() {
        // 「继续」 opens both stretches. The one nearest the stamp is the one meant.
        val rows = listOf(
            user("继续", 0), bot("好", 1), user("再来", 2),
            user("继续", 120), bot("好", 121), user("下一个话题", 240),
        )
        val range = TopicSplitter.locateRows(rows, "继续", 120 * MIN, "下一个话题", 240 * MIN)
        assertEquals(3..4, range)
    }

    @Test
    fun `a row within the tolerance still matches a minute-floored stamp`() {
        // The stamp is floored to the minute and taken after the row was written.
        val rows = listOf(user("开始", 10), bot("好", 11), user("结束", 100))
        assertEquals(
            0..1,
            TopicSplitter.locateRows(rows, "开始", 10 * MIN - 30_000, "结束", 100 * MIN - 30_000),
        )
    }

    @Test
    fun `a clock too far from every candidate is no match`() {
        val rows = listOf(user("开始", 0), bot("好", 1), user("结束", 100))
        assertNull(TopicSplitter.locateRows(rows, "开始", 50 * MIN, "结束", 100 * MIN))
    }

    @Test
    fun `without a clock only a unique match is accepted`() {
        val unique = listOf(user("开始", 0), bot("好", 1), user("结束", 100))
        assertEquals(0..1, TopicSplitter.locateRows(unique, "开始", 0, "结束", 0))
        val ambiguous = listOf(user("开始", 0), user("开始", 5), bot("好", 6), user("结束", 100))
        assertNull(TopicSplitter.locateRows(ambiguous, "开始", 0, "结束", 100 * MIN))
    }

    @Test
    fun `an assistant row is never mistaken for an opener`() {
        // Same words, wrong speaker: only a user row can open a stretch.
        val rows = listOf(bot("开始", 0), user("别的", 1), user("结束", 100))
        assertNull(TopicSplitter.locateRows(rows, "开始", 0, "结束", 100 * MIN))
    }

    @Test
    fun `nothing found, or nothing after, is null rather than a guess`() {
        val rows = listOf(user("开始", 0), bot("好", 1))
        assertNull(TopicSplitter.locateRows(rows, "没说过", 0, "结束", 100 * MIN))
        assertNull(TopicSplitter.locateRows(rows, "开始", 0, "结束", 100 * MIN))
        assertNull(TopicSplitter.locateRows(rows, "", 0, "结束", 100 * MIN))
    }

    @Test
    fun `a truncated row still matches the head of the sentence`() {
        val said = "帮我做一个可以倒计时二十五分钟并且到点会响铃的番茄钟应用"
        val rows = listOf(user(said, 0), bot("好", 1), user("下一个", 100))
        assertEquals(0..1, TopicSplitter.locateRows(rows, said, 0, "下一个", 100 * MIN))
    }
}
