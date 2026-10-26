package net.kuafuai.andee.net

/**
 * Which apps may be driven on the **background screen** — the rules, with no
 * Android in them.
 *
 * The background screen is the one surface the user cannot see while it is
 * being used, so the question "may I touch this app there?" is not the model's
 * to answer and not a prompt's to request. It is this object's, and it is
 * deliberately plain data: the user's two lists, a set of shipped defaults, and
 * one pure function. That is what makes it testable on a desktop JVM
 * (`SurfacePolicyTest`) and what keeps the answer identical on every body.
 *
 * ## The order, which is the whole design
 *
 * 1. **The user's blacklist wins.** It is checked first and nothing overrides
 *    it — not a category, not a whitelist entry. A person who wrote down "never
 *    this app" has already made the decision the rest of this file exists to
 *    make.
 * 2. **Then the user's whitelist**, which overrides the shipped defaults. The
 *    defaults are opinions about categories (banks, games, flash sales); the
 *    whitelist is a fact about one package on this device, so it wins.
 * 3. **Then the defaults.** Shipped, explained, and off by nothing but the
 *    whitelist above.
 *
 * ## What the defaults are, and are not
 *
 * They are **not** a security boundary — an app can be installed under a name
 * nobody listed, and a category is a guess about a package's purpose. They are
 * the honest version of "we know these four kinds of app punish automation":
 * the risk-control engines of payment, banking, game and flash-sale apps look
 * for exactly the signals a background screen produces. The device says so
 * instead of trying to be undetectable, and the user can overrule it one package
 * at a time.
 *
 * Matching is by **prefix on package segments**: `com.icbc` matches
 * `com.icbc` and `com.icbc.mobile`, never `com.icbcx`. Case is folded, because
 * package names are lower-case by convention but a user typing a list by hand
 * is not.
 */
object SurfacePolicy {

    /** A kind of app whose risk control reacts to automated operation. */
    enum class Category(val key: String, val prefixes: List<String>) {
        FINANCE(
            "finance",
            listOf(
                "com.icbc", "com.ccb", "com.abchina", "com.bankcomm", "com.cmbchina",
                "cmb.pb", "com.chinamworld", "com.bankofbeijing", "com.spdbccc",
                "com.pingan.paces", "com.ecitic", "com.citic", "com.boc", "com.cebbank",
            ),
        ),
        PAYMENT(
            "payment",
            listOf(
                "com.eg.android.alipaygphone", "com.tenpay.android", "com.unionpay",
                "com.yeepay", "com.chase", "com.paypal",
            ),
        ),
        GAME(
            "game",
            listOf(
                "com.tencent.tmgp", "com.netease", "com.mihoyo", "com.epicgames",
                "com.supercell", "com.roblox", "com.garena", "com.ea.game", "com.kiloo",
            ),
        ),
        FLASH_SALE(
            "flash_sale",
            listOf(
                "com.taobao.taobao", "com.jingdong.app.mall", "com.xunmeng.pinduoduo",
                "cn.damai", "com.damai",
            ),
        ),
    }

    /**
     * The lists, as the user wrote them.
     *
     * @param blocked packages or segment prefixes the user forbade outright.
     * @param allowed packages or segment prefixes the user permitted, overriding
     *   the shipped [categories].
     * @param categories which shipped defaults are in force. All of them by
     *   default; a user who does their banking through a whitelisted app does
     *   not need to disable the category.
     */
    data class Rules(
        val blocked: List<String> = emptyList(),
        val allowed: List<String> = emptyList(),
        val categories: Set<Category> = Category.values().toSet(),
    ) {
        companion object {
            /** No restrictions at all — what a turn with no policy handed to it gets. */
            val OPEN = Rules(categories = emptySet())
        }
    }

    /**
     * @param allowed true when the app may be driven on the background screen.
     * @param reason one English sentence, written for the model, naming the rule
     *   that decided it. It is a tool result, so it stays in the model's
     *   language, like the rest of `CommandDispatcher`.
     */
    data class Verdict(val allowed: Boolean, val reason: String)

    /** Whether [pkg] may be driven in the background under [rules]. */
    fun verdict(pkg: String?, rules: Rules): Verdict {
        val name = pkg?.trim()?.lowercase().orEmpty()
        if (name.isEmpty()) {
            // No package named is not a package that is forbidden: the callers
            // that pass null are asking about the surface itself (status, close),
            // not about an app.
            return Verdict(true, "no app named")
        }
        rules.blocked.firstOrNull { matches(name, it) }?.let {
            return Verdict(
                false,
                "$name is on the user's background blacklist (\"$it\"). Do not open it on the " +
                    "background screen; ask them whether they want it there, or do it with them " +
                    "watching.",
            )
        }
        rules.allowed.firstOrNull { matches(name, it) }?.let {
            return Verdict(true, "$name is on the user's background whitelist (\"$it\")")
        }
        for (c in rules.categories) {
            if (c.prefixes.any { matches(name, it) }) {
                return Verdict(
                    false,
                    "$name looks like a ${c.key} app, and those are kept off the background screen: " +
                        "their risk control reacts to exactly what a hidden screen looks like, and a " +
                        "flagged account is not worth it. Do it with the user watching, or ask them " +
                        "to whitelist it in ⚙ → 屏幕与后台 if they accept that.",
                )
            }
        }
        return Verdict(true, "no rule blocks $name")
    }

    /**
     * One package or prefix, matched the way this file documents.
     *
     * `entry` may arrive with a trailing dot, a `*`, or spaces from a text field;
     * all three are tolerated rather than refused, because a list the user typed
     * is not a programming language and a silently ignored line is the kind of
     * failure this project keeps writing notes about.
     */
    fun matches(pkg: String, entry: String): Boolean {
        val e = entry.trim().lowercase().removeSuffix("*").removeSuffix(".").removeSuffix("/")
        if (e.isEmpty()) return false
        val p = pkg.trim().lowercase()
        return p == e || p.startsWith("$e.")
    }

    /**
     * The user's list as entries: newline- or comma-separated (a text row on the
     * settings card holds one line, a file or a message holds several, and both
     * are legitimate ways to write it), `#` starts a comment, blanks dropped,
     * duplicates removed in first-seen order.
     *
     * Both lists go through here, so they cannot disagree about what an empty
     * entry means.
     */
    fun parse(text: String?): List<String> =
        text.orEmpty()
            .split('\n', ',', '，', ';')
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
}
