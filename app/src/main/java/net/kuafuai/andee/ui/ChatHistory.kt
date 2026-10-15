package net.kuafuai.andee.ui

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What has been said and shown on this device, in order, so the card has
 * something to scroll.
 *
 * Deliberately *this* side's record and not the brain's transcript. The brain
 * has the real conversation — tool calls, reasoning, the lot — but it lives
 * behind a socket that drops, and asking it for scrollback would mean the list
 * is empty exactly when the network is the thing that broke. What the device
 * saw with its own eyes and said with its own speaker is always available, and
 * it is also the only part the user actually witnessed, which makes it the part
 * they would be scrolling back to find.
 *
 * Three kinds of entry, because three things happen in front of the user: they
 * speak ([Role.USER]), the assistant answers out loud ([Role.ASSISTANT]), and
 * the assistant draws a page ([Role.PAGE]). Only the third is re-openable, and
 * it is the reason this class owns files at all. [Role.ERROR] is the fourth and
 * differs in kind: it is not something anyone said, it is the device failing,
 * and it is here because the card that announces a failure times out while the
 * question "what went wrong a minute ago" does not.
 *
 * **A page is a *version* of an artifact, not an artifact.** Revising a page
 * used to archive the result as a brand-new row, so 继续修改 twice left three
 * near-identical entries on the shelf and no way to tell which one was current
 * — the user asked for "a new version", not "another page". Rows now carry
 * [Entry.groupId] (which artifact) and [Entry.version] (which revision of it),
 * one page = one artifact in the panel, and the older revisions stay on disk
 * behind it. See [artifacts] / [versionsOf] / [restoreVersion].
 *
 * Singleton with an [init] rather than a constructor-injected dependency,
 * matching [CardUi]: the appenders are scattered across the service, the
 * dispatcher and the hub client, and threading one object through all of them
 * buys nothing when there is exactly one device.
 *
 * Thread-safe. Appends arrive from the ASR callback, the hub receive thread and
 * dispatcher workers; reads come from the UI thread.
 */
object ChatHistory {

    enum class Role { USER, ASSISTANT, PAGE, ERROR }

    /**
     * @param page for [Role.PAGE], the archived HTML — null once it has been
     *   evicted, which is how a row knows to stop offering to re-open itself.
     * @param turn which run of the brain produced this row, named the way
     *   [net.kuafuai.andee.brain.BrainTrace] names it, or null when there is no
     *   log to open — see [addAssistant].
     * @param groupId which artifact this row is a revision of. For the first
     *   version it is the row's own [id]; every later version carries the
     *   first one's, so the rows of one artifact can be gathered back together
     *   (see [artifacts]). Meaningless for the three non-page roles, which
     *   carry their own id and are never grouped.
     * @param version 1-based, within [groupId]. Only ever increases, including
     *   across a [restoreVersion] — going back to an older revision makes a
     *   *new* one, so nothing already written is silently rewritten.
     */
    data class Entry(
        val id: Long,
        val ts: Long,
        val role: Role,
        val text: String,
        val page: File?,
        val turn: String? = null,
        val groupId: Long = id,
        val version: Int = 1,
    )

    /**
     * Two separate caps, because the two costs are unrelated: a line of text is
     * a few hundred bytes and the list is only worth scrolling if it goes back
     * a while, whereas a generated page is routinely 50–100 KB of inlined CSS,
     * JS and base64 images — 100 of them is up to ~10 MB, the price of the 产物
     * panel being a shelf rather than a recent-items strip.
     *
     * A live page does not count against [MAX_ENTRIES]. It used to, and then the
     * page cap was fiction: 80 rows of chatter pushed pages out (file and all)
     * long before [MAX_PAGES] was reached.
     *
     * A page evicted from disk leaves its row behind with `page = null`. The
     * row stays because "you showed me a 贪吃蛇 at 3pm" is still true and still
     * worth scrolling past; it just stops claiming to be re-openable.
     */
    private const val MAX_ENTRIES = 80
    private const val MAX_PAGES = 100

