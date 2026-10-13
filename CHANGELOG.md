# Changelog

Notable changes to Andee. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## Versioning policy

- `versionCode` / `versionName` live in `app/build.gradle` and are currently **`5` / `"0.3.1"`**.
- Releases are tagged `vMAJOR.MINOR.PATCH`, `versionName` mirrors the tag without the leading `v`,
  and `versionCode` increases by one per release. `versionCode` must increase **monotonically** or
  Android refuses the upgrade; the same number must never be reused for two different builds.
- MAJOR for a change that breaks the wire protocol or an existing configuration; MINOR for a new
  capability; PATCH for fixes.

**The project starts at `0.x`, and that is the accurate number rather than a modest one.** Nothing is
frozen. The tool-schema contract a hub brain speaks has been rewritten repeatedly
(`net/ToolSchemas.kt` has been rewritten in every release so far), the `local.properties`
build flags that decide the first-install language, the thinking default and whether CodeFlying is
compiled in have all been added since, and a saved scene is a shape that only appeared in `0.2.0`. Each of those is something a user or a second implementation would have to rebuild against,
so `0.x` is being honest that MINOR may break. The release that moves off `0` should be the one where
the wire contract is written down well enough that a second implementation could be built from it.

**A version number identifies a release, not a build.** `versionCode` now moves with each release,
so the installed app can say which *release* it came from — but nothing in it moves when `main`
does. A build made from a commit between two tags reports the older release's number, so for
anything not built at a tag, identify it by the short commit hash it was built from. (Embedding that
hash into `versionName` at build time would close this hole; it is not implemented yet.)

## [Unreleased]

## [0.3.1] - 2026-10-13

**A patch release by decision, against this file's own definition.** The delta carries six `feat`
commits and the versioning policy above says a new capability is MINOR; the maintainer chose PATCH
deliberately. It is written down here rather than left for someone to reconstruct from the history,
because a version number that disagrees with the policy is exactly the kind of thing that reads as an
oversight later.

### Added

- **A day palette, alongside the night one.** The card had been one look — a translucent pane over a
  blurred photograph, a bright hairline, pale type — and half the requests on top of it wanted the
  opposite of those values. What blocked every one of them was that `Glass`'s colours were `const
  val`s: inlined into their readers at compile time, and so unable to vary at runtime at all. They are
  getters over a day and a night `Palette` now, **every name unchanged**, which is what let forty call
  sites stay where they are. Night is what the app shipped with and is the default; day is the
  inversion of it, with three values deliberately left alone because they name a position rather than
  a colour — the well stays recessed, the thumb stays raised, and the sheen flips end for end, because
  its job is a lit edge and the light does not come from below on either palette. A theme is a
  process-global with one listener, shaped after `AppLocale` for the same reason: almost no UI here
  lives in an Activity, so no system mechanism could reach a Service's overlays. The backdrop is the
  same photograph, blurred once and veiled in whichever direction the palette needs — black down to
  0.22 at night (the original composite to the digit) and **white** up to 0.62 by day. One image, two
  veils, no asset change. Four faults presented as a single element looking wrong and are the reason
  this is one commit: `CardUi`'s colours lived in a companion object and were evaluated once at class
  load, so the card kept whichever palette the process saw first *for the life of the process*; the
  window's own chrome is built once and never hears about a change, which is what `applyPaletteToChrome`
  is for; the row tints were cached; and the light card's dim greys could not survive being asked to
  sit on a photograph.
- **A "Looks & expressions" card in Settings**, showing every look and every expression on a ball of
  its own. A look chip is saved on tap; an expression chip only previews.
- **A generated page picks its own look.** Every page came back the same dark slate, and the reason
  was not taste — it was a HOUSE STYLE block in the `show_html` description, ending in "use these
  exact values, no others". Asking for a light page worked only because the block left itself a way
  out, and that is a lever the user had to pull every single time. It could not have been fixed by
  wiring the block to the theme: a tool description is a string and cannot branch on `Theme.night`, so
  on a device switched to day the cards, the ball and the backdrop all went light and the one surface
  the model composes stayed in the night palette. That fix was on the table and was turned down on
  purpose — a page's look is the model's business, not ours. Two things stay, and neither is a matter
  of taste: the `<meta viewport>`, because `HtmlActivity` sets `useWideViewPort` and a page without
  one is laid out at desktop width and scaled down, and the ~110×44 px the ✕完成 pill occupies in the
  top-right corner, because that pill is ours. **The cost is on the record**: for the small models this
  runs against, that description was the only lever on page quality, and nothing enforces any more
  that a generated page reads as part of Andee.
- **The local LLM endpoint and model follow the UI language.** The 本机 tab's two LLM rows already had
  a hint that doubled as the value a blank row resolves to, and both were hardcoded to DeepSeek —
  self-consistent for a Chinese card and wrong for an English one. The cheapest possible fix, changing
  only the hint text, is the worst version of it: the box would advertise `https://api.openai.com/v1`
  and then, on a blank row, call `https://api.deepseek.com`. The hint is read exactly when a user is
  deciding what to type, so a hint that lies about the fallback lies in the one place it will be
  believed. Hint and fallback now come from one pair of resolvers keyed off `uiLanguage`: an English
  card advertises and calls `https://api.openai.com/v1` + `gpt-4o-mini`, a Chinese card stays on
  `https://api.deepseek.com` + `deepseek-flash`. CodeFlying is deliberately *not* switched by language,
  because its model belongs to the hosted backend and an English build inventing `gpt-4o-mini` there
  would name a model CodeFlying does not serve.
- **Errand trips from the check card are handed to the page instead of made by it**, so the list
  survives the trip. On the check *page* tapping a row's 处理 sent the user to Settings and coming back
  landed on the list; from the check *card* the same three taps ended on the desktop with a ball in the
  corner, because the card was closed and the assistant folded before the destination launched — which
  does clear the screen, since an overlay outranks every Activity, but throws the list away: a window
  gets no `onResume`, and the destination went out with `FLAG_ACTIVITY_NEW_TASK` into a task of its
  own, so back from Settings had nowhere of ours to return to. `SelfCheckUi.onErrand` (was
  `onYieldScreen`) now hands the host the Intent and lets the host say whether it can carry it.
  `Fix.Grant` goes through the same door, so 允许 on 麦克风 / 弹出通知 stops ending on the launcher too.
- **The prop-and-pose layer is on every look now**, not just Andee — stars, a heart, a shield, a sweat
  bead, the sliced lid, and the idle life that lets a look wear its own expressions and nap while
  nothing is happening. The argument for keeping it to one look was that the others had been tuned
  without it; that is overruled, because a look that is not alive standing next to one that is reads as
  the broken one. Switching it on for Imp needed a fix nothing at the call site would have hinted at:
  the lid's eye mesh was cut at a fixed 0.38 and Imp ships a resting eye already cut at 0.48, so an
  angry imp would have *opened* its eyes, in two moods, silently.
- **Andee's horns stand higher**, fade to a peach tip and hang on two springs, so a wiggle or a turn
  swings them and they ring out; the mood sets where they rest. While idle it now wears a short run of
  safe faces and naps for under a minute instead of sleeping until touched — faces that report what the
  device is doing, or that read as an alarm, are left out on purpose. Hands are a round palm, a thumb
  and two capsule fingers, and the wink carries a floating heart instead of a hand, because a V, a
  pointing finger and a thumbs-up were each tried and each misread at this size.

### Changed

