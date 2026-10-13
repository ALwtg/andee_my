package net.kuafuai.andee.ui.ball

/**
 * Where one hand wants to be, in the ball's own space (body radius 0.87, +X to the
 * viewer's right, +Y up). The hand sits on the body's surface at that x/y, so
 * only two numbers are needed; its depth is worked out at draw time.
 *
 * Hands are the part of the face the old moods had no word for. A closed fist
 * under the chin says *thinking*, two raised fists say *we did it*, a hand over
 * the belly says *resting* — none of which a round eye and a curved mouth can
 * say, and all of which survive being drawn a thumb wide because they are
 * shapes, not colours.
 */
class HandPose(
    val x: Float,
    val y: Float,
    /** 0 = tucked away. The hand grows from nothing at its own spot, it does not fly in. */
    val s: Float,
    /**
     * Degrees counter-clockwise on screen, for *this* hand as drawn. The geometry
     * is mirrored for the left hand but the angle is not: a raised left fist
     * leaning outward is a positive roll, a raised right one a negative.
     */
    val roll: Float = 0f,
    /** 0 = open mitten, 1 = fist. */
    val fist: Float = 0f,
    /** Idle motion: a swing of the wrist if [wave], otherwise a small vertical bob. */
    val jig: Float = 0f,
    val wave: Boolean = false,
)

/**
 * Everything a mood adds on top of [Face], and the reason the two are separate
 * classes: [Face] is a row of numbers every look interprets, and each number is
 * a *continuous* dial. These are the discrete things — a wink, a hand, a star —
 * that only the looks with [BallLook.gestures] draw. A look without it ignores
 * the whole table, so adding a mood here can never change what the other five
 * characters look like.
 *
 * Each value is a target, not a state. [EmotionState] tweens toward it, which is
 * what makes a hand slide to the chin instead of blinking onto it, and a star
 * pop in instead of appearing.
 */
class Pose(
    /** The viewer's-right eye closes into an arc and the other stays open. */
    val wink: Float = 0f,
    /** Where the eyes look, −1..1; positive X is the viewer's right, positive Y is up. */
    val lookX: Float = 0f,
    val lookY: Float = 0f,
    /**
     * 0 = round eyes, 1 = the top of each eye sliced flat and rolled inward: a
     * determined look. **Negative rolls the other way and slices nothing** — the
     * slant of a worried brow, which is what the sad rows need: their [Face.brow]
     * rolls the eyes the angry way, and this is what turns it back.
     */
    val lidCut: Float = 0f,
    /** Resting body squash on top of breathing. Negative Y flattens, positive X widens. */
    val sqx: Float = 0f,
    val sqy: Float = 0f,
    /**
     * Where the horns rest, in degrees from their usual lean: **negative stands them
     * up, positive lets them flop outward.** The spring in [EmotionState] settles
     * on it, which is why a mood change makes them spring rather than snap. Perked
     * for curious, listening and celebrating; flopped for sad, tired and failed —
     * the one part of the head that can show a mood while the face is looking away.
     */
    val hornLean: Float = 0f,
    val handL: HandPose = HAND_OFF_L,
    val handR: HandPose = HAND_OFF_R,
    val stars: Float = 0f,
    val sparkL: Float = 0f,
    /** A small heart floating beside the face. */
    val heart: Float = 0f,
    val sparkTop: Float = 0f,
    val shield: Float = 0f,
    val drop: Float = 0f,
    /** Closed eyes drawn as `⌣` instead of `⌢`: a lid, not a laugh. Not tweened — it flips. */
    val arcFlip: Boolean = false,
) {
    companion object {
        val NONE = Pose()
    }
}

const val PROP_STARS = 0
const val PROP_SPARK_L = 1
const val PROP_HEART = 2
const val PROP_SPARK_TOP = 3
const val PROP_SHIELD = 4
const val PROP_DROP = 5
const val PROP_COUNT = 6

val HAND_OFF_L = HandPose(-0.62f, -0.15f, 0f)
val HAND_OFF_R = HandPose(0.62f, -0.15f, 0f)

/** The wave [Mood.WAVE] raises: a left hand up at the side, swinging. */
val HAND_WAVE = HandPose(-0.96f, 0.05f, 1f, roll = -10f, jig = 1f, wave = true)

/**
 * The poses. The numbers were read off the reference sketches (wink, wave, think,
 * rest, protect, celebrate) the same way the look's own were off the picture:
 * measured on the sketch, divided by the head's width, times the body's 1.74
 * diameter. Where there was no sketch — the work moods, the worried ones — they
 * are the nearest gesture a person would make.
 */