    /**
     * How much of a message is kept.
     *
     * It used to be 400 — "long enough to identify a row at a glance, short
     * enough to stay cheap" — and that was right while a row *was* the message:
     * six lines on screen, and the rest never seen. It stopped being right when
     * a row gained a reader: double-tap opens the message fullscreen
     * ([TextPage]), and a 400-char cap would mean the reader showed the same
     * sentence and a half the row already showed, with the rest gone.
     *
     * So the cap is now about how long an answer can be rather than about how
     * much fits in a bubble. 4000 characters is roughly three screens of
     * Chinese — past what any spoken answer gets to — and it costs 40 KB per
     * row at [MAX_ENTRIES], which is the JSON rewritten on each message.
     */
    private const val MAX_TEXT = 4000

    private const val TAG = "Body"

    private var appContext: Context? = null
    private val lock = Any()
    private val entries = ArrayList<Entry>()
    private var nextId = 1L

    /** Notified on every change; the card re-reads [snapshot] and redraws. */
    @Volatile
    private var listener: (() -> Unit)? = null

    fun init(context: Context) {
        synchronized(lock) {
            if (appContext != null) return
            appContext = context.applicationContext
            load()
        }
        listener?.invoke()
    }

    fun setListener(l: (() -> Unit)?) {
        listener = l
    }

    /** Oldest first — the order the card lays rows out in. */
    fun snapshot(): List<Entry> = synchronized(lock) { ArrayList(entries) }

    /** How many revisions one artifact is allowed to keep. */
    private const val MAX_VERSIONS = 20

    /**
     * Fold a page list down to **one row per artifact**, newest first.
     *
     * Pure, and separated from [artifacts] / [pages] so the rule can be tested
     * without a device: the two callers differ only in what they do about a
     * revision whose file is gone, and having that difference be one argument
     * is what keeps "the panel and the brain agree about what exists" true by
     * construction rather than by review.
     *
     * @param keepMissing when true, an artifact whose every revision has lost
     *   its file still gets a row — its newest revision. The brain's list wants
     *   that (a page that is gone is still a page that was made); the panel
     *   does not (a row that offers to open nothing is worse than an empty
     *   shelf, and [artifacts] shows the newest revision *that is there*).
     */
    internal fun collapseToArtifacts(
        all: List<Entry>,
        keepMissing: Boolean,
    ): List<Entry> = all
        .filter { it.role == Role.PAGE }
        .groupBy { it.groupId }
        .values
        .mapNotNull { revs ->
            revs.filter { it.page != null }.maxByOrNull { it.version }
                ?: if (keepMissing) revs.maxByOrNull { it.version } else null
        }
        .sortedByDescending { it.ts }

    /**
     * The version number a new revision of [groupId] should get: one past the
     * highest already used, or 1 when the artifact is new.
     *
     * Pure for the same reason as [collapseToArtifacts]. Monotonic on purpose —
     * nothing here reuses a number, so [restoreVersion] can file old bytes as a
     * new version and leave the history append-only.
     */
    internal fun nextVersionOf(all: List<Entry>, groupId: Long): Int =
        all.filter { it.role == Role.PAGE && it.groupId == groupId }
            .maxOfOrNull { it.version }?.plus(1) ?: 1

    /**
     * What a `show_html` should be filed under, and the target to carry into
     * the rest of the turn.
     *
     * `explicit` is the model naming a page it wants to revise; `armed` is what
     * the turn has established so far (a `get_page`, or the panel's 继续修改).
     * Explicit wins. **Either way the target comes back cleared**, and that is
     * the load-bearing half: a target is good for *one* page, so a turn that
     * reads a page back and then draws two of them gets a revision and then a
     * new artifact — not two revisions of the same thing.
     *
     * Found the hard way: `get_page` followed by two `show_html` calls left the
     * second, unrelated page numbered 「v5」 of the first, because the target was
     * read without being consumed.
     *
     * @return the group to file under (null = a new artifact), and the target
     *   the caller should keep for the next call in this turn.
     */
    internal fun resolveRevisionTarget(
        explicit: Long?,
        armed: Long?,
    ): Pair<Long?, Long?> = if (explicit != null) explicit to null else armed to null