- **Both READMEs lead with what it is.** They opened by showing what the thing *does* under a headline
  that framed it as a phone upgrade. The H3, the hero and the lead now say what it *is* and what it can
  *do* second, and the "why it is not the assistant already in your phone" section moved above the
  examples, which are now explicitly what its hands look like. Two earlier drafts were self-defeating
  and both were caught by reading them: one claimed "not a tool, not a companion" and then proved it
  with a capability list, the other denied being an assistant that operates your phone while the whole
  section below was exactly that. Neither is fixable with an adjective; the negations are now scoped to
  a category, and the section opens with the axis that separates the two — its own schedule, notebook,
  judgement about when to speak, and limits, all of which happen in the time you did **not** ask for
  anything.
- **The English README no longer contains a single Chinese character** (`\p{Han}` returns nothing): the
  language badge, five look names, a dozen `([中文](…))` links, the `(中文)` markers on the CodeFlying
  sites and a button quoted in Chinese. The Chinese README keeps its own language.

### Fixed

- **`POST_NOTIFICATIONS` was declared in the manifest since the beginning and never requested** — never
  checked, never asked for, so on Android 13+ everything this app posts was dropped in silence: the
  persistent notification that is the way back to it, and every reminder `Scheduler` fires. The
  codebase half knew: `Scheduler` carries a comment blaming this permission for its own failure to
  notify, and nothing anywhere tested the claim. The row that looked like it covered this does not —
  通知使用权 is the notification *listener*, which can be granted and green while every notification
  the app sends disappears. Two things this needed beyond a catalog entry: `Perm.api`, because on
  API 32 and below the permission does not exist and `checkSelfPermission` answers **denied** for
  anything it does not know, so an ungated row would report a missing permission on every Android 12
  device with no button and no way to fix it; and `SOLO_IDS`, because that set is a whitelist and a
  permission added by someone who never read it routes to handoff.
- **Three documents still described things the code had stopped doing.** The data-flow table in both
  `DISCLAIMER`s and both `permissions` documents named `api.deepseek.com` as the default destination
  for the conversation, with no mention of the language dependency added in this release — so an
  English reader was told their data goes somewhere it does not. `CLAUDE.md` still called the
  `show_html` house style "the one deliberate exception" to the single-palette rule, two paragraphs
  after the same file recorded that the house style had been removed; there is no exception now. And
  the `POST_NOTIFICATIONS` row in the permission table said only that a reminder would not arrive,
  which undersold it: refusing also kills the notification that is the route back to screen control.

### Removed

- **The raised mitts are gone.** Andee's expressions were built around a pair of mitts raised at the
  side — a fist under the chin for thinking, two fists up for celebrating, a hand on the belly for
  resting — and every one of them read as a lump at the eighty dp the ball is actually drawn at, with
  the parts sharing the body's own colour and material. Dropping them is the honest version of "this
  does not work at this size": the ball keeps the paws it hangs off the screen edge with. No expression
  collapses into CALM for it, because a mood is read through its own `Face` row and its body motion,
  neither of which ever came from the pose table. Two leftovers are deliberate and both say so where
  they live: WAVE keeps its fan of motion strokes with no hand to fan off them, and HOLDING keeps an
  empty row, because the signboard it waits behind is a real card in the ball's own window.
- **The `show_html` house style**, along with the `LocalPrompt` §7 sentence that pointed at it and then
  asserted "that style is what you look like". Both were describing the prescribed look being removed.

## [0.3.0] - 2026-10-12

The release where the assistant stops living inside the accessibility service. Everything else here
is smaller than that; the split is the reason it is a minor bump and the reason the upgrade notes
matter, because a ROM that revokes accessibility now costs one capability instead of the whole app.

### Added

- **The body is a foreground service; the accessibility service is a shim.** The body — ball, card,
  microphone, ASR/TTS, brain socket, scrollback, todos — used to be constructed inside
  `ScreenBodyService.onServiceConnected()`, and that class is an `AccessibilityService`. So a ROM
  that revokes accessibility (HyperOS, observed) took the whole assistant with it: no ball, no way to
  talk, no way back except knowing which switch in Settings to find. The failure mode was never
  "screen control is gone", it was "the app is gone". `BodyCoreService` now owns everything else and
  outlives the accessibility service, which is reduced to handing the screen capability over and
  taking it back — so a revoked service costs one capability, and the return trip is one tap on a
  notification that is still there. `screen.*` goes through a provider, so a call with no
  accessibility throws a readable error instead of no-op'ing or holding a controller built around a
  dead binding; the shot and gesture guards moved from an `init` block to `wireScreen()`, called on
  every attach, because left on the previous controller they would simply be absent on the new one.
  The launch is a persistent notification that carries whether screen control is up and the way back
  to Settings — posted by the body, so it survives the state it describes. The launcher is the one
  context allowed to claim the microphone foreground-service type, because a microphone-typed service
  cannot be created from the background and every other starter is background.
- **The screen can be read with `MediaProjection` when accessibility is gone.** The split stopped a
  revoked service from taking the whole assistant, except that the assistant went **blind** — every
  way of reading a screen went through the accessibility service, so "what is this screen?" was the
  one question it could not answer on exactly the devices this project exists for. Deliberately a
  fallback and deliberately weaker: `screen.screenshot` only, because `look_region` and `zoom_aim`
  exist to zoom a target for a tap and `dispatchGesture` has no non-root substitute, so there is no
  tap on this path. The result says which way in it used, and carries no aim grid and no element list
  — handing the brain a picture it believes it can aim with would be worse than handing it fewer
  fields. Asked for only while it is the only option: the consent is a notification action, offered
  only when Andee is blind and has no capture, and `ACTION_ACCESSIBILITY_SETTINGS` stays the first
  action because the projection buys reading, not acting.
- **Every mood has a `Pose` on top of its `Face` row** — a wink, eyes that look up and aside, lowered
  or worried lids, a squashed resting body, two puffy hands (open, fist, pointing) and pop-in props.
  Five gesture moods (wink, wave, protect, celebrate, rest) can be asked for by name or synonym in
  the model's own text, and the prompt says to keep them rare. Only a look with `BallLook.gestures`
  reads it, so the other five characters are unchanged. There is deliberately no open mouth:
  cross-fading it with the thin smile drew both at once and one poked through the other.
- **The default ball is now the blue horned Andee** — a cobalt head with curled horns and glassy
  eyes, first in `BallLooks.ALL`, so a fresh install and a factory reset both land on it. Its numbers
  were measured off the reference picture through the renderer's own camera rather than eyeballed.
  The launcher icon set, README covers, social previews and org avatar are regenerated from the same
  source art.
- **The CodeFlying per-user token is read from `assets/andee_key.txt`**, with `BuildConfig` as the
  fallback for dev builds. Production APKs are built once from shared source and the per-user `kft_`
  token is written into that asset at package time, so the base APK stays identical across users and
  the Gradle build cache no longer misses per user.
- **`-PdbgSuffix=` builds a parallel package** for device verification. The package on the tablet was
  signed with a key this checkout does not have, so `pm install -r` was refused and the only
  alternative was uninstalling — which throws away chat history, notebook and vault. Off unless the
  property is passed, so ordinary builds are byte-identical.
- Debug builds gain `BallLabActivity`, which shows one mood on a plain background for screenshots.

### Changed

