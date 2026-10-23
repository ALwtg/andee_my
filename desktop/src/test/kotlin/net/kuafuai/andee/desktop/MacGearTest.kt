package net.kuafuai.andee.desktop

import net.kuafuai.andee.desktop.mac.MacGear
import org.junit.Assert.assertEquals
import org.junit.Test

class MacGearTest {
    // Real mdls output: attributes in mdls's own order, a missing one printed as (null).
    private val sample = """
kMDItemCFBundleIdentifier = "com.google.Chrome"
kMDItemDisplayName        = "Google Chrome.app"
kMDItemLastUsedDate       = 2026-10-23 03:53:52 +0000
kMDItemUseCount           = 7742
kMDItemVersion            = "155.0.8059.85"
kMDItemCFBundleIdentifier = "com.apple.Notes"
kMDItemDisplayName        = "备忘录.app"
kMDItemLastUsedDate       = (null)
kMDItemUseCount           = (null)
kMDItemVersion            = "4.12.6"
kMDItemCFBundleIdentifier = "com.tencent.workbuddy.mac"
kMDItemDisplayName        = "WorkBuddy.app"
kMDItemLastUsedDate       = 2026-10-23 03:57:03 +0000
kMDItemUseCount           = 336
kMDItemUsedDates          = (
    "2026-10-18 16:00:00 +0000",
    "2026-10-19 16:00:00 +0000"
)
kMDItemVersion            = "2.1.0"
""".trimIndent()

    @Test fun aMissingAttributeDoesNotSlideTheOthers() {
        val items = MacGear().parse(sample)
        assertEquals(3, items.size)
        val notes = items.single { it.id == "com.apple.Notes" }
        assertEquals("备忘录", notes.label)
        assertEquals("4.12.6", notes.version)       // would be "" or the next app's if read by position
        assertEquals(0L, notes.lastUsedAt)
        assertEquals(0, notes.uses)
    }

    @Test fun countsAndDatesComeThrough() {
        val chrome = MacGear().parse(sample).single { it.id == "com.google.Chrome" }
        assertEquals(7742, chrome.uses)
        assertEquals("155.0.8059.85", chrome.version)
        assertEquals(true, chrome.lastUsedAt > 0)
    }

    @Test fun aMultiLineAttributeIsNotMistakenForARecord() {
        val wb = MacGear().parse(sample).single { it.id == "com.tencent.workbuddy.mac" }
        assertEquals("2.1.0", wb.version)
        assertEquals(336, wb.uses)
    }
}
