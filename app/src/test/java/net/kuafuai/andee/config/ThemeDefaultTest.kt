package net.kuafuai.andee.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The theme default, which is a one-line rule with three ways to get it wrong.
 *
 * The default is **light**, and the test is written against the non-default value
 * on purpose: only the string the picker actually wrote may turn night on. That
 * shape is what makes "change the default" a one-line edit that cannot overrule a
 * user who chose otherwise, and it is the property worth pinning down — a rewrite
 * to `saved != "night"` would look equivalent and would flip every device that has
 * never opened the setting.
 *
 * `nightFromSaved` exists as a separate function precisely so this can run without
 * a device. There is no Robolectric in this project, and the alternative — testing
 * `nightTheme(Context)` — needs one.
 */
class ThemeDefaultTest {

    @Test
    fun `a device that has never chosen comes up light`() {
        // The absent key: a fresh install, and every device updating from a build
        // that predates the setting.
        assertFalse(VoiceConfig.nightFromSaved(""))
    }

    @Test
    fun `an explicit night is kept`() {
        assertTrue(VoiceConfig.nightFromSaved("night"))
    }

    @Test
    fun `an explicit day is kept`() {
        // Not the default any more, but still a real choice a user can have made
        // on a device that has since been updated.
        assertFalse(VoiceConfig.nightFromSaved("day"))
    }

    @Test
    fun `case and padding do not decide the theme`() {
        assertTrue(VoiceConfig.nightFromSaved("  NIGHT  "))
        assertTrue(VoiceConfig.nightFromSaved("Night"))
        assertFalse(VoiceConfig.nightFromSaved(" DAY "))
    }

    @Test
    fun `an unrecognised value falls back to light rather than to night`() {
        // A key written by hand, or by a build that used a different vocabulary.
        // Reading it as night would be a value nobody chose, on the default a
        // user never saw.
        assertFalse(VoiceConfig.nightFromSaved("dark"))
        assertFalse(VoiceConfig.nightFromSaved("n"))
        assertFalse(VoiceConfig.nightFromSaved("1"))
    }

    @Test
    fun `the two strings the picker writes are the two that mean something`() {
        // `Theme.DAY` / `Theme.NIGHT` are what `Theme.set` puts on disk; this is
        // the other end of that wire, so the pair is asserted together rather
        // than one at a time.
        assertFalse(VoiceConfig.nightFromSaved("day"))
        assertTrue(VoiceConfig.nightFromSaved("night"))
    }
}