- **The version check moved to `https://api.heyandee.com`, from `https://andee.kuafuai.net`.**
  `VERSION_API_BASE` is still a hardcoded constant in `device/SelfCheck.kt`, and still the only
  address this app calls that the user did not configure — see `docs/permissions.md` — so the move is
  one line of source plus the three places that quote it and have to stay in step: both `permissions`
  documents and the note in `network_security_config.xml`.
  The old host was on the mainland, where the domain had to be ICP-filed before TLS would reach it at
  all; the new address resolves outside the mainland and retires that particular trap. It does not
  retire the other one: a domain still has to resolve *and* serve 443, and a fresh record is not
  everywhere at once. Measured while making the move, `1.1.1.1` still answered `NXDOMAIN` while
  `223.5.5.5` already had the address — on the wrong resolver that reads as 查不到 on the 版本 row,
  which is the intended failure rather than a fault to report. The service itself was checked before
  the switch: the new host answers `GET /api/version` with the same envelope, and the same `data`
  fields the client parses.
- **The self-check page now stays up until the user closes it.** It is a list, and a user with five
  rows works through them one at a time — out to a Settings screen and back for each — but the page
  used to hand the screen to the assistant as soon as it could draw one, which happens on the *first*
  of those returns, so the list was taken away after row one. On a page whose whole job is to walk
  somebody down a list, the user is the only one who knows when the list is finished. Closing it with
  完成 puts the assistant back. Two surfaces fighting for the screen was the real bug underneath and
  it stays fixed by the same move: nothing unfolds a card while the page is up.
- **Closing a page puts the assistant back at full size, unconditionally.** The fold that makes room
  for a page is ours — our card is a fullscreen opaque overlay and would cover it — so undoing it is
  ours too, and it was missing from `pageClosed`. The first cut restored the card only if it had been
  fullscreen when the page went up; the report was the other way round, and closing the self-check
  card drew the same complaint and got the same answer.
- Both READMEs point their CodeFlying links at the `/andee` one-click landing page rather than the
  generic official site, so every jump lands on the Andee entry page.

### Fixed

- **`ui.show_html` no longer needs accessibility**, which it had started to. `awaitUncovered()` asked
  its question through `requireScreen()`, and `awaitUncovered` runs off `ensureCompact()` — which
  `ui.show_html` calls too, because our card would cover the page it is about to draw. So with
  accessibility off, the one tool that draws a page answered "accessibility is off" and the assistant
  told the user it could not show them anything. Introduced by `f364490`, which turned a
  nullable-but-always-present field into a throwing provider and rewrote every call site
  mechanically; the other sixteen are inside `screen.*` branches and are meant to throw. Returning
  early is also right on the merits: the wait exists so that a *tap* lands on the app rather than on
  the folded card, and there are no taps without accessibility.
- **The self-check no longer mistakes a re-creation for a launch.** This page sends the user out
  through its own rows, so the platform killing it while they are away and rebuilding it on return is
  routine here rather than an edge case: grant the overlay, come back to a restarted Activity, and it
  used to unfold the card and finish before a single row was drawn. `savedInstanceState != null` is
  what tells the two apart. A related report — "I granted it, came back, it still said no, then
  re-check was fine" — is the platform's answer about a setting changed seconds ago not being ready
  in the frame the user returns on: the notification-access row is `OK` only once the listener is
  actually bound, and the read that finds it unbound is also the one that requests the rebind.
  `settleRecheck` takes two more local passes and stops, because a page that keeps re-reading itself
  is a page whose numbers stop meaning anything. (The retry is reasoned from the code path, not
  measured: writing the notification listener into `enabled_notification_listeners` does not stick,
  so the state cannot be manufactured from adb.)
- **The body stays off the screen while the check page is up.** The report was "the check said the
  overlay was off, I granted it, came back, and I was on the assistant instead of the check" — the
  check was never gone, the page was still the resumed Activity with the assistant's fullscreen card
  painted over it. Two things put it there, both in the body's birth: `window.setCompact(false)`, and
  `window.show()`, which is subtler — this window is fullscreen *even when the ball is compact* and
  swallows the touches it does not use, so a page with a ball in the corner is a page whose buttons
  cannot be pressed (measured: 完成 did nothing). Telling a fresh launch apart from a return needed
  `SelfCheck.isPageOpen`, written down in the prefs file the startup check already uses. The startup
  self-check card is skipped while the page is up as well, because that page *is* the list.
- **A just-fixed row stops showing as red when the card resumes.** The card's own rows send the user
  out to a system screen and the only thing that happened on the way back was a language comparison,
  so a row the user had just fixed stayed red until they found 重新检查 — and a stale red row reads as
  "the fix did not take". An Activity gets a resume callback and an overlay does not, which is why
  this is a change to one host rather than to the list; it is local-only on purpose, because the
  probes hold the card open for seconds. The first resume is skipped (a full pass just ran), the
  three probed rows keep their last reading (`SelfCheck.mergeLocal`), and `render` restores the
  scroll position — which only started to matter once redraws stopped being deliberate.
- **A perch slide dragged the whole card off-screen, and a voice failure repeated forever.**
  A card held by the folded ball makes the window card-sized, so a perch slide (task start, page up)
  dragged the whole card into the bottom-right corner, half off-screen and untouchable; the perch
  entry points now take the signboard down first. Separately, the auto follow-up mic reopens after
  every answer, so a device with no voice set up raised the same failure card and scrollback row each
  time — now shown once per failure streak, reset by a working voice turn, leaving settings, or the
  user pressing the ball.
- **`screen.screenshot`'s schema claimed something false.** It opened "every screenshot arrives with
  an 8x12 AIM GRID drawn on it" and promised the element list, both exactly untrue on the projection
  path — which is the only screen tool that still works there. The runtime `note` said so, but the
  schema is what the model reads *before* deciding to call it.
- **Three documents still described the pre-split architecture.** `README.md` and `README.zh-CN.md`
  said "this app *is* the accessibility service" and pointed a reader at `ScreenBodyService.kt` as
  the file to read first — both false since `f364490`, which is the whole point of the split. The
  permission table in `docs/permissions.md` / `.zh-CN.md` said of accessibility that "every screen
  action errors", which stopped being true in `82f08db`: `screen.screenshot` has a fallback and the
  row now says so, along with what the fallback cannot do.

### Removed

- `tools/check_launcher_icon.py`, `tools/cosmic_backdrop.py` and the old `make_launcher_icon.py`,
  replaced by a rewritten generator built on `tools/andee_head.py`. All three were written for the
  previous character and a star-field backdrop, neither of which is in use any more.
  `tools/make_social_preview.py` generates the social previews, both README covers and the org avatar
  from the same source art.

## [0.2.0] - 2026-10-10

Three new capabilities and two permission fixes. A minor bump rather than a patch, because the three
are new capabilities by this file's own definition — the first release where that rule has had to
decide anything. The permission pair is the part worth reading: the app had been telling the model
things about the system permission sheet that measuring on a device showed to be false, in one case
in the direction that made the model act on a grant nobody had given.

### Added

- **A single tap on the ball now stops whatever is running and then listens.** A tap used to mean
  "listen" and nothing else, which left a gap the moment the ball was busy: to redirect it the user
  had to stop it first (three taps) and then ask again (one more) — two gestures for one intent.
  Tapping something busy and having it stop and listen is what one person does to another, and it is
  the only reading of the tap that is not "talk over the thing you cannot hear". The stop is
  `stopEverything()`, reused rather than reimplemented, so the single tap and the triple tap cannot
  drift into two different stops and the hub gets its `task.stop` either way. It is guarded on
  `dispatcher.isTaskActive()`: on an idle ball there is nothing to stop, and the call would still
  clear a pending 继续修改, tell the hub to stop, and flash 已停止 ahead of 听着… — three side effects
  for a tap that only ever meant "listen". Triple-tap still stops, and is still the *immediate* stop,
  because a single tap has to wait out the double-tap window before it knows it is not the first of
  two. No gesture moved and no string changed: what a tap *does* depends on what is running, which is
  not something a line of text can say. Verified on device (HONOR AMM-AN00) — an idle tap listens
  without flashing 已停止.
