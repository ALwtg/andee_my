package net.kuafuai.andee.wake

import kotlin.math.sqrt

/**
 * Cuts a continuous microphone stream into "somebody just said something"
 * clips.
 *
 * Plain energy VAD against an adaptive noise floor, because the wake word only
 * needs to know *where* an utterance is, not what it is — [Mfcc] and [Dtw]
 * decide the rest. Anything heavier would be running a model on every second
 * of room tone, all day, for a phrase that arrives twice an hour.
 *
 * The floor adapts because the alternative is a fixed threshold, and a fixed
 * threshold is deaf in a kitchen and permanently triggered next to a fan. It
 * only moves while the room is quiet — a floor that learned during speech
 * would chase the user's own voice upward and cut them off mid-phrase.
 *
 * Pre-roll ([PREROLL_FRAMES]) matters more than it looks: a voice crosses the
 * threshold a few frames *after* it starts, and the missing onset is the
 * consonant — the 'h' of "嘿" — which is most of what distinguishes the wake
 * phrase from every other short utterance. It is also what buys the start
 * *decision* its slowness without costing the user a syllable: the detector may
 * take 80 ms to make up its mind ([START_FRAMES]) because those 80 ms are still
 * in the ring. Delaying the decision is free; discarding the audio is not.
 *
 * Not thread-safe; it belongs to whichever thread is reading the microphone.
 */
class VoiceSegmenter(private val onSegment: (ShortArray) -> Unit) {

    private val frame = ShortArray(FRAME)
    private var filled = 0

    private var floor = -1f
    private var speech = false
    private var loudRun = 0
    private var quietRun = 0

    // Counters, for [stats] and nothing else. Deliberately not logging from
    // here: this class is the DSP half and carries no Android dependency — see
    // [Mfcc] and [Dtw]. The caller owns the log.
    private var seenFrames = 0
    private var loudFrames = 0
    private var firstLoud = -1
    private var peakRms = 0f
    private var emitted = 0
    private var droppedShort = 0
    private var droppedLong = 0

    /** Pre-roll ring, and the utterance once one starts. */
    private val preroll = ArrayDeque<ShortArray>()
    private var collected = ArrayList<ShortArray>()

    fun feed(pcm: ShortArray, count: Int) {
        var off = 0
        while (off < count) {
            val take = minOf(FRAME - filled, count - off)
            System.arraycopy(pcm, off, frame, filled, take)
            filled += take
            off += take
            if (filled == FRAME) {
                filled = 0
                onFrame()
            }
        }
    }

    /** Forget any partial utterance — used when the microphone is handed away. */
    fun reset() {
        filled = 0
        speech = false
        loudRun = 0
        quietRun = 0
        preroll.clear()
        collected = ArrayList()
        floor = -1f
        // The counters describe a capture window, and this ends one — see [stats].
        seenFrames = 0
        loudFrames = 0
        firstLoud = -1
        peakRms = 0f
        emitted = 0
        droppedShort = 0
        droppedLong = 0
    }

    /**
     * What this segmenter has seen since construction, or since the last [reset].
     *
     * It exists for one question, and it is the question that turned out to be
     * unanswerable from the outside: *why did a capture window produce no
     * segment?* Four paths answer "nothing", and every one of them used to do it
     * without a word anywhere — the two guards below, [WakeMic]'s capture thread
     * giving up on a bad read, and the floor sitting above the speaker for the
     * whole window. From a log that mentions none of them, "the user said it and
     * nothing happened" is indistinguishable from "the user never spoke", which
     * is how this stayed invisible.
     *
     * `loudFrames == 0` on a window with real [frames] is the last of those four:
     * audio arrived and the detector never saw a syllable, which is a floor
     * problem and not a drop. Anything else shows up in the three counts.
     */
    fun stats(): Stats = Stats(
        frames = seenFrames,
        loudFrames = loudFrames,
        firstLoud = firstLoud,
        peakRms = peakRms,
        floorRms = if (floor < 0f) 0f else floor,
        segments = emitted,
        droppedShort = droppedShort,
        droppedLong = droppedLong,
    )

    /**
     * @param frames how many 10 ms frames arrived at all.
     * @param loudFrames how many of them crossed the start line — the one that
     *   separates "a drop ate it" from "the floor was above the speaker".
     * @param firstLoud which frame crossed first, 0-based, or -1 for never. This
     *   is the number that settles the case the rest of them only describe: a
     *   window whose first loud frame is at index 2 did not start on the user,
     *   it started on the microphone, and the segment that follows is long,
     *   dirty and — past [MAX_FRAMES] — thrown away. Measured values are 0–5 for
     *   that and 30-plus for a window that waited for the voice.
     * @param peakRms / @param floorRms the two numbers that decide whether
     *   anything ever could have, in 16-bit units.
     * @param segments / @param droppedShort / @param droppedLong what happened
     *   to the utterances that formed.
     */
    class Stats(
        val frames: Int,
        val loudFrames: Int,
        val firstLoud: Int,
        val peakRms: Float,
        val floorRms: Float,
        val segments: Int,
        val droppedShort: Int,
        val droppedLong: Int,
    ) {
        override fun toString(): String =
            "frames=$frames loud=$loudFrames firstLoud=$firstLoud peak=${peakRms.toInt()} " +
                "floor=${floorRms.toInt()} segments=$segments droppedShort=$droppedShort " +
                "droppedLong=$droppedLong"
    }

