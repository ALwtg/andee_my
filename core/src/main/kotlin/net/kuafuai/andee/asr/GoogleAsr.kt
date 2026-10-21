package net.kuafuai.andee.asr

import net.kuafuai.andee.util.Log
import net.kuafuai.andee.config.VoiceSettings
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Google speech recognition, over the **Gemini Live API** — a WebSocket that
 * takes audio in and hands text back while the user is still talking.
 *
 * **Why a socket, when the rest of the Google path here is plain HTTP.** The
 * only models Google serves for transcription are the `*-transcribe-live-*`
 * ones, and they exist on the Live API and nowhere else — the same id on
 * `generateContent` is a 400. Using a real transcription model and not using a
 * WebSocket are therefore not both on offer, and this build takes the model: a
 * general Gemini would have to be *instructed* to transcribe verbatim and
 * could decide to paraphrase, on a transcript the meeting recorder writes to
 * disk as the record of what was said.
 *
 * **The shape of the conversation is the reference gateway's**, and three
 * parts of it are things it measured rather than guessed:
 *
 *  * **The model must be named by its full resource path.** A bare name is
 *    read as a project id → `1008 Permission denied on resource project …`;
 *    `models/x` is a 1007. Hence [VoiceSettings.googleProject] and
 *    [VoiceSettings.googleLocation].
 *  * **`responseModalities` must be `TEXT`.** A transcription model wants the
 *    opposite of a talking one, and getting it backwards drops the connection.
 *  * **`languageCodes` is sent as an explicit empty array**, not omitted, when
 *    no language is set. Omitting the field is not stating a preference;
 *    an empty array is. The two look identical until the upstream changes.
 *
 * **Two results per utterance, and only one of them ends the turn.** The
 * upstream finals *at every pause* — "查一下……杭州的天气" arrives in two
 * pieces — so its finals are accumulated and shown as [AsrEngine.Listener]
 * `onPartial`, never reported as the finished transcript. The turn ends when
 * the *caller* says the user has stopped, which is what `AsrController`'s
 * three seconds of silence means. Reporting each upstream final as a final
 * would send half-sentences to the brain at every comma, which is the bug the
 * reference gateway's accumulator exists to prevent.
 *
 * Threading: [sendAudio] is called on the capture thread and returns at once;
 * all I/O is on [io]; the socket's callbacks arrive on OkHttp's thread.
 */