- **A device with no conversation is offered ready-made scenes instead of a dead line.** It used to
  show 还没有对话 and nothing under it. There is nothing to be helpful *about* yet, and a ball in the
  corner has taught a newcomer nothing about what the thing does, so the one moment this app can be
  proactive without being early went unused. The card now shows a hint line — 告诉我，或者选一个 —
  then a bubble per preset from `ScenePresets`, each labelled with the scene's name and carrying a
  sentence the user could have said: the name is what a person picks between, the sentence is exactly
  the part a newcomer cannot invent. Both are fields of one `ScenePresets.Text`, so a bubble cannot
  promise one thing and send another. **Tapping a bubble adopts the scene before it speaks** — this
  was wrong first and is the part worth naming: a scene is something the notebook holds, so a bubble
  that only sent its sentence asked the model about a scene nobody had saved, and it correctly
  answered that it had never seen one, in front of the user. It is `adoptIfMissing` rather than
  `adopt` on purpose: a bubble is offered whether or not the preset was adopted, and `saveScene`
  replaces the whole row including `prompt`, so adopting twice would put the preset's text back over
  the user's own edited version with nothing to say it had happened. Pre-adopting the presets at
  install was the other candidate and is worse — a written-down preset is an ordinary scene, so
  deleting one would have it come back at the next install, the language would freeze to whatever the
  device spoke then, and it contradicts `LocalPrompt` §11. Emptiness is the whole safety story and it
  is mechanical: the moment there is a conversation the strip is `GONE` for good, because a
  suggestion strip parked over a live chat would be the app talking about itself instead of
  answering. Two numbers are enforced rather than asked for by `ScenePresetsTest`, which caps every
  summary in both languages and checks that every name a bubble can send is one `byName` can look
  back up. Verified on device (HONOR AMM-AN00) by reading the notebook over the 9008 tool port.
- **Long-pressing a history row opens the log behind it.** The scrollback says what was said;
  everything that earned the right to say it — which tools ran, with what arguments, what came back,
  what the model reasoned, and what each request cost in prompt tokens — was already in
  `files/brain_trace.jsonl` and reachable only with `run-as`. The new page is built from those
  records and opened through `HtmlActivity`, which is where folding the card, hanging the ball off
  the ledge and the 完成 pill were already solved. Long-press because the other two gestures on a row
  are taken — a single tap opens a page row's page, a double tap reads a message fullscreen — and the
  two detectors had to become **one**, because `setOnTouchListener` replaces rather than chains, so a
  second one would have silently killed the first. **The row and its log are joined by
  `<process start>-<gen>`, and `gen` alone would have been wrong:** it is an in-memory counter that
  restarts at 1 with every process while the trace file is persistent, so on the test device `"gen":1`
  accounted for 176 of 278 records, and installing the change reproduced the collision within 89
  seconds. A scrollback matching on `gen` would have opened a reinstall's first turn onto yesterday's
  first turn, with nothing on screen to say so; `BrainTraceTest` locks the join down, dash included.
  Two things the page got wrong before it was ever shown to anyone, both found by reading records
  back off the device rather than by reasoning about them: the `turn` card labelled its body "Said",
  so the user's own sentence appeared in the assistant's mouth on the first card of the page (the
  heading is where the origin belongs, and `kind` is the field that knows it), and the `end` card
  said "Turn ended" when `BrainTrace.end` is only written on an *abnormal* finish — two of three
  turns measured had none at all. Records written before this commit have no `turn` field, so a row
  from before the upgrade offers no gesture, and the same rule keeps hub-brain rows, scene-change
  rows and notification read-outs from offering a dead entry.

### Fixed

- **`request_permissions` answered `{"started": true}` no matter what actually happened.** That was a
  hard-coded `true` meaning only that `startActivity` had not thrown. After a second refusal Android
  11+ draws no dialog at all and the call still said true, so the brain read "it is on" and
  immediately called `get_location`. Measured on turn `1791621839492-4`: the request returned at
  +19.257s, `get_location` fired at +20.839s and answered `permission denied` 1 ms later — the model
  gave the user 1.582s to find a dialog that was never drawn, then spent 34.6s looking for a way
  around it. The reply now carries what happened rather than that something was attempted: `mode`
  (`solo` / `handoff` / `settings`, who answers the sheet), `result` (`granted` / `denied` /
  `no_dialog` / `waiting`) and `next`, one line saying what to do about that result. `mode` comes from
  `routeOf`, a pure function of the ids kept on a whitelist so a permission added to the catalog later
  falls on "the user was asked once more than necessary" rather than "the ball agreed on their
  behalf". `result` resolves the distinction that pays for all of it: a refusal and a silent block
  both read `PERMISSION_DENIED` and demand opposite responses, and
  `shouldShowRequestPermissionRationale` — readable only from an Activity — is what separates them.
  `PermissionRequestActivity` used to drop `grantResults` on the floor; it now hands the verdict back
  through arm/await/settle, and `PermissionsController` waits `DIALOG_GRACE_MS` for it, a grace
  period rather than a wait for the human. Verified on HONOR AMM-AN00 (location/solo/granted,
  contacts/handoff/waiting, steps/solo/waiting, each with the sheet really on screen); 37 unit tests
  pass, with `PermissionsRouteTest` pinning the routes from the id list without a device.
- **The permission sheet was drawn *underneath* the expanded card, so the user could never see it.**
  An expanded card is a fullscreen surface and our overlay sits above every activity, so
  `GrantPermissionsActivity` was drawn below it. Verified on device: the dialog really was up — it
  was both `ResumedActivity` and `mCurrentFocus` — while a screenshot showed nothing but the card. A
  question nobody can see is worse than no question, and it is exactly the state `waiting` promises.
  `device.*` deliberately never folds, on the reasoning that reading state is a conversation rather
  than an actuation; this branch is the exception that reasoning did not cover, because it addresses
  the user in a window of the system's own. `device.scan` and `device.vault.fill` already fold for
  the same reason, so this joins them — and only when `confirm=true`, because the first call is the
  script the brain reads back and that one belongs in the card.
- **Both READMEs gained a one-click build page and had its likely-reading fixed.** The nav and the
  CodeFlying paragraph now point at the `/andee` page — describe the app, tap 立即打包, get a
  pre-configured package with a QR code — and the "any app" feature line was reworded in both
  languages, because "not a list of integrations" was being read as a claim about integrations this
  app has rather than a claim about aim. The page itself is a separate service with no direct
  relationship to this project, as that paragraph already says.

### Changed

- **The prompt's rule about the system permission sheet was wrong, and is now the measured one.** It
  said a system permission sheet "does not take a tap you inject", and that turned out to be false.
  With the card folded, an injected tap on 「始终允许」 really does grant: `READ_CONTACTS` went
  false → true, and one tap on 「本次使用允许」 produced an `ACCESS_FINE_LOCATION` carrying `ONE_TIME`,
  a flag only that button can set. The earlier four failures had one cause between them and it was our
  own card eating the touch, not the sheet refusing it. So the rule changes from "you cannot press it"
  to **"you cannot read it, so do not aim at it"**: the sheet exposes no accessibility elements at
  all, so any tap is blind, and the button at the bottom of this particular screen is
  「禁止后不再提示」, which nothing can undo. That is the real reason this step stays the user's, and
  it is a reason that survives being measured. `LocalPrompt` §0's `type_text`-only rule is stated as
  the general one — a tool saying it pressed something is not the thing being done — and §1's list of
  genuine exceptions gains the sheet, which is worth naming because putting it on screen is the whole
  of what firing does.

