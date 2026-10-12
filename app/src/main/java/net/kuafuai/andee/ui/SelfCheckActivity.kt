package net.kuafuai.andee.ui

import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.provider.Settings
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import net.kuafuai.andee.BodyCoreService
import net.kuafuai.andee.ScreenBodyService
import net.kuafuai.andee.config.FactoryReset
import net.kuafuai.andee.config.Notebook
import net.kuafuai.andee.config.VoiceConfig
import net.kuafuai.andee.i18n.AppLocale

/**
 * The one screen in this app a user can reach while the app is not working.
 *
 * ### What this class used to be, and what it is now
 *
 * The checklist was a fullscreen page: this Activity folded the assistant away,
 * perked the ball, and drew the rows over its own blurred backdrop. It is now a
 * **card**, opened from `✓` in the assistant's control bar and rendered by
 * [SelfCheckUi] **inside the assistant's own window** — see that class for why
 * two buttons sitting next to each other have to open the same kind of surface.
 *
 * So on a working device this Activity is never *seen*: the icon starts it, it
 * hands the request to the service and finishes — see the next section. All the
 * drawing it used to do lives in [SelfCheckUi] now, and what is left here is the
 * case the card cannot cover.
 *
 * ### Why it still has to exist, and why it still has to be in the launcher
 *
 * Everything else here is an overlay owned by [BodyCoreService]. That used to
 * make this page the only way in *and* the only place that could explain why
 * there was no way in: the overlays belonged to an accessibility service, so
 * with accessibility off there was no ball, no card, and no surface on which to
 * describe the absence. The body no longer dies with that grant — a revoked
 * service now costs screen control and nothing else, and the ball stays up —
 * but that has not made this page redundant. It is still the only surface that
 * runs *before* the body does, and it is still the only one that can *render*
 * [net.kuafuai.andee.device.SelfCheck.Level.FAIL] on `accessibility` rather
 * than merely believe it: a red row about accessibility has to be drawn by
 * something the accessibility service is not holding.
 *
 * It also does the one job no other component can. It starts the body, and it
 * is the single context in the app allowed to claim the microphone type for it
 * — the platform refuses a microphone-typed foreground service created from the
 * background, and every other starter is background (the ROM handing
 * accessibility back, the notification's own action, boot). See
 * `BodyCoreService.promoteForMicrophone`. That is also why the launcher entry
 * matters: a door you can only reach from inside the broken room is not a door.
 *
 * ### The icon opens two doors, and the body decides which
 *
 * Being the launcher entry point does not make this page what the icon *means*.
 * With the body up, tapping the icon does what a long-press on the ball does —
 * `dispatcher.expand()` — and this Activity finishes without drawing a frame;
 * on a working device it is therefore still never *seen*, only started and
 * dropped. With the body down it falls through to the checklist, because there
 * is nothing to unfold and this is the only surface that can say why.
 *
 * One icon, two behaviours, and the branch is not a heuristic: [onCreate] asks
 * whether the ball is actually on screen — and asks in the one way that also
 * puts it there if the only thing wrong was a grant that arrived too late (see
 * `BodyCoreService.ensureOverlays`). It also has to allow for being a frame
 * early: starting the body is asynchronous, so a device that is perfectly well
 * can still read "no body yet" here. See the second branch in [onCreate]. All
 * of that is the load-bearing half of the original argument, unchanged — the
 * launcher entry exists so that a device whose assistant is not running still
 * has somewhere to go — and the other half still holds too: a device whose
 * assistant *is* running does not pay for the launcher's existence by being
 * taken to a diagnostic page every time the user taps the app.
 *
 * See `Theme.Body.Stage.NoPreview` for the other half of making the first door
 * invisible: without it the platform paints a black starting window for a page
 * that is about to finish, and "tap the icon, then the card grows" reads as
 * "tap the icon, the screen goes black, then the card grows".
 *
 * ### A host, not a second implementation
 *
 * All this class does now is give [SelfCheckUi] a `stage` to put the card in
 * instead of a window to make. That is the whole reason the look cannot drift:
 * there is one builder, and it is the same one the working device sees. The two
 * hosts differ in exactly two things — where the card goes, and whether we may
 * ask the compositor to blur — and both of those are [SelfCheckUi]'s to decide.
 *
 * ### The backdrop is what makes the card's fill honest
 *
 * [SelfCheckUi] hands the card the *frosted* fill unconditionally when it is
 * hosted, on the reasoning that the blur behind it is real and it did not have
 * to ask for it. That is only true because of [installBackdrop]: without it,
 * "frosted" is 60% black over this Activity's black window, which is a black
 * rectangle. Same blurred image and the same *derived* scrim the assistant's
 * card brings up, via the same `CENTER_CROP` recipe [TextInputActivity] uses —
 * see [Backdrop] for why "same object, same scrim" beats "a similar dark
 * colour".
 *
 * ### Language
 *
 * [SelfCheckUi] carries the picker and writes the preference; this class only
 * has to notice a change made *somewhere else*. There is exactly one such path
 * and it is reachable from this card: the 打开设置 row opens the assistant's
 * settings sheet, and *its* picker writes the same key. [LangToggle]'s own
 * callback has already told the service by the time the sheet closes; this is
 * the other direction, and [onResume] is where it is caught.
 *
 * Note this page is no longer the thing that paints the rows, so the old
 * `lctx`-as-`var` rule now lives in [SelfCheckUi]. [paintedLang] survives because
 * the *comparison* it makes is still the only way to notice the other path, not
 * because this class draws anything with it.
 */
