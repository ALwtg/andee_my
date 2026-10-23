package net.kuafuai.andee.asr

import net.kuafuai.andee.util.Log
import net.kuafuai.andee.config.VoiceSettings
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.net.ssl.SSLContext

/**
 * ByteDance Volcano (火山) streaming ASR client.
 * Direct Kotlin port of wxBot's ui/mic_asr_stream.py binary protocol.
 *
 * Wire format for each message:
 *   header (4 bytes) + seq (int32 BE) + payloadSize (uint32 BE) + gzipped(payload)
 *
 * Header bytes:
 *   [0] (proto_ver<<4) | header_size(=1)  → 0x11
 *   [1] (message_type<<4) | flags
 *   [2] (serialization<<4) | compression  → 0x11 (JSON + gzip)
 *   [3] reserved (0)
 */
class HuoshanAsr(
    private val config: VoiceSettings,
    private val listener: AsrEngine.Listener,
) : AsrEngine {

    /**
     * How long the server is given to answer our final chunk.
     *
     * **3000 is thin, and the three measured turns that say so belong next to
     * it.** The gap from the silence rule firing to the transcript reaching the
     * brain was 198 ms, 2676 ms and 2660 ms — a 13× spread whose slow end sits
     * at 89 % of this budget. Past it the turn does not degrade, it *fails*:
     * the socket closes with the last frame still in flight, `onFinal` never
     * fires, and a sentence the user definitely said reaches nothing at all.
     * That is the failure with no cause visible from the outside, so a turn
     * that does absolutely nothing starts by looking here.
     *
     * 5000 was tried and reverted: this is a ceiling rather than a sleep, so it
     * buys nothing on a server that answers in 200 ms — which is most of them —
     * and only lengthens the dead case.
     */
    override val finalWaitMs: Long = 3000L

    /**
     * **火山 never says a turn is over — this is the only thing that ends
     * one.** Its value is therefore the user-facing pause tolerance, and it is
     * unchanged from what this device has always used.
     */
    override val silenceMs: Long = 3000L

    private var client: WebSocketClient? = null

    // Full client request uses seq=1; audio chunks start at 2 (火山 protocol).
    private val seq = AtomicInteger(2)

    /** Guards [pending] — the capture thread appends, the connect drains. */
    private val lock = Any()

    /** Audio that arrived before the handshake landed. See [sendAudio]. */
    private val pending = ArrayList<ByteArray>()
    private var pendingBytes = 0

    /** Whether the socket may be written to. Set inside [flushPending]. */
    @Volatile
    private var ready = false

    @Volatile
    private var lastPartial: String = ""

    @Volatile
    private var gotAnyText: Boolean = false
    private val chunksSent = AtomicInteger(0)

    // Counted down when server sends an isLast response, or on error/close.
    // waitFinished() blocks on this so we don't close the WS while the final
    // message is still in flight.
    private val finishedLatch = java.util.concurrent.CountDownLatch(1)

    /**
     * Why the handshake failed, when it failed by being *answered* rather than
     * by going unanswered.
     *
     * `connectBlocking` returns false for both, so without this the one error
     * anyone ever sees is "connect timeout" — which sends whoever reads it to
     * look at the network, when the truth is usually a server that replied at
     * once and said no (a blank or revoked `X-Api-Key` answers 403). That has
     * already cost one round of debugging; the self-check's voice row probes a
     * bare TCP connect for the same reason, and a green row beside a red
     * "timeout" is the contradiction this removes.
     *
     * Only written before [connected] is set, so a normal close at the end of
     * a session cannot overwrite it with something irrelevant.
     */
    @Volatile
    private var handshakeRefusal: String? = null

    @Volatile
    private var connected = false

    override fun start(sampleRate: Int, channels: Int) {
        val requestId = UUID.randomUUID().toString()
        val connectId = UUID.randomUUID().toString()
        val headers = mapOf(
            "X-Api-Key" to config.apiKey,
            "X-Api-Resource-Id" to config.asrResourceId,
            "X-Api-Request-Id" to requestId,
            "X-Api-Sequence" to "-1",
            "X-Api-Connect-Id" to connectId,
        )

        Log.d(TAG, "start: endpoint=${config.asrEndpoint} sr=$sampleRate uid=${config.asrUid}")
        val uri = URI(config.asrEndpoint)
        val c = object : WebSocketClient(uri, headers) {
            override fun onOpen(handshake: ServerHandshake?) {
                connected = true
                Log.d(
                    TAG,
                    "onOpen status=${handshake?.httpStatus} msg=${handshake?.httpStatusMessage}"
                )
                val cfg = JSONObject().apply {
                    put("user", JSONObject().put("uid", config.asrUid))
                    put(
                        "audio",
                        JSONObject()
                            .put("format", "pcm")
                            .put("codec", "raw")
                            .put("rate", sampleRate)
                            .put("bits", 16)
                            .put("channel", channels),
                    )
                    put(
                        "request",
                        JSONObject()
                            .put("model_name", "bigmodel")
                            .put("enable_itn", true)
                            .put("enable_punc", true)
                            .put("enable_ddc", true)
                            .put("show_utterances", true)
                            .put("enable_nonstream", false),
                    )
                }
                send(buildFullClientRequest(1, cfg.toString()))
                Log.d(TAG, "sent full client request payload=$cfg")
            }

            override fun onMessage(message: String?) {
                Log.d(TAG, "onMessage(text): $message")
            }

            override fun onMessage(bytes: ByteBuffer?) {
                bytes ?: return
                val raw = ByteArray(bytes.remaining())
                bytes.get(raw)
                handleResponse(raw)
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                if (!connected) handshakeRefusal = reason?.takeIf { it.isNotBlank() } ?: "code $code"
                Log.d(
                    TAG,
                    "onClose code=$code reason=$reason remote=$remote chunksSent=${chunksSent.get()}"
                )
                listener.onClosed()
                finishedLatch.countDown()
            }

            override fun onError(ex: Exception?) {
                Log.e(TAG, "onError", ex)
                listener.onError("ws error: ${ex?.message ?: ex?.javaClass?.simpleName}")
                finishedLatch.countDown()
            }
        }
        // Force TLS for wss://.
        if (uri.scheme.equals("wss", ignoreCase = true)) {
            c.setSocketFactory(SSLContext.getDefault().socketFactory)
        }
        client = c
        val opened = c.connectBlocking(5, TimeUnit.SECONDS)
        if (!opened) {
            val refusal = handshakeRefusal
            throw IllegalStateException(
                if (refusal == null) {
                    "ASR ws connect timeout"
                } else {
                    // The server answered. Name the key, because a blank or
                    // revoked one is what 403 means here and the endpoint is
                    // the thing everyone checks first.
                    "ASR ws refused: $refusal (check the 火山 API Key in ⚙)"
                },
            )
        }
        // Connected and configured, so the session can hear from here. The
        // caller's silence clock starts on this rather than on the microphone
        // opening — see [AsrEngine.Listener.onReady].
        //
        // The flush comes first, so that "ready" means the session has
        // everything said so far — not merely that it could start listening.
        flushPending()
        listener.onReady()
    }

    /**
     * Send one block, or hold it until the handshake lands.
     *
     * **Held rather than dropped, and that is new.** The microphone now opens
     * before the connect begins — see the note in `AsrController.startImpl` —
     * so roughly two seconds of speech arrives while the socket is still
     * connecting. [client] is non-null by then but the connection is not open,
     * so a send at that point throws `WebsocketNotConnectedException` twenty
     * times a second and the audio goes with it. Those two seconds are the
     * beginning of the user's sentence, and losing them shows up as a
     * connection that works perfectly and transcribes half a question.
     *
     * The same shape as [GoogleAsr]'s buffer, and for the same reason: its
     * engine has always opened the microphone first, because its `start`
     * returns at once instead of blocking on a connect.
     */
    override fun sendAudio(pcm: ByteArray, isLast: Boolean) {
        val c = client
        // The decision and the drain happen under one lock, so "held this" and
        // "the buffer has been emptied" cannot both be true. Deciding outside
        // it leaves a window where a block is appended just after the drain
        // took its copy — stuck in the list forever, and audible in the
        // transcript as a dropped word.
        synchronized(lock) {
            if (ready && c != null) {
                sendNow(c, pcm, isLast)
            } else if (!isLast && pendingBytes + pcm.size <= MAX_PENDING_BYTES) {
                // A last frame is never held: it only arrives while the turn is
                // being torn down, and a session that never came up has nothing
                // left to finalise.
                pending.add(pcm)
                pendingBytes += pcm.size
            }
        }
    }

    /** [lock] held. Assigns the sequence number at *send* time, so order survives buffering. */
    private fun sendNow(c: WebSocketClient, pcm: ByteArray, isLast: Boolean) {
        val current = seq.getAndIncrement()
        c.send(buildAudioRequest(current, pcm, isLast))
        val n = chunksSent.incrementAndGet()
        if (isLast || n <= 3 || n % 20 == 0) {
            Log.d(TAG, "sendAudio seq=$current bytes=${pcm.size} isLast=$isLast totalChunks=$n")
        }
    }

    /**
     * Become ready and hand over everything held, as one indivisible step.
     *
     * [ready] is set *inside* the lock rather than before it, for the reason
     * [GoogleAsr.flushPending] gives: a sender that sees `ready == true` has
     * then necessarily also seen the drain's effect.
     */
    private fun flushPending() {
        synchronized(lock) {
            ready = true
            val c = client
            if (c != null && pending.isNotEmpty()) {
                Log.i(TAG, "flushing ${pending.size} buffered blocks")
                for (pcm in pending) sendNow(c, pcm, isLast = false)
                pending.clear()
                pendingBytes = 0
            }
        }
    }

    override fun close() {
        try {
            client?.close()
        } catch (_: Throwable) {
        }
        client = null
        // Nothing is left holding audio for a socket that is gone. The capture
        // has already stopped by the time this runs — [AsrController.stopImpl]
        // stops it before it closes the engine — so this is a clear, not a
        // race being won.
        synchronized(lock) {
            ready = false
            pending.clear()
            pendingBytes = 0
        }
    }

    // ---- Response handling ----

    private fun handleResponse(msg: ByteArray) {
        if (msg.size < 4) return
        val headerSize = (msg[0].toInt() and 0x0f)
        val messageType = ((msg[1].toInt() shr 4) and 0x0f)
        val flags = msg[1].toInt() and 0x0f
        val compression = msg[2].toInt() and 0x0f

        var offset = headerSize * 4
        var isLast = false

        if (flags and 0x01 != 0) offset += 4               // seq present
        if (flags and 0x02 != 0) isLast = true             // last package
        if (flags and 0x04 != 0) offset += 4               // event present

        val payload: ByteArray = when (messageType) {
            MSG_SERVER_FULL_RESPONSE -> {
                if (offset + 4 > msg.size) return
                val size = intAtBE(msg, offset)
                offset += 4
                if (offset + size > msg.size) return
                msg.copyOfRange(offset, offset + size)
            }

            MSG_SERVER_ERROR_RESPONSE -> {
                if (offset + 8 > msg.size) return
                val code = intAtBE(msg, offset)
                val size = intAtBE(msg, offset + 4)
                offset += 8
                val body = if (offset + size <= msg.size) msg.copyOfRange(
                    offset,
                    offset + size
                ) else ByteArray(0)
                val decompressed =
                    if (compression == COMPRESSION_GZIP) runCatching { ungzip(body) }.getOrDefault(
                        body
                    ) else body
                val errMsg = String(decompressed, Charsets.UTF_8)
                Log.e(TAG, "recv ERROR code=$code msg=$errMsg")
                listener.onError("ASR server error code=$code msg=$errMsg")
                finishedLatch.countDown()
                return
            }

            else -> {
                Log.w(TAG, "recv unknown msgType=$messageType flags=$flags")
                return
            }
        }

        val decompressed = if (compression == COMPRESSION_GZIP) {
            runCatching { ungzip(payload) }.getOrElse {
                Log.w(TAG, "gunzip failed on ${payload.size} bytes")
                return
            }
        } else payload

        val bodyStr = String(decompressed, Charsets.UTF_8)
        Log.d(
            TAG,
            "recv msgType=$messageType flags=$flags isLast=$isLast body=${bodyStr.take(300)}"
        )

        val text = try {
            JSONObject(bodyStr).optJSONObject("result")?.optString("text").orEmpty().trim()
        } catch (_: Throwable) {
            Log.w(TAG, "response payload not JSON with result.text")
            return
        }

        if (text.isNotEmpty()) {
            lastPartial = text
            gotAnyText = true
            listener.onPartial(text)
        }
        if (isLast) {
            if (gotAnyText) {
                listener.onFinal(lastPartial)
            } else {
                // A clean end with nothing recognised is an empty turn: the ball
                // got tapped and nobody said anything. It used to be reported as
                // an error, which meant flashing a red server-diagnostic string
                // at a user whose only mistake was silence.
                //
                // The auth / config failures this once tried to catch don't look
                // like this — they arrive as MSG_SERVER_ERROR_RESPONSE above, or
                // as a ws error, never as a well-formed isLast after a handshake
                // and N accepted audio chunks. Kept at info level because the
                // chunk count is the first thing to look at if the mic itself is
                // broken: an empty turn still sends chunks, a dead mic doesn't.
                Log.i(
                    TAG,
                    "isLast with no transcription — empty turn " +
                        "(chunksSent=${chunksSent.get()}, body=${bodyStr.take(200)})",
                )
                listener.onFinal("")
            }
            finishedLatch.countDown()
        }
    }

    /**
     * Block up to [timeoutMs] waiting for the server's isLast response (or an
     * error / socket close). Call this after sending the isLast audio chunk,
     * before [close], so the final transcript has a chance to arrive.
     */
    override fun waitFinished(timeoutMs: Long): Boolean =
        finishedLatch.await(timeoutMs, TimeUnit.MILLISECONDS)

    // ---- Frame builders ----

    private fun header(msgType: Int, flags: Int): ByteArray = byteArrayOf(
        ((PROTOCOL_VERSION_V1 shl 4) or 1).toByte(),
        ((msgType shl 4) or flags).toByte(),
        ((SERIALIZATION_JSON shl 4) or COMPRESSION_GZIP).toByte(),
        0x00,
    )

    private fun buildFullClientRequest(seqNum: Int, payloadJson: String): ByteArray {
        val body = gzip(payloadJson.toByteArray(Charsets.UTF_8))
        val out = ByteArrayOutputStream()
        out.write(header(MSG_CLIENT_FULL_REQUEST, FLAG_POS_SEQUENCE))
        out.write(ByteBuffer.allocate(4).putInt(seqNum).array())
        out.write(ByteBuffer.allocate(4).putInt(body.size).array())
        out.write(body)
        return out.toByteArray()
    }

    private fun buildAudioRequest(seqNum: Int, pcm: ByteArray, isLast: Boolean): ByteArray {
        val actualSeq = if (isLast) -seqNum else seqNum
        val flags = if (isLast) FLAG_NEG_WITH_SEQUENCE else FLAG_POS_SEQUENCE
        val body = gzip(pcm)
        val out = ByteArrayOutputStream()
        out.write(header(MSG_CLIENT_AUDIO_ONLY_REQUEST, flags))
        out.write(ByteBuffer.allocate(4).putInt(actualSeq).array())
        out.write(ByteBuffer.allocate(4).putInt(body.size).array())
        out.write(body)
        return out.toByteArray()
    }

    private fun gzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun ungzip(data: ByteArray): ByteArray {
        return GZIPInputStream(data.inputStream()).use { it.readBytes() }
    }

    private fun intAtBE(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xff) shl 24) or
                ((b[o + 1].toInt() and 0xff) shl 16) or
                ((b[o + 2].toInt() and 0xff) shl 8) or
                (b[o + 3].toInt() and 0xff)

    companion object {
        private const val TAG = "HuoshanAsr"
        private const val PROTOCOL_VERSION_V1 = 0b0001
        private const val MSG_CLIENT_FULL_REQUEST = 0b0001
        private const val MSG_CLIENT_AUDIO_ONLY_REQUEST = 0b0010
        private const val MSG_SERVER_FULL_RESPONSE = 0b1001
        private const val MSG_SERVER_ERROR_RESPONSE = 0b1111
        private const val FLAG_POS_SEQUENCE = 0b0001
        private const val FLAG_NEG_WITH_SEQUENCE = 0b0011
        private const val SERIALIZATION_JSON = 0b0001
        private const val COMPRESSION_GZIP = 0b0001

        /**
         * The hold's ceiling while the handshake is in flight — 16 kHz × 2
         * bytes × 20 s, the same number [GoogleAsr] uses. Two seconds of
         * speech is 64 KB; this is the backstop for a microphone that somehow
         * outruns a connect that never lands.
         */
        private const val MAX_PENDING_BYTES = 600 * 1024
    }
}