## [0.1.1] - 2026-10-09

### Fixed

- **Four strings sent the user to a menu label that no longer exists — and one to a gesture that does
  not open settings at all.** The settings card has been reorganised twice since these sentences were
  written, and the sentences stayed behind, which is the worst kind of copy bug: the user follows the
  instructions, finds nothing, and concludes the app is broken rather than the text.

  - `check_detail_brain_key_missing`, `check_detail_brain_hub_missing` and `brain_no_key` all said
    **⚙ → 「大脑」 / "Brain"**. The section header is `settings_section_brain`, which reads
    **「后端」 / "Backend"** — there is no label containing 大脑 in the settings card at all. The
    火山 tab names use 大脑 only in prose ("the brain lives on a hub you run yourself"), never as a
    destination.
  - `check_detail_voice_key_missing` said **⚙ → 「语音（火山引擎）」 / "Speech (Volcengine)"** — a
    section that no longer exists. The Volcengine rows were moved into 后端 → 本机, and
    `settings_section_voice` / `settings_section_voice_key` are still in both string files but
    referenced by nothing. The path is now the same **后端 → 本机** as the two brain keys, which is
    where the field actually is.
  - `brain_no_key` also said **"长按小球打开设置" / "long-press the ball, open settings"**.
    `onLongPress` does not open settings; it calls `dispatcher.expand()`, which brings the full card
    back. Settings is the ⚙ button in that card's control bar. The sentence now names both steps,
    because the first one is genuinely required — the control bar only exists at full size.
  - `check_detail_brain_hub_missing` gained the missing precondition: **「云端」 is hidden unless
    高级 →「显示云端选项」 is on.** `showHub` gates the tab in the picker, but `brainMode` does not
    consult it, so a device can sit in hub mode with the tab that fixes it invisible — turn the
    toggle on, pick 云端, turn it off again, and the self-check would have pointed at nothing.

  Every label these strings now name was checked against its resource value rather than transcribed
  from the sentence being replaced — which is how the one remaining error was caught: the English
  label is **"Show cloud option"**, singular, and the first draft of this fix wrote it plural.
- **Both READMEs promised a first-run wizard that no longer exists.** "The first run is a stepped
  setup that walks you through both" / 「第一次启动会有一个分步引导」 — `8dac568` deleted the wizard
  (`ui/FirstRunUi.kt`). A missing setup guide is indistinguishable from a broken install, so this
  read as a bug report rather than as stale prose. Both now say what the app does instead: the
  self-check runs on every start, the card names the missing key, and its button opens settings.
- `README.md` named the Volcengine field as **火山 API key** — the Chinese label — while the English
  build displays **Volcengine API key** (`settings_voice_api_key`). The Chinese README already had
  this right; only the English file was pointing at a word that never appears on an English screen.
- **A picture, a video or a clip now goes into a page, instead of being handed over as a URL.** The
  prompt said the opposite, and said it wrongly: *"this device cannot turn an image into a URL, so an
  `img` tag will not open"*. That is false for anything on the network — the page is served against a
  fake `https://` origin, the app holds `INTERNET`, and WebView loads an absolute `https://`
  subresource without complaint. What is true is narrower, and the old sentence flattened it into a
  blanket ban: a *local* file has no address a page can point at, and `http://` is refused. So the
  model, obeying its instructions, could not put a picture on screen — and what it did instead was
  hand the user a URL to open, on the device they were already holding. §7 now asks for the embedding
  directly (`<img>`, `<video controls>`, `<audio controls>`, absolute `https://` only), separates a
  media file from a *site* — the latter belongs in Chrome through `open_url`, not in an `iframe`,
  which most sites refuse — and says outright that passing over a bare URL is not an answer. The
  wrong half of the same sentence sat in the `show_html` description too, which is the text the model
  reads while composing; media is carved out there as well, because what that rule protects is a
  self-contained shell. Verified by compiling and by reading both strings back out of the compiled
  classes — the new text is present, all three old phrasings gone. **Not verified on a device**: none
  was attached, so a real `https` image and a real `.mp4` rendering in the page are still
  unconfirmed.
- **The launcher icon could become a permanent dead end, because the overlay was inferred from a view
  object instead of asked for as a grant.** First run, accessibility on, 悬浮球权限 off: the checklist
  came up, the user closed it, and from then on the icon did nothing — no screen, no error, no way
  back in short of reinstalling. Nothing was removing the ball's window. On HONOR/MagicOS a denied
  `SYSTEM_ALERT_WINDOW` does not make `addView` throw: it succeeds, and the system hides the window
  instead — present in the window list with `alpha=0.0`, `mAppOpVisibility=false`,
  `isReadyForDisplay()=false`. So the view object outlives the grant, and `FloatingWindowUi.isShown()`
  — which is only `root != null`, a memory marker and nothing more — keeps answering true while the
  user can see nothing at all. `ensureOverlays()` opened with exactly that test, so
  `SelfCheckActivity` took its expand-and-finish branch, called `expand()` on a card that cannot be
  drawn, finished itself, and left an empty screen; the checklist branch, which is the one that names
  this failure in red, never ran. The grant is now asked **first and directly**, as
  `Settings.canDrawOverlays()` — the same predicate `SelfCheck.overlay` uses for the 悬浮窗 row, so the
  page and the door can no longer contradict each other (which is what the user hit: the page said
  没开 while the door said 在屏). It also covers revoking a grant that was already given, when `root`
  stays non-null for the life of the process. `showSelfCheckCard()` guarded with the same marker,
  where its own comment had claimed it already did. Verified on the device (LIO-TL00) on **both**
  paths, because this edits the working path's entry test too — grant denied now reaches the
  checklist (13 activity records), grant allowed still takes the expand branch (0 records); checking
  only the first would have shipped "the icon always shows the checklist".

### Changed

- **The 悬浮窗权限 row now leads the self-check list**, ahead of 无障碍服务. Both are hard gates and
  the order between them was arbitrary; what settles it is that they fail in opposite directions.
  With the service off the app is visibly inert — nothing to run, and every row below is decoration.
  With the grant off it *looks* broken instead: the service runs perfectly well, silently cannot
  draw, and tapping the icon appears to do nothing at all. That second shape is the one users misread
  as "the app is dead", so it goes first.
- 无障碍服务's off-state copy named the row's list position — 「后面几条都不用看」 / *"the rest of this
  list does not matter yet"* — which the reorder above invalidated. It is now 「其他都不用看」 /
  *"nothing else here matters either"*: same meaning, no longer positional. Same edit in both string
  files.

### Removed

- **The first-run wizard is gone** (`ui/FirstRunUi.kt`, its wiring in `ScreenBodyService`, the 重新设置
  button on the self-check card, and its strings in both languages). The wizard and the startup
  self-check both wanted to put a card up on a first start, and when the check found a problem it
  covered the wizard, so a new user saw whichever lost the race. The wizard was removed rather than
  arbitrate between the two. The self-check's own prefs file stays, as does
  `PermissionRequestActivity`, which the check still uses; an old `wizard_done` flag on existing
  installs is simply never read. The wake word is now offered only from settings and as a note row on
  the self-check. The two READMEs had promised this wizard — see the *Fixed* entry above.
