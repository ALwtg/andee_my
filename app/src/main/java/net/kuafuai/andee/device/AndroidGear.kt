package net.kuafuai.andee.device

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import net.kuafuai.andee.config.Categories
import net.kuafuai.andee.config.Gear
import net.kuafuai.andee.config.Notebook
import net.kuafuai.andee.util.Log
import org.json.JSONObject

/**
 * The gear shelf on the phone: what is installed and what each thing is for. Same shelf, same rules as the
 * Mac's (`Gear` in the shared module); only the scan differs.
 *
 * Facts come from `PackageManager` (launchable apps only — what a person means by "what is on this tablet",
 * not the 400 system packages) and, when the user has granted **usage access**, from `UsageStatsManager`:
 * last time used and foreground time over the last month. That permission is a Settings switch the app can
 * only point at, never grant; without it the shelf still works, ranked by what Andee itself opens
 * ([noteLaunched]) and by what it has learned, and says so rather than pretending to know.
 */
class AndroidGear(private val context: Context) {

    val gear: Gear get() = Notebook.gear(context)

    @Volatile private var lastScanAt = 0L

    /** True when the system will tell us how much each app is used. */
    fun hasUsageAccess(): Boolean = runCatching {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /** The line for the user message. Scans in the background when it has never been, or is a day stale. */
    fun indexForPrompt(): String {
        if (System.currentTimeMillis() - lastScanAt > SCAN_TTL_MS) {
            lastScanAt = System.currentTimeMillis()   // first: two callers must not both scan
            Thread { scanNow() }.apply { isDaemon = true; name = "gear-scan" }.start()
        }
        return gear.indexForPrompt()
    }

    fun scanNow() {
        runCatching {
            val diff = gear.applyScan(scan())
            if (diff.added.isNotEmpty() || diff.gone.isNotEmpty()) Log.i(TAG, "gear: +${diff.added.size} -${diff.gone.size}")
        }.onFailure { Log.w(TAG, "gear scan failed", it) }
    }

    /** Make sure the shelf has been read at least once before a tool answers from it. */
    fun ensureScanned() {
        if (gear.all().isEmpty() || System.currentTimeMillis() - lastScanAt > SCAN_TTL_MS) {
            lastScanAt = System.currentTimeMillis()
            scanNow()
        }
    }

    private fun scan(): List<Gear.Item> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val usage = usageByPackage()
        val own = launched()
        val out = LinkedHashMap<String, Gear.Item>()
        for (ri in pm.queryIntentActivities(launcher, 0)) {
            val pkg = ri.activityInfo.packageName
            if (pkg == context.packageName || pkg in out) continue
            val label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg)
            val version = runCatching { pm.getPackageInfo(pkg, 0).versionName.orEmpty() }.getOrDefault("")
            val u = usage[pkg]
            // The system's numbers when we have them; otherwise our own count of the apps *we* opened.
            out[pkg] = Gear.Item(
                id = pkg, label = label, version = version,
                lastUsedAt = u?.first ?: own.optLong("t:$pkg"),
                uses = u?.second ?: own.optInt("n:$pkg"),
            )
        }
        return out.values.toList()
    }

    /** package → (last used ms, approximate launches over the last 30 days), or empty without usage access. */
    private fun usageByPackage(): Map<String, Pair<Long, Int>> {
        if (!hasUsageAccess()) return emptyMap()
        return runCatching {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, end - 30L * 86_400_000, end)
            val m = HashMap<String, Pair<Long, Int>>()
            for (s in stats) {
                // A day with the app in the foreground at all counts as a use: an honest number from a
                // source that gives time, not launches.
                val used = if (s.totalTimeInForeground > 0) 1 else 0
                val old = m[s.packageName]
                m[s.packageName] = maxOf(old?.first ?: 0L, s.lastTimeUsed) to ((old?.second ?: 0) + used)
            }
            m
        }.getOrDefault(emptyMap())
    }

    // ---- Andee's own count of what it opened, for when the system will not say ----------------------

    private fun prefs() = context.getSharedPreferences("gear_launches", Context.MODE_PRIVATE)

    private fun launched(): JSONObject = JSONObject().also { o ->
        for ((k, v) in prefs().all) when (v) { is Long -> o.put(k, v); is Int -> o.put(k, v) }
    }

    /** `launch_app` opened [pkg]: the one usage signal available with no permission at all. */
    fun noteLaunched(pkg: String) {
        if (pkg.isBlank()) return
        prefs().edit().putLong("t:$pkg", System.currentTimeMillis()).putInt("n:$pkg", prefs().getInt("n:$pkg", 0) + 1).apply()
    }

    companion object {
        private const val TAG = "Gear"
        private const val SCAN_TTL_MS = 24L * 60 * 60 * 1000
    }
}
