package net.kuafuai.andee.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GearTest {
    private class MemStore : NotebookStore {
        var s: String? = null
        override fun read() = s
        override fun write(plain: String): Boolean { s = plain; return true }
        override fun wipe() { s = null }
    }

    private fun gear() = Gear(NotebookBook(MemStore()))
    private fun item(id: String, label: String, last: Long = 0, uses: Int = 0) =
        Gear.Item(id = id, label = label, lastUsedAt = last, uses = uses)
    private val now get() = System.currentTimeMillis()

    @Test fun adultIsGuessedFromTheNameAndHiddenByDefault() {
        val g = gear()
        g.applyScan(listOf(item("com.x.pornhub", "Pornhub", now, 50), item("com.google.chrome", "Chrome", now, 80)))
        val ids = g.visible().map { it.id }
        assertFalse("adult must not be visible", "com.x.pornhub" in ids)
        assertTrue("com.google.chrome" in ids)
    }

    @Test fun hiddenMeansAbsentFromTheIndexAndFromTheCount() {
        val g = gear()
        g.applyScan(listOf(item("com.x.pornhub", "Pornhub", now, 50), item("a", "Alpha", now, 5), item("b", "Beta", now, 4)))
        g.note("a", "does alpha things", null, null, null, null)
        val idx = g.indexForPrompt(max = 1)
        assertFalse(idx.contains("Pornhub", ignoreCase = true))
        // "and N more" counts what is visible only: Beta, not Beta plus the hidden one.
        assertTrue(idx, idx.contains("…and 1 more"))
    }

    @Test fun theUserCanTurnAHiddenCategoryOnAndOffAgain() {
        val g = gear()
        g.applyScan(listOf(item("com.x.pornhub", "Pornhub", now, 50)))
        assertTrue(g.visible().isEmpty())
        g.setShown(Categories.ADULT, true)
        assertEquals(1, g.visible().size)
        g.setShown(Categories.ADULT, false)
        assertTrue(g.visible().isEmpty())
    }

    @Test fun anExplicitCategoryOnTheItemBeatsTheGuess() {
        val g = gear()
        g.applyScan(listOf(item("com.example.reader", "Reader", now, 9)))
        g.note("com.example.reader", null, null, null, null, Categories.ADULT)
        assertTrue(g.visible().isEmpty())
    }

    @Test fun aRescanKeepsWhatTheBrainLearnedAndMarksMissingNotDeleted() {
        val g = gear()
        g.applyScan(listOf(item("a", "Alpha", now, 3)))
        g.note("a", "does alpha", "can alpha", "yes", null, null)
        val diff = g.applyScan(emptyList())
        assertEquals(listOf("Alpha"), diff.gone)
        assertTrue(g.visible().isEmpty())
        g.applyScan(listOf(item("a", "Alpha", now, 4)))
        assertEquals("does alpha", g.visible().single().does)
    }

    @Test fun aLoginIsDatedAndOnlyYesOrNoIsAccepted() {
        val g = gear()
        g.applyScan(listOf(item("a", "Alpha", now, 3)))
        g.note("a", null, null, "maybe", null, null)
        assertEquals("", g.all().single().signedIn)
        g.note("a", null, null, "yes", null, null)
        val i = g.all().single()
        assertEquals("yes", i.signedIn)
        assertTrue(i.signedInAt > 0)
    }

    @Test fun anUnknownIdIsRefusedNotInvented() {
        val g = gear()
        g.applyScan(listOf(item("a", "Alpha")))
        assertNull(g.note("nope", "x", null, null, null, null))
    }

    @Test fun aLabelIsAcceptedWhereAnIdIsExpected() {
        val g = gear()
        g.applyScan(listOf(item("com.x.a", "Alpha")))
        assertEquals("does it", g.note("alpha", "does it", null, null, null, null)?.does)
    }

    @Test fun recentUseOutranksOldHeavyUse() {
        val g = gear()
        val day = 86_400_000L
        g.applyScan(listOf(item("old", "Old", now - 300 * day, 500), item("new", "New", now, 3)))
        g.note("old", "o", null, null, null, null); g.note("new", "n", null, null, null, null)
        assertTrue(g.indexForPrompt().startsWith("New"))
    }

    @Test fun whatTheUserWroteIsNotOverwrittenByTheBrain() {
        val g = gear()
        g.applyScan(listOf(item("a", "Alpha", now, 3)))
        g.note("a", "brain guess", "brain can", null, null, null)
        g.edit("a", "my description", "my can-do")
        g.note("a", "brain guess again", "brain can again", "yes", "5 a day", null)
        val i = g.all().single()
        assertEquals("my description", i.does)
        assertEquals("my can-do", i.canDo)
        // But an observation is still the brain's to record.
        assertEquals("yes", i.signedIn)
        assertEquals("5 a day", i.limit)
    }

    @Test fun anIgnoredAppIsNeverMentionedAndComesBack() {
        val g = gear()
        g.applyScan(listOf(item("a", "Alpha", now, 9), item("b", "Beta", now, 8)))
        g.setIgnored("a", true)
        assertEquals(listOf("Beta"), g.visible().map { it.label })
        assertFalse(g.indexForPrompt().contains("Alpha"))
        g.setIgnored("a", false)
        assertEquals(2, g.visible().size)
    }

    @Test fun theCardShowsEverythingTheModelDoesNotAndFlagsIt() {
        val g = gear()
        g.applyScan(listOf(item("com.x.pornhub", "Pornhub", now, 50), item("a", "Alpha", now, 5)))
        val card = g.forCard()
        assertEquals(2, card.size)
        assertEquals(true, card.single { it.first.id == "com.x.pornhub" }.second)
        assertEquals(false, card.single { it.first.id == "a" }.second)
    }

    @Test fun forgettingClearsMeaningButKeepsTheDevicesFacts() {
        val g = gear()
        g.applyScan(listOf(item("a", "Alpha", now, 7)))
        g.note("a", "does it", "can it", "yes", "lim", null)
        g.forget("a")
        val i = g.all().single()
        assertEquals("", i.does); assertEquals("", i.signedIn); assertEquals("", i.limit)
        assertEquals(7, i.uses)
    }

    @Test fun clearingBothFieldsReturnsTheEntryToTheBrain() {
        val g = gear()
        g.applyScan(listOf(item("a", "Alpha", now, 3)))
        g.edit("a", "mine", "mine")
        g.edit("a", "", "")
        g.note("a", "brain now", null, null, null, null)
        assertEquals("brain now", g.all().single().does)
    }

    @Test fun nothingToSayCostsNothing() {
        assertEquals("", gear().indexForPrompt())
        val g = gear(); g.applyScan(listOf(item("a", "Alpha")))
        assertEquals("a thing never used and never learned is not worth a line", "", g.indexForPrompt())
    }

    @Test fun listHonoursTheSameVisibility() {
        val g = gear()
        g.applyScan(listOf(item("com.x.pornhub", "Pornhub", now, 50), item("a", "Alpha", now, 5)))
        val labels = g.list("", 50).getJSONArray("apps").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("label") } }
        assertEquals(listOf("Alpha"), labels)
        assertEquals(0, g.list("porn", 50).getJSONArray("apps").length())
    }

    @Test fun financeAndHealthAreGuessedButShownUntilTheUserHidesThem() {
        assertEquals(Categories.FINANCE, Categories.guess("com.eg.android.AlipayGphone", "支付宝"))
        assertEquals(Categories.HEALTH, Categories.guess("x", "平安好医生"))
        val g = gear()
        g.applyScan(listOf(item("com.eg.android.AlipayGphone", "支付宝", now, 9)))
        assertEquals(1, g.visible().size)
        g.setShown(Categories.FINANCE, false)
        assertEquals(0, g.visible().size)
    }
}

class GearSharesTheBookTest {
    private class MemStore : NotebookStore {
        var s: String? = null
        override fun read() = s
        override fun write(plain: String): Boolean { s = plain; return true }
        override fun wipe() { s = null }
    }

    @Test fun savingGearDoesNotEraseMemoriesAndTheOtherWayRound() {
        val book = NotebookBook(MemStore())
        val g = Gear(book)
        book.remember("口味", "不吃香菜", "不吃香菜", "user")
        g.applyScan(listOf(Gear.Item(id = "a", label = "Alpha", uses = 3, lastUsedAt = System.currentTimeMillis())))
        book.remember("住址", "在杭州", "在杭州", "user")
        g.note("a", "does alpha", null, null, null, null)
        assertEquals(2, book.memories().size)
        assertEquals("does alpha", g.all().single().does)
    }

    @Test fun wipingTheBookTakesGearWithIt() {
        val book = NotebookBook(MemStore())
        val g = Gear(book)
        g.applyScan(listOf(Gear.Item(id = "a", label = "Alpha")))
        book.wipe()
        assertTrue(g.all().isEmpty())
    }
}
