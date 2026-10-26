package net.kuafuai.andee.config

import org.json.JSONArray
import org.json.JSONObject

/**
 * What is installed on this machine and what each thing is *for* — the brain's answer to "is there already
 * something that does this?" before it sets about doing the thing itself, one tap at a time.
 *
 * Two layers, deliberately kept apart:
 *
 *  * **Facts** — installed, version, last used, how often. Read by the device ([GearScan]); the brain never
 *    guesses these and cannot overwrite them.
 *  * **Meaning** — what it does, what it can do on my behalf, whether it is signed in, any limit. Written by
 *    the brain, once, the first time it actually uses the thing ([noteGear]); facts the device cannot see.
 *    A login is dated, because logins lapse: "signed in" without a date is a claim nobody can age.
 *
 * Sensitive categories (adult content by default) are **kept out of the prompt entirely**: not listed, not
 * counted, not named in "and N more". Hidden means absent from what the model reads, not merely unlabelled.
 * The user can turn a category on or off by asking; the choice is theirs and is stored, not inferred.
 *
 * Stored in the notebook under `gear`, **through the book itself** (see [NotebookBook.section]), so wipe, factory
 * reset and export cover it for free and there is exactly one cache and one writer.
 */
class Gear(private val book: NotebookBook) {

    /** One thing installed. */
    data class Item(
        val id: String,              // bundle id (Mac) / package name (Android) — the stable key
        val label: String,
        val version: String = "",
        val lastUsedAt: Long = 0L,   // device fact; 0 = unknown
        val uses: Int = 0,           // device fact; uses in the recent window when known, else Andee's own count
        val category: String = "",   // see [Categories]; blank = not yet classified
        val does: String = "",       // meaning, written by the brain
        val canDo: String = "",      // what it can do on my behalf
        val signedIn: String = "",   // "yes"/"no"/"" — the brain's observation
        val signedInAt: Long = 0L,   // when it observed that
        val limit: String = "",      // e.g. "free tier: 5 a day"
        val missing: Boolean = false, // was seen before, not on the last scan
        /** The user asked that this one is never mentioned. Finer than a category, and theirs alone. */
        val ignored: Boolean = false,
        /** The meaning was written or edited by the user, so the brain's own notes must not overwrite it. */
        val byUser: Boolean = false,
    )

    private val lock = Any()

    private fun load(): JSONObject = book.section(SECTION)

    private fun save(g: JSONObject) = book.putSection(SECTION, g)

    // ---- facts: the scan ---------------------------------------------------------------------------

    /**
     * Merge a fresh scan in. Facts are replaced; meaning the brain wrote is kept. Anything seen before and
     * absent now is marked missing rather than deleted — an app that was uninstalled and comes back should
     * not lose what was learned about it.
     */
    fun applyScan(scan: List<Item>): ScanDiff = synchronized(lock) {
        val g = load()
        val items = g.optJSONObject("items") ?: JSONObject()
        val seen = HashSet<String>()
        val added = ArrayList<String>()
        for (n in scan) {
            if (n.id.isBlank()) continue
            seen += n.id
            val old = items.optJSONObject(n.id)
            if (old == null) added += n.label
            val merged = (old ?: JSONObject()).apply {
                put("label", n.label)
                put("version", n.version)
                put("last_used_at", n.lastUsedAt)
                put("uses", n.uses)
                if (n.category.isNotBlank() && optString("category").isBlank()) put("category", n.category)
                put("missing", false)
            }
            items.put(n.id, merged)
        }
        val gone = ArrayList<String>()
        for (id in items.keys().asSequence().toList()) {
            if (id !in seen) {
                val o = items.getJSONObject(id)
                if (!o.optBoolean("missing")) gone += o.optString("label", id)
                o.put("missing", true)
            }
        }
        g.put("items", items).put("scanned_at", System.currentTimeMillis())
        save(g)
        ScanDiff(added, gone)
    }

    data class ScanDiff(val added: List<String>, val gone: List<String>)

    // ---- meaning: what the brain learned ------------------------------------------------------------

    /**
     * Record what a thing is for. Only the fields passed are changed; an empty string leaves a field alone,
     * so a later note about the login does not wipe what it does. Returns null when the id is unknown (the
     * brain wrote about something the last scan did not see — better refused than invented).
     */
    fun note(
        id: String, does: String?, canDo: String?, signedIn: String?, limit: String?, category: String?,
    ): Item? = synchronized(lock) {
        val g = load()
        val items = g.optJSONObject("items") ?: return null
        val key = resolve(items, id) ?: return null
        val o = items.getJSONObject(key)
        // What the user wrote is not the brain's to overwrite: a person who corrected a note did it because
        // the brain had it wrong, and the next session's guess must not undo them. Observations (a login, a
        // limit it hit) are still the brain's to record.
        val userOwns = o.optBoolean("by_user")
        if (!does.isNullOrBlank() && !userOwns) o.put("does", does.take(160))
        if (!canDo.isNullOrBlank() && !userOwns) o.put("can_do", canDo.take(200))
        if (!limit.isNullOrBlank()) o.put("limit", limit.take(120))
        if (!category.isNullOrBlank() && category in Categories.ALL) o.put("category", category)
        if (!signedIn.isNullOrBlank() && signedIn in setOf("yes", "no")) {
            o.put("signed_in", signedIn).put("signed_in_at", System.currentTimeMillis())
        }
        save(g.put("items", items))
        toItem(key, o)
    }

