package net.kuafuai.andee.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The background screen's app rules, which are the only thing standing between
 * "drive this app where nobody can see it" and an account that gets flagged.
 *
 * Every case here is a decision a user can make on the card, so the assertions
 * are about *order* as much as about matching: the blacklist beating the
 * whitelist and the whitelist beating the shipped categories is the whole
 * contract.
 */
class SurfacePolicyTest {

    private fun allowed(pkg: String, rules: SurfacePolicy.Rules): Boolean =
        SurfacePolicy.verdict(pkg, rules).allowed

    @Test
    fun `nothing is blocked when no lists and no categories are in force`() {
        val open = SurfacePolicy.Rules.OPEN
        assertTrue(allowed("com.eg.android.alipaygphone", open))
        assertTrue(allowed("com.tencent.mm", open))
    }

    @Test
    fun `a shipped category keeps payment and finance and game and flash-sale apps off`() {
        val rules = SurfacePolicy.Rules()
        for (pkg in listOf(
            "com.eg.android.alipaygphone",
            "com.icbc.mobile",
            "com.tencent.tmgp.pubgm",
            "cn.damai",
        )) {
            assertFalse("$pkg should be blocked by a shipped category", allowed(pkg, rules))
        }
    }

    @Test
    fun `an ordinary app is not caught by a category`() {
        val rules = SurfacePolicy.Rules()
        for (pkg in listOf("com.tencent.mm", "com.sina.weibo", "com.xingin.xhs", "com.taobao.idlefish")) {
            assertTrue("$pkg should be allowed", allowed(pkg, rules))
        }
    }

    @Test
    fun `matching is by package segment, not by string prefix`() {
        assertTrue(SurfacePolicy.matches("com.icbc", "com.icbc"))
        assertTrue(SurfacePolicy.matches("com.icbc.mobile", "com.icbc"))
        assertFalse(SurfacePolicy.matches("com.icbcx.mobile", "com.icbc"))
        // What a person types into a text field, tolerated rather than refused.
        assertTrue(SurfacePolicy.matches("com.icbc.mobile", " com.icbc. "))
        assertTrue(SurfacePolicy.matches("com.icbc.mobile", "com.icbc*"))
    }

    @Test
    fun `the user's whitelist overrides a shipped category`() {
        val rules = SurfacePolicy.Rules(
            allowed = listOf("com.eg.android.alipaygphone"),
        )
        assertTrue(allowed("com.eg.android.alipaygphone", rules))
        // And only that app: the category is still in force for its siblings.
        assertFalse(allowed("com.unionpay", rules))
    }

    @Test
    fun `the user's blacklist beats both the whitelist and the categories`() {
        val rules = SurfacePolicy.Rules(
            blocked = listOf("com.tencent.mm"),
            allowed = listOf("com.tencent.mm"),
            categories = emptySet(),
        )
        assertFalse(allowed("com.tencent.mm", rules))
        assertTrue(SurfacePolicy.verdict("com.tencent.mm", rules).reason.contains("blacklist"))
    }

    @Test
    fun `a disabled category no longer blocks its apps`() {
        val rules = SurfacePolicy.Rules(
            categories = SurfacePolicy.Category.values().toSet() - SurfacePolicy.Category.PAYMENT,
        )
        assertTrue(allowed("com.eg.android.alipaygphone", rules))
        assertFalse(allowed("com.icbc.mobile", rules))
    }

    @Test
    fun `an unnamed package is not a forbidden package`() {
        // status and close calls pass no app; that must not read as "blocked".
        assertTrue(SurfacePolicy.verdict(null, SurfacePolicy.Rules()).allowed)
        assertTrue(SurfacePolicy.verdict("", SurfacePolicy.Rules()).allowed)
    }

    @Test
    fun `a refusal names the rule that fired`() {
        val byCategory = SurfacePolicy.verdict("cn.damai", SurfacePolicy.Rules()).reason
        assertTrue(byCategory.contains("flash_sale"))
        val byList = SurfacePolicy.verdict(
            "com.tencent.mm",
            SurfacePolicy.Rules(blocked = listOf("com.tencent.mm")),
        ).reason
        assertTrue(byList.contains("blacklist"))
    }

    @Test
    fun `parsing a list ignores blanks and comments and keeps order`() {
        val parsed = SurfacePolicy.parse(
            """
            # never in the background
            com.tencent.mm

            com.icbc   # a bank
            """.trimIndent(),
        )
        assertEquals(listOf("com.tencent.mm", "com.icbc"), parsed)
    }
}
