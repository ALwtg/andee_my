package net.kuafuai.andee.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * The artifact-versioning rules, tested without a device.
 *
 * This is the half of 产物 versioning that has no UI to sanity-check it by eye:
 * which rows the panel shows, which the brain is told about, and what number a
 * revision gets. Getting any of them wrong is invisible until a user has
 * revised a page twice and finds three of them on the shelf — the exact bug
 * these rules exist to prevent.
 *
 * [ChatHistory.collapseToArtifacts] / [ChatHistory.nextVersionOf] are pure and
 * `internal` for this reason; everything else in the object needs a Context.
 */
class ChatHistoryVersionsTest {

    private fun page(
        id: Long,
        group: Long,
        version: Int,
        hasFile: Boolean = true,
        ts: Long = id,
        title: String = "番茄钟",
    ) = ChatHistory.Entry(
        id = id,
        ts = ts,
        role = ChatHistory.Role.PAGE,
        text = title,
        page = if (hasFile) File("/tmp/page_$id.html") else null,
        turn = null,
        groupId = group,
        version = version,
    )

    private fun chatter(id: Long) = ChatHistory.Entry(
        id = id,
        ts = id,
        role = ChatHistory.Role.USER,
        text = "做个番茄钟",
        page = null,
        turn = null,
        groupId = id,
        version = 1,
    )

    // ---- collapseToArtifacts ----

    @Test
    fun `three revisions collapse to one row, the newest`() {
        val all = listOf(
            page(1, group = 1, version = 1),
            page(2, group = 1, version = 2),
            page(3, group = 1, version = 3),
        )
        val out = ChatHistory.collapseToArtifacts(all, keepMissing = false)
        assertEquals(1, out.size)
        assertEquals(3L, out[0].id)
        assertEquals(3, out[0].version)
    }

    @Test
    fun `separate artifacts stay separate`() {
        val all = listOf(
            page(1, group = 1, version = 1),
            page(2, group = 1, version = 2),
            page(3, group = 3, version = 1, ts = 99),
        )
        val out = ChatHistory.collapseToArtifacts(all, keepMissing = false)
        assertEquals(2, out.size)
        // Newest first by the surviving revision's timestamp.
        assertEquals(3L, out[0].id)
        assertEquals(2L, out[1].id)
    }

    @Test
    fun `newest first ordering`() {
        val all = listOf(
            page(1, group = 1, version = 1, ts = 100),
            page(2, group = 2, version = 1, ts = 300),
            page(3, group = 3, version = 1, ts = 200),
        )
        val out = ChatHistory.collapseToArtifacts(all, keepMissing = false)
        assertEquals(listOf(2L, 3L, 1L), out.map { it.id })
    }

    @Test
    fun `non-page rows are ignored`() {
        val all = listOf(chatter(9), page(1, group = 1, version = 1))
        val out = ChatHistory.collapseToArtifacts(all, keepMissing = false)
        assertEquals(1, out.size)
        assertEquals(1L, out[0].id)
    }

    @Test
    fun `a revision whose file is gone falls back to an older one that survives`() {
        // The 100-page cap took v3's file. The panel must still offer
        // *something* openable rather than a row that opens nothing.
        val all = listOf(
            page(1, group = 1, version = 1),
            page(2, group = 1, version = 2),
            page(3, group = 1, version = 3, hasFile = false),
        )
        val panel = ChatHistory.collapseToArtifacts(all, keepMissing = false)
        assertEquals(2L, panel[0].id)
        assertEquals(2, panel[0].version)
    }

    @Test
    fun `the brain still sees an artifact whose every file is gone`() {
        // Same input, keepMissing: a page that was made and then lost is still
        // a page that was made — "I never made that" is the failure here.
        val all = listOf(
            page(1, group = 1, version = 1, hasFile = false),
            page(2, group = 1, version = 2, hasFile = false),
        )
        val panel = ChatHistory.collapseToArtifacts(all, keepMissing = false)
        assertEquals(0, panel.size)

        val brain = ChatHistory.collapseToArtifacts(all, keepMissing = true)
        assertEquals(1, brain.size)
        assertEquals(2, brain[0].version)
    }