    /**
     * One row per **artifact**, newest first — what the 产物 panel lists.
     *
     * Not one row per page file: revising a page appends a new revision rather
     * than replacing the old one, so a raw filter would show 番茄钟 three times
     * after two rounds of 继续修改, with nothing saying which is current. This
     * gathers each artifact's revisions and keeps the **newest available** one
     * — available, not merely newest, because the 100-page cap can take a
     * revision's file while an older one survives, and a panel row that offers
     * to open something which is not there is worse than one showing an older
     * revision that is.
     *
     * The row still carries its own [Entry.version], which is what the panel
     * prints as 「v3」 and what [versionsOf] takes to list the rest.
     */
    fun artifacts(): List<Entry> = synchronized(lock) {
        collapseToArtifacts(entries, keepMissing = false)
    }

    /**
     * Every revision of one artifact, **newest version first**, including the
     * ones whose file is gone.
     *
     * For [net.kuafuai.andee.ui.ArtifactsUi]'s version list. Unlike [artifacts]
     * this does not drop a missing file: a version whose bytes are gone is
     * still a fact about the artifact's history, and the list has to be able to
     * say "v2 was here, it is not any more" rather than quietly skip from v3
     * to v1.
     */
    fun versionsOf(groupId: Long): List<Entry> = synchronized(lock) {
        entries.filter { it.role == Role.PAGE && it.groupId == groupId }
            .sortedByDescending { it.version }
    }

    /** The artifact's newest revision that still has bytes, or null if none do. */
    fun currentOf(groupId: Long): Entry? = synchronized(lock) {
        entries.filter { it.role == Role.PAGE && it.groupId == groupId && it.page != null }
            .maxByOrNull { it.version }
    }

    /**
     * Every page ever archived, newest first — **including the rows whose file
     * is gone**.
     *
     * [artifacts] cannot serve the brain: it filters on `page != null`, which is
     * right for a panel that offers to open one and wrong for a question about
     * what was made. A row whose file the 100-page cap has taken still records
     * that the page existed and when — the honest answer to 「上次那个番茄钟」 for
     * a page that is actually gone — and a brain that cannot see the row answers
     * "I never made that", which is a failure the user can check against the
     * panel in front of them.
     *
     * **Collapsed to one row per artifact, the newest available revision** — the
     * same shape [artifacts] gives the panel, for the same reason. The brain
     * asks "what have I made"; twelve rows of one timer's revision history is
     * not an answer to that. A superseded revision is not lost — `get_page`
     * takes any id [versionsOf] would show — but it does not crowd the list.
     */
    fun pages(): List<Entry> = synchronized(lock) {
        collapseToArtifacts(entries, keepMissing = true)
    }

