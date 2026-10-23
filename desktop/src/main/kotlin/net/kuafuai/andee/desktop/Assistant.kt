package net.kuafuai.andee.desktop

import net.kuafuai.andee.brain.LocalBrain
import net.kuafuai.andee.config.BrainConfig
import net.kuafuai.andee.util.Log
import org.json.JSONObject

/**
 * The running assistant: one brain, the providers it can use, and the
 * settings it was built from. Rebuilt only when *who answers* changes —
 * a different key or endpoint — never for how it is asked (model, thinking),
 * which [LocalBrain.retune] applies with the conversation intact.
 */
object Assistant {

    private const val TAG = "Assistant"

    /** Progress lines and final answers, for the window. Called off the UI thread. */
    @Volatile var onProgress: (String) -> Unit = {}
    @Volatile var onFinal: (String) -> Unit = {}
    @Volatile var onBusy: (Boolean) -> Unit = {}
    @Volatile var onSceneChanged: (String?) -> Unit = {}

    /**
     * Answers are read aloud however the question came in — the phone does the same, and a voice that
     * goes quiet when you start typing reads as broken rather than polite. Quiet is the user's call:
     * the speaker button in the chat header, remembered across launches.
     */
    @Volatile var muted: Boolean = Settings.get("mute") == "1"
        private set
    @Volatile var onMuteChanged: () -> Unit = {}

    fun setMuted(m: Boolean) {
        muted = m
        Settings.save(mapOf("mute" to if (m) "1" else "0"))
        // Muting means now, not after the sentence it was already saying.
        if (m) net.kuafuai.andee.desktop.voice.MacVoice.stopSpeaking()
        onMuteChanged()
    }

    /**
     * Shows a command for approval and returns the button pressed. Set by the
     * window; until it is, everything that needs asking is declined.
     */
    @Volatile var askApproval: (net.kuafuai.andee.desktop.shell.ShellTools.Approval) ->
        net.kuafuai.andee.desktop.shell.ShellTools.Answer = { net.kuafuai.andee.desktop.shell.ShellTools.Answer.DENY }

    /** The terminal hand; one per app so running jobs survive a brain rebuild. */
    val shell: net.kuafuai.andee.desktop.shell.ShellTools by lazy {
        net.kuafuai.andee.desktop.shell.ShellTools(
            ask = { askApproval(it) },
            secrets = { secrets() },
            record = { line -> Scrollback.add(Scrollback.Role.ASSISTANT, line) },
        )
    }

    /** Hides Andee's own windows for the instant of a click; set by the window. */
    @Volatile var hideWindows: (Boolean) -> Unit = {}

    /** Gets the chat window out of the way before a screen tool runs; set by the window. */
    @Volatile var yieldScreen: () -> Unit = {}

    /** The screen hand: accessibility tree, clicks, keys and screenshots. */
    val screen: net.kuafuai.andee.desktop.mac.MacScreen by lazy {
        net.kuafuai.andee.desktop.mac.MacScreen({ hide -> hideWindows(hide) }, { yieldScreen() })
    }

    /** What is installed and what each thing is for: the shelf the brain checks before doing a job by hand. */
    val gear: net.kuafuai.andee.desktop.mac.MacGear by lazy { net.kuafuai.andee.desktop.mac.MacGear() }

    /** Apps, calendar, reminders, contacts, volume, battery and the vault. */
    val device: net.kuafuai.andee.desktop.mac.MacDevice by lazy {
        net.kuafuai.andee.desktop.mac.MacVault.entries()
        net.kuafuai.andee.desktop.mac.MacDevice()
    }

    /** Values that must never reach the model or a log: Andee's own keys, then the vault. */
    private fun secrets(): List<Pair<String, String>> = buildList {
        Settings.get("llm_api_key").takeIf { it.length >= 8 }?.let { add("Andee's model key" to it) }
        Settings.get("api_key").takeIf { it.length >= 8 }?.let { add("Andee's voice key" to it) }
        addAll(VaultSecrets.values())
    }

    private var brain: LocalBrain? = null
    private var builtFor: BrainConfig? = null

