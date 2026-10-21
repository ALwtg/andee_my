package net.kuafuai.andee.asr

/**
 * One speech-recognition session, whichever backend is behind it.
 *
 * The shape is 火山's, because 火山 was here first: open a session, push PCM
 * into it as it arrives, ask it to finish, wait for the final line. Both
 * backends fit it and neither had to be reshaped to fit the other, which is
 * the only reason this interface exists rather than a branch inside
 * [AsrController].
 *
 * Both backends are streaming ones — 火山 over its own binary protocol, Google
 * over the Gemini Live API — which is why they reduce to the same five calls.
 * **The caller's silence rule reads [Listener.onPartial] and nothing else**,
 * which is what makes the two engines interchangeable rather than merely
 * similar: both keep reporting while the user talks, so a window that exceeds
 * their reporting cadence needs to know nothing about the room, the microphone
 * or which vendor is behind it.
 */
interface AsrEngine {

    /**
     * How long [waitFinished] should be given — **this engine's own doing
     * included**.
     *
     * Asked of the engine rather than fixed by the caller, because the two
     * differ by more than a fudge factor and the reason is structural. 火山
     * produces a final *only* in response to being told the utterance is over,
     * so its answer is on its way as soon as the question is asked and half a
     * second covers it. The Gemini Live API ends turns on its own voice
     * detector, so by the time it is asked it has usually already answered and
     * the request is what provokes a last push of trailing silence plus the
     * wait for its reply — see `GoogleAsr.finishTurn`.
     *
     * One shared number therefore cannot be right for both, and the earlier
     * version of this was exactly that mistake in reverse: raising the shared
     * value to suit Google quietly made 火山 slower to give up on a server that
     * had stopped answering.
     */
    val finalWaitMs: Long

    /**
     * How long a silence the caller should let pass before ending the turn
     * itself.
     *
     * **Two meanings, and which one applies depends on the engine.** For one
     * that never announces an end of turn this *is* what ends the turn — 火山
     * has no such signal, so a pause is all there is. For one that does
     * (the Live API reports `turnComplete` from its own voice detector), this
     * is the backstop for when that report does not arrive, and the only thing
     * that closes a microphone on a connection that has gone quiet.
     *
     * Per-engine for the same reason as [finalWaitMs]: a single shared value
     * cannot be right for both, and changing it for one silently changes the
     * other.
     */
    val silenceMs: Long

    interface Listener {
        /**
         * The engine is connected and can hear.
         *
         * **The caller's silence clock must not run before this.** The
         * microphone opens at one moment and the recogniser's session comes up
         * at another, and on this device they are 1.4–1.9 s apart — against a
         * silence window of 1.5 s. With the clock counting from the microphone,
         * the deadline landed on top of the handshake, and four measured turns
         * were cut off at 1.5 s with the first result arriving 100–350 ms later.
         * Whether any given turn survived came down to tens of milliseconds of
         * connection luck.
         *
         * It does not cover for a broken engine: audio is still captured and
         * buffered from the moment the microphone opens, and one that never
         * connects reports [onError] and the turn ends that way.
         */
        fun onReady() {}

        /**
         * What has been heard so far, good enough to draw but not to act on.
         *
         * **Both backends call this while the user is still talking**, and
         * `AsrController` depends on it: the silence watchdog that closes the
         * microphone is fed from these calls and from nothing else.
         */
        fun onPartial(text: String)

        /** The finished transcript. Empty means "there was nothing to hear". */
        fun onFinal(text: String)

        fun onError(msg: String)

        /** The upstream connection ended. */
        fun onClosed()
    }

    /**
     * The turn is ending, and **how** it is ending.
     *
     * [bySilence] is true when the caller's own silence rule expired — see
     * [silenceMs] — and false when the user ended it by hand or something else
     * did. The two are not interchangeable to an engine: a silence-ended turn
     * has, by construction, already been quiet for [silenceMs], which is far
     * longer than any recogniser needs to settle what it heard, so the last
     * results are already in hand. A hand-ended turn has no such guarantee —
     * the user may have stopped mid-word a moment ago.
     *
     * Optional to implement: an engine with nothing to gain ignores it, which
     * is why it carries a default body.
     */
    fun endOfTurn(bySilence: Boolean) = Unit

    fun start(sampleRate: Int, channels: Int)

    /**
     * One block of 16-bit little-endian PCM, or the end-of-utterance marker
     * when [isLast] — in which case [pcm] is ignored.
     *
     * Called on the capture thread; must not block.
     */
    fun sendAudio(pcm: ByteArray, isLast: Boolean)

    /**
     * Block until the final line has been delivered, or [timeoutMs] passes.
     *
     * The timeout is the caller's because the two backends need very different
     * ones: 火山 answers in ~500 ms, while a Google request carries the whole
     * utterance and has a network round trip of its own.
     */
    fun waitFinished(timeoutMs: Long): Boolean

    fun close()
}
