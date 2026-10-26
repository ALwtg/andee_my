package net.kuafuai.andee.desktop.shell

import net.kuafuai.andee.desktop.shell.RiskClassifier.Tier
import net.kuafuai.andee.desktop.shell.ShellPolicy.Decision
import net.kuafuai.andee.desktop.shell.ShellPolicy.Mode
import net.kuafuai.andee.desktop.shell.ShellPolicy.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellPolicyTest {

    private fun d(tier: Tier, turn: Turn = Turn.USER, mode: Mode = Mode.ASK, always: Boolean = false, confirm: Boolean = true) =
        ShellPolicy.decide(tier, turn, mode, always, confirm)

    @Test
    fun `a stranger's message can never run a command, not even in full-open`() {
        for (m in Mode.values()) for (t in Tier.values()) assertTrue(d(t, Turn.NOTIFY, m) is Decision.Refuse)
        for (m in Mode.values()) assertTrue(d(Tier.SAFE, Turn.SILENT, m) is Decision.Refuse)
    }

    @Test
    fun `a todo someone else set may only read`() {
        assertEquals(Decision.Run, d(Tier.SAFE, Turn.WAKE_OTHER, Mode.FULL))
        assertTrue(d(Tier.LOW, Turn.WAKE_OTHER, Mode.FULL) is Decision.Refuse)
    }

    @Test
    fun `the user's own todo may write only in full-open`() {
        assertTrue(d(Tier.LOW, Turn.WAKE_OWN, Mode.AUTO) is Decision.Refuse)
        assertEquals(Decision.Run, d(Tier.LOW, Turn.WAKE_OWN, Mode.FULL))
    }

    @Test
    fun `ask mode asks for anything not read-only, and always-allow skips only low and medium`() {
        assertEquals(Decision.Run, d(Tier.SAFE))
        assertEquals(Decision.Ask(true, false), d(Tier.LOW))
        assertEquals(Decision.Run, d(Tier.MEDIUM, always = true))
        assertEquals(Decision.Ask(false, false), d(Tier.HIGH, always = true))
        assertEquals(Decision.Ask(false, true), d(Tier.CRITICAL, always = true))
    }

    @Test
    fun `auto runs low and medium but still asks for high and critical`() {
        assertEquals(Decision.Run, d(Tier.MEDIUM, mode = Mode.AUTO))
        assertEquals(Decision.Ask(false, false), d(Tier.HIGH, mode = Mode.AUTO))
    }

    @Test
    fun `read-only refuses anything that is not safe, without asking`() {
        assertTrue(d(Tier.LOW, mode = Mode.READ_ONLY) is Decision.Refuse)
        assertEquals(Decision.Run, d(Tier.SAFE, mode = Mode.READ_ONLY))
    }

    @Test
    fun `full-open runs everything but keeps the critical double-check unless turned off`() {
        assertEquals(Decision.Run, d(Tier.HIGH, mode = Mode.FULL))
        assertEquals(Decision.Ask(false, true), d(Tier.CRITICAL, mode = Mode.FULL))
        assertEquals(Decision.Run, d(Tier.CRITICAL, mode = Mode.FULL, confirm = false))
    }

    @Test
    fun `always-allow is keyed by command and first word, never across a pipe or a dangerous flag`() {
        assertEquals("git push", ShellPolicy.prefixOf("git push origin feat"))
        assertEquals("npm test", ShellPolicy.prefixOf("npm test -- --watch"))
        assertEquals("ls", ShellPolicy.prefixOf("ls ./src"))
    }
}
