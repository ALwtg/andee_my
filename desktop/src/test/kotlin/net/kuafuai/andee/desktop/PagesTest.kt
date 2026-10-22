package net.kuafuai.andee.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The artifact-shelf invariants, the ones the phone hit with `ChatHistoryVersionsTest`:
 * one artifact = one group, versions only ever go up, and the revision target is
 * consumed exactly once — a turn that reads a page back and then draws two of
 * them gets a revision and then a new artifact, not two revisions of the first.
 */
class PagesTest {

    private fun fresh(): File = File.createTempFile("pages", "").also { it.delete(); it.mkdirs() }

    @Test
    fun `a new page is version 1 and its own group`() {
        Pages.resetForTest(fresh())
        val a = Pages.add("a", "<html>", null)!!
        assertEquals(1, a.version)
        assertEquals(a.id, a.groupId)
    }

    @Test
    fun `a revision gets the next version, not a new artifact`() {
        Pages.resetForTest(fresh())
        val a = Pages.add("a", "<html>", null)!!
        val a2 = Pages.add("a", "<html>", a.groupId)!!
        assertEquals(a.groupId, a2.groupId)
        assertEquals(2, a2.version)
        assertEquals(1, Pages.pages().size)   // collapsed to one artifact
        assertEquals(2, Pages.versionsOf(a.groupId).size)
    }

    @Test
    fun `the revision target is consumed once`() {
        Pages.resetForTest(fresh())
        val a = Pages.add("a", "<html>", null)!!
        Pages.armGroup(a.groupId)
        assertEquals(a.groupId, Pages.armOrResolve(null))   // consumed by the first draw
        assertNull(Pages.armOrResolve(null))                 // the next draw is a new artifact
    }

    @Test
    fun `an explicit revise wins and clears the armed target`() {
        Pages.resetForTest(fresh())
        val a = Pages.add("a", "<html>", null)!!
        val b = Pages.add("b", "<html>", null)!!
        Pages.armGroup(a.groupId)
        assertEquals(b.groupId, Pages.armOrResolve(b.groupId))
        assertNull(Pages.armOrResolve(null))                 // armed was cleared, not just overridden
    }

    @Test
    fun `the armed target is cleared at the turn boundary`() {
        Pages.resetForTest(fresh())
        val a = Pages.add("a", "<html>", null)!!
        Pages.armGroup(a.groupId)
        Pages.beginTurn()
        assertNull(Pages.armOrResolve(null))
    }

    @Test
    fun `rollback files an old revision as the next version`() {
        Pages.resetForTest(fresh())
        val a = Pages.add("a", "v1", null)!!
        val a2 = Pages.add("a", "v2", a.groupId)!!
        val back = Pages.restoreVersion(a.id)!!
        assertEquals(a.groupId, back.groupId)
        assertEquals(3, back.version)          // append-only, not v1 again
        assertTrue(Pages.versionsOf(a.groupId).any { it.id == a2.id })
    }

    @Test
    fun `an artifact with twenty revisions keeps the newest and drops the oldest file`() {
        Pages.resetForTest(fresh())
        var g: Long? = null
        for (i in 1..25) { val p = Pages.add("a", "<html>$i", g); g = p!!.groupId }
        val revs = Pages.versionsOf(g!!)
        assertTrue(revs.size <= 20)
        assertNotNull(revs.firstOrNull { it.version == 25 })
    }
}
