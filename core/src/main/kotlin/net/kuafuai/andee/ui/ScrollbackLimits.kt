package net.kuafuai.andee.ui

/**
 * Two caps that both the scrollback and the tool text quote, so they must be
 * one number. A tool description that promises 60 000 characters while the
 * store cuts at 50 000 would have the model trust a page that was truncated.
 */
object ScrollbackLimits {
    /** Longest folded-topic text `get_topic` returns before cutting. */
    const val TOPIC_TEXT_MAX = 40_000

    /** Longest page source `get_page` returns before cutting. */
    const val ARTIFACT_SOURCE_MAX = 60_000
}