class SelfCheckActivity : StageActivity() {

    companion object {
        /**
         * Start-intent extra: ask for the screen-capture consent as soon as
         * this page is up.
         *
         * Set by the body's notification action, which is the only way in —
         * `MediaProjectionManager.createScreenCaptureIntent()` needs an
         * Activity, and this is the only one the app has. See
         * [requestScreenCapture].
         */
        const val EXTRA_SCREEN_CAPTURE = "screen_capture"
    }

    /**
     * The card. Built here on the service-off path only, placed on [stage], and
     * gone with the Activity.
     *
     * `lateinit` rather than nullable because on this path it is assigned in
     * [onCreate] before anything can reach it. Both the callbacks that *could*
     * arrive first — [onResume] and [onDestroy] — check `isInitialized`, because
     * the other path through [onCreate] returns before this is ever assigned and
     * a lifecycle callback is not a good moment to throw.
     */
    private lateinit var ui: SelfCheckUi

    /**
     * The fullscreen backdrop. Built once and **never rebuilt** — see
     * [installBackdrop].
     */
    private var backdropView: ImageView? = null

    /**
     * The language this page is currently painted in, to notice a change made
     * somewhere else. See [onResume].
     */
    private var paintedLang = ""

    /**
     * Whether this page has already been resumed once.
     *
     * [onCreate] runs the check itself ([SelfCheckUi.start]), and the first
     * resume lands immediately behind it — same launch, no user in between, so
     * there is nothing new to read and a refresh there would be a local pass for
     * nothing. Worse than nothing, in fact: that first pass's probe is still on
     * its socket, and the refresh would overwrite the rows it is about to fill
     * in with the placeholders it is about to replace.
     *
     * Every resume *after* this one is the case the refresher is for. See
     * [onResume].
     */
    private var resumedOnce = false

    /**
     * Our settings sheet while it is up — see [openOwnSettings]. Held only so a
     * second tap on 打开设置 does not stack a second window on the first.
     */
    private var settings: SettingsUi? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The notification's 允许看屏幕 action lands here — see
        // [requestScreenCapture]. Asked before the branch below, because the
        // point of the action is that the page it opens is a means to an end.
        if (intent?.getBooleanExtra(EXTRA_SCREEN_CAPTURE, false) == true) {
            requestScreenCapture()
        }