    /**
     * One line per recent artifact for the per-turn user message; empty when
     * there are none.
     *
     * **Why this rides on the user message and not in the system prompt.**
     * Same reason the notebook index does (see [LocalBrain]): `LocalPrompt` is a
     * constant precisely so DeepSeek's prefix cache survives, and the system
     * message is the head of the request — anything injected there invalidates
     * the whole conversation behind it. The user message is the one part of the
     * request that is already new every turn (it carries the clock), so this
     * costs nothing that was not already being paid.
     *
     * **Why an index as well as `list_pages`.** A tool answers a question the
     * model thought to ask. The failure this is here to prevent is the one
     * where it does not think to ask — 「上次那个表格改一下」 drawing a brand new
     * table, because nothing in front of it said the old one still existed. A
     * line in the context is the difference between "there is a tool for that"
     * and knowing the thing is there.
     *
     * One line per artifact, and the version is printed: a model revising
     * 「番茄钟 (v3)」 knows it is working on a page that has already been through
     * three rounds, which is the difference between continuing someone's work
     * and starting over on top of it.
     */
    fun indexForPrompt(max: Int = 5): String {
        val recent = pages().take(max)
        if (recent.isEmpty()) return ""
        // `MM-dd HH:mm` and not `yyyy-MM-dd`: the year is noise on a shelf of
        // things made on this device, and the model reads a month-day fine.
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)
        return recent.joinToString("\n") { e ->
            val bytes = e.page?.length() ?: 0L
            val state = if (bytes > 0) "${(bytes + 1023) / 1024} KB" else "file gone"
            "- #${e.id} ${e.text} (v${e.version} · ${fmt.format(Date(e.ts))} · $state)"
        }
    }

    /**
     * One page by the id [pages] reported, or null when nothing has that id.
     *
     * Filtered on [Role.PAGE] and not merely on the id: ids are shared with the
     * chatter rows, and a caller that read an id off a stale list would
     * otherwise be handed someone's sentence as if it were a page.
     *
     * **Any revision is addressable, not only the current one.** Once a
     * revision has an id the brain may hold it across turns, and the user can
     * ask for "the version before this one" — so an id that names a superseded
     * revision has to keep working rather than answer "no such page".
     */
    fun pageById(id: Long): Entry? = synchronized(lock) {
        entries.firstOrNull { it.id == id && it.role == Role.PAGE }
    }

    /**
     * How much of a page's source is handed to the brain in one go.
     *
     * Two callers, so it lives here rather than at either one: the 继续修改
     * prompt ([net.kuafuai.andee.BodyCoreService.artifactEditQuery], which
     * inlines a page so a revision is a revision) and `ui.get_page` (which
     * reads one back so the brain can revise or answer about it unaided).
     * They must not drift — the same page has to come back the same size
     * whichever door it went out of.
     *
     * A generated page is routinely 10–60 KB of inline CSS and JS, which is
     * nothing to the request; one with a base64 image can be several hundred
     * KB, which is most of a context window for a picture the brain already
     * drew and cannot usefully redraw from text.
     */
    const val ARTIFACT_SOURCE_MAX = 60_000

    /**
     * Delete one artifact — every revision of it — and keep one row behind in
     * its place, the same end state eviction leaves: "you showed me a page at
     * 3pm" stays true after the page is gone.
     *
     * **It takes a group, not a revision.** Deleting from the panel means
     * "get this off my shelf", and leaving v2 and v1 of the thing the user just
     * deleted still sitting in the version list would be the opposite of that.
     * A single revision is not something the panel offers to remove, which is
     * why this reads the group off [id] rather than trusting a caller to pass
     * the right one.
     */
    fun deletePage(id: Long) {
        synchronized(lock) {
            val hit = entries.firstOrNull { it.id == id && it.role == Role.PAGE } ?: return
            val group = hit.groupId
            // Newest first, so the row left standing is the current version —
            // the one whose title and date the user recognises.
            val doomed = entries.filter { it.role == Role.PAGE && it.groupId == group }
                .sortedByDescending { it.version }
            for (e in doomed) {
                e.page?.let { runCatching { it.delete() } }
                entries.remove(e)
            }
            entries.add(hit.copy(page = null, version = hit.version))
            save()
        }
        listener?.invoke()
    }

    /**
     * Delete everything and forget it. For 恢复出厂设置.
     *
     * Two halves, and both are needed: the files on disk, and the list this
     * singleton is holding in memory. Clearing only the files would leave the
     * card still scrolling a conversation whose every row had been deleted —
     * and the next append would write that list back out.
     *
     * The listener is invoked so an open card redraws empty instead of holding
     * rows whose backing pages no longer exist.
     */
    fun wipe(context: Context) {
        synchronized(lock) {
            entries.clear()
            nextId = 1L
        }
        runCatching {
            File(context.filesDir, "chat_history.json").delete()
            File(context.filesDir, "pages").deleteRecursively()
        }.onFailure { Log.w(TAG, "history: wipe failed: ${it.message}") }
        listener?.invoke()
    }

    fun addUser(text: String) = add(Role.USER, text, null)

    /**
     * The assistant's finished answer.
     *
     * @param turn the run of the local brain this came out of, so the row can be
     *   long-pressed into its own log (see [net.kuafuai.andee.ui.TracePage]).
     *   **Null is the normal case, not an oversight.** Most answers in the
     *   scrollback were not produced by a turn at all — a scene being entered, a
     *   notification read out, a line from `ScreenController` — and the hub
     *   brain writes no trace even when it did answer. A row with no [Entry.turn]
     *   simply has no log to offer, and must not pretend otherwise: an entry
     *   point that opens an empty page is worse than no entry point.
     *
     *   The caller reads it from [net.kuafuai.andee.brain.BrainTrace.currentTurn]
     *   *while the turn is still in flight*, which is safe because this is
     *   called from `onFinal` on the loop thread and the next turn cannot have
     *   started.
     */
    fun addAssistant(text: String, turn: String? = null) = add(Role.ASSISTANT, text, null, turn)

    /** Call through [CardUi.error], which de-dups bursts before they get here. */
    fun addError(text: String) = add(Role.ERROR, text, null)

    /**
     * Archive a page the brain just composed and record it as a revision of an
     * artifact.
     *
     * Returns the archived file for the caller to hand to [HtmlActivity], so
     * the copy being displayed and the copy in the history are the same bytes
     * in the same place — an earlier split (cache dir for display, files dir
     * for the archive) meant re-opening from history could show something
     * subtly different from what was on screen a minute ago.
     *
     * **@param groupId which artifact this is a revision of.** Omitted (or
     *   null) means a brand-new artifact: the row becomes its own group and
     *   starts at v1. Pass an existing artifact's group — the panel's 继续修改
     *   does, and so does `ui.get_page`'s follow-up `show_html` — and this
     *   lands as its next revision instead of a near-identical neighbour on the
     *   shelf. Callers get the group from [Entry.groupId].
     *
     * Falls back to null on IO failure; the caller still has the HTML string
     * and can stage it itself, it just won't be in the list.
     */
    fun addPage(title: String, html: String, groupId: Long? = null): File? {
        val ctx = appContext ?: return null
        val (id, group, version) = synchronized(lock) {
            val g = groupId ?: nextId
            Triple(nextId, g, nextVersionOf(entries, g))
        }
        val file = runCatching {
            val dir = File(ctx.filesDir, "pages").apply { mkdirs() }
            File(dir, "page_$id.html").also { it.writeText(html) }
        }.getOrElse {
            Log.w(TAG, "history: could not archive page: ${it.message}")
            return null
        }
        add(Role.PAGE, title, file, groupId = group, version = version)
        return file
    }

    /**
     * Make an older revision current again — **as a new revision**, not by
     * rewriting the old one.
     *
     * Nothing already written is touched: the returned entry is the previous
     * version's bytes filed as the next version number, so the artifact's
     * history stays append-only and 撤销 is just another 回退. That is what
     * makes this safe to expose on a tap — a rollback that edited history in
     * place would leave no way back to the thing being rolled back from.
     *
     * Returns the new revision's file, or null when [id] names nothing, is not
     * a page, or has no bytes left to copy.
     */
    fun restoreVersion(id: Long): File? {
        val ctx = appContext ?: return null
        val source = synchronized(lock) { entries.firstOrNull { it.id == id && it.role == Role.PAGE } }
            ?: return null
        val bytes = source.page ?: return null
        val html = runCatching { bytes.readText() }.getOrNull() ?: return null
        return addPage(source.text, html, groupId = source.groupId)
    }

    private fun add(
        role: Role,
        text: String,
        page: File?,
        turn: String? = null,
        groupId: Long? = null,
        version: Int = 1,
    ) {
        if (appContext == null) return
        val clean = text.trim().let {
            if (it.length > MAX_TEXT) it.take(MAX_TEXT) + "…" else it
        }
        if (clean.isEmpty()) return
        synchronized(lock) {
            val id = nextId++
            entries.add(
                Entry(
                    id = id,
                    ts = System.currentTimeMillis(),
                    role = role,
                    text = clean,
                    page = page,
                    turn = turn,
                    // A non-page row is never grouped, so it is its own group:
                    // that keeps `groupId` a total function over the list rather
                    // than a field that is meaningful for one role and garbage
                    // for the other three.
                    groupId = groupId ?: id,
                    version = version,
                ),
            )
            trim()
            save()
        }
        listener?.invoke()
    }

    /** Caller holds [lock]. */
    private fun trim() {
        // Versions of one artifact first: a page revised twenty times should
        // not be able to push every other artifact off the shelf, and the
        // oldest revisions are the ones nobody is coming back for. Oldest
        // first, so the current version and the one before it always survive.
        val overVersions = entries.filter { it.role == Role.PAGE }
            .groupBy { it.groupId }
            .flatMap { (_, revs) ->
                revs.sortedBy { it.version }.dropLast(MAX_VERSIONS)
            }
        for (old in overVersions) {
            val i = entries.indexOfFirst { it.id == old.id }
            if (i < 0) continue
            old.page?.let { runCatching { it.delete() } }
            entries.removeAt(i)
        }
        // Pages next, so one that just lost its file is an ordinary row below.
        var pages = 0
        for (i in entries.indices.reversed()) {
            val e = entries[i]
            if (e.page == null) continue
            pages++
            if (pages > MAX_PAGES) {
                runCatching { e.page.delete() }
                entries[i] = e.copy(page = null)
            }
        }
        var over = entries.count { it.page == null } - MAX_ENTRIES
        val it = entries.iterator()
        while (over > 0 && it.hasNext()) {
            if (it.next().page == null) {
                it.remove()
                over--
            }
        }
    }

    // ---- Persistence ----
    //
    // A plain JSON file rather than SharedPreferences: this is an append-only
    // log with a per-row shape, and VoiceConfig's prefs file is the *settings*,
    // which are hand-edited and whitelisted (see its ALLOWED_KEYS). Rewriting
    // the whole log on every append is fine at 180 rows and saves having to
    // reason about partial writes.

    private fun file(): File? = appContext?.let { File(it.filesDir, "chat_history.json") }

    /** Caller holds [lock]. */
    private fun save() {
        val f = file() ?: return
        runCatching {
            val arr = JSONArray()
            for (e in entries) {
                arr.put(
                    JSONObject()
                        .put("id", e.id)
                        .put("ts", e.ts)
                        .put("role", e.role.name)
                        .put("text", e.text)
                        .put("page", e.page?.name ?: JSONObject.NULL)
                        .put("turn", e.turn ?: JSONObject.NULL)
                        .put("group", e.groupId)
                        .put("version", e.version)
                )
            }
            f.writeText(arr.toString())
        }.onFailure { Log.w(TAG, "history: save failed: ${it.message}") }
    }

    /** Caller holds [lock]. */
    private fun load() {
        val f = file() ?: return
        if (!f.exists()) return
        runCatching {
            val dir = File(appContext!!.filesDir, "pages")
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val role = runCatching { Role.valueOf(o.optString("role")) }.getOrNull() ?: continue
                // Re-checked against the filesystem rather than trusted: the
                // log and the pages directory are written separately and the
                // process can die between them, and a row that offers to open
                // a file that isn't there is worse than one that doesn't.
                val page = o.optString("page").takeIf { it.isNotEmpty() && it != "null" }
                    ?.let { File(dir, it) }?.takeIf { it.exists() }
                // Same treatment as `page`: a row written before this field
                // existed, or by a build that never had a turn for it, reads as
                // absent rather than as a turn named "null".
                val turn = o.optString("turn").takeIf { it.isNotEmpty() && it != "null" }
                val id = o.optLong("id")
                // Rows written before versions existed have no `group`, and
                // reading them as absent must not regroup them under id 0 —
                // that would collapse every pre-existing page into one artifact
                // the first time the panel was opened. Absent means "its own
                // group", which is exactly what those rows are: one page each,
                // v1, because nothing could revise them back then.
                val group = if (o.has("group")) o.optLong("group") else id
                val version = if (o.has("version")) o.optInt("version", 1) else 1
                entries.add(
                    Entry(id, o.optLong("ts"), role, o.optString("text"), page, turn, group, version),
                )
                if (id >= nextId) nextId = id + 1
            }
        }.onFailure {
            Log.w(TAG, "history: load failed, starting empty: ${it.message}")
            entries.clear()
        }
    }
}
