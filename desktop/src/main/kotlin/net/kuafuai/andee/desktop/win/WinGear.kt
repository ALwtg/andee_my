package net.kuafuai.andee.desktop.win

import net.kuafuai.andee.config.Categories
import net.kuafuai.andee.config.Gear
import net.kuafuai.andee.desktop.DesktopNotebook
import net.kuafuai.andee.desktop.GearHand
import net.kuafuai.andee.util.Log
import org.json.JSONObject
import java.io.File

/**
 * The gear shelf on Windows: what is installed, and what each thing is for.
 *
 * **The facts come from the Start Menu**, which is the only place Windows keeps a
 * list of *launchable* things — the registry's Uninstall keys also include
 * runtimes and drivers that nobody would ever ask Andee to open, and `C:\Program
 * Files` is a directory tree, not an inventory. A shortcut is a user-visible
 * promise that this program can be started, which is exactly the question the
 * shelf answers.
 *
 * Two things are deliberately weaker than the Mac's version, and both are said
 * out loud rather than faked:
 *
 *  * **no use counts.** macOS hands over `kMDItemUseCount` in one Spotlight call.
 *    Windows keeps the equivalent in `UserAssist`, which is ROT13-encoded,
 *    binary-blobbed and per-user; parsing it would buy a ranking and cost a
 *    dependency on an undocumented format. So the shelf is alphabetical, and the
 *    model is told the order means nothing.
 *  * **no bundle id.** A `.lnk` has no stable reverse-DNS identity, so the id is
 *    the shortcut's own path — stable across scans, unique, and meaningless to a
 *    human, which is why [Gear.Item.label] exists.
 */
class WinGear : GearHand {

    override val gear: Gear by lazy { Gear(DesktopNotebook.book) }

    override fun handles(method: String) = method in METHODS

    override fun dispatch(method: String, p: JSONObject, silent: Boolean, untrusted: Boolean): Any? =
        when (method) {
            "gear.list" -> {
                ensureScanned()
                gear.list(p.optString("query"), p.optInt("limit", 25))
            }

            "gear.note" -> {
                val id = p.optString("id").trim()
                    .ifEmpty { throw IllegalArgumentException("note_gear needs an id from list_gear") }
                val item = gear.note(
                    id, p.optString("does"), p.optString("can_do"), p.optString("signed_in"),
                    p.optString("limit"), p.optString("category"),
                ) ?: throw IllegalArgumentException(
                    "\"$id\" is not on the shelf. Take the id from list_gear; do not write about " +
                        "something you have not seen listed.",
                )
                JSONObject().put("saved", true).put("id", item.id).put("label", item.label)
            }

            "gear.visibility" -> visibility(p)
            else -> throw IllegalArgumentException("unknown method $method")
        }

    private fun visibility(p: JSONObject): JSONObject {
        val cat = p.optString("category").trim()
        if (cat.isEmpty() || !p.has("show")) return JSONObject().put("visible", gear.visibility())
        require(cat in Categories.ALL) { "category must be one of ${Categories.ALL.joinToString(" / ")}" }
        val on = p.optBoolean("show")
        gear.setShown(cat, on)
        Log.i(TAG, "gear: $cat ${if (on) "shown" else "hidden"} by the user's request")
        return JSONObject().put("category", cat).put("show", on).put("visible", gear.visibility())
    }

    // ---- the scan ---------------------------------------------------------

    @Volatile private var lastScanAt = 0L

    override fun indexForPrompt(): String {
        ensureScanned(block = false)
        val line = gear.indexForPrompt()
        if (line.isNotBlank()) return line
        // An empty index from a shelf that has items: Windows has no bundle ids, so
        // the category filter that drives the Mac's index has nothing to match on.
        // A plain list of names is worth more to the model than a blank line, and it
        // is what the user would have said if asked.
        val names = runCatching { scan().take(40).map { it.label } }.getOrDefault(emptyList())
        return if (names.isEmpty()) "" else "Installed (partial): " + names.joinToString(", ")
    }

    private fun ensureScanned(block: Boolean = true) {
        if (System.currentTimeMillis() - lastScanAt <= SCAN_TTL_MS) return
        // Set first: two callers must not both walk the Start Menu.
        lastScanAt = System.currentTimeMillis()
        if (block) scanNow() else Thread { scanNow() }.apply { isDaemon = true; name = "gear-scan" }.start()
    }

    override fun scanNow() {
        runCatching {
            val items = scan()
            val diff = gear.applyScan(items)
            Log.i(TAG, "gear: scanned ${items.size}, +${diff.added.size} -${diff.gone.size}")
        }.onFailure {
            // `Log.w(tag, msg, throwable)` prints the message but not the cause in a
            // console log; naming both is what turns "gear scan failed" into a fix.
            Log.w(TAG, "gear scan failed: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /**
     * Every `.lnk` under the two Start Menu folders, as shelf items.
     *
     * Both roots on purpose: the user's own menu holds what they installed for
     * themselves, the all-users one holds what the machine came with, and a shelf
     * with only one of those would answer "I could not find WeChat" to half the
     * people who ask.
     */
    private fun scan(): List<Gear.Item> {
        val roots = listOfNotNull(
            System.getenv("ProgramData")?.let { File(it, "Microsoft/Windows/Start Menu/Programs") },
            System.getenv("APPDATA")?.let { File(it, "Microsoft/Windows/Start Menu/Programs") },
        )
        val out = ArrayList<Gear.Item>()
        for (root in roots) {
            if (!root.isDirectory) continue
            root.walkTopDown()
                .onEnter { it.name != "Windows Kits" } // the SDK's own folder is thousands of links
                .filter { it.isFile && it.extension.equals("lnk", ignoreCase = true) }
                .take(MAX_ITEMS)
                .forEach { lnk ->
                    val label = lnk.nameWithoutExtension
                    // Uninstallers, documentation and the administrative toolboxes are
                    // not things a person asks an assistant to open; listing them makes
                    // the shelf longer and the answer worse.
                    if (label.startsWith("Uninstall", true) || label.contains("Readme", true)) return@forEach
                    val folder = lnk.parentFile?.name.orEmpty()
                    if (folder in NOISY_FOLDERS) return@forEach
                    out.add(
                        Gear.Item(
                            id = lnk.absolutePath,
                            label = label,
                            version = "",
                            // No honest use data on Windows; see the class comment.
                            lastUsedAt = 0L,
                            uses = 0,
                        ),
                    )
                }
        }
        return out
    }

    companion object {
        /** Folders whose contents are tools for administrators, not apps to open. */
        private val NOISY_FOLDERS = setOf(
            "Administrative Tools", "Windows Administrative Tools", "System Tools",
            "Windows System", "Startup", "Accessibility", "Windows Kits",
        )

        private const val TAG = "Gear"
        private const val SCAN_TTL_MS = 24L * 60 * 60 * 1000
        private const val MAX_ITEMS = 600
        private val METHODS = setOf("gear.list", "gear.note", "gear.visibility")
    }
}