        // A working assistant ⇒ this icon means what every other icon on the
        // home screen means: open the app. That is the *same request* a
        // long-press on the ball makes, so it is the same call — one entry point
        // cannot be allowed to mean "show me the assistant" and the other
        // "show me a checklist", since which one the user gets would then depend
        // on which door happened to be on screen. `expand()` is idempotent, so
        // tapping the icon with the card already full is a no-op, exactly as it
        // is on the ball.
        //
        // Then `finish()` and nothing else: this Activity has no window worth
        // showing, and `Theme.Body.Stage.NoPreview` is what stops the platform
        // painting a black starting window for a page that is about to vanish.
        //
        // `ballWindow()` and not merely the instance, because a service that is
        // alive but has not attached its overlay yet is not something to unfold
        // — same guard `openSettingsForUser` uses for the same reason.
        //
        // And `ensureOverlays()` rather than a bare `isShown()` test, because
        // the interesting case is the one where the answer is *no but fixable*:
        // the service connected while the overlay grant was missing (a fresh
        // install, a package rename), `addView` was refused, and the grant
        // arrived afterwards. Asking the question is also the retry, so the icon
        // is the way back rather than a door that reports the room is empty —
        // see `BodyCoreService.ensureOverlays`. When it is still not fixable
        // we fall through to the checklist, which is where the red overlay row
        // is.
        //
        // The report this branch got wrong: on a phone where `addView` succeeds
        // under a denied appop and the window is merely hidden, `ensureOverlays`
        // used to answer "the ball is up", take `expand()`, `finish()`, and
        // leave an empty screen — so the icon did nothing, and did it again
        // every time. The grant is now asked directly, which sends that case
        // down to the checklist instead. See `SelfCheck.overlay`.
        //
        // This is also the only context in the app that may claim the
        // microphone type for that service — the platform refuses a
        // microphone-typed foreground service created from the background, and
        // a visible Activity is the one place the request is legal. Doing it
        // here rather than at boot is what makes the assistant keep working on
        // a device whose accessibility the ROM takes back later;
        // see `BodyCoreService.promoteForMicrophone`.
        BodyCoreService.start(this, microphone = true)

        val svc = BodyCoreService.get()
        if (svc != null && svc.ensureOverlays()) {
            svc.dispatcherForNotification()?.expand()
            finish()
            return
        }
        // Accessibility is ours and the overlay grant is in hand, so the ball
        // is on its way — this Activity is simply a frame early. `start` is
        // asynchronous and the new service's `onCreate` is queued behind this
        // one, so the test above reads null on the cold path that matters most
        // (first launch after a fresh grant). Falling through here would draw a
        // checklist for a device that is working, which is the exact report
        // this branch was rewritten to stop producing.
        if (svc == null && ScreenBodyService.get() != null && Settings.canDrawOverlays(this)) {
            finish()
            return
        }