    @Test
    fun `empty input stays empty`() {
        assertEquals(0, ChatHistory.collapseToArtifacts(emptyList(), keepMissing = true).size)
        assertEquals(0, ChatHistory.collapseToArtifacts(emptyList(), keepMissing = false).size)
    }

    // ---- nextVersionOf ----

    @Test
    fun `a new artifact starts at version one`() {
        assertEquals(1, ChatHistory.nextVersionOf(emptyList(), groupId = 7))
    }

    @Test
    fun `the next revision is one past the highest, not the newest by id`() {
        // v2 was evicted, v1 and v3 remain: the next one is v4, and it must not
        // collide with the v3 that is still on the shelf.
        val all = listOf(
            page(1, group = 1, version = 1),
            page(3, group = 1, version = 3),
        )
        assertEquals(4, ChatHistory.nextVersionOf(all, groupId = 1))
    }

    @Test
    fun `another artifact's versions do not leak in`() {
        val all = listOf(
            page(1, group = 1, version = 1),
            page(2, group = 2, version = 9),
        )
        assertEquals(2, ChatHistory.nextVersionOf(all, groupId = 1))
    }

    @Test
    fun `nextVersionOf ignores a missing file`() {
        // A revision whose bytes are gone still occupies its number — reusing
        // it would make two different pages both claim to be v2.
        val all = listOf(page(1, group = 1, version = 1, hasFile = false))
        assertEquals(2, ChatHistory.nextVersionOf(all, groupId = 1))
    }

    // ---- the two lists agree about what exists ----

    @Test
    fun `panel and brain never disagree about a page that is still there`() {
        val all = listOf(
            page(1, group = 1, version = 1),
            page(2, group = 1, version = 2),
            page(3, group = 3, version = 1, ts = 500),
            chatter(4),
        )
        val panel = ChatHistory.collapseToArtifacts(all, keepMissing = false)
        val brain = ChatHistory.collapseToArtifacts(all, keepMissing = true)
        // Everything the panel shows, the brain shows too — under the same id.
        val brainIds = brain.map { it.id }.toSet()
        for (p in panel) assertEquals(true, p.id in brainIds)
    }

    @Test
    fun `a default-constructed entry is its own artifact at v1`() {
        // The shape `add()` builds for a page with no group given: a brand-new
        // artifact. If this ever drifts, every fresh page would join group 0.
        val e = ChatHistory.Entry(
            id = 42, ts = 0, role = ChatHistory.Role.PAGE, text = "x", page = null,
        )
        assertEquals(42L, e.groupId)
        assertEquals(1, e.version)
        assertNull(e.turn)
    }

    // ---- resolveRevisionTarget: the consume-once rule ----

    @Test
    fun `an armed target files the next page as that revision`() {
        val (group, rest) = ChatHistory.resolveRevisionTarget(explicit = null, armed = 7L)
        assertEquals(7L, group)
        // And it is spent: the next show_html in the same turn is a new artifact.
        assertNull(rest)
    }

    @Test
    fun `the second show_html in a turn does not join the first`() {
        // The regression: get_page(3) then two show_html calls must give a
        // revision AND a new artifact — not two revisions of the same page.
        var target: Long? = 3L
        val (first, r1) = ChatHistory.resolveRevisionTarget(explicit = null, armed = target)
        target = r1
        val (second, r2) = ChatHistory.resolveRevisionTarget(explicit = null, armed = target)
        assertEquals(3L, first)
        assertNull(second)
        assertNull(r2)
    }

    @Test
    fun `an explicit revise outranks an armed target`() {
        val (group, rest) = ChatHistory.resolveRevisionTarget(explicit = 9L, armed = 3L)
        assertEquals(9L, group)
        assertNull(rest)
    }

    @Test
    fun `with nothing armed or named, a page is a new artifact`() {
        val (group, rest) = ChatHistory.resolveRevisionTarget(explicit = null, armed = null)
        assertNull(group)
        assertNull(rest)
    }

    @Test
    fun `a revise that names nothing falls back to the armed target`() {
        // Callers resolve `revise` against the page list before getting here, so
        // an id that names nothing arrives as null — and the turn's own context
        // should still win rather than the page becoming a stray new artifact.
        val (group, _) = ChatHistory.resolveRevisionTarget(explicit = null, armed = 5L)
        assertEquals(5L, group)
    }
}
