package net.kuafuai.andee

import android.app.Application
import android.util.Log

/**
 * Runs before any service, receiver or activity of this app. It exists to
 * point the shared module's logging at logcat before the first line is
 * written; nothing else belongs here.
 */
class AndeeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        net.kuafuai.andee.util.Log.sink = object : net.kuafuai.andee.util.Log.Sink {
            override fun log(level: Char, tag: String, msg: String, t: Throwable?) {
                when (level) {
                    'V' -> Log.v(tag, msg)
                    'D' -> Log.d(tag, msg)
                    'I' -> Log.i(tag, msg)
                    'W' -> if (t == null) Log.w(tag, msg) else Log.w(tag, msg, t)
                    else -> if (t == null) Log.e(tag, msg) else Log.e(tag, msg, t)
                }
            }
        }
    }
}
