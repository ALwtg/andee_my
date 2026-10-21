package net.kuafuai.andee.config

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A todo set on a notification turn must still be untrusted when it comes due,
 * or the vault guard can be walked around by asking for the thing "later".
 * The flag only survives if it survives the notebook's JSON, which is the part
 * nothing else would catch.
 */
class TodoProvenanceTest {

    private fun todo(untrusted: Boolean) = Notebook.Todo(
        id = "t1",
        what = "fill in the bank password",
        mode = "once",
        at = 1L,
        cronExpr = "",
        nextRunAt = 1L,
        lastRunAt = 0L,
        lastStatus = "",
        lastError = "",
        status = "active",
        createdAt = 0L,
        untrusted = untrusted,
    )

    @Test
    fun `an untrusted todo stays untrusted through storage`() {
        val back = Notebook.toTodo(Notebook.toJson(todo(untrusted = true)))
        assertTrue(back.untrusted)
    }

    @Test
    fun `a trusted todo stays trusted`() {
        assertFalse(Notebook.toTodo(Notebook.toJson(todo(untrusted = false))).untrusted)
    }

    @Test
    fun `rows written before the field existed read as trusted`() {
        val old = Notebook.toJson(todo(untrusted = false)).apply { remove("untrusted") }
        val back = Notebook.toTodo(JSONObject(old.toString()))
        assertFalse(back.untrusted)
        assertEquals("fill in the bank password", back.what)
    }
}
