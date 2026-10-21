package net.kuafuai.andee.screen

/**
 * The words a tap may use to say *where inside a grid cell* its target is.
 *
 * Lives on its own, with no Android in it, because two very different things
 * read it: [ScreenController] resolves a `part` to a point, and
 * [net.kuafuai.andee.net.ToolSchemas] lists the same words in the tool text the
 * model reads. They must never disagree, so there is one list.
 */
object AimParts {

    /**
     * The cell's subdivisions a tap may name, in reading order: 3 columns by
     * **5 rows**.
     *
     * Both numbers are a measurement, not a taste. On a 1200x2664 screen a
     * cell is 150x222 px, so one *third* of it vertically is 74 px — taller
     * than the things these taps are aimed at. Measured 2026-10-20 in
     * WeChat's 视频号 点赞 list: the 「谢谢」 button is ~100x50 px, its row's
     * name is 200 px above it and holds 20x the tappable area, so a single
     * step of 74 px was enough to miss the button and hit the name — which
     * opens a profile card. A fifth is 44 px, inside the button with room.
     *
     * Columns stay at three: 50 px per step against a target ~100 px wide
     * was never the problem, and going to five columns there would double
     * the vocabulary for nothing.
     */
    const val COLS = 3
    const val ROWS = 5

    /** The middle row of the subdivision, used when `part` is omitted. */
    const val MID_ROW = ROWS / 2

    /** Every subdivision of a cell a tap may name, in reading order. */
    val PARTS = listOf(
        "top-left", "top", "top-right",
        "upper-left", "upper", "upper-right",
        "center-left", "center", "center-right",
        "lower-left", "lower", "lower-right",
        "bottom-left", "bottom", "bottom-right",
    )

    // Aliases are the names a model reaches for when it is not looking at
    // the schema — a Chinese answer says 右下, not bottom-right. The
    // nine-name grid the vocabulary used before still parses: 上/中/下 and
    // their corners map to the outer rows, so an old payload aims where it
    // used to.
    private val CELLS: Map<String, IntArray> = buildMap {
        PARTS.forEachIndexed { i, p -> put(p, intArrayOf(i % COLS, i / COLS)) }
        // Row 0 / middle / last, as the old thirds meant them.
        put("left", intArrayOf(0, MID_ROW)); put("right", intArrayOf(2, MID_ROW))
        listOf("左上", "上", "右上", "左", "中", "右", "左下", "下", "右下")
            .forEachIndexed { i, p ->
                val fy = when (i / 3) {
                    0 -> 0
                    1 -> MID_ROW
                    else -> ROWS - 1
                }
                put(p, intArrayOf(i % 3, fy))
            }
        put("centre", intArrayOf(1, MID_ROW)); put("middle", intArrayOf(1, MID_ROW))
        put("中间", intArrayOf(1, MID_ROW)); put("中心", intArrayOf(1, MID_ROW))
        put("top-center", intArrayOf(1, 0)); put("bottom-center", intArrayOf(1, ROWS - 1))
        put("center-left", intArrayOf(0, MID_ROW)); put("center-right", intArrayOf(2, MID_ROW))
        put("left-top", intArrayOf(0, 0)); put("right-top", intArrayOf(2, 0))
        put("left-bottom", intArrayOf(0, ROWS - 1))
        put("right-bottom", intArrayOf(2, ROWS - 1))
    }

    /** `(column, row)` of a normalised part name, or null if it names nothing. */
    fun cellOf(normalised: String): IntArray? = CELLS[normalised]
}
