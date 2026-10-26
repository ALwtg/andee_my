package net.kuafuai.andee.desktop

/**
 * What this body is, put ahead of the shared system prompt (which was written
 * for the phone). It has to be read first: the shared text opens with "you are
 * a tablet", and a model that believes it can tap the screen will promise the
 * user things this Mac cannot do yet.
 *
 * It names what is missing as plainly as what is there, and it must be kept in
 * step with the tools [DesktopBrainHost] actually registers: a capability
 * mentioned here and absent from the tool list is a lie told in the first
 * paragraph the model reads.
 */
object MacPrompt {

    /**
     * The prompt, with the machine-specific paragraphs supplied by [notes].
     *
     * The default is the Mac, byte for byte the text this file used to hold, so
     * every existing call site means exactly what it did before. The Mac's own
     * sentences moved into [PlatformNotes.mac] only to be *replaced* on Windows —
     * not to be rewritten.
     */
    fun text(caps: Capabilities, notes: PlatformNotes = PlatformNotes.mac): String = buildString {
        append(
            """
### 0. Read this first: on this device you are a ${notes.name} app, not a tablet

Everything below was written for the phone and tablet version of you. On this device you are **Andee running on the user's ${notes.name}** — a desktop app with a window. Wherever the text below says "tablet", "phone", "the ball", "swipe", "long-press", 微信 package names or Android settings, read it as describing the other version of you, not this one.

What you can do here, right now, is exactly what your tool list contains — nothing in the text below adds to it.
""".trim(),
        )
        append("\n\n")
        if (caps.screen) {
            append(notes.screenParagraph())
        } else {
            append("- **You cannot see or operate this ${notes.name}'s screen yet.** There are no screen tools on this device. Do not offer to open, tap, click, type into or look at anything on the screen, and do not say you did.\n")
        }
        if (caps.voice) {
            append("- The user may type or speak (the microphone button). A spoken turn is transcribed, so §6 about mishearing applies to it; a typed one is exact. Spoken turns are answered aloud, typed ones are not.\n")
        } else {
            append("- **The user types to you here; nothing is transcribed.** §6 about mishearing does not apply: take their characters as written.\n")
        }
        append(notes.shellParagraph(caps.shell))
        if (!caps.pages) {
            append("- show_html is not available here yet: give structured answers as a well-laid-out reply instead of a page.\n")
        }
        append(notes.notebookParagraph())
        append("- If the user asks for something this device cannot do yet, say so in one line and, if it fits, offer what you can do instead. Never pretend.")
    }

    /** What this build of the Mac body really has. Each flag must match a registered tool family. */
    data class Capabilities(
        val screen: Boolean = false,
        val voice: Boolean = false,
        val shell: Boolean = false,
        val pages: Boolean = false,
    )
}
