package net.kuafuai.andee.desktop.voice

import net.kuafuai.andee.asr.AsrEngine
import net.kuafuai.andee.asr.GoogleAsr
import net.kuafuai.andee.asr.HuoshanAsr
import net.kuafuai.andee.brain.SpeechMood
import net.kuafuai.andee.config.VoiceSettings
import net.kuafuai.andee.desktop.Settings
import net.kuafuai.andee.tts.GoogleTts
import net.kuafuai.andee.tts.HuoshanTts
import net.kuafuai.andee.tts.TtsEngine
import net.kuafuai.andee.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

/**
 * Voice on the Mac: the phone's own 火山 / Google engines from the shared
 * module, on Java Sound for the microphone and the speaker.
 *
 * Listening is push-to-talk (the window's microphone button, or the shortcut):
 * a laptop has no echo cancellation here, so an always-on wake word would hear
 * Andee's own voice. The phone's wake word stays a phone feature for now.
 */
object MacVoice {

    private const val TAG = "Voice"
    private const val ASR_RATE = 16_000

    @Volatile var onPartial: (String) -> Unit = {}
    @Volatile var onFinal: (String) -> Unit = {}
    @Volatile var onState: (State) -> Unit = {}

    enum class State { IDLE, LISTENING, SPEAKING }

    /** Every change goes to the ball as well as the window, which has its own listener slot. */
    @Volatile private var current = State.IDLE

    /** The answer is being read out loud right now. */
    fun isSpeaking() = current == State.SPEAKING

    private fun state(s: State) {
        current = s
        net.kuafuai.andee.desktop.MacBall.onVoice(s)
        onState(s)
    }

    private val io = Executors.newCachedThreadPool { r -> Thread(r, "voice").apply { isDaemon = true } }
    private val listening = AtomicBoolean(false)
    @Volatile private var mic: TargetDataLine? = null
    @Volatile private var asr: AsrEngine? = null
    @Volatile private var speaker: SourceDataLine? = null
    @Volatile private var tts: TtsEngine? = null

    /** What the phone calls VoiceConfig.load(): the same keys, the same defaults. */
    fun settings(): VoiceSettings = VoiceSettings(
        apiKey = Settings.get("api_key"),
        asrEndpoint = Settings.get("asr_endpoint").ifEmpty { "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async" },
        asrResourceId = Settings.get("asr_resource_id").ifEmpty { "volc.bigasr.sauc.duration" },
        asrUid = Settings.uid(),
        ttsEndpoint = Settings.get("tts_endpoint").ifEmpty { "wss://openspeech.bytedance.com/api/v3/tts/bidirection" },
        ttsResourceId = Settings.get("tts_resource_id").ifEmpty { "seed-tts-2.0" },
        // The voice is the character's: read from the look the ball is wearing, per utterance, as the phone does.
        ttsSpeaker = net.kuafuai.andee.ui.ball.BallLooks.byName(Settings.get("ball").ifEmpty { null }).voice
            .ifEmpty { "zh_female_vv_uranus_bigtts" },
        ttsSampleRate = Settings.get("tts_sample_rate").toIntOrNull()?.takeIf { it in 8_000..48_000 } ?: 24_000,
        voiceProvider = Settings.voiceProvider(),
        googleApiKey = Settings.get("google_api_key"),
        googleBaseUrl = Settings.get("google_base_url").ifEmpty { "https://aiplatform.googleapis.com" },
        googleProject = Settings.get("google_project"),
        googleLocation = Settings.get("google_location").ifEmpty { "asia-northeast1" },
        googleAsrModel = Settings.get("google_asr_model").ifEmpty { "gemini-live-2.5-flash-preview-native-audio" },
        googleTtsModel = Settings.get("google_tts_model").ifEmpty { "gemini-2.5-flash-preview-tts" },
        googleLanguage = Settings.get("google_language"),
    )

    fun configured(): Boolean = settings().let {
        if (it.voiceProvider == VoiceSettings.VOICE_GOOGLE) it.googleApiKey.isNotBlank() else it.apiKey.isNotBlank()
    }

    // ---- listening -------------------------------------------------------------

    fun isListening() = listening.get()