    fun config(): BrainConfig {
        val mode = Settings.get("brain").let { if (it == BrainConfig.BRAIN_CODEFLYING) it else BrainConfig.BRAIN_LOCAL }
        val zh = Strings.lang() == "zh"
        return BrainConfig(
            mode = mode,
            apiKey = Settings.get("llm_api_key"),
            baseUrl = Settings.get("llm_base_url").trimEnd('/').ifEmpty {
                if (zh) "https://api.deepseek.com" else "https://api.openai.com/v1"
            },
            model = Settings.get("llm_model").ifEmpty { if (zh) "deepseek-flash" else "gpt-4o-mini" },
            // Unset reads as on, as on the phone (its build default is on); only an explicit "disabled" turns it off.
            thinking = Settings.get("llm_thinking") != "disabled",
            reasoningEffort = Settings.get("llm_reasoning_effort").ifEmpty { "high" },
        )
    }

    private fun providers(): List<DesktopBrainHost.ToolProvider> {
        val notes = NotebookTools { onSceneChanged(it) }
        return listOf(
            UiTools(),
            MeetingTools(),
            object : DesktopBrainHost.ToolProvider {
                override fun handles(method: String) = notes.handles(method)
                override fun dispatch(method: String, p: JSONObject, silent: Boolean, untrusted: Boolean) =
                    notes.dispatch(method, p, silent, untrusted)
            },
            shell,
            screen,
            device,
            gear,
        )
    }

    @Synchronized
    fun ensure(): LocalBrain {
        val cfg = config()
        brain?.let { b ->
            val old = builtFor
            if (old != null && old.sameBackend(cfg)) {
                if (old != cfg) b.retune(cfg)
                builtFor = cfg
                return b
            }
            b.stop()
        }
        Log.i(TAG, "building the brain (${cfg.mode}, ${cfg.model})")
        val b = LocalBrain(
            cfg = cfg,
            host = DesktopBrainHost(providers(), MacPrompt.Capabilities(shell = true, screen = true, voice = true)),
            onProgress = { BallCaption.push(it); onProgress(it) },
            onFinal = { text ->
                val clean = net.kuafuai.andee.brain.SpeechMood.parse(text).text.replace("[END]", "").trim()
                Scrollback.add(Scrollback.Role.ASSISTANT, clean)
                MacBall.onAnswer(text)
                BallCaption.clear()
                onFinal(clean)
                busy(false)
                if (!muted) net.kuafuai.andee.desktop.voice.MacVoice.speak(text)
            },
            onTurnEnd = { busy(false) },
            onSweepSpent = { Sweep.spent(it) },
        )
        brain = b
        builtFor = cfg
        return b
    }

    /**
     * 新对话. Puts the stretch on screen away as a topic (no model call: the title is the
     * first thing the user said, the text is the verbatim exchange) and gives the brain a
     * clean context. Same fold the quiet-hour review makes, started by the user instead.
     * Does nothing to a conversation too short to be worth keeping; never touches a turn
     * that is still running — that one is stopped first, as ■ would.
     */
    @Synchronized
    fun newConversation() {
        if (brain?.isBusy() == true) stop()
        val open = Scrollback.snapshot().filter { it.topicId == null && it.role != Scrollback.Role.ERROR }
        if (open.count { it.role == Scrollback.Role.USER } >= 1 && open.size >= 2) {
            val first = open.first { it.role == Scrollback.Role.USER }.text.replace('\n', ' ').trim()
            val text = open.joinToString("\n\n") { (if (it.role == Scrollback.Role.USER) "User: " else "Andee: ") + it.text }
            Scrollback.fold(
                title = first.take(28) + if (first.length > 28) "…" else "",
                summary = first.take(120),
                members = open.map { it.id },
                text = text,
            )
        }
        brain?.stop()
        brain = null
        builtFor = null
        progressClear()
    }

    private fun progressClear() { BallCaption.clear(); onProgress("") }

    /** The quiet-hour review (also the sidebar's 整理 button). Silent: no answer, no voice, no task light. */
    fun runSweep() {
        if (config().apiKey.isEmpty()) { Log.i(TAG, "sweep skipped: no API key"); return }
        ensure().runSweep()
    }

    /** A turn started or ended: the window's stop button and the ball's working face. */
    private fun busy(b: Boolean) {
        MacBall.onBusy(b)
        if (b) BallCaption.thinking() else BallCaption.clear()
        onBusy(b)
    }

    /** What the user tapped 继续 on and has not yet said what to do with. Cleared by the next words, or by stop. */
    sealed class Pending {
        data class Edit(val page: Pages.Page) : Pending()
        data class Topic(val topic: Scrollback.Topic) : Pending()
    }
    @Volatile var pending: Pending? = null
        private set
    @Volatile var onPendingChanged: () -> Unit = {}

