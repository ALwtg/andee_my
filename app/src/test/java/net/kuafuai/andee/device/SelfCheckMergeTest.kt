package net.kuafuai.andee.device

import net.kuafuai.andee.device.SelfCheck.Finding
import net.kuafuai.andee.device.SelfCheck.Level
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A resumed card re-reads the local half and splices it over what a probe had
 * already found. The splicing rule is small and easy to get subtly wrong in a
 * way that only shows up on a device — a row that quietly reverts to "checking…"
 * forever, or a permission that stays red after the user granted it. So it is
 * pinned down here, away from the View layer it is used from.
 *
 * [SelfCheck.mergeLocal] carries the argument for the rule itself.
 */
class SelfCheckMergeTest {

    /** The ids matter here and nothing else does, so `title`/`detail` are unused. */
    private fun row(id: String, level: Level) = Finding(id = id, title = 0, level = level, detail = 0)

    @Test
    fun `a local re-read keeps the three rows only a probe could answer`() {
        val previous = listOf(
            row("backend", Level.OK),
            row("version", Level.NOTE),
            row("voice_host", Level.FAIL),
        )
        // What a no-probe pass produces for those same three: placeholders.
        val fresh = listOf(
            row("backend", Level.NOTE),
            row("version", Level.NOTE),
            row("voice_host", Level.NOTE),
        )

        val merged = SelfCheck.mergeLocal(fresh, previous)

        assertEquals(Level.OK, merged.first { it.id == "backend" }.level)
        assertEquals(Level.NOTE, merged.first { it.id == "version" }.level)
        assertEquals(Level.FAIL, merged.first { it.id == "voice_host" }.level)
    }

    @Test
    fun `every other row is the fresh reading, which is the whole point`() {
        // The permission rows are what the user went to Settings to change.
        val previous = listOf(row("overlay", Level.FAIL), row("accessibility", Level.FAIL))
        val fresh = listOf(row("overlay", Level.OK), row("accessibility", Level.OK))

        val merged = SelfCheck.mergeLocal(fresh, previous)

        assertEquals(Level.OK, merged.first { it.id == "overlay" }.level)
        assertEquals(Level.OK, merged.first { it.id == "accessibility" }.level)
    }

    @Test
    fun `a first paint has nothing to keep`() {
        val fresh = listOf(row("overlay", Level.OK), row("backend", Level.NOTE))

        assertEquals(fresh, SelfCheck.mergeLocal(fresh, previous = null))
    }

    @Test
    fun `a probed row the last pass never had stays a placeholder`() {
        // Deeper than it looks: the alternative is dropping the row, which would
        // silently shorten the list the first time a probe is added.
        val merged = SelfCheck.mergeLocal(
            fresh = listOf(row("backend", Level.NOTE)),
            previous = listOf(row("overlay", Level.OK)),
        )

        assertEquals(Level.NOTE, merged.first { it.id == "backend" }.level)
    }

    @Test
    fun `the fresh pass owns the shape of the list, not the previous one`() {
        // Ids are the join key, not positions — a pass that reorders or drops
        // rows must not hand the previous pass's rows a new identity.
        val merged = SelfCheck.mergeLocal(
            fresh = listOf(row("backend", Level.NOTE), row("overlay", Level.OK)),
            previous = listOf(row("overlay", Level.FAIL), row("backend", Level.OK)),
        )

        assertEquals(listOf("backend", "overlay"), merged.map { it.id })
        assertEquals(Level.OK, merged.first { it.id == "backend" }.level)
    }
}