    /** Start listening; [stopListening] (or the engine's own end-of-turn) delivers [onFinal]. */
    fun startListening() {
        if (net.kuafuai.andee.desktop.Meeting.isActive()) return  // the meeting holds the microphone
        if (!listening.compareAndSet(false, true)) return
        stopSpeaking()
        val cfg = settings()
        val engine: AsrEngine = if (cfg.voiceProvider == VoiceSettings.VOICE_GOOGLE) GoogleAsr(cfg, listener) else HuoshanAsr(cfg, listener)
        asr = engine
        state(State.LISTENING)
        io.execute {
            runCatching {
                engine.start(ASR_RATE, 1)
                val fmt = AudioFormat(ASR_RATE.toFloat(), 16, 1, true, false)
                val line = AudioSystem.getTargetDataLine(fmt).apply { open(fmt, 6400); start() }
                mic = line
                val buf = ByteArray(3200) // 100 ms
                while (listening.get()) {
                    val n = line.read(buf, 0, buf.size)
                    if (n > 0) engine.sendAudio(buf.copyOf(n), isLast = false)
                }
                engine.sendAudio(ByteArray(0), isLast = true)
                engine.waitFinished(engine.finalWaitMs)
            }.onFailure {
                Log.e(TAG, "listening failed", it)
                listener.onError(it.message ?: "microphone unavailable")
            }
            runCatching { mic?.stop(); mic?.close() }
            mic = null
            runCatching { engine.close() }
            if (asr === engine) asr = null
            state(State.IDLE)
        }
    }

    /** The ball's tap and the 🎤 button: start, or say "done". False when there is no voice key. */
    fun toggle(): Boolean {
        if (!configured()) return false
        if (isListening()) stopListening() else startListening()
        return true
    }

    fun recordingMeeting(): Boolean = net.kuafuai.andee.desktop.Meeting.isActive()

    fun stopListening() {
        if (listening.compareAndSet(true, false)) asr?.endOfTurn(bySilence = false)
    }

    private val partial = StringBuilder()

    private val listener = object : AsrEngine.Listener {
        override fun onPartial(text: String) { onPartial.invoke(text) }
        override fun onFinal(text: String) {
            listening.set(false)
            if (text.isNotBlank()) onFinal.invoke(text.trim())
        }
        override fun onError(msg: String) {
            listening.set(false)
            Log.w(TAG, "asr: $msg")
            onPartial.invoke("")
        }
        override fun onClosed() { listening.set(false) }
    }

    // ---- speaking ---------------------------------------------------------------

    /** The brain's "this settles it" sentinel; the phone strips it before speaking too (case-insensitive). */
    private val END_MARKER = Regex("""(?i)\[END]""")

    /**
     * What the mouth gets: the same three steps the phone takes, in the same order — the end
     * sentinel, then Markdown, then mood markers. Whatever survives is read out loud as a word.
     */
    internal fun speakable(raw: String): String =
        SpeechMood.parse(net.kuafuai.andee.text.stripMarkdown(END_MARKER.replace(raw, ""))).text.trim()

    /** Speak [raw] (sentinel, Markdown and mood markers are stripped first). Returns at once; audio plays in the background. */
    fun speak(raw: String) {
        if (net.kuafuai.andee.desktop.Meeting.isActive()) return  // never speak into the recording
        val text = speakable(raw)
        if (text.isEmpty() || !configured()) return
        stopSpeaking()
        val cfg = settings()
        val done = AtomicBoolean(false)
        var engineRef: TtsEngine? = null
        val l = object : TtsEngine.Listener {
            override fun onOpen(sampleRate: Int) {
                // The session is ready only now; text pushed before this is dropped (as on the phone).
                engineRef?.let { it.push(text); it.finish() }
                runCatching {
                    val fmt = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
                    speaker = AudioSystem.getSourceDataLine(fmt).apply { open(fmt, sampleRate * 2); start() }
                    state(State.SPEAKING)
                }.onFailure { Log.e(TAG, "speaker unavailable", it) }
            }
            override fun onAudio(pcm: ByteArray) { speaker?.write(pcm, 0, pcm.size) }
            override fun onSentenceStart(text: String) {}
            override fun onSentenceEnd(text: String) {}
            override fun onDone() { finish() }
            override fun onError(msg: String) { Log.w(TAG, "tts: $msg"); finish() }
            override fun onClose(reason: TtsEngine.CloseReason) { finish() }
            fun finish() {
                if (!done.compareAndSet(false, true)) return
                io.execute {
                    runCatching { speaker?.drain(); speaker?.close() }
                    speaker = null
                    state(State.IDLE)
                }
            }
        }
        val engine: TtsEngine = if (cfg.voiceProvider == VoiceSettings.VOICE_GOOGLE) GoogleTts(cfg, l) else HuoshanTts(cfg, l)
        engineRef = engine
        tts = engine
        io.execute {
            runCatching { engine.open() }
                .onFailure { Log.e(TAG, "speaking failed", it); l.onError(it.message ?: "") }
        }
    }

    fun stopSpeaking() {
        runCatching { tts?.cancel() }
        tts = null
        runCatching { speaker?.stop(); speaker?.flush(); speaker?.close() }
        speaker = null
    }
}
