package net.kuafuai.andee.desktop

import androidx.compose.runtime.mutableStateListOf

/**
 * Where the window is. A stack, because settings opens the notebook and the
 * notebook closes back to settings; the sidebar jumps by replacing the stack.
 * Screens are plain strings: "chat", "settings", "notebook", "vault", "scenes",
 * "expressions", "gear", "peers", "selfcheck", "artifacts", and "topic:<id>".
 */
class Nav {
    val stack = mutableStateListOf("chat")
    val current: String get() = stack.last()

    /** Open [s] on top of what is showing. */
    fun push(s: String) { if (current != s) stack.add(s) }

    /** The sidebar's jump: a top-level destination replaces the stack, with chat underneath so ✕ lands there. */
    fun go(s: String) {
        stack.clear()
        stack.add("chat")
        if (s != "chat") stack.add(s)
    }

    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
}