- `check_page_no_overlay` and the `SelfCheckUi.note()` helper it was the only caller of. The note
  explained that the settings sheet is drawn as an overlay — a fact the page does not need to teach,
  because the 悬浮窗 row directly below it already carries the fix, in that row's own words and with a
  button. `fixButton()`'s comment used to point at "the note above the list"; it now states the rule
  on its own: a button that does nothing is worse than no button.

## [0.1.0] - 2026-10-09

The first tagged release. Because nothing was tagged before it, this section is the whole project
rather than a delta from a previous version — it reads as a long list of *Added* for that reason, not
because a release this size is the norm.

### Added

- **`docs/architecture.svg` + `docs/architecture.zh-CN.svg`, and a `## Architecture` section in both
  READMEs.** The README described pieces — the ball, the vault, the dog — and never showed how a turn
  actually moves. Drawing it forced two corrections to the diagram before it was right, both of them
  worth recording. The first was factual: the draft showed "two brains" when there are **three
  mutually exclusive backends**, and it marked the hosted one as the default when an unconfigured
  device reads as `BRAIN_LOCAL`. The second was structural and worse: the draft stopped at the
  backend and drew nothing after it — no agent loop, no outputs, which is most of the interesting
  half. No class is named after the harness; it is `LocalBrain` itself. The picture now shows the
  system prompt held constant with the volatile parts (clock, notebook index, scenes, photos)
  deliberately inlined into the user message so the provider's prefix cache still hits, the tool
  table rebuilt every turn, and the non-streaming step loop with its guards. Outputs come back
  through six channels, none of which had been drawn: speech, text, a full-screen page, saved
  artifacts, long text, and the dog. Both files are hand-built SVG with hardcoded colours, because
  GitHub renders a README image through `<img>` and `var(--color-*)` resolves to black there; both
  were rendered at 1:1 and read back to check for overflow, which caught three layout faults that
  reading the source alone would not have shown.
- **Both READMEs now ask for your use case (`## Showcase` / `## 好玩的用法`).** The
  README described what the thing can do and never asked anyone to show what they did with it,
  which is how a project ends up with no idea whether the odd corners work. The section is an
  invitation and three things that make a post useful: the device and ROM (this app talks to Android
  at a level where ROMs disagree, so "worked on my phone" is not reproducible), the sentence you
  actually said, and a link if there is one. It goes to a Discussions **Show and tell** rather than
  the issue tracker, which follows the rule `config.yml` already sets — the tracker is for
  reproducible defects, and a story is not one. Two details are deliberate rather than decorative.
  **GitHub cannot play a video inside a README** (iframes are stripped, so a YouTube link stays a
  link), and the section hands over the thumbnail form that makes it look like a player anyway,
  rather than letting twenty people discover that separately. And it says plainly that **a
  screenshot of this app is a picture of your messages** — the one thing in a showcase that can do
  real harm is posting someone's private screen content, including your own.
- `LICENSE` — Apache-2.0 with additional conditions (Part A: no multi-tenant SaaS, no
  white-labelling that removes the branding, no embedding it in another product without written
  authorisation). Note that GitHub reports this as `NOASSERTION`.
- `SECURITY.md` — threat model, the five known security limitations, and a private reporting channel.
- `docs/permissions.md` + `docs/permissions.zh-CN.md` — why the app asks for the permissions it asks
  for, each one tied to a concrete tool and to what happens if you refuse it. One file per language.
- `CONTRIBUTING.md` + `CONTRIBUTING.zh-CN.md` — code style, the rules for the three kinds of Chinese
  in this codebase, and the contribution licensing terms. One file per language.