    fun beginEdit(page: Pages.Page) { pending = Pending.Edit(page); onPendingChanged() }
    fun beginTopic(topic: Scrollback.Topic) { pending = Pending.Topic(topic); onPendingChanged() }
    fun cancelPending() { if (pending != null) { pending = null; onPendingChanged() } }

    fun say(text: String, attachments: List<Attachment> = emptyList()) {
        val t = text.trim()
        // A question card is up and the brain is blocked on it: these words are its answer, not a new
        // turn (which would queue behind the very tool call waiting for them). Attachments cannot be
        // an answer, so a message carrying any is still a message.
        if (attachments.isEmpty() && AskGate.tryAnswer(t)) return
        val photos = attachments.filterIsInstance<Attachment.Photo>()
        val files = attachments.filterIsInstance<Attachment.FileRef>()
        // Pictures alone are a complete message ("what is this"); so are files.
        if (t.isEmpty() && attachments.isEmpty()) return
        // A 继续 waiting for its words: the scrollback gets what they said, the brain gets that plus
        // what it is about. The artifact is named here, before the brain can call show_html, so the
        // revision is filed under it without the model having to keep an id straight.
        val p = pending
        pending = null
        if (p != null) onPendingChanged()
        // A new request replaces whatever is running: cut the old turn off before anything else, the way
        // ■ does, so it stops talking and stops driving the screen while the new one starts.
        if (brain?.isBusy() == true || net.kuafuai.andee.desktop.voice.MacVoice.isSpeaking()) interruptForNewRequest()
        val zh = Strings.lang() == "zh"
        val row = when (p) {
            is Pending.Edit -> if (zh) "修改「${p.page.title}」：$t" else "Revise “${p.page.title}”: $t"
            is Pending.Topic -> if (zh) "继续「${p.topic.title}」：$t" else "Continue “${p.topic.title}”: $t"
            null -> t
        }
        // The record says what was sent in words, since a picture has no text to show.
        val zh0 = Strings.lang() == "zh"
        val tag = buildList {
            if (photos.isNotEmpty()) add(if (zh0) "[${photos.size} 张图片]" else "[${photos.size} picture${if (photos.size > 1) "s" else ""}]")
            files.forEach { add("[${it.label}]") }
        }.joinToString(" ")
        Scrollback.add(Scrollback.Role.USER, listOf(tag, row).filter { it.isNotEmpty() }.joinToString(" "))
        busy(true)
        // submit() begins the turn, which clears the revision target; so arm after, from the turn's own start.
        val forBrain = when (p) {
            is Pending.Edit -> { Pages.armNextTurn(p.page.groupId); editQuery(p.page, row) }
            is Pending.Topic -> topicQuery(p.topic, row)
            null -> t
        }
        val withFiles = if (files.isEmpty()) forBrain else forBrain + Attachments.fileNote(files)
        ensure().submit(withFiles, photos.map { it.jpegBase64 })
    }

    private fun editQuery(page: Pages.Page, said: String): String {
        val cap = net.kuafuai.andee.ui.ScrollbackLimits.ARTIFACT_SOURCE_MAX
        val html = page.file?.let { runCatching { it.readText() }.getOrNull() }.orEmpty()
        val cut = html.length > cap
        return buildString {
            append(said)
            append("\n\n(I picked this from the Artifacts panel: it is about the page you made earlier titled \"")
            append(page.title)
            append("\". Apply my change above to that page and show the full revised page with show_html,")
            append(" keeping everything I did not ask to change. Its current source follows")
            if (cut) append(" — truncated at $cap characters; rebuild the missing tail in the same style")
            append(".)\n\n<<<CURRENT PAGE SOURCE>>>\n")
            append(if (cut) html.take(cap) else html)
            append("\n<<<END PAGE SOURCE>>>")
        }
    }

    private fun topicQuery(t: Scrollback.Topic, said: String): String {
        val cap = net.kuafuai.andee.ui.ScrollbackLimits.TOPIC_TEXT_MAX
        val text = Scrollback.topicText(t.id)
        return buildString {
            append(said)
            append("\n\n(I picked this from a finished topic in our conversation, topic #").append(t.id)
            append(" \"").append(t.title).append("\", and want to carry on with it. What it came to: ").append(t.summary)
            if (text == null) {
                append("\nIts full text is no longer stored — work from the summary and say so if a detail is missing.)")
            } else {
                val cut = text.length > cap
                append("\nThe conversation as it happened follows")
                if (cut) append(" — cut at $cap characters, the beginning only")
                append(".)\n\n<<<TOPIC CONVERSATION>>>\n").append(if (cut) text.take(cap) else text)
                append("\n<<<END TOPIC CONVERSATION>>>")
            }
        }
    }

