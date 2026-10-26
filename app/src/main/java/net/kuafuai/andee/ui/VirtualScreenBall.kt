package net.kuafuai.andee.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import net.kuafuai.andee.config.VoiceConfig
import kotlin.math.abs

/**
 * The **second** ball: the one that says something is happening on a screen the
 * person cannot see.
 *
 * Deliberately not the assistant's own ball ([FloatingWindowUi]). That one is
 * the character — face, moods, the way into a conversation — and it must keep
 * meaning exactly that. This one is a status light with a door in it: the icon
 * of the app currently being driven on the hidden screen, and a tap opens
 * `ui/VirtualScreenActivity`, where the screen can be watched and taken over by
 * hand.
 *
 * ## Why it is shaped this way
 *
 *  * **The icon, not a generic glyph.** "Which app is he in right now" is the
 *    only question this ball exists to answer at a glance, and the app's own
 *    icon answers it without a word of the interface language.
 *  * **It gets out of the way by itself.** Parked against an edge, after
 *    [IDLE_MS] without a touch it drops to [DIM_ALPHA] and slides half off the
 *    screen; a touch puts it back whole. A permanent 56 dp disc over an app the
 *    user is reading is the cost of the feature, and this is the smallest
 *    honest way to pay it.
 *  * **Size is adjustable, and remembered.** Long-press cycles the four sizes.
 *    Not a slider: a slider needs a control surface of its own, and this window
 *    has to stay too small to obscure anything (see `TapMarkerUi`'s note on the
 *    0.80 obscuring budget — every overlay this app adds spends from it).
 *
 * **Hidden from accessibility** like every other overlay here
 * ([hideFromAccessibility]): it is our furniture, and the brain reading it back
 * as app content is the bug that note is about.
 */