    private fun onFrame() {
        var sum = 0.0
        for (s in frame) sum += s.toDouble() * s
        val rms = sqrt(sum / FRAME).toFloat()

        if (floor < 0f) floor = rms
        val startAt = maxOf(floor * START_MULT, ABS_MIN)
        val endAt = maxOf(floor * END_MULT, ABS_MIN * 0.7f)

        seenFrames++
        if (rms > peakRms) peakRms = rms
        if (rms > startAt) {
            loudFrames++
            if (firstLoud < 0) firstLoud = seenFrames - 1
        }

        if (!speech) {
            // Only while idle, and only downward-biased: rising slowly means a
            // fan that starts up is eventually accepted as silence, while a
            // voice that lasts a second barely moves it.
            floor = if (rms < floor) floor * 0.9f + rms * 0.1f else floor * 0.995f + rms * 0.005f

            preroll.addLast(frame.copyOf())
            if (preroll.size > PREROLL_FRAMES) preroll.removeFirst()

            if (rms > startAt) {
                loudRun++
                if (loudRun >= START_FRAMES) {
                    speech = true
                    quietRun = 0
                    collected = ArrayList(preroll)
                    preroll.clear()
                }
            } else {
                loudRun = 0
            }
            return
        }

        collected.add(frame.copyOf())
        if (rms < endAt) {
            quietRun++
            if (quietRun >= END_FRAMES) {
                emit()
                return
            }
        } else {
            quietRun = 0
        }
        // Too long to be a two-syllable wake phrase. Dropped rather than
        // truncated: a sentence clipped at 1.8 s matched against the templates
        // is a coin toss, and the user talking to somebody else in the room is
        // by far the most common way to get here.
        if (collected.size > MAX_FRAMES) {
            droppedLong++
            speech = false
            loudRun = 0
            quietRun = 0
            collected = ArrayList()
        }
    }

    private fun emit() {
        val frames = collected
        speech = false
        loudRun = 0
        quietRun = 0
        collected = ArrayList()
        preroll.clear()
        if (frames.size < MIN_FRAMES) {
            droppedShort++
            return
        }
        emitted++
        val out = ShortArray(frames.size * FRAME)
        var i = 0
        for (f in frames) {
            System.arraycopy(f, 0, out, i, FRAME)
            i += FRAME
        }
        onSegment(out)
    }

    private companion object {
        /** 10 ms at 16 kHz — same grid as [Mfcc.HOP], so segment bounds land on frames. */
        const val FRAME = Mfcc.HOP

        /**
         * 300 ms of run-up, and it is load-bearing twice over.
         *
         * The original reason: a voice crosses the line a few frames *after* it
         * starts, and the missing onset is the consonant — the 'h' of "嘿" —
         * which is most of what tells the wake phrase from every other short
         * utterance. 150 ms was judged enough for that.
         *
         * The second reason is the one that turned out to matter: this ring is
         * what lets the start decision be *slow* without throwing audio away.
         * [START_FRAMES] has to be long enough that a transient cannot open a
         * segment, and whatever that decision costs in delay is paid out of
         * this ring rather than out of the user's first syllable. Measured on
         * the tablet: a user who taps and speaks immediately was losing the
         * onset to a segment that had started on the microphone's own
         * warm-up — see [MAX_FRAMES].
         */
        const val PREROLL_FRAMES = 30

        /**
         * 80 ms above the line before an utterance counts as started. Was 30 ms.
         *
         * 30 ms is three frames, which is a click, a tap on the tablet's edge,
         * or the microphone's first read after `startRecording`. Every one of
         * those opened a segment, and a segment opened early runs long — the
         * first take of every enrolment measured on this device came out
         * *longer* than the two after it, and the extra was not speech.
         */
        const val START_FRAMES = 8

        /** 300 ms of quiet ends the utterance. */
        const val END_FRAMES = 30

        /**
         * 500 ms. Raised with [PREROLL_FRAMES] so the floor still means what it
         * meant: this is `preroll + 20 frames`, the same allowance as the old
         * 35 against a 15-frame ring, so the guard keeps its old reach rather
         * than quietly becoming unreachable.
         */
        const val MIN_FRAMES = 50

        /**
         * 1.95 s — longer than the phrase can be.
         *
         * Raised by exactly the 15 frames [PREROLL_FRAMES] gained. The headroom
         * that matters is `MAX_FRAMES - PREROLL_FRAMES - END_FRAMES` — the
         * speech itself — and this keeps it at its old 135 frames. Getting this
         * wrong in the other direction would have re-created, in a new place,
         * the very bug the diagnostics were added to find: a segment dropped for
         * being too long, in silence, with the user convinced they had said it.
         */
        const val MAX_FRAMES = 195

        const val START_MULT = 3.5f
        const val END_MULT = 2.0f

        /** RMS in 16-bit units; below this it is room tone however quiet the room. */
        const val ABS_MIN = 350f
    }
}