    /** A turn is running, or its answer is being read out: what a tap on the ball should cut short. */
    fun working(): Boolean = brain?.isBusy() == true || net.kuafuai.andee.desktop.voice.MacVoice.isSpeaking()

    /**
     * A new request replaces the running one: the same cut as ■ (the turn, the commands it started, and
     * the voice still reading the old answer). [say] has already taken the 继续 target before this, so the
     * stop clearing [pending] loses nothing.
     */
    private fun interruptForNewRequest() = stop()

    /** The user's stop: the turn ends, and every command it started ends with it. */
    fun stop() {
        cancelPending()
        brain?.cancel()
        shell.stopAll()
        net.kuafuai.andee.desktop.voice.MacVoice.stopSpeaking()
        busy(false)
    }

    /** Notifications the triage judged worth acting on, or telling. */
    private val notifyHost = object : net.kuafuai.andee.desktop.mac.MacNotifications.Host {
        override fun brainConfig() = config()
        override fun langTag() = Strings.lang()
        override fun scene() = Settings.get("scene").ifEmpty { null }?.let { DesktopNotebook.book.scene(it) }
        override fun busy() = brain?.isBusy() == true
        override fun tell(n: JSONObject, line: String) {
            // No turn and no task light: the sentence is already written, nothing is being driven.
            val clean = net.kuafuai.andee.brain.SpeechMood.parse(line).text.trim()
            Scrollback.add(Scrollback.Role.ASSISTANT, clean)
            onFinal(clean)
            if (!muted) net.kuafuai.andee.desktop.voice.MacVoice.speak(line)
        }
        override fun act(text: String) {
            busy(true)
            ensure().notified(text)
        }
    }

    fun start() {
        Sweep.start()
        Meeting.onUserEnded = { payload ->
            // The user ended the recording themselves, so the brain does not have the
            // transcript — ask it for the minutes on a fresh turn (the phone's asr.final path).
            busy(true)
            ensure().submit(meetingMinutesQuery(payload))
        }
        Meeting.onRecordingChanged = { MacBall.onRecording(it) }
        net.kuafuai.andee.desktop.mac.MacNotifications.start(notifyHost)
        DesktopScheduler.onDue = { todo -> wakeFor(todo) }
        DesktopScheduler.catchUp()
    }

    /**
     * A todo came due. Deferred rather than interrupting — a wake-up is the one turn nobody asked for, so
     * it must never cut off something the person is in the middle of:
     *  * a turn running (theirs or another wake-up): it queues behind it inside the brain, and an identical
     *    alarm already queued is not queued twice;
     *  * the answer to them still being read aloud: wait for the voice to finish, so two voices never
     *    overlap;
     *  * a question card waiting on them, or they are typing: wait until that is over, because the wake-up
     *    would otherwise start driving the screen or speaking over it.
     * The wait is bounded, so a card nobody answers cannot hold an alarm back forever — it fires anyway
     * after [WAKE_PATIENCE_MS], when the card has timed out on its own.
     */
    private fun wakeFor(todo: net.kuafuai.andee.config.Todo) {
        Thread {
            val until = System.currentTimeMillis() + WAKE_PATIENCE_MS
            while (System.currentTimeMillis() < until && (net.kuafuai.andee.desktop.voice.MacVoice.isSpeaking() ||
                    AskGate.isWaiting || net.kuafuai.andee.desktop.voice.MacVoice.isListening())) {
                Thread.sleep(500)
            }
            // busy() is the window's "working" light: only turn it on when a turn really starts, not when
            // the wake-up is merely parked behind another one.
            if (brain?.isBusy() != true) busy(true)
            ensure().wake(dueText(todo), untrusted = todo.untrusted)
        }.apply { isDaemon = true; name = "wake-${todo.id}" }.start()
    }

    private const val WAKE_PATIENCE_MS = 150_000L

    private fun dueText(todo: net.kuafuai.andee.config.Todo): String =
        net.kuafuai.andee.brain.WakeText.due(todo.id, todo.what, todo.untrusted)
}