class VirtualScreenBall(
    private val context: Context,
    /** Opens supervision. Wired to the Activity; the ball knows nothing about it. */
    private val onTap: () -> Unit,
) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val ui = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density

    private var view: BallView? = null
    private var params: WindowManager.LayoutParams? = null
    private var icon: Bitmap? = null
    private var label: String = ""

    /** Which edge it rests against; decides which way "half hidden" goes. */
    private var onLeft = true

    /** True while it is dimmed and half off-screen. */
    private var dimmed = false

    private val dim = Runnable { applyDim() }

    fun isShowing(): Boolean = view != null

    /**
     * Put the ball up for [pkg] and label it [label].
     *
     * Returns false when the window cannot be added — no overlay grant is the
     * usual cause, and it is the same condition `SelfCheck.overlay` reports, so
     * the caller treats it as "the user cannot see the hidden screen" and says
     * so rather than pretending the feature is invisible-by-design.
     */
    fun show(pkg: String, label: String): Boolean {
        hide()
        this.label = label
        icon = loadIcon(pkg)
        val size = (VoiceConfig.virtualBallSizeDp(context) * density).toInt()
        val v = BallView(size)
        v.hideFromAccessibility()
        val p = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Not focusable: it must never take a key from the app underneath.
            // NOT_TOUCH_MODAL is implied by that; NO_LIMITS lets the half-off
            // position actually stay half off instead of being clamped back.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            fitInsetsTypes = 0
            x = screenW() - size
            y = screenH() / 3
            alpha = 1f
        }
        v.setOnTouchListener(dragAndTap(size, p))
        val ok = runCatching { wm.addView(v, p) }.isSuccess
        if (!ok) return false
        view = v
        params = p
        onLeft = false
        dimmed = false
        armDim()
        return true
    }

    fun hide() {
        ui.removeCallbacks(dim)
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        params = null
        icon = null
        dimmed = false
    }

    // ---- touch ----------------------------------------------------------

    /**
     * Drag, tap, long-press — three gestures on one 56 dp disc.
     *
     * The threshold is a finger's wobble, not a design token: a tap that moves
     * 3 px is still a tap, and a long press that moves 40 px was a drag that
     * started slowly.
     */
    private fun dragAndTap(size: Int, p: WindowManager.LayoutParams): View.OnTouchListener {
        var downAt = 0L
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        return View.OnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Restore first: it moves the window, and a drag measured from
                    // the old x would jump by half a ball on the first MOVE.
                    restoreFromDim()
                    downAt = SystemClock.uptimeMillis()
                    downX = e.rawX
                    downY = e.rawY
                    startX = p.x
                    startY = p.y
                    dragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > SLOP_PX || abs(dy) > SLOP_PX)) {
                        dragging = true
                        ui.removeCallbacks(dim)
                    }
                    if (dragging) {
                        p.x = (startX + dx).toInt()
                        p.y = (startY + dy).toInt()
                        runCatching { wm.updateViewLayout(view, p) }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val held = SystemClock.uptimeMillis() - downAt
                    if (!dragging && held >= LONG_PRESS_MS) {
                        cycleSize()
                    } else if (!dragging && held < LONG_PRESS_MS) {
                        onTap()
                    } else {
                        snapToEdge(size, p)
                    }
                    armDim()
                    true
                }

                else -> false
            }
        }
    }

    /** Park against whichever edge is nearer, whole and fully opaque. */
    private fun snapToEdge(size: Int, p: WindowManager.LayoutParams) {
        onLeft = p.x + size / 2 < screenW() / 2
        p.x = if (onLeft) 0 else screenW() - size
        p.y = p.y.coerceIn(0, (screenH() - size).coerceAtLeast(0))
        p.alpha = 1f
        dimmed = false
        runCatching { wm.updateViewLayout(view, p) }
    }

    private fun armDim() {
        ui.removeCallbacks(dim)
        ui.postDelayed(dim, IDLE_MS)
    }

    /**
     * Slide half off the screen and fade.
     *
     * The x is set past the edge rather than clipped by a smaller window: the
     * window keeps its size, so the visible half is still a touch target and one
     * tap brings the whole ball back — a shrunk window would put the tap target
     * under the user's finger centre, which is exactly where it is not.
     */
    private fun applyDim() {
        val p = params ?: return
        val v = view ?: return
        val size = p.width
        dimmed = true
        p.alpha = DIM_ALPHA
        p.x = if (onLeft) -size / 2 else screenW() - size / 2
        runCatching { wm.updateViewLayout(v, p) }
    }

    private fun restoreFromDim() {
        if (!dimmed) return
        val p = params ?: return
        val v = view ?: return
        val size = p.width
        dimmed = false
        p.alpha = 1f
        p.x = if (onLeft) 0 else screenW() - size
        runCatching { wm.updateViewLayout(v, p) }
    }

    /**
     * Long-press cycles the size and rebuilds the window in place.
     *
     * A rebuild rather than a resize because a window's width and height are
     * fixed at add time; `updateViewLayout` with a new size is honoured on some
     * ROMs and not others, and "the ball got bigger but its frame did not" is a
     * bug that reads as a drawing error.
     */
    private fun cycleSize() {
        val now = VoiceConfig.virtualBallSizeDp(context)
        val next = SIZES_DP[(SIZES_DP.indexOf(now).takeIf { it >= 0 }?.plus(1) ?: 0) % SIZES_DP.size]
        VoiceConfig.setVirtualBallSizeDp(context, next)
        val old = params ?: return
        val v = view ?: return
        val size = (next * density).toInt()
        val x = old.x
        val y = old.y
        val alpha = old.alpha
        runCatching { wm.removeView(v) }
        val fresh = BallView(size)
        fresh.hideFromAccessibility()
        val np = WindowManager.LayoutParams(size, size, old.type, old.flags, old.format).apply {
            gravity = Gravity.TOP or Gravity.START
            fitInsetsTypes = 0
            this.x = x
            this.y = y
            this.alpha = alpha
        }
        fresh.setOnTouchListener(dragAndTap(size, np))
        if (!runCatching { wm.addView(fresh, np) }.isSuccess) {
            // Put the old one back rather than leaving the user with no ball at
            // all — the size is written, so the next show() will honour it.
            view = null
            params = null
            return
        }
        view = fresh
        params = np
    }

    // ---- drawing --------------------------------------------------------

    /**
     * A disc with the app's icon inside it.
     *
     * The ring is drawn in the glass palette rather than the app's colour: the
     * icon already carries the identity, and a coloured ring around a coloured
     * icon is two signals fighting. It also keeps this window from looking like
     * a notification badge, which is the one thing a user would try to swipe
     * away and be unable to.
     */
    private inner class BallView(size: Int) : View(context) {
        private val px = size
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
            color = Color.parseColor(Glass.SECONDARY)
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(Glass.ACCENT)
            alpha = 30
        }
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }

        override fun onDraw(canvas: Canvas) {
            val r = px / 2f
            canvas.drawCircle(r, r, r - ring.strokeWidth, fill)
            canvas.drawCircle(r, r, r - ring.strokeWidth, ring)
            val bmp = icon
            if (bmp != null) {
                val side = px * 0.56f
                val left = (px - side) / 2f
                canvas.drawBitmap(
                    bmp, null,
                    android.graphics.RectF(left, left, left + side, left + side),
                    iconPaint,
                )
            } else {
                val text = label.take(1)
                if (text.isNotEmpty()) {
                    val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.parseColor(Glass.TITLE)
                        textSize = px * 0.42f
                        textAlign = Paint.Align.CENTER
                    }
                    canvas.drawText(text, r, r + tp.textSize * 0.35f, tp)
                }
            }
        }
    }

    // ---- helpers --------------------------------------------------------

    private fun loadIcon(pkg: String): Bitmap? = runCatching {
        val d = context.packageManager.getApplicationIcon(pkg)
        val size = (96 * density).toInt()
        if (d is BitmapDrawable && d.bitmap != null) {
            // Already a bitmap: scale down rather than redraw, so a vector-ish
            // icon and a bitmap one do not come out at different visual weights.
            return@runCatching Bitmap.createScaledBitmap(d.bitmap, size, size, true)
        }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, size, size)
        d.draw(c)
        bmp
    }.getOrNull()

    private fun screenW(): Int = context.resources.displayMetrics.widthPixels

    private fun screenH(): Int = context.resources.displayMetrics.heightPixels

    private companion object {
        /** Long enough that a considered tap never becomes a resize. */
        const val LONG_PRESS_MS = 600L

        /** A finger's wobble, in pixels, before a touch counts as a drag. */
        const val SLOP_PX = 12f

        /** How long it sits untouched before getting out of the way. */
        const val IDLE_MS = 8_000L

        /** Present, not loud. Half off the screen it is a hint, not a widget. */
        const val DIM_ALPHA = 0.35f

        val SIZES_DP = intArrayOf(36, 48, 56, 72)
    }
}