val POSES: Map<Mood, Pose> = mapOf(
    Mood.CALM to Pose(),

    // A pleased face with a little burst above the head. No hands: a happy face is
    // the one that most needs its face visible.
    Mood.HAPPY to Pose(sparkTop = 1f, hornLean = -8f),

    // Eyes up and to one side, as if something is on the shelf above you, and the
    // horns stand straight up to listen to it.
    Mood.CURIOUS to Pose(lookX = 0.6f, lookY = 0.6f, hornLean = -18f),

    // Annoyed rather than hurt: the lids come down, the horns lie back a little.
    Mood.TENSE to Pose(lidCut = 0.8f, hornLean = 10f),

    // Fists drawn in at the chest, a bead of sweat, and the shiver the Face row
    // already supplies.
    Mood.ANXIOUS to Pose(
        drop = 1f, lookY = 0.1f, lidCut = -1f, hornLean = 14f,
        handL = HandPose(-0.24f, -0.46f, 0.8f, roll = 25f, fist = 1f, jig = 0.12f),
        handR = HandPose(0.24f, -0.46f, 0.8f, roll = -25f, fist = 1f, jig = 0.12f),
    ),

    // Hands folded low, eyes cast a little down: sorry, and about to say so.
    Mood.CONCERNED to Pose(
        lookY = -0.2f, lidCut = -1f, hornLean = 18f,
        handL = HandPose(-0.17f, -0.64f, 0.85f, roll = 30f, fist = 0.4f),
        handR = HandPose(0.17f, -0.64f, 0.85f, roll = -30f, fist = 0.4f),
    ),

    // wink.webp — a closed eye and a heart floating beside the cheek. The reference
    // has a pointing hand; four hands were tried here (a V, a pointing finger, a
    // thumbs up, a fat thumb) and every one misread at eighty dp — the pointer as the
    // rude finger — so the wink carries a heart, which cannot be misread.
    Mood.WINK to Pose(wink = 1f, heart = 1f, hornLean = -6f),

    // wave.webp — a hand up at the side, swinging, with its motion lines.
    Mood.WAVE to Pose(sparkL = 1f, handL = HAND_WAVE, hornLean = -10f),

    // protect.webp — brows set, a fist at the ready and a shield held out.
    Mood.PROTECT to Pose(
        lidCut = 1f, shield = 1f, hornLean = -8f,
        handL = HandPose(-0.84f, -0.50f, 1f, roll = 20f, fist = 1f),
    ),

    // celebrate.webp — both fists in the air, stars, a grin; the horns spring.
    Mood.CELEBRATE to Pose(
        stars = 1f, sparkTop = 1f, hornLean = -16f,
        handL = HandPose(-1.12f, 0.0f, 1f, roll = 18f, fist = 1f, jig = 1f),
        handR = HandPose(1.12f, 0.0f, 1f, roll = -18f, fist = 1f, jig = 1f),
    ),

    // rest.webp — squashed down, eyes shut, both hands on the belly, horns slack.
    Mood.REST to Pose(
        sqx = 0.08f, sqy = -0.13f, arcFlip = true, hornLean = 24f,
        handL = HandPose(-0.47f, -0.50f, 0.9f, roll = 20f, fist = 0.2f),
        handR = HandPose(0.55f, -0.46f, 0.9f, roll = -20f, fist = 0.2f),
    ),

    // A hand cupped behind the ear: the mic is open, and the horns are up too.
    Mood.LISTENING to Pose(
        hornLean = -16f,
        handL = HandPose(-0.98f, 0.10f, 0.95f, roll = -25f, fist = 0f),
    ),

    // Holding the signboard up: both fists raised on either side.
    Mood.HOLDING to Pose(
        handL = HandPose(-0.96f, 0.40f, 0.85f, roll = 15f, fist = 1f, jig = 0.25f),
        handR = HandPose(0.96f, 0.40f, 0.85f, roll = -15f, fist = 1f, jig = 0.25f),
    ),

    // think.webp — eyes up and to the side, a fist under the chin.
    Mood.THINKING to Pose(
        lookX = 0.5f, lookY = 0.9f, hornLean = -8f,
        handL = HandPose(-0.04f, -0.66f, 1f, roll = 25f, fist = 1f),
    ),

    // The mouth is the talk envelope's, which opens the thin smile rather than
    // swapping it for another shape. No hands, so the face is clear while it is
    // being listened to.
    Mood.SPEAKING to Pose(),

    // Driving an app: a fist at the glass, tapping. It was one finger out, and a
    // single finger standing up beside the face read as the rude one — two fingers
    // leaned 35° overlap into a lone vertical bar. A fist has no such reading.
    Mood.SEARCHING to Pose(
        lookY = -0.15f, hornLean = -4f,
        handR = HandPose(0.80f, -0.40f, 0.9f, roll = 15f, fist = 1f, jig = 0.9f),
    ),

    Mood.SUCCESS to Pose(
        stars = 1f, sparkTop = 1f, hornLean = -16f,
        handL = HandPose(-1.0f, 0.0f, 1f, roll = 18f, fist = 1f, jig = 1f),
        handR = HandPose(1.0f, 0.0f, 1f, roll = -18f, fist = 1f, jig = 1f),
    ),

    // Eyes down, a bead of sweat, slumped, horns flopped.
    Mood.FAILED to Pose(lookY = -0.5f, lidCut = -1.2f, drop = 1f, sqy = -0.05f, hornLean = 24f),

    Mood.SLEEPING to Pose(
        sqx = 0.08f, sqy = -0.13f, arcFlip = true, hornLean = 24f,
        handL = HandPose(-0.47f, -0.50f, 0.9f, roll = 20f, fist = 0.2f),
        handR = HandPose(0.55f, -0.46f, 0.9f, roll = -20f, fist = 0.2f),
    ),
)