        // ---- Everything below is the case this class exists for ----
        //
        // No service: accessibility is off, or the overlay grant is missing, or
        // the app was just reinstalled. There is no ball, no card and no window
        // to fold, so the checklist is not a fallback here — it is the only
        // thing that can tell the user why the equipment in their hand does
        // nothing. The ball is therefore *not* perked (there is no ball) and the
        // backdrop is not decoration: it is what makes the card's frosted fill
        // honest, see [installBackdrop].
        paintedLang = VoiceConfig.uiLanguage(this)
        installBackdrop()
        ui = SelfCheckUi(
            context = this,
            host = stage,
            // The flag is not consulted here: this host has no assistant to
            // unfold *at this point* — but see [onStageLeaving], which is where
            // that assumption gets checked rather than trusted.
            onDismiss = { _ -> leave() },
            // The two rows a first-run user actually has to act on — 大脑 and
            // 语音密钥 — are `Fix.OurSettings`, and this page used to draw them
            // with no button at all on the argument that the sheet belongs to the
            // service. It does not: `SettingsUi` is a plain overlay built from a
            // Context, and the grant it needs is one of the rows right here. So
            // the door exists whenever that grant does, and only the
            // overlay-refused case falls back to words — see
            // [SelfCheckUi.canOpenSettings] and [openOwnSettings].
            onOpenSettings = if (Settings.canDrawOverlays(this)) {
                { openOwnSettings() }
            } else {
                null
            },
            // The card's picker writes the preference and repaints itself, but
            // this Activity is what *remembers* the language on screen —
            // otherwise the next [onResume] would read a mismatch it caused
            // itself and swap the whole card for nothing. The other callback
            // [SelfCheckUi] offers used to exist only to tell the service
            // something, which is what left this one free.
            onLocaleChanged = { paintedLang = VoiceConfig.uiLanguage(this) },
        )
        // Local half first and it is not an optimisation: `local` is what the
        // card's first painted frame is made of. See [SelfCheckUi.start].
        ui.show()
        ui.start()
        revealStage()
    }

    /**
     * Give the screen back to the assistant — which, by now, may well exist.
     *
     * The first half is the original job: drop the ball off the ledge it was
     * parked on while this page covered the screen.
     *
     * The second half corrects the assumption the first version was written on
     * ("nothing to unfold: see [onCreate]"). That assumption is true when this
     * page *opens* and false by the time it *closes*, because the page's own
     * first row is the overlay grant and its own buttons send the user off to
     * fix the rows they can. So the device this page exists for is precisely the
     * one where the assistant comes up while the page is on screen — and the
     * user closes the page onto a lone ball in the corner, with the full card
     * they were just setting up nowhere to be found.
     *
     * That is the report: grant the permission, close the check, and get a ball
     * instead of the assistant. The card is not "somewhere else" on that device;
     * it is there and folded, and the one surface that knows the user has
     * finished with the diagnosis is this one, on its way out.
     *
     * Unfolding here rather than after the exit animation is deliberate:
     * [StageActivity.leave] hands the screen back synchronously before the
     * window shrinks, so the card grows into place *underneath* the shrinking
     * page rather than appearing after it — see that method's `onStageLeaving`.
     *
     * `BodyCoreService.get()` may be null, and that is the ordinary case this
     * page opens in: nothing to hand back, and nothing to unfold.
     */
    override fun onStageLeaving() {
        val svc = BodyCoreService.get()
        svc?.ballWindow()?.setPagePerched(false)
        svc?.dispatcherForNotification()?.expand()
    }

    /**
     * The settings sheet, raised by this page rather than by the service.
     *
     * This is the path where there is no service — that is the whole reason the
     * page is on screen — so `BodyCoreService.openSettingsForUser` has nobody
     * to ask. `SettingsUi` never needed one: it is a `TYPE_APPLICATION_OVERLAY`
     * built from a Context, and it lands on top of this Activity exactly as it
     * lands on top of the assistant's card.
     *
     * The three wipes are the only thing the service was carrying, and each is
     * one static call plus a brain rebuild — so here they are just the static
     * call, because there is no brain to rebuild. That is not a degraded version
     * of `BodyCoreService.clearChatHistory`: what that method's second half is
     * *for* is a model still holding the conversation, and on this path nothing
     * is.
     *
     * Two sheets can never exist at once. The only way to this page is
     * [onCreate] falling through, which happens when the service is absent or
     * has no overlay — and in the second case `canDrawOverlays` is false too, so
     * this is not even offered.
     *
     * Repainting on dismiss rather than trusting [onResume]: an overlay does not
     * pause the Activity underneath it, so a language changed inside the sheet
     * would otherwise leave this card in the old one until something else
     * happened to resume it.
     */
    private fun openOwnSettings() {
        if (settings?.isShowing() == true) return
        val s = SettingsUi(
            context = this,
            onDismiss = {
                settings = null
                paintedLang = VoiceConfig.uiLanguage(this)
                // Re-run the list, not just repaint it: the user came here to
                // fill in a key, and the row they were sent from is exactly the
                // one that should be green when they get back. [SelfCheckUi.start]
                // is what 重新检查 calls.
                if (::ui.isInitialized) ui.start()
            },
            onFactoryReset = { FactoryReset.wipe(this) },
            onClearChat = { ChatHistory.wipe(this) },
            onClearNotes = { Notebook.wipe(this) },
            onLocaleChanged = {
                paintedLang = VoiceConfig.uiLanguage(this)
                if (::ui.isInitialized) ui.repaintForLanguage()
            },
        )
        settings = s
        s.show()
    }

    /**
     * Catch a language change that did not come from this card's picker.
     *
     * This Activity paints nothing itself any more, so what it keeps
     * [paintedLang] for is the *comparison*: it is the language the card was
     * last drawn in, and a mismatch on resume means the preference moved behind
     * the card's back. The card's own picker is not that case — it repaints
     * itself and hands the new value over through `onLocaleChanged` — so what
     * lands here is a change made somewhere this page cannot see.
     *
     * Narrower than it looks, and deliberately kept anyway. The two doors out of
     * the card that *can* move the language — its own picker, and the settings
     * sheet at [openOwnSettings] — both hand the new value back directly, and the
     * sheet is an overlay that never pauses this Activity, so neither arrives
     * here. What is left is a change written while the user was off in a system
     * screen. It is one `SharedPreferences` read per resume — [AppLocale]
     * deliberately does not cache, see its doc.
     *
     * `paintedLang` is set in [onCreate] before the first resume, so this cannot
     * fire on open. Guarded on [ui] for the *other* path through [onCreate]: the
     * icon-as-app entry finishes before the card is ever built, and a lifecycle
     * callback arriving afterwards is not a good moment to throw.
     */
    /**
     * The screen-capture consent, and what the answer goes to.
     *
     * Registered as a field rather than from [onCreate] because the lifecycle
     * requires the callback to exist before the Activity is started, and this
     * page is started by an icon tap — there is no earlier moment.
     *
     * The grant goes to the *body*, not to this Activity: the capture has to
     * outlive this page, and on Android 14 it has to be re-asked for every
     * session, so the thing that owns it is the thing that is still running
     * tomorrow. Nothing is announced here either — the body's own notification
     * is what reports this state, and a second voice describing the same fact
     * is how two surfaces start disagreeing.
     */
    private val captureConsent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val granted = BodyCoreService.get()
            ?.offerScreenCapture(result.resultCode, result.data) == true
        android.util.Log.i("Body", "screen capture consent: granted=$granted")
    }

    /**
     * Ask the user to let Andee read the screen without accessibility.
     *
     * **Only reachable while the assistant is blind**, which is the only time
     * it is worth a system dialog: with accessibility on, `takeScreenshot` is
     * richer, free and already granted — an aim grid the brain can tap from and
     * the element list beside it — and asking for a second, weaker way in would
     * be asking the user to make a decision with no benefit.
     *
     * The caller is the body's notification action, `Intent` extra
     * [EXTRA_SCREEN_CAPTURE]. Failing to launch is not worth a card: the
     * platform simply has no such screen on some ROMs, and the notification
     * will go on offering it.
     */
    private fun requestScreenCapture() {
        val mgr = getSystemService(android.media.projection.MediaProjectionManager::class.java)
            ?: return
        runCatching { captureConsent.launch(mgr.createScreenCaptureIntent()) }
            .onFailure {
                android.util.Log.w("Body", "screen capture consent would not launch", it)
            }
    }

    /**
     * The other half of [EXTRA_SCREEN_CAPTURE], and not a formality.
     *
     * This page is `singleTop`, and its two callers both routinely arrive while
     * it is already the top instance: the notification action is tapped from
     * the shade with the page up, and the launcher icon does the same. On that
     * path `onCreate` does not run, so an extra read only there would be a
     * button that works the first time and silently stops working afterwards —
     * and the first time is the one where the user had not yet refused.
     * [setIntent] keeps `intent` honest for anything that reads it later.
     */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SCREEN_CAPTURE, false)) requestScreenCapture()
    }

    override fun onResume() {
        super.onResume()

        // Before anything else, and before the `ui` guard: the card this page
        // drew may have stopped being the right surface to be showing while the
        // user was away fixing it. See [handOverToBody].
        if (handOverToBody()) return

        if (!::ui.isInitialized) return
        val now = VoiceConfig.uiLanguage(this)
        if (paintedLang.isNotEmpty() && now != paintedLang) ui.repaintForLanguage()
        paintedLang = now

        // Coming back from a system screen, and re-reading the local half.
        //
        // This page's own rows send the user out — 去开启, 允许, 去下载 — and the
        // only thing that used to happen on the way back was the language
        // comparison above. So a row the user had just fixed stayed red until
        // they found 重新检查 at the bottom of the card, and a stale red row
        // reads as "the fix did not take". Being an Activity is what makes
        // noticing this possible at all: the same list on a working device is an
        // overlay owned by the service and gets no resume callback, so there the
        // card is closed on the way out instead and reopened reads fresh — see
        // `BodyCoreService.openSelfCheck`.
        //
        // Local only, deliberately. The probes are a socket that holds the card
        // for seconds, and running them every time the user comes back would
        // make the check slower the more of it you have already done. See
        // [SelfCheckUi.refreshLocal] for the whole argument, including why the
        // three probed rows are carried over rather than reset.
        //
        // Skipped on the first resume, which is [onCreate]'s own: see
        // [resumedOnce].
        if (!resumedOnce) {
            resumedOnce = true
            return
        }
        ui.refreshLocal()
    }

    /**
     * Hand the screen to the assistant, if it has arrived.
     *
     * [onCreate] asks this question once, at launch, and at that moment the
     * answer is usually *no*: this page is drawn before the body it starts has
     * attached its overlays. But this page is also where the user fixes the
     * reasons it was drawn at all — its first row *is* the overlay grant, and
     * its buttons are all doors to system screens — so the answer changes while
     * the page is up, and nothing asks again.
     *
     * What that looks like from the outside is the report this fixes: grant the
     * permission, come back, and there is a diagnostic page on screen with a
     * full-size assistant card painted over it. Two surfaces, one vision, and no
     * way to tell which one the ✕ in the corner belongs to — it belongs to the
     * card, so the user who means "close the check" folds the assistant instead
     * and is left looking at a ball.
     *
     * So the page's job is over the moment the assistant can draw itself, which
     * is the same question `SelfCheck.overlay` answers: this closes the page and
     * unfolds the card in one breath, and the two never share the screen.
     *
     * Asked on *every* resume, unlike the local re-read below — and for the same
     * reason, from the other side: the user is very often returning from exactly
     * the Settings screen that granted this, which is a resume with the service
     * newly up.
     *
     * Returns whether the hand-over happened, so [onResume] can stop: everything
     * after that point is work on a card that is on its way out.
     *
     * Nothing happens unless the card is genuinely drawable. A red overlay row
     * is on this very page, and closing the page for a card that cannot appear
     * would trade a diagnosis for an empty screen — the one outcome worse than
     * the bug being fixed here.
     */
    private fun handOverToBody(): Boolean {
        val svc = BodyCoreService.get() ?: return false
        if (!svc.ensureOverlays()) return false
        val d = svc.dispatcherForNotification() ?: return false
        d.expand()
        leave()
        return true
    }

    override fun onDestroy() {
        // Our sheet is a window, not a child of this Activity, so nothing takes
        // it down with us — an Activity destroyed with it up would leave an
        // orphan overlay on screen with no way to close it.
        settings?.hide()
        settings = null
        // Guarded because this also runs when onCreate threw before the card was
        // built, and dying is not a good moment to throw a second exception.
        if (::ui.isInitialized) ui.destroy()
        super.onDestroy()
    }

    // ---- The backdrop ----

    /**
     * Decode-and-blur via [Backdrop], into a full-bleed `CENTER_CROP`
     * ImageView, with the scrim as a foreground.
     *
     * The same three lines [TextInputActivity] runs, deliberately: the scrim is
     * *derived from the image's own brightness* (see `Backdrop.scrim`), so
     * pairing this image with any other fill would put the card at a different
     * mean luminance than the ball's card, which shares the image.
     *
     * There is nothing to rebuild it *for* any more — the language switch swaps
     * the card, which is a child of [stage] and not of this — so it is added
     * once and left alone. The `ready` branch skips the round trip on a second
     * open in the same process.
     */
    private fun installBackdrop() {
        if (backdropView != null) return
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = matchParent()
        }
        if (Backdrop.ready()) {
            iv.setImageBitmap(Backdrop.bitmap())
            iv.foreground = ColorDrawable(Backdrop.scrim())
        } else {
            // Never blocks the main thread, and on a cold start the window is
            // already dark (`Theme.Body.Stage`), so the wait is invisible.
            Backdrop.warm(this) { bmp ->
                if (isFinishing || isDestroyed) return@warm
                iv.setImageBitmap(bmp)
                iv.foreground = ColorDrawable(Backdrop.scrim())
            }
        }
        stage.addView(iv)
        backdropView = iv
    }
}