class GoogleAsr(
    private val config: VoiceSettings,
    private val listener: AsrEngine.Listener,
) : AsrEngine {

    /**
     * [EMIT_GRACE_MS] plus the trailing-silence push and a round trip — see
     * [finishTurn]. Comfortably more than the engine needs, so that the caller
     * giving up first is never what loses a transcript.
     */
    override val finalWaitMs: Long = EMIT_GRACE_MS + 2000L

    /**
     * **This is what ends a turn here, the same as for 火山** — see
     * [VAD_SILENCE_MS] for the experiment that established there is nothing
     * else. [AsrEngine]'s note explains why the caller can rely on a plain
     * silence window at all.
     *
     * Three seconds, and the number is sized against this engine's own
     * reporting cadence rather than against a vendor's default. Its results
     * arrive **about 2.5–4 s after the session is up** — measured over six
     * turns, during which the caller's watchdog fired on its deadline every
     * time and the transcripts of 12, 17, 22 and 29 characters were the
     * opening fraction of what was said. That latency is what the clock has to
     * survive, and 3 s is the shortest window with room to spare. A published
     * default like ZEGO's 500 ms is a number for a recogniser that starts
     * answering immediately.
     */
    override val silenceMs: Long = 3000L

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "GoogleAsr").apply { isDaemon = true }
    }

    /**
     * No read timeout: a Live session is silent for exactly as long as the
     * user is, and any timeout at all would kill a thoughtful pause. The ping
     * is what notices a socket that has actually died.
     */
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    /** Guards [pending], [finals] and [interim] — written from two threads. */
    private val lock = Any()

    /** Audio that arrived before the session was up. See [flushPending]. */
    private val pending = ArrayList<ByteArray>()
    private var pendingBytes = 0

    /** Settled upstream results, in order. */
    private val finals = StringBuilder()

    /** The current, still-changing result. Superseded by the next final. */
    private var interim = ""

    /**
     * How many **settled** results the upstream sent, and how many hypotheses.
     *
     * Kept apart rather than totalled, because the two answer different
     * questions and an earlier single counter could answer neither. A final is
     * a sentence the upstream has finished with; an interim is one it is still
     * revising. Whether the transcript handed to the brain is Google's final
     * *and* whether a late one is still worth waiting for both depend on the
     * split, and "results=4" does not say which it was.
     */
    @Volatile
    private var finalsSeen = 0

    @Volatile
    private var interimsSeen = 0

    /** The last field-set logged; see the note in [handle]. */
    @Volatile
    private var lastContentKeys = ""

    /** See [endOfTurn]; decides which grace [finishTurn] uses. */
    @Volatile
    private var endedBySilence = false

    @Volatile
    private var peakMax = 0

    @Volatile
    private var ws: WebSocket? = null

    @Volatile
    private var ready = false

    @Volatile
    private var closed = false

    /** The caller has said the user stopped; an upstream final has no more to add. */
    private val finished = AtomicBoolean(false)

    /** An upstream final arrived *after* [finished]. See [finishTurn]. */
    private val lateFinal = CountDownLatch(1)

    private val emitted = AtomicBoolean(false)
    private val reported = AtomicBoolean(false)

    /** Released when the upstream session is usable, or when it failed. */
    private val setup = CountDownLatch(1)

    /** Released when the transcript has been delivered. */
    private val done = CountDownLatch(1)

    override fun start(sampleRate: Int, channels: Int) {
        Log.d(TAG, "start model=${modelPath()} lang=${config.googleLanguage.ifBlank { "(auto)" }}")
        io.execute {
            runCatching { connect() }.onFailure {
                Log.e(TAG, "connect failed", it)
                fail(it.message ?: "ASR connect failed")
            }
        }
    }

    /**
     * Audio while the session is still being set up is **buffered, not
     * dropped**.
     *
     * There is no "I am ready" signal on this side of the handshake: the
     * capture loop starts as soon as [start] returns, and the setup takes
     * about a second. What arrives in that second is the beginning of the
     * user's sentence, and losing it shows up as a connection that works
     * perfectly and transcribes nothing — with no clue anywhere. The reference
     * gateway buffers for the same reason and at the same size.
     */
    override fun sendAudio(pcm: ByteArray, isLast: Boolean) {
        if (isLast) {
            finished.set(true)
            io.execute { runCatching { finishTurn() } }
            return
        }
        if (closed || pcm.isEmpty()) return
        peakMax = maxOf(peakMax, pcmPeak(pcm))

        val socket = ws
        // The decision is made under [lock], and so is the flip to ready, so
        // that "I buffered this" and "the buffer has been drained" cannot both
        // be true. Deciding outside the lock leaves a window where a block is
        // appended just after the drain took its copy — stuck in the list
        // forever, and audible in the transcript as a dropped word.
        synchronized(lock) {
            if (!ready || socket == null) {
                if (pendingBytes + pcm.size <= MAX_PENDING_BYTES) {
                    pending.add(pcm)
                    pendingBytes += pcm.size
                }
                return
            }
            // Sent under the lock as well — `send` only enqueues, so the
            // capture thread loses nothing by it, and it keeps "buffered" and
            // "sent" from being decided on either side of the drain.
            socket.send(audioFrame(pcm))
        }
    }

    override fun endOfTurn(bySilence: Boolean) {
        endedBySilence = bySilence
    }

    override fun waitFinished(timeoutMs: Long): Boolean =
        done.await(timeoutMs, TimeUnit.MILLISECONDS)

    override fun close() {
        closed = true
        // Nobody is left waiting on a socket that is going away.
        done.countDown()
        val socket = ws
        ws = null
        if (socket != null) {
            runCatching { socket.close(1000, "done") }.onFailure { socket.cancel() }
        }
        io.shutdownNow()
    }

    // ---- session ----

    private fun connect() {
        if (config.googleApiKey.isBlank()) {
            throw IllegalStateException("Google API key 未填写 (⚙ → 后端 → 本机)")
        }

        val socket = http.newWebSocket(
            Request.Builder().url("$LIVE_URL?key=${config.googleApiKey}").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(setupMessage())
                }

                override fun onMessage(webSocket: WebSocket, text: String) = handle(text)

                /** The Live API packs its JSON into binary frames, not text ones. */
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) =
                    handle(bytes.string(Charsets.UTF_8))

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    // Release the setup wait as *failed*, so connect() does not
                    // sit out its whole timeout on a socket that is already gone.
                    setup.countDown()
                    fail(t.message ?: t.javaClass.simpleName)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    setup.countDown()
                    // A close we did not ask for is a turn that cannot produce
                    // a transcript, so the failure is reported rather than
                    // papered over with whatever has been heard so far: half of
                    // what someone said, handed to the brain as if it were all
                    // of it, is a worse outcome than being told the turn is
                    // dead.
                    if (!closed && !emitted.get()) {
                        Log.w(TAG, "socket closed early code=$code reason=$reason")
                        fail("ASR socket closed ($code)")
                    }
                }
            },
        )
        ws = socket

        if (!setup.await(SETUP_TIMEOUT_S, TimeUnit.SECONDS)) {
            throw IllegalStateException("ASR setup 超时 (检查 project number / location / 模型名)")
        }
        // The latch is also counted down by a failure, so reaching here is not
        // proof of success — the error path has already said its piece.
        if (closed || reported.get()) return

        // Sets `ready` itself, under the lock that [sendAudio] decides in.
        flushPending()
    }

    private fun handle(raw: String) {
        // Frames can still be in flight when the turn ends. A late partial
        // would repaint the subtitle the controller has already cleared, and
        // would move a silence clock that is no longer running.
        if (closed) return

        val d = runCatching { JSONObject(raw) }.getOrNull()
        if (d == null) {
            Log.w(TAG, "unparseable frame: ${raw.take(200)}")
            return
        }

        if (d.has("setupComplete")) {
            Log.i(TAG, "session up")
            setup.countDown()
            // The session can hear from here — see [AsrEngine.Listener.onReady]
            // for why the caller needs to be told rather than assuming.
            listener.onReady()
            return
        }

        val error = d.optJSONObject("error")
        if (error != null) {
            val msg = error.optString("message").ifBlank { raw.take(200) }
            setup.countDown()
            fail("ASR upstream: $msg")
            return
        }

        val content = d.optJSONObject("serverContent") ?: return

        // What the upstream actually sends, printed each time the set of fields
        // changes.
        //
        // This is here because a whole design was built on `turnComplete` and
        // then reverted on the evidence that it never arrives — and "we never
        // saw it" is not the same claim as "it is not sent". One `serverContent`
        // frame does not answer it either: the frames differ, an interim
        // carrying one field and a settled result another. Logging the distinct
        // shapes of a session settles the question both ways, and costs one
        // line per shape.
        val keys = content.keys().asSequence().joinToString()
        if (keys != lastContentKeys) {
            lastContentKeys = keys
            Log.i(TAG, "serverContent fields: $keys")
        }

        // A hypothesis: shown, not accumulated — it is replaced by the final
        // for the same speech, and adding it to [finals] would print the
        // sentence twice.
        content.optJSONObject("interimInputTranscription")
            ?.optString("text")?.takeIf { it.isNotEmpty() }
            ?.let {
                synchronized(lock) { interim = it }
                interimsSeen++
                listener.onPartial(currentText())
            }

        // A settled result. It supersedes the interim it grew out of, which is
        // why that is cleared here rather than counted as well.
        content.optJSONObject("inputTranscription")
            ?.optString("text")?.takeIf { it.isNotEmpty() }
            ?.let {
                synchronized(lock) {
                    finals.append(it)
                    interim = ""
                }
                finalsSeen++
                listener.onPartial(currentText())
                if (finished.get()) lateFinal.countDown()
            }

        // The upstream's own end of turn, and it is the primary one from here
        // on — see [endTurnUpstream].
        if (content.optBoolean("turnComplete")) endTurnUpstream()
    }

    /**
     * The upstream has decided the user finished, and said so.
     *
     * **This is what ends a turn now, rather than the caller's silence
     * timer.** The reasoning is that the upstream is better at the question
     * than anything measurable here: its detector is trained on speech, and
     * the local one is a root-mean-square against a tracked noise floor —
     * which is why three turns tonight opened on a room's noise and
     * transcribed nothing. It also arrives sooner, because the caller no
     * longer has to wait out a pause it cannot distinguish from a thought.
     *
     * The silence timer has not gone away; it is the backstop for this not
     * arriving, and [silenceMs] says why it is the longer of the two.
     *
     * Delivery goes through the caller's `onFinal` — the same door everything
     * else uses, so the subtitle, the history row and the brain see one
     * unchanged shape. `emitted` is what stops [finishTurn] from delivering it
     * a second time if the caller's own timer happens to fire as well.
     */
    private fun endTurnUpstream() {
        if (closed) return
        if (!emitted.compareAndSet(false, true)) return
        val text = currentText()
        Log.i(
            TAG,
            "upstream ended the turn — ${text.length} chars " +
                "(finals=$finalsSeen interims=$interimsSeen)",
        )
        listener.onFinal(text)
        done.countDown()
    }

    /**
     * End the turn: tell the upstream the user stopped, then hand over
     * everything heard.
     *
     * **The trailing silence is not a courtesy, it is what produces the
     * transcript.** The upstream decides where a turn ends from the *audio
     * content* — including the quiet parts — so a client that simply stops
     * sending leaves it with nothing to judge by, and it answers nothing at
     * all. Measured on this device before the fix: the same code produced
     * `transcript 12 chars` on one turn and `transcript 0 chars (peak=21799)`
     * on the next, with the failures correlating exactly with this grace
     * expiring. [VAD_SILENCE_MS] is the upstream's own threshold, so the push
     * has to comfortably exceed it, chunked at the same 100 ms granularity as
     * real capture — one abrupt block of silence is not the same signal as a
     * stream that trails off.
     *
     * `audioStreamEnd` rides along after it, and is exactly the formality the
     * reference gateway calls it: their measurement is that it does *not* end
     * the turn on its own (0/3 with it, 0/3 without it, silence is what does).
     *
     * **[EMIT_GRACE_MS] must stay well under [finalWaitMs]**, which is what
     * the caller gives this method to finish in — a caller that has already
     * given up is one nobody can deliver a transcript to, and the reference
     * records hitting exactly that: an early fallback "sends the wrong thing,
     * and the real result arriving afterwards is then thrown away as a
     * non-final".
     */
    private fun finishTurn() {
        if (closed) return
        // The upstream already ended this turn and the transcript is delivered.
        // Pushing silence at a socket that is about to close would be harmless
        // and pointless, so it is not done.
        if (emitted.get()) return

        // Decided before the push because the push is what this decides about:
        // a settled, silence-ended turn does not need one. See the note on the
        // grace below for why that combination is the safe one — everything
        // else still gets it, because everything else is a turn where the
        // upstream has not yet said what it heard.
        //
        // **The third condition is the one a long utterance taught.** "Some
        // final has arrived" is not the same as "everything has been settled":
        // measured on a 22-second passage, the upstream had delivered exactly
        // one final and twenty interims, so `finalsSeen > 0` was true while
        // most of the transcript was still a hypothesis. Taking the fast path
        // there hands over `finals + interim`, and an interim is by definition
        // a result the upstream has not finished revising — which is how a long
        // answer comes back with its tail short. With a pending interim there
        // is genuinely something still coming, so the full grace is right.
        val settled = endedBySilence && finalsSeen > 0 &&
            synchronized(lock) { interim.isEmpty() }

        val socket = ws
        if (!settled && socket != null && ready) {
            val silent = ByteArray(SILENCE_CHUNK_BYTES)
            var pushed = 0
            while (pushed < TAIL_SILENCE_MS && !closed) {
                socket.send(audioFrame(silent))
                pushed += SILENCE_CHUNK_MS
            }
            socket.send(JSONObject()
                .put("realtimeInput", JSONObject().put("audioStreamEnd", true))
                .toString())
            Log.d(TAG, "pushed ${TAIL_SILENCE_MS}ms of silence to close the turn")
        }

        // **Two graces, and the caller's silence rule is what picks between
        // them.**
        //
        // When the turn ended because the caller's own silence window expired,
        // the upstream has already been fed that silence — three seconds of it
        // — and its own detector fires at 800 ms. So it settled everything it
        // heard long before this point, and in five measured turns `finalsSeen`
        // was never zero here. The remaining wait is spent on nothing, and
        // three seconds of nothing is the difference between an assistant that
        // answers and one that pauses first.
        //
        // When the user ended the turn by hand, none of that holds: they may
        // have stopped mid-word a moment ago, the upstream may still be
        // finalising it, and cutting the wait short would lose the tail of the
        // sentence they just chose to stop on. That case keeps the full grace.
        //
        // `settled` needs all three: the caller's own silence rule ended the
        // turn, at least one final is in hand, and no hypothesis is still
        // pending — see its definition above, which is also what skips the
        // trailing-silence push. Measured five times over, a push into a turn
        // that already had its final produced nothing at all, so it is not done.
        val grace = if (settled) SETTLED_GRACE_MS else EMIT_GRACE_MS
        if (!lateFinal.await(grace, TimeUnit.MILLISECONDS)) {
            Log.d(
                TAG,
                "no late final within ${grace}ms " +
                    "(finals=$finalsSeen bySilence=$endedBySilence) — using what we have",
            )
        }
        emit()
    }

    private fun emit() {
        // A turn that already failed does not also get a transcript: reporting
        // both an error and a final is a contract the caller has no way to
        // read, and the final it would get would be the empty string anyway.
        if (reported.get()) return
        if (!emitted.compareAndSet(false, true)) return
        val text = currentText()
        // `results` is here so that "this engine reports late" stays auditable
        // from a log line rather than trusted: if it is ever
        // non-zero, the controller's energy VAD is belt-and-braces and the
        // comment above it needs re-reading.
        Log.i(
            TAG,
            "transcript ${text.length} chars (peak=$peakMax/32767 " +
                "finals=$finalsSeen interims=$interimsSeen)",
        )
        if (closed) {
            done.countDown()
            return
        }
        listener.onFinal(text)
        done.countDown()
    }

    /** Idempotent: a failure that is already reported is not reported twice. */
    private fun fail(msg: String) {
        if (!reported.compareAndSet(false, true)) return
        Log.w(TAG, "failed: $msg")
        if (!closed) listener.onError(msg)
        done.countDown()
    }

    /** Everything settled, plus the hypothesis that has not been settled yet. */
    private fun currentText(): String = synchronized(lock) {
        finals.toString() + interim
    }

    /**
     * Become ready and send what was held, as one indivisible step.
     *
     * [ready] is set *inside* the lock rather than before it: a sender that
     * sees `ready == true` has then necessarily also seen an empty [pending],
     * so nothing can be left behind and nothing can overtake. `send` only
     * enqueues, so doing it while holding the lock costs the capture thread a
     * moment at most.
     */
    private fun flushPending() {
        synchronized(lock) {
            ready = true
            if (pending.isEmpty()) return
            val socket = ws ?: return
            Log.i(TAG, "flushing ${pending.size} buffered blocks")
            for (pcm in pending) socket.send(audioFrame(pcm))
            pending.clear()
            pendingBytes = 0
        }
    }

    // ---- wire ----

    /**
     * The setup message, and every field in it is one the reference gateway
     * arrived at by getting it wrong first — see the class note.
     *
     * `silenceDurationMs` is the upstream's own idea of "that pause ends the
     * turn". It is left at 800 rather than tuned down: the gateway's note on
     * doing so is that 500 is inside Google's recommended range and still cut
     * a sentence in half at a breath, and that a parameter changed without a
     * measurement is the user's experience used as an experiment.
     */
    private fun setupMessage(): String {
        val languages = JSONArray()
        if (config.googleLanguage.isNotBlank()) languages.put(config.googleLanguage)

        val setup = JSONObject()
            .put("model", modelPath())
            .put(
                "generationConfig",
                JSONObject().put("responseModalities", JSONArray().put("TEXT")),
            )
            .put(
                "inputAudioTranscription",
                JSONObject()
                    .put("languageCodes", languages)
                    .put("mode", "VERBATIM"),
            )
            .put(
                "realtimeInputConfig",
                JSONObject().put(
                    "automaticActivityDetection",
                    JSONObject().put("silenceDurationMs", VAD_SILENCE_MS),
                ),
            )
        return JSONObject().put("setup", setup).toString()
    }

    private fun audioFrame(pcm: ByteArray): String = JSONObject()
        .put(
            "realtimeInput",
            JSONObject().put(
                "audio",
                JSONObject()
                    .put("data", java.util.Base64.getEncoder().encodeToString(pcm))
                    .put("mimeType", "audio/pcm;rate=$SAMPLE_RATE"),
            ),
        )
        .toString()

    /**
     * The full resource path, or the bare name when no project is configured.
     *
     * Bare is not a working configuration — the gateway's note is that it comes
     * back as a permission error naming a project nobody asked for — but
     * inventing a path around an empty field would replace a clear upstream
     * error with a confusing local one.
     */
    private fun modelPath(): String {
        val project = config.googleProject
        if (project.isBlank()) return config.googleAsrModel
        return "projects/$project/locations/${config.googleLocation}" +
            "/publishers/google/models/${config.googleAsrModel}"
    }

    companion object {
        private const val TAG = "GoogleAsr"

        /**
         * The Live API endpoint. `v1beta1` is not a typo and not a preference:
         * it is the version that serves `BidiGenerateContent`.
         */
        private const val LIVE_URL =
            "wss://aiplatform.googleapis.com/ws/" +
                "google.cloud.aiplatform.v1beta1.LlmBidiService/BidiGenerateContent"

        private const val SAMPLE_RATE = 16_000

        /** How long the upstream's own silence detector waits before ending a turn. */
        /**
         * How long the upstream's voice detector waits before it calls a turn
         * over. **The reference gateway's measured value, restored after 2500
         * was tried and reverted.**
         *
         * The experiment was to raise this and end the turn on the upstream's
         * own `turnComplete` instead of on the caller's silence timer, which
         * would have judged the pause with a trained detector and skipped the
         * local wait entirely. It does not work here: across every turn
         * measured the upstream settled its own transcript and **never once
         * sent `turnComplete`** — so the caller's timer was the only thing that
         * ever ended a turn, and raising this number only made each of them
         * twice as long. The field appears to belong to conversational sessions
         * and not to a transcription-only one like this.
         *
         * Back to 800, the gateway's value, and it is kept because it is the
         * one with a measurement behind it: 500 was tried there and "cut the
         * sentence in half at a breath".
         */
        private const val VAD_SILENCE_MS = 800

        /** Waiting for `setupComplete`. Not getting one means the config is wrong. */
        private const val SETUP_TIMEOUT_S = 20L

        /**
         * Silence pushed after the user stops, so the upstream has something to
         * hear itself stop hearing. Must exceed [VAD_SILENCE_MS] — see
         * [finishTurn]. The reference gateway's value, kept because it is the
         * one with a measurement behind it.
         */
        private const val TAIL_SILENCE_MS = 1200

        /** One capture block: 100 ms of 16 kHz mono 16-bit. */
        private const val SILENCE_CHUNK_MS = 100
        private const val SILENCE_CHUNK_BYTES = SAMPLE_RATE * 2 * SILENCE_CHUNK_MS / 1000

        /**
         * How long an upstream final is given after the caller says "done".
         *
         * **Not the reference gateway's 2000, because that value was measured
         * on this device and landed on the boundary.** Two consecutive turns:
         * a final at 1.94 s (transcribed, 16 characters) and one that had not
         * arrived at 2.00 s (discarded, empty transcript). The cost is real in
         * both directions — too tight loses the sentence, too loose is silence
         * in the user's ear — so this sits 50 % above the measured figure and
         * comfortably under `AsrController.FINAL_WAIT_MS`.
         */
        private const val EMIT_GRACE_MS = 3000L

        /**
         * The wait when the turn ended on the caller's silence rule and a
         * settled transcript is already in hand — see [finishTurn].
         *
         * Not zero, because a result can still be in flight by milliseconds,
         * and not short-of-a-round-trip either: this is a formality, not a
         * deadline. Its real justification is the precondition.
         */
        private const val SETTLED_GRACE_MS = 300L

        /** 16 kHz × 2 bytes × 20 s, the reference gateway's measured buffer size. */
        private const val MAX_PENDING_BYTES = 600 * 1024

        /** Absolute peak of 16-bit little-endian PCM. For the log, not for a decision. */
        private fun pcmPeak(pcm: ByteArray): Int {
            var max = 0
            var i = 0
            while (i + 1 < pcm.size) {
                val v = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
                val abs = if (v >= 0) v else -v
                if (abs > max) max = abs
                i += 2
            }
            return max
        }
    }
}