    /**
     * The user's own edit, from the settings card. Unlike [note] this always applies and marks the entry as
     * the user's, so the brain's later notes leave the meaning alone. Blank clears the field.
     */
    fun edit(id: String, does: String, canDo: String): Item? = synchronized(lock) {
        val g = load()
        val items = g.optJSONObject("items") ?: return null
        val key = resolve(items, id) ?: return null
        val o = items.getJSONObject(key)
        o.put("does", does.trim().take(160)).put("can_do", canDo.trim().take(200))
        o.put("by_user", does.isNotBlank() || canDo.isNotBlank())
        save(g.put("items", items))
        toItem(key, o)
    }

    /** Never mention this one to the model, whatever its category. Reversible. */
    fun setIgnored(id: String, ignored: Boolean): Boolean = synchronized(lock) {
        val g = load()
        val items = g.optJSONObject("items") ?: return false
        val key = resolve(items, id) ?: return false
        items.getJSONObject(key).put("ignored", ignored)
        save(g.put("items", items))
        true
    }

    /** Forget what was learned about one app (meaning, login, limit); the device's facts stay. */
    fun forget(id: String): Boolean = synchronized(lock) {
        val g = load()
        val items = g.optJSONObject("items") ?: return false
        val key = resolve(items, id) ?: return false
        val o = items.getJSONObject(key)
        for (f in listOf("does", "can_do", "signed_in", "signed_in_at", "limit", "by_user")) o.remove(f)
        save(g.put("items", items))
        true
    }

    /** When the last scan finished, or 0 — so the card can say how fresh the facts are. */
    fun scannedAt(): Long = load().optLong("scanned_at")

    /** Accept an id, or a label the brain copied off the list. */
    private fun resolve(items: JSONObject, ref: String): String? {
        if (items.has(ref)) return ref
        val want = ref.trim().lowercase()
        return items.keys().asSequence().firstOrNull { items.getJSONObject(it).optString("label").lowercase() == want }
    }

    // ---- what the user allows ---------------------------------------------------------------------

    /** Categories the user has switched on or off; absent = the default in [Categories.defaultShown]. */
    fun shown(category: String): Boolean {
        val s = load().optJSONObject("shown")
        return if (s != null && s.has(category)) s.optBoolean(category) else Categories.defaultShown(category)
    }

    fun setShown(category: String, on: Boolean): Boolean = synchronized(lock) {
        if (category !in Categories.ALL) return false
        val g = load()
        val s = g.optJSONObject("shown") ?: JSONObject()
        s.put(category, on)
        save(g.put("shown", s))
        true
    }

    fun visibility(): JSONObject {
        val o = JSONObject()
        for (c in Categories.ALL) o.put(c, shown(c))
        return o
    }

    // ---- reading ----------------------------------------------------------------------------------

    fun all(): List<Item> {
        val items = load().optJSONObject("items") ?: return emptyList()
        return items.keys().asSequence().map { toItem(it, items.getJSONObject(it)) }.toList()
    }

    /** Everything the model may see: installed, and not in a category the user keeps out. */
    fun visible(): List<Item> = all().filter { !it.missing && !it.ignored && shown(effectiveCategory(it)) }

    /**
     * Everything installed, for **the user's own card**: hidden categories and ignored ones included, each
     * flagged, because the person is entitled to see exactly what has been kept out of the model's view and
     * to change it. Never used to build anything the model reads.
     */
    fun forCard(): List<Pair<Item, Boolean>> = all().filter { !it.missing }
        .map { it to (it.ignored || !shown(effectiveCategory(it))) }
        .sortedWith(compareByDescending<Pair<Item, Boolean>> { score(it.first, System.currentTimeMillis()) }.thenBy { it.first.label.lowercase() })

    fun categoryOf(i: Item): String = effectiveCategory(i)

    private fun effectiveCategory(i: Item): String =
        i.category.ifBlank { Categories.guess(i.id, i.label) }

