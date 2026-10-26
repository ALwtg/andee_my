package net.kuafuai.andee.tts

/**
 * One utterance, whichever backend speaks it.
 *
 * The call sequence is 火山's and both backends honour it: [open], then
 * [push] with the text (once, from the driver's `onOpen`) — for a backend that
 * synthesises on demand, however many times — then [finish]. Audio comes back
 * through [Listener.onAudio] as raw 16-bit little-endian PCM.
 *
 * **What a backend cannot promise is when the text arrives relative to the
 * network call.** 火山 accepts text into an open socket, so it can report its
 * sample rate before it has seen a word; a backend that sends one HTTP request
 * per utterance only learns the rate from the response. [Listener.onOpen] is
 * therefore called *before* [push] by construction — see `GoogleTts` for what
 * that forces it to declare up front.
 */
interface TtsEngine {

    /** Why a session ended. Narrated to nobody; used to describe a dead socket. */
    enum class CloseReason { USER, SERVER, ERROR }

    interface Listener {
        /**
         * The session is up and [sampleRate] is what the PCM will be in.
         *
         * Called once, before any [onAudio], and — for every backend — before
         * the driver can push text, because the driver pushes from this
         * callback.
         */
        fun onOpen(sampleRate: Int)

        fun onAudio(pcm: ByteArray)

        fun onSentenceStart(text: String)

        fun onSentenceEnd(text: String)

        /** No more audio is coming. The driver keeps its own drain clock. */
        fun onDone()

        fun onError(msg: String)

        fun onClose(reason: CloseReason)
    }

    fun open()

    fun push(text: String)

    fun finish()

    fun cancel()
}
