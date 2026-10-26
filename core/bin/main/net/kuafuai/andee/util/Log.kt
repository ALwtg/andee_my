package net.kuafuai.andee.util

/**
 * Logging for shared code, with Android's call shape so code moved out of the
 * app reads unchanged (`Log.i(TAG, "…")`).
 *
 * Each platform installs its sink once at startup — the phone forwards to
 * `android.util.Log`, the desktop writes a file. Until then lines go to stderr,
 * which is what unit tests see.
 */
object Log {

    interface Sink {
        fun log(level: Char, tag: String, msg: String, t: Throwable?)
    }

    @Volatile
    var sink: Sink = object : Sink {
        override fun log(level: Char, tag: String, msg: String, t: Throwable?) {
            System.err.println("$level/$tag: $msg")
            t?.printStackTrace()
        }
    }

    fun v(tag: String, msg: String) = sink.log('V', tag, msg, null)
    fun d(tag: String, msg: String) = sink.log('D', tag, msg, null)
    fun i(tag: String, msg: String) = sink.log('I', tag, msg, null)
    fun w(tag: String, msg: String) = sink.log('W', tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable?) = sink.log('W', tag, msg, t)
    fun e(tag: String, msg: String) = sink.log('E', tag, msg, null)
    fun e(tag: String, msg: String, t: Throwable?) = sink.log('E', tag, msg, t)
}