- **A `Signed-off-by:` sign-off is now required on every commit, and CI enforces it.**
  `.github/workflows/dco.yml` checks each commit on a pull request, and both `CONTRIBUTING` files
  plus the pull request template explain the one-line change (`git commit -s`). This is a
  [DCO](https://developercertificate.org/), not a CLA: the contributor keeps their copyright and
  certifies only that they had the right to submit the work. The reason it exists is LICENSE A.2 —
  the project is relicensed under terms that differ from stock Apache 2.0, and A.2 asks contributors
  to grant that. A term nobody has explicitly agreed to is a term a contributor can later dispute;
  a sign-off line is a per-commit record that they did. Deliberately *not* a CLA, which would need a
  signature process and an entity to hold it — friction that buys nothing here.
- `CODE_OF_CONDUCT.md` — Contributor Covenant v2.1.
- `THIRD_PARTY_NOTICES.md` — generated dependency license inventory: 93 components across 5
  licenses. Notably flags that **ML Kit and the `play-services-*` stubs are not open-source**.
- `tools/license_audit.py` — the generator behind the notices file.
- `.github/` — bug report and feature request issue forms, a pull request template, and issue
  routing with blank issues disabled.
- `README.zh-CN.md` — the Chinese setup guide, split out from `README.md`.
- `DISCLAIMER.md` + `DISCLAIMER.zh-CN.md` — the user-facing terms, one file per language (same
  convention as the two READMEs). Covers the four things Apache-2.0 does not: **where your data
  actually goes** (at the time this file was written, no telemetry in the repo, but speech goes to
  Volcengine and the conversation plus every tool result goes to the brain endpoint you configure),
  **what the software is genuinely
  capable of** (it can complete a payment by tapping; the only guardrail is Android's `FLAG_SECURE`),
  **who bears the consequences**, and third-party platform terms.
- `docs/acceptable-use.md` + `docs/acceptable-use.zh-CN.md` — the prohibited uses, one file per
  language. This is the part that matters legally: naming the prohibited uses is what keeps this
  project from being characterised as *providing assistance* to them. It calls out fund movement
  (including the Article 287-2 aiding-information-network-crime exposure) and publishing false
  information separately.
- `docs/permissions.md` gained a **"data goes where"** section — the honest version of
  "we do not collect your data", which is true of the maintainers and false of the device.
- Both READMEs now carry a warning above the fold and a `## Disclaimer and acceptable use` section;
  `SECURITY.md` states the triage boundary (steering it is a vulnerability; doing what it was built
  to do is documented behaviour).
- **A 版本 row in the self-check list — the first thing this app ever sends anywhere.** It asks a
  version service whether a newer build exists and reports the answer as one more row in the same list
  the device diagnostics live in: one row, one renderer, no second settings page. The level mapping is
  the deliberate part. **Current → OK, newer release available → NOTE, forced update → WARN, and
  everything undetermined → NOTE.** An outdated app is not a broken one, and only FAIL and WARN may
  raise the startup card, so a vendor that popped a card every time it shipped a patch would be
  nagware — which is the exact failure `SelfCheck`'s level doc is written against. The forced case is
  the one real WARN, because the service is then saying this build is no longer supported. A
  statistics endpoint being unreachable is never the user's problem, so it never paints the card red.
  **The address is a hardcoded constant, not a setting.** `VERSION_API_BASE` lives in
  `device/SelfCheck.kt` (`https://andee.kuafuai.net`), with no settings row, no build flag and no
  `local.properties` key behind it, so turning the check off means editing that line and rebuilding.
  That is a deliberate trade rather than an oversight: a check which can be silently misconfigured is
  a check that silently stops working, and this one should be on for everyone or off for everyone.
  The price is that there is no build-time way to opt out, and `docs/permissions.md` now says so
  instead of pretending otherwise — which it had to, because the first cut of this feature did put the
  address in `local.properties` and then advertised a fresh clone as reporting nothing.
- **`res/xml/network_security_config.xml` deliberately did *not* gain the version host, on the second
  pass.** Android refuses cleartext HTTP to any host not named there, and what it produces —
  `CLEARTEXT communication to X not permitted by network security policy` — arrives as a version row
  reading 查不到, a message about a policy rather than about anything the user did. So the first cut
  added the service's IP address to that file, which is the right move *for a `http://` address*. The
  address then became `https://andee.kuafuai.net`, which needs no exception at all, and the entry was
  deleted rather than left in place: an exception for a host nobody calls only widens what this app
  will talk to in the clear. `124.71.176.202` (the CodeFlying backend) is still listed and still needs
  to be. The rule to carry forward is that this file tracks the addresses actually in use — adding a
  line is part of pointing the app at plain HTTP, and removing it is part of moving away.

### Fixed

- **A failed voice turn crashed the app with `StackOverflowError` instead of reporting the failure.**
  `AsrController`'s listener implements `onError(msg)`, and its body called `onError(msg)` — written
  meaning the constructor's `(String) -> Unit` callback that routes the message to the owner. Kotlin
  resolves an unqualified call to the nearest scope first, so the line called *itself*: one recognizer
  error became infinite recursion until the stack ran out (`stack size 1039KB`, thousands of frames of
  `AsrController.kt:152`). Any 火山 failure — a dead network, an expired token, a rejected handshake —
  took the whole process down, which is the opposite of what the callback is for. Now qualified
  `this@AsrController.onError(msg)`. The listener's three other callbacks are unaffected: none of them
  shares a name with an outer member.

### Changed

- **The APK went from ~39 MB to ~8 MB.** Two causes, both structural rather than a matter of
  trimming assets. First, every build produced one *fat* APK for all four ABIs, and ML Kit's
  `libbarhopper_v3.so` is ~4 MB, so it was paid for four times over. Second, the debug variant ships
  un-shrunk dex (16.7 MB of it, against 3.2 MB after R8), and `minifyEnabled` was off on release too.
  Fixed with an ABI split, `minifyEnabled` + `shrinkResources` on release, and deflated native libs.
  **The output paths changed**: there is no `app-debug.apk` any more, only `app-<abi>-debug.apk` and
  `app-<abi>-release-unsigned.apk`. `assembleRelease` also runs in CI now, as a job parallel to the
  debug one, because R8 and `lintVitalRelease` only run on release — and that is exactly how a
  release build that failed on every invocation sat in the repository unnoticed.
- **`assembleRelease` builds again.** It had been failing in `lintVitalRelease` on
  `res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml`, both of which exclude
  `domain="device_root"`. That is a real domain — the framework has carried
  `DEVICE_ROOT_TREE_TOKEN` since device-protected storage landed, and the Auto Backup reference
  defines `device_root` as "like root but for the device-protected storage". AGP 8.3's
  `FullBackupContent` check validates against a five-value list that predates it, so the error was a
  false positive. Both lines now carry a scoped `tools:ignore`; the check still covers everything
  else in those files, including a genuine typo in a domain.
- **`SECURITY.md` no longer claims a safeguard the software does not have.** Its
  prompt-injection section stated that destructive or irreversible actions are
  *"gated behind `ask_user` / `confirm` flows"*. That is not true, and it was the
  one sentence in the repository that could be turned against the project:
  `ask_user` is an ordinary tool the model chooses to call, `confirm` exists on a
  single tool (`request_permissions`, where it prevents an unwanted permission
  dialog, not a payment), and nothing forces a confirmation before
  `tap_by_coordinates` — which is the path a payment is completed along and the
  very thing the disclaimer is about. `DISCLAIMER.md` and
  `docs/acceptable-use.md` had both already said the opposite in as many words
  ("no guardrail whatsoever", "no technical guardrail on that path"), so the three
  files contradicted each other and the security page was the one that was wrong.
  It now states the posture plainly: the checks on risky actions are instructions
  to the model rather than enforcement, and the device should be assumed drivable
  into anything its permissions and the user's own logged-in state allow —
  including moving money — with no prompt appearing. The same edit strips detail
  that made the page read as a recipe rather than a warning: the example request
  frame, the per-method enumeration of what a LAN peer can reach, and the note
  that the listening port is a fixed constant. The port number stays — it is what
  the recommended mitigation (firewall it) refers to. Two smaller changes follow
  from the same reasoning. The scope list no longer counts "an injection caused an
  action the user did not ask for" as a vulnerability, because that is the
  limitation §3 now describes rather than a promise the project can be held to;
  and the reporting promises are now conditional ("where we can", "no timeframe is
  promised"), so a small project is not bound to a reply it may not send. A line
  at the top states the out-loud version: nothing in the file is a promise that
  any particular action is prevented.
- **`LICENSE` Part A gained a fourth condition, (d): shipping the software inside another product
  now needs written authorisation.** Conditions (a)–(c) already stopped white-labelling, multi-tenant
  SaaS and dropping the attribution, but nothing stopped someone taking the code and folding it into
  a product they sell — which is the one path that makes the rest moot. (d) closes it, and is written
  as narrowly as it can be: running it yourself, running it across your own organisation's devices,
  forking it and distributing the source so others can build it, and charging to install, configure
  or support it for a client who runs it themselves are all still permitted. The line drawn is
  between a **service** and a **product** — doing work that uses the software is fine, shipping the
  software as part of something else is not. The heading and the lead sentence were corrected from
  "three conditions" to match. `README.md`, `DISCLAIMER.md` and `docs/acceptable-use.md` reference
  Part A without counting the clauses, so no copy drifted — the enumerations they do carry are
  illustrative, and only `LICENSE` itself states all four.
- **Positioning corrected throughout the docs.** Both READMEs and `CLAUDE.md` described this as
  "the body side of a Physical Agent system" — i.e. an assistant that a brain drives. That
  contradicts the project's own first instruction, in `LocalPrompt.TEXT`: *"you are not a tablet's
  assistant, you are an individual, and the tablet is only what carries you."* The docs now lead
  with the individual, and give the body / persona / notebook mechanisms that back it up in code
  (fourteen moods and idle fidgets, `BallLook.persona` reaching the head of the system prompt, the
  private `Notebook`, the `(to yourself …)` self-review turn, and §11's rule that the character is
  delivery and never a licence). No code changed — the code was already right; the prose was not.
- `README.md` is now the **English** front page. The detailed Chinese provisioning guide moved to
  `README.zh-CN.md`, and both cross-link.
- `gradle.properties` no longer pins `org.gradle.java.home`. It previously hardcoded a
  machine-specific JDK path, so the build failed immediately for everyone else while looking like
  an environment problem. The real constraint (JDK 17–20; Gradle 8.4 cannot run on Java 21+,
  Android Studio's bundled JBR 25 is rejected) is now documented instead of worked around.
- `tools/check_look_sync.py` derives the repository root from its own location instead of a
  hardcoded absolute path.
- `tools/dump_faces_ascii.py` and `tools/verify_faces_lab.py` locate Chrome by `CHROME_PATH`, then
  common install locations, then `PATH`, instead of hardcoding a macOS-only path.
- `CLAUDE.md` corrected: the Gradle JDK note, the stale claim that the checkout is `frontend/`, and
  the description of which README is which.
- **Every bilingual document now follows one file per language, and each language links only to its
  own counterparts.** `CONTRIBUTING.md` and `docs/permissions.md` were written in Chinese under the
  English default filename — the opposite of the convention the other four files follow. They are
  now split into `.md` (English) and `.zh-CN.md` (Chinese), and every Chinese page that used to
  point at an English file now points at the Chinese one, so a Chinese reader is never dropped onto
  an English page. `SECURITY.md` and `CODE_OF_CONDUCT.md` have no Chinese edition; the Chinese docs
  now say so explicitly instead of linking silently.

- **Both READMEs restructured and fact-checked against the code.** The 16-item feature list now sits
  above *Quick start* instead of below *What it looks like*, so the page leads with what the thing
  does; both nav bars follow. Three claims were corrected against the source: the ball has
  **fourteen** moods (`ui/ball/Mood.kt`), not fifteen; the tool list no longer names `files` (there
  is no file tool — `location` does exist); and the Kotlin line count is now 43k+. The
  two-credential block puts the **brain key first and marks it required**, with the voice key second
  and optional — voice used to lead, which buried the one whose absence stops the brain from running
  at all. The "Things you can just say" table no longer repeats the WeChat example: that one is told
  as a single moment above the table ("you are driving when your mother messages you"), which is what
  the rest of the list is a variation on. And a short note near the top now says plainly that
  **Android is the only supported platform today** — the "any device with a body" line is about what
  the software is written for, and was reading as though other platforms already shipped.

- **Four documents corrected where they contradicted the code — no behaviour changed.**
  `DISCLAIMER.md` / `DISCLAIMER.zh-CN.md` and `docs/permissions.md` / `docs/permissions.zh-CN.md` all
  listed `brain=hub` first in their "data goes where" table's *Default* column, which read as though
  the hub were the out-of-box mode. It is not: `VoiceConfig.brainConfig` reads
  `getString("brain", BRAIN_LOCAL)`, and the `brain` picker lists 本机 leftmost for the same reason.
  The row now reads `brain=local` (the default) → `api.deepseek.com`, with hub mode as the other
  case. Separately, `CLAUDE.md`'s "Existing but not wired up" section claimed there was **no `tts.*`
  dispatcher namespace** — there is one (`tts.speak`, `CommandDispatcher`); what it lacks is a
  `ToolSchemas` entry, which is the actual reason the model cannot call it, and the distinction
  matters because the fix for "let it choose to speak" is adding a schema, not a branch. The
  matching comment at the head of `CommandDispatcher` ("will be added later") is corrected too. The
  error was load-bearing in the disclaimer especially: a reader who believed hub was the default
  would size the data-path risk wrongly.

- **CodeFlying is now introduced as what it is, and disclaimed.** *Quick start* used to name
  codeflying.app / codeflying.net as simply "the fastest route", which read as though it were this
  project's own or affiliated build channel — an impression a reader could act on (and later hold
  *this* project to). It is now described in its own terms first — **a general platform where you
  describe the app you want and it develops and publishes it** — followed by one plain sentence:
  **a separate service with no direct relationship to this project**, its own terms, pricing and data
  handling. Same treatment in `## Using Andee` / `## 三种使用方式`; the Chinese `## 从哪里开始看`
  table carried it too, and has since been removed (see *Removed*). The practical guidance is
  unchanged (still the shortest path; still an APK you have to install; the free token allowance is
  still finite and then paid), because none of that was wrong — only the implied ownership was.

- **The English README now addresses an English reader — in its examples and in the panel names it
  points at.** Two different faults with one symptom: a reader concluding the page is not for them.
  The examples were the Chinese ones with the words swapped (`WeChat`, `Taobao`, `JD`), and an
  example is precisely what a reader uses to judge whether something is addressed to them. They are
  now `WhatsApp`, `Amazon` and `eBay` — WhatsApp rather than a platform-native messenger because this
  runs on Android only, and naming an iOS-only one would be a false claim rather than a foreign one.
  Two of the scene examples needed rewriting rather than substituting: "look after my WeChat" is
  帮我看着微信 carried across word for word, which is not a sentence English builds. Separately, the
  panel names were the Chinese labels, so a reader on an English device was being told to look for
  **产物** — a word their screen does not have. They now use the labels `values-en/` actually ships:
  **Scenes**, **Made for you**, **What it remembers**, **Voice API key**.
  The **measured** passage keeps every fact. Xiaomi Pad 5, MIUI, WeChat 8.0.78 and the 219-node count
  are what was observed, and changing the app name there to make the prose consistent would be
  inventing a measurement. Only its framing moved: it opened on WeChat, which a non-Chinese reader
  reads as someone else's app having someone else's problem, so it now opens on the general
  behaviour and names WeChat as the one we happened to measure.
- **The blanket "没有任何遥测 / no telemetry at all" claim is gone, because the version row above
  made it false.** It appeared in nine places — `DISCLAIMER.md`, `DISCLAIMER.zh-CN.md`,
  `docs/permissions.md`, `docs/permissions.zh-CN.md`, both READMEs, and both README badges — and all
  nine were in the same voice: not "we anonymise it", not "opt out in settings", but a flat statement
  that the reader was invisible. A check that carries an IP, a phone model and a stable per-install ID
  is telemetry by any ordinary reading of the word, so keeping the sentence would have converted a
  product guarantee into a lie. The replacements keep the half that is still true and still
  checkable — no analytics SDK, no crash reporting, no event tracking, no advertising ID, all four
  confirmed by the same grep the old text quoted — and then state the single exception field by field,
  in a row of its own in the data table, next to an explicit list of what that request does *not*
  carry (conversation, screen, app list, contacts, location, crash log, advertising ID). `docs/permissions.md`
  is the canonical copy; the rest point at it. The badge went from `遥测-无` / `telemetry-none` to
  `遥测-不含内容` / `telemetry-no content`, because "no content" is the claim that survives.
  Worth recording for whoever edits these next: the old sentence was true of the *repository* on the
  day it was written and was always going to be false the moment the project shipped anything that
  phoned home. This file had already annotated that distinction in the DISCLAIMER's own entry above —
  *"we do not collect your data", which is true of the maintainers and false of the device* — and the
  claim that had to be corrected anyway was the neighbouring one about the maintainers.

### Removed

- `brain/andee-system-prompt.md` — an unused mirror of the cloud brain's prompt. It had no runtime
  role (no build step references it, no code loads it). **The cloud brain mode itself is not
  retired**; only this repo's copy of its prompt was removed. Recover it with
  `git show <rev>:brain/andee-system-prompt.md`.
- `dog/NRF24_Dongle/README.md` reference in the README — the dongle firmware is not in this
  repository and never was. The README now says so.
- The `## 从哪里开始看` section in `README.zh-CN.md`, heading included. Its visible content was three
  rows announcing that the demo video and the screenshots do not exist yet, which tells a reader
  nothing they cannot see for themselves. The heading went too: once the table was gone the section
  held nothing but an HTML comment, and a heading over nothing renders as exactly that. Every
  pointer to it went as well — the nav anchor, the header comment, and the sentence above. In its
  place, a slot for the demo video sits directly under the hero in both language files, and the
  asset checklist moved down beside the contributors block, which is where the English file already
  kept it.

### Known issues

- `tools/verify_faces_lab.py` reports 7/16 assertions passing against the committed
  `reports/ball-faces-lab.html`. This is pre-existing (identical before and after the portability
  changes above) and is not caused by the open-sourcing work. Left as-is; worth resolving before
  contributors are pointed at the ball-face lab.
- The debug WebSocket server (`net/BodyWsServer`) binds `0.0.0.0:9008` with no authentication. See
  `SECURITY.md`. Not fixed.