    /**
     * The one-line shelf that rides on the user message, newest-first by how much it is actually used. Empty
     * when there is nothing worth saying, so a fresh install costs nothing.
     *
     * Only things that *do something on the user's behalf* are named: a browser, a chat app, an agent. The
     * rest is a count, and a count that excludes hidden categories, so "and 40 more" cannot be used to infer
     * what was kept out.
     */
    fun indexForPrompt(max: Int = 10): String {
        val vis = visible()
        if (vis.isEmpty()) return ""
        val now = System.currentTimeMillis()
        val ranked = vis.sortedWith(
            compareByDescending<Item> { score(it, now) }.thenBy { it.label.lowercase() },
        )
        val named = ranked.filter { it.does.isNotBlank() || score(it, now) > 0.0 }.take(max)
        if (named.isEmpty()) return ""
        val line = named.joinToString(" · ") { i ->
            val tail = buildList {
                if (i.does.isNotBlank()) add(i.does)
                if (i.signedIn == "yes") add("signed in")
                if (i.signedIn == "no") add("not signed in")
            }.joinToString(", ")
            if (tail.isEmpty()) i.label else "${i.label} ($tail)"
        }
        val more = vis.size - named.size
        return if (more > 0) "$line · …and $more more (list_gear)" else line
    }

    /** Recency-weighted use. A thing used a lot last year ranks below one used a little this week. */
    fun score(i: Item, now: Long): Double {
        val days = if (i.lastUsedAt > 0) ((now - i.lastUsedAt) / 86_400_000.0).coerceAtLeast(0.0) else 365.0
        val recency = 1.0 / (1.0 + days / 7.0)
        return recency * (1.0 + kotlin.math.ln(1.0 + i.uses)) * (if (i.lastUsedAt > 0 || i.uses > 0) 1.0 else 0.0)
    }

    /** What the model asks for when it wants the whole list. Honours the same visibility. */
    fun list(query: String, limit: Int): JSONObject {
        val q = query.trim().lowercase()
        val now = System.currentTimeMillis()
        val rows = visible()
            .filter { q.isEmpty() || it.label.lowercase().contains(q) || it.id.lowercase().contains(q) ||
                it.does.lowercase().contains(q) || it.canDo.lowercase().contains(q) }
            .sortedByDescending { score(it, now) }
            .take(limit.coerceIn(1, 100))
        val arr = JSONArray()
        for (i in rows) {
            arr.put(
                JSONObject().put("id", i.id).put("label", i.label)
                    .apply {
                        if (i.does.isNotBlank()) put("does", i.does)
                        if (i.canDo.isNotBlank()) put("can_do", i.canDo)
                        if (i.signedIn.isNotBlank()) put("signed_in", i.signedIn).put("signed_in_checked", Ages.ago(now - i.signedInAt))
                        if (i.limit.isNotBlank()) put("limit", i.limit)
                        if (i.lastUsedAt > 0) put("last_used", Ages.ago(now - i.lastUsedAt))
                        if (i.uses > 0) put("uses", i.uses)
                        if (i.does.isBlank()) put("unlearned", true)
                    },
            )
        }
        return JSONObject().put("apps", arr).put("count", arr.length())
            .put("note", "Facts (last_used, uses) come from the device. does / can_do / signed_in are what you or an earlier you wrote — " +
                "signed_in_checked says how old that is; a login older than a few days is a guess until you look.")
    }

    // ---- housekeeping -----------------------------------------------------------------------------

    /** The quiet-hour pass: how many items there are with no meaning yet that have actually been used. */
    fun unlearnedUsed(): List<Item> = visible().filter { it.does.isBlank() && (it.uses > 0 || it.lastUsedAt > 0) }

    fun wipe() = synchronized(lock) { book.removeSection(SECTION) }

    private companion object { const val SECTION = "gear" }

    private fun toItem(id: String, o: JSONObject) = Item(
        id = id,
        label = o.optString("label", id),
        version = o.optString("version"),
        lastUsedAt = o.optLong("last_used_at"),
        uses = o.optInt("uses"),
        category = o.optString("category"),
        does = o.optString("does"),
        canDo = o.optString("can_do"),
        signedIn = o.optString("signed_in"),
        signedInAt = o.optLong("signed_in_at"),
        limit = o.optString("limit"),
        missing = o.optBoolean("missing"),
        ignored = o.optBoolean("ignored"),
        byUser = o.optBoolean("by_user"),
    )
}

/**
 * "3 days ago", for facts that have to be aged. English by default because the model reads it; a card for a
 * person passes [zh] so they read their own language.
 */
object Ages {
    fun ago(ms: Long, zh: Boolean = false): String {
        val m = (ms / 60_000).coerceAtLeast(0)
        return if (zh) when {
            m < 2 -> "刚刚"
            m < 90 -> "$m 分钟前"
            m < 36 * 60 -> "${m / 60} 小时前"
            else -> "${m / (24 * 60)} 天前"
        } else when {
            m < 2 -> "just now"
            m < 90 -> "$m min ago"
            m < 36 * 60 -> "${m / 60} h ago"
            else -> "${m / (24 * 60)} days ago"
        }
    }
}
