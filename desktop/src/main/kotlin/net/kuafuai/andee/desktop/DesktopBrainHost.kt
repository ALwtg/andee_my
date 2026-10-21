package net.kuafuai.andee.desktop

import net.kuafuai.andee.brain.BrainHost
import net.kuafuai.andee.brain.BrainText
import net.kuafuai.andee.brain.FoldedTopic
import net.kuafuai.andee.brain.ScrollRow
import net.kuafuai.andee.config.Scene
import net.kuafuai.andee.net.ToolSchemas
import net.kuafuai.andee.net.TurnGuards
import net.kuafuai.andee.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.Properties

/**
 * The Mac's answer to [BrainHost]. Each body capability registers here as a
 * [ToolProvider]; the brain sees exactly the tools some provider handles, and
 * nothing else — a tool listed but not implemented would be a promise the
 * model makes to the user and cannot keep.
 */
class DesktopBrainHost(
    private val providers: List<ToolProvider>,
    private val caps: MacPrompt.Capabilities,
) : BrainHost {

    /** One family of tools the Mac can run. */
    interface ToolProvider {
        fun handles(method: String): Boolean
        fun dispatch(method: String, p: JSONObject, silent: Boolean, untrusted: Boolean): Any?
        /** Tools that exist only here and are not in the shared [ToolSchemas]. */
        fun extraTools(): JSONArray = JSONArray()
        fun methodOfExtra(toolName: String): String? = null
    }

    override fun text(key: BrainText, vararg args: Any): String {
        val pattern = Strings.get(key.name)
        return if (args.isEmpty()) pattern else String.format(locale(), pattern, *args)
    }

    override fun locale(): Locale = Strings.locale()

    override fun logInfo(tag: String, msg: String) = Log.i(tag, msg)
    override fun logWarn(tag: String, msg: String, t: Throwable?) = Log.w(tag, msg, t)
    override fun logError(tag: String, msg: String, t: Throwable?) = Log.e(tag, msg, t)

    override fun maxSteps(): Int = Settings.get("max_steps").toIntOrNull()?.coerceIn(1, 200) ?: 25

    override fun activeSceneName(): String? = Settings.get("scene").ifEmpty { null }
        ?.takeIf { DesktopNotebook.book.scene(it) != null }

    override fun persona(): String = ""

    override fun platformPrompt(): String = MacPrompt.text(caps)

    override fun touchActivity() {
        AppDirs.file("last_activity").writeText(System.currentTimeMillis().toString())
    }

    override fun notebookIndex(): String = DesktopNotebook.book.indexForPrompt()
    override fun scenesIndex(): String = DesktopNotebook.book.scenesForPrompt()
    override fun scene(name: String): Scene? = DesktopNotebook.book.scene(name)

    override fun scrollback(): List<ScrollRow> = Scrollback.snapshot().map {
        ScrollRow(
            isUser = it.role == Scrollback.Role.USER,
            isAssistant = it.role == Scrollback.Role.ASSISTANT,
            text = it.text,
            ts = it.ts,
            topicId = it.topicId,
            handle = it.id,
        )
    }

    override fun pagesIndex(): String = ""
    override fun topicsIndex(): String = Scrollback.topicsIndex()

    override fun foldTopic(title: String, summary: String, rows: List<ScrollRow>, text: String): FoldedTopic? {
        val t = Scrollback.fold(title, summary, rows.mapNotNull { it.handle as? Long }, text) ?: return null
        return FoldedTopic(t.id, t.title, t.summary)
    }

    override fun toolLists(): List<JSONArray> {
        val shared = ToolSchemas.subset { m -> providers.any { it.handles(m) } }
        return listOf(shared) + providers.map { it.extraTools() }.filter { it.length() > 0 }
    }

    override fun methodOf(toolName: String): String? =
        ToolSchemas.methodOf(toolName)?.takeIf { m -> providers.any { it.handles(m) } }
            ?: providers.firstNotNullOfOrNull { it.methodOfExtra(toolName) }

    override fun dispatch(method: String, params: JSONObject, silent: Boolean, untrusted: Boolean): Any? {
        TurnGuards.check(method, silent, untrusted)
        val p = providers.firstOrNull { it.handles(method) }
            ?: throw IllegalArgumentException("unknown method $method")
        return p.dispatch(method, params, silent, untrusted)
    }

    override fun traceDir(): File = AppDirs.root
}

/**
 * User-facing words, from `strings_<lang>.properties` — generated from the
 * phone's `strings.xml`, so both apps say the same thing.
 */
object Strings {
    private val cache = HashMap<String, Properties>()

    fun lang(): String = Settings.get("lang").ifEmpty {
        if (Locale.getDefault().language == "zh") "zh" else "en"
    }

    fun locale(): Locale = if (lang() == "zh") Locale.SIMPLIFIED_CHINESE else Locale.ENGLISH

    fun get(key: String): String {
        val l = lang()
        val p = synchronized(cache) {
            cache.getOrPut(l) {
                Properties().apply {
                    Strings::class.java.getResourceAsStream("/strings_$l.properties")
                        ?.use { load(it.reader(Charsets.UTF_8)) }
                }
            }
        }
        return p.getProperty(key) ?: key
    }
}
