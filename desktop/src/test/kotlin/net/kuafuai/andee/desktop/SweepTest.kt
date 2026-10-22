package net.kuafuai.andee.desktop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SweepTest {
    private val quiet = 20 * 60_000L
    private val t0 = 1_000_000_000L

    private fun go(
        now: Long = t0 + quiet + 1, activity: Long = t0, last: Long = 0, count: Int = 0, cap: Int = 12,
        enabled: Boolean = true, meeting: Boolean = false,
    ) = Sweep.shouldSweep(now, activity, last, quiet, count, cap, enabled, meeting)

    @Test fun `fires once the conversation has been quiet long enough`() = assertTrue(go())
    @Test fun `waits while it is not quiet yet`() = assertFalse(go(now = t0 + quiet - 1))
    @Test fun `never fires for a user who has not spoken`() = assertFalse(go(activity = 0))

    @Test fun `does not retry the same silence`() {
        // A sweep was attempted after the last activity (and skipped: too few messages).
        assertFalse(go(last = t0 + quiet))
    }

    @Test fun `new activity re-opens it`() = assertTrue(go(activity = t0 + 5 * quiet, now = t0 + 6 * quiet + 1, last = t0 + quiet))
    @Test fun `stops at the daily cap`() = assertFalse(go(count = 12))
    @Test fun `a cap of zero means off`() = assertFalse(go(cap = 0))
    @Test fun `off means off`() = assertFalse(go(enabled = false))
    @Test fun `never during a meeting`() = assertFalse(go(meeting = true))
}
