package net.kuafuai.andee.ui

import android.content.Context
import net.kuafuai.andee.config.VoiceConfig

/**
 * Which of [Glass]'s two palettes everything is drawing with, and how a change
 * reaches what is already on screen.
 *
 * **Why a process-global flag rather than a palette handed around with a Context.**
 * [Glass]'s colours are read as bare names from about forty call sites —
 * `Color.parseColor(Glass.LABEL)` inside view builders, most of them several frames
 * away from anything that has a Context to consult. Threading a Context (or a
 * palette) through all of them to answer a question whose answer is the same for the
 * whole process would be a change to every UI file to buy nothing. So the palette
 * resolves a flag, and the flag is set here.
 *
 * **The price of that choice is the thing to be careful about:** a `Glass.*` read is
 * a read of whatever the theme was *when it ran*. A view built before a change keeps
 * the old colours until it is rebuilt. That is why [set] fires [onChanged] rather
 * than leaving callers to remember — nobody flips this and hopes.
 *
 * Shaped after [net.kuafuai.andee.i18n.AppLocale], which solves the same problem for
 * the language and for the same reason: almost no UI here lives in an Activity, so no
 * system mechanism can reach it. `values-night/` resources and
 * `AppCompatDelegate.setDefaultNightMode` both rebuild Activities; the ball, the
 * card, the signboards and the settings sheet are `WindowManager` overlays owned by a
 * `Service` and would keep the old palette until something happened to rebuild them.
 * A theme is a setting here in exactly the way the language is, including that it
 * outranks the system.
 */
object Theme {

    /**
     * The two values the picker, the prefs and [set] agree on.
     *
     * [NIGHT] is the string that goes to disk; [DEVICE] is deliberately absent, because
     * following the system would need a reload on every configuration change in a
     * process whose UI is a Service overlay — see the class note. Light and dark are
     * the whole vocabulary.
     */
    const val DAY = "day"
    const val NIGHT = "night"

    /**
     * The state of the switch. Set by [load] and [set]; read by [Glass].
     *
     * **Initialised to night, because night is the default**
     * ([VoiceConfig.nightTheme]). [load] is what makes that true from the saved
     * preference, but this value is the one that must not be *wrong* in the window
     * between process start and the service's first line: a surface drawn there would
     * otherwise be light on a device whose answer is dark, and then flip.
     */
    @Volatile
    var night: Boolean = true
        private set

    /**
     * Told after every change, on the thread that made it.
     *
     * One listener rather than a list, because there is exactly one owner of the live
     * UI — [net.kuafuai.andee.BodyCoreService]; a list would be an invitation for a
     * card to register itself and never unregister.
     */
    @Volatile
    var onChanged: (() -> Unit)? = null

    /**
     * Adopt the saved preference.
     *
     * Call once and **before the first view is built** — this is the only thing that
     * decides what colour a surface created in the next frame will be, and a service
     * that reads it late is a service whose first card is in the other theme.
     */
    fun load(context: Context) {
        night = VoiceConfig.nightTheme(context)
    }

    /**
     * Save, adopt, and tell everyone.
     *
     * A no-op when the answer has not changed, so the picker can call it blindly on
     * every tap without writing the same value twice — the eager-write pattern
     * [VoiceConfig.uiLanguage] uses for exactly the same reason.
     */
    fun set(context: Context, wantNight: Boolean) {
        if (wantNight == night) return
        VoiceConfig.save(context, mapOf("theme" to if (wantNight) "night" else "day"))
        night = wantNight
        onChanged?.invoke()
    }
}
