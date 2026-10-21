package net.kuafuai.andee.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The notebook's rules with the storage swapped for memory — the same code the
 * phone and the desktop both run.
 */
class NotebookBookTest {

    private class MemStore : NotebookStore {
        var blob: String? = null
        var writes = 0
        override fun read() = blob
        override fun write(plain: String): Boolean { blob = plain; writes++; return true }
        override fun wipe() { blob = null }
    }

    private fun fresh() = MemStore().let { it to NotebookBook(it) }

    @Test
    fun `remember then recall by keyword`() {
        val (_, b) = fresh()
        b.remember("fruit", "likes mango", "", "user")
        b.remember("route", "settings then display", "", "way")
        assertEquals(listOf("fruit"), b.recall("mango", "", 10).map { it.name })
        assertEquals(2, b.memories().size)
    }

    @Test
    fun `overwriting by name keeps the old version in history`() {
        val (_, b) = fresh()
        b.remember("fruit", "likes mango", "", "user")
        val (m, overwrote) = b.remember("Fruit", "likes durian now", "", "user")
        assertTrue(overwrote)
        assertEquals(1, b.memories().size)
        assertTrue(m.history.single().endsWith("likes mango"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an unknown type is refused`() {
        fresh().second.remember("x", "y", "", "gossip")
    }

    @Test
    fun `forget deletes and reports whether it found the entry`() {
        val (_, b) = fresh()
        b.remember("fruit", "likes mango", "", "user")
        assertTrue(b.forget("FRUIT"))
        assertFalse(b.forget("fruit"))
        assertTrue(b.memories().isEmpty())
    }

    @Test
    fun `the book survives a reload from the store`() {
        val (store, b) = fresh()
        b.remember("fruit", "likes mango", "", "user")
        b.saveScene("english", "English practice", "speak English", "Only English.", emptyList())
        val reloaded = NotebookBook(store)
        assertEquals("likes mango", reloaded.memories().single().description)
        assertEquals("English practice", reloaded.scene("english")?.title)
    }

    @Test
    fun `todos keep their provenance through the store`() {
        val (store, b) = fresh()
        val t = Todo("t1", "pay", "once", 1L, "", 1L, 0L, "", "", "active", 0L, untrusted = true)
        b.addTodo(t)
        assertTrue(NotebookBook(store).todoById("t1")!!.untrusted)
        b.patchTodo("t1", status = "done")
        assertEquals(0, b.todos("active").size)
        assertNull(b.patchTodo("nope", status = "done"))
    }

    @Test
    fun `a write the store refuses fails loudly and leaves nothing behind`() {
        val store = object : NotebookStore {
            override fun read(): String? = null
            override fun write(plain: String) = false
            override fun wipe() {}
        }
        val b = NotebookBook(store)
        val failed = runCatching { b.remember("fruit", "likes mango", "", "user") }.isFailure
        assertTrue("remember must throw so the model is not told it saved", failed)
        assertTrue(b.memories().isEmpty())
    }

    @Test
    fun `wipe forgets the cached copy too`() {
        val (_, b) = fresh()
        b.remember("fruit", "likes mango", "", "user")
        b.wipe()
        assertTrue(b.memories().isEmpty())
    }
}
