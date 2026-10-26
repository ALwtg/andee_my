package net.kuafuai.andee.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import net.kuafuai.andee.R
import net.kuafuai.andee.i18n.AppLocale
import net.kuafuai.andee.screen.VirtualScreens
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Supervision, and a hand — the door in the background screen's ball opens here.
 *
 * Three jobs, in the order the user needs them:
 *
 *  1. **Show it.** The screen nobody is looking at is polled as PNG frames and
 *     drawn. A feature that drives an app where the user cannot watch is not
 *     something this project is willing to ship quietly, so the picture is the
 *     point of the page rather than a debug extra.
 *  2. **Let them take over.** A tap on the picture is injected at the same place
 *     on the hidden screen; the back key and a text field do the same through
 *     `input`. If the model is halfway through something and the user sees it
 *     going wrong, this is where they stop it — with the same hand, not a
 *     conversation.
 *  3. **Let them end it.** Closing the screen from here closes it everywhere
 *     ([VirtualScreens.closeAll]); the ball goes away with it.
 *
 * **Why an Activity and not a card.** The card is an overlay, and an overlay
 * cannot raise the soft keyboard on this ROM (see `ui/TextInputActivity`'s
 * note). Typing into the hidden screen is half of manual intervention, so this
 * has to be a real window.
 *
 * **The frames are slow on purpose.** `screencap -d` is a full round trip
 * through another process — a few hundred kilobytes and a few hundred
 * milliseconds each time. Polling at [POLL_MS] is enough to watch a list settle
 * and far below the rate at which it would cost the phone anything. Nothing is
 * requested while a frame is still in flight, so a slow frame lengthens the
 * interval instead of queueing up.
 */
class VirtualScreenActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private val inFlight = AtomicBoolean(false)

    private lateinit var picture: ImageView
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var typeField: EditText

    private var displayId = 0
    private var pkg = ""

    /** The Activity's own resources, in the user's language — see `AppLocale`. */
    private val lctx: Context by lazy { AppLocale.wrap(this) }

    /** How the bitmap is fitted into the view; needed to aim a tap. */
    private var fitScale = 1f
    private var fitOffX = 0f
    private var fitOffY = 0f

    /**
     * Explicitly typed: the body re-posts `this`, and a val whose type is being
     * inferred from an object that refers to itself is the one shape Kotlin
     * refuses.
     */
    private val poll: Runnable = object : Runnable {
        override fun run() {
            if (displayId == 0) return
            if (!inFlight.compareAndSet(false, true)) {
                ui.postDelayed(this, POLL_MS)
                return
            }
            Thread {
                val bytes = VirtualScreens.frame(displayId)
                val bmp = bytes?.let {
                    runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull()
                }
                ui.post {
                    inFlight.set(false)
                    if (isFinishing || isDestroyed) return@post
                    if (bmp != null) {
                        show(bmp)
                        status.visibility = View.GONE
                    } else {
                        status.visibility = View.VISIBLE
                        status.text = lctx.getString(R.string.vscreen_no_frame)
                    }
                    ui.postDelayed(poll, POLL_MS)
                }
            }.apply { isDaemon = true }.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val screen = VirtualScreens.state()
        if (screen == null) {
            // Nothing to supervise: the screen was closed from the card between
            // the tap and this page opening. Say so and leave.
            finish()
            return
        }
        displayId = screen.displayId
        pkg = screen.pkg
        setContentView(build(screen.label))
        VirtualScreens.onChanged = { ui.post { if (VirtualScreens.state() == null) finish() } }
    }

    override fun onResume() {
        super.onResume()
        if (displayId != 0) ui.post(poll)
    }

    override fun onPause() {
        ui.removeCallbacks(poll)
        super.onPause()
    }

    override fun onDestroy() {
        ui.removeCallbacks(poll)
        // Leave the hook as it was, not as a closure over a dead Activity: the
        // service installs its own, and a stale one here would keep finishing a
        // page that no longer exists.
        VirtualScreens.onChanged = null
        super.onDestroy()
    }

    // ---- layout ---------------------------------------------------------

    private fun build(label: String): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        title = TextView(this).apply {
            text = getString(R.string.vscreen_title, label, displayId)
            textSize = 15f
            setTextColor(Color.WHITE)
        }
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(
            chip(getString(R.string.vscreen_close)) { closeScreen() },
        )
        bar.addView(
            chip(getString(R.string.vscreen_done)) { finish() },
        )
        root.addView(bar)

        picture = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            setOnTouchListener { v, e -> onPictureTouch(v, e) }
        }
        root.addView(picture, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        status = TextView(this).apply {
            text = getString(R.string.vscreen_waiting)
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#9AA4B2"))
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        root.addView(status)

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(14))
        }
        bottom.addView(chip(getString(R.string.vscreen_back_key)) { send { VirtualScreens.key(displayId, 4) } })
        typeField = EditText(this).apply {
            hint = getString(R.string.vscreen_type_hint)
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#6B7684"))
            isSingleLine = true
            setBackgroundColor(Color.parseColor("#1A1F26"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        bottom.addView(typeField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottom.addView(chip(getString(R.string.vscreen_send)) {
            val text = typeField.text?.toString().orEmpty()
            if (text.isNotEmpty()) {
                typeField.setText("")
                send { VirtualScreens.type(displayId, text) }
            }
        })
        root.addView(bottom)
        return root
    }

    private fun chip(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 13f
        setTextColor(Color.WHITE)
        background = Glass.tinted(this@VirtualScreenActivity, dp(20))
        setPadding(dp(14), dp(8), dp(14), dp(8))
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = dp(8) }
    }

    // ---- frames ---------------------------------------------------------

    /**
     * Fit the frame and remember the mapping, so a tap can be turned back into
     * a coordinate on the hidden display.
     *
     * The picture is the *whole* hidden screen at its own resolution, so this is
     * the entire coordinate story: no aim grid, no 0-1000 space — those exist to
     * survive a model's arithmetic, and a finger that lands on a pixel does not
     * need them.
     */
    private fun show(bmp: Bitmap) {
        picture.setImageBitmap(bmp)
        val vw = picture.width.toFloat()
        val vh = picture.height.toFloat()
        if (vw <= 0f || vh <= 0f) return
        fitScale = min(vw / bmp.width, vh / bmp.height)
        fitOffX = (vw - bmp.width * fitScale) / 2f
        fitOffY = (vh - bmp.height * fitScale) / 2f
    }

    private fun onPictureTouch(v: View, e: MotionEvent): Boolean {
        if (e.actionMasked != MotionEvent.ACTION_UP) return true
        if (fitScale <= 0f) return true
        val x = ((e.x - fitOffX) / fitScale).toInt()
        val y = ((e.y - fitOffY) / fitScale).toInt()
        if (x < 0 || y < 0) return true
        // The shell does the injection; a few hundred milliseconds later the
        // next polled frame shows what it did.
        send { VirtualScreens.tap(displayId, x, y) }
        return true
    }

    private fun send(action: () -> Boolean) {
        Thread {
            action()
            ui.post { if (!isFinishing) ui.post(poll) }
        }.apply { isDaemon = true }.start()
    }

    private fun closeScreen() {
        status.visibility = View.VISIBLE
        status.text = getString(R.string.vscreen_closing)
        Thread {
            VirtualScreens.closeAll(applicationContext)
            ui.post { finish() }
        }.apply { isDaemon = true }.start()
    }

    private fun dp(v: Int): Int = Glass.dp(this, v)

    companion object {
        /** Fast enough to watch a list settle, slow enough for a PNG round trip. */
        private const val POLL_MS = 700L

        /** Open the page for the screen that is up; a no-op when there is none. */
        fun open(context: Context): Boolean {
            if (VirtualScreens.state() == null) return false
            val ok = runCatching {
                context.startActivity(
                    Intent(context, VirtualScreenActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.isSuccess
            return ok
        }
    }
}
