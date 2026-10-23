package net.kuafuai.andee.desktop

/**
 * Ready-made scenes offered on an empty chat — the phone's 情景 bubbles, on the
 * Mac. Picking one is the "yes": it writes the scene into the notebook and then
 * sends 「进入…情景」 as a normal turn, so the model still decides whether to step
 * in. A preset is not a scene until the user picks it.
 */
object ScenePresets {

    data class Preset(val name: String, val title: String, val example: String, val summary: String, val prompt: String)

    val ALL = listOf(
        Preset(
            name = "chat_duty",
            title = "消息值班台",
            example = "帮我盯着通知，要紧的马上告诉我，其余的先别烦我",
            summary = "盯着聊天应用的通知，要紧的立刻告诉你。",
            prompt = "当我处于这个场景中时，我是这个人所有聊天应用通知的值班台。我读的是 get_notifications，它是跨应用的。" +
                "每一轮从最新开始：需要现在处理的是真人的直接提问、@ 提及、带时间的截止事项、少数重要名字的消息；" +
                "可以等的是一般群聊、简报、订阅推送。有重要的就立刻告诉用户，一条一行；其余保持安静。",
        ),
        Preset(
            name = "english",
            title = "陪我练英语",
            example = "陪我练英语，用简单一点的话，错了帮我纠正",
            summary = "用英语对话，纠正我的错误，别用太难的词。",
            prompt = "当我处于这个场景中时，我们用英语对话。我用词要简单，句子要短；用户说错了就温和地纠正，给出正确的说法。" +
                "除非用户切回中文，否则一直用英语。",
        ),
        Preset(
            name = "shopping",
            title = "比价购物",
            example = "帮我在几个购物网站比价，找最划算的",
            summary = "跨网站比价，给出最划算的选择。",
            prompt = "当我处于这个场景中时，用户想买东西。我打开相关的购物网站或搜索结果页，比较价格、运费和优惠，给出最划算的选择。" +
                "下单、付款前必须先问用户确认。",
        ),
    )

    fun byName(name: String): Preset? = ALL.firstOrNull { it.name == name }

    /** Write into the notebook if it is not there already; safe to tap twice. */
    fun adoptIfMissing(name: String): Boolean {
        val p = byName(name) ?: return false
        if (DesktopNotebook.book.scene(p.name) != null) return false
        val english = Strings.lang() == "en"
        DesktopNotebook.book.saveScene(
            name = p.name,
            title = p.title,
            summary = p.summary,
            prompt = p.prompt,
            triggerApps = emptyList(),
        )
        return true
    }
}
