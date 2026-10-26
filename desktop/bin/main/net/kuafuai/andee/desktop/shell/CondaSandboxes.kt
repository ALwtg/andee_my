package net.kuafuai.andee.desktop.shell

import net.kuafuai.andee.desktop.AppDirs
import net.kuafuai.andee.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Sandboxes on Windows, built out of **conda environments**.
 *
 * ## Why conda and not something heavier
 *
 * The Mac confines writes with `sandbox-exec` — a kernel policy, no daemon, no
 * image to build. Windows has no user-mode equivalent: the real options are a
 * container (excluded by the user), an AppContainer (needs a packaged identity),
 * or **an environment whose PATH is its own**. Conda gives the last one honestly:
 * a conda env is a directory plus a PATH prefix, so a command run inside it sees
 * only that env's tools, and nothing it installs leaks into the user's Python.
 *
 * ## What this is not
 *
 * It is **not a security boundary**. A process can still write anywhere the user
 * can, exactly like on the Mac where SBPL only constrains writes. Conda narrows
 * *which tools and which packages* a command finds; the cwd rule in [ShellTools]
 * and the environment scrub are what keep it in its lane. Saying this in the code
 * is deliberate: a sandbox that overpromises is worse than a PATH prefix that
 * explains itself.
 *
 * ## Registry, because the user asked for it
 *
 * Every environment this creates is written to `sandboxes.json` — name, prefix,
 * when it was made, when it was last used, how many times. `list` prunes entries
 * whose directory has gone (someone deleted it in Explorer), and `clearAll`
 * removes every environment and then `conda clean --all`, which is where the
 * actual disk hogs live (package tarballs and caches, not the envs).
 */
object CondaSandboxes {

    private const val TAG = "Sandbox"
    private const val ENV_PREFIX = "andee-"

    data class Sandbox(
        val id: String,
        val name: String,
        val prefix: String,
        val createdAt: Long,
        val lastUsedAt: Long,
        val uses: Int,
        val label: String,
        /**
         * Whether **Andee** made it.
         *
         * Everything the user already had in `envs/` is listed but not managed:
         * they can be run in, and they are never deleted by `clearAll`. This flag
         * is the difference between a useful tool and one that wipes a data
         * science environment somebody spent a weekend building.
         */
        val managed: Boolean,
    ) {
        val onDisk: Boolean get() = File(prefix).isDirectory
        fun sizeMb(): Long = runCatching {
            File(prefix).walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1024 / 1024
        }.getOrDefault(0L)
    }

    private val registryFile: File get() = AppDirs.file("sandboxes.json")

    // ---- conda itself -----------------------------------------------------

    @Volatile private var cachedConda: String? = null
    @Volatile private var lookedUp = false

    /**
     * The `conda.bat` to call, or null.
     *
     * `.bat` rather than `conda.exe`: on a standard Windows install the exe lives
     * in `Scripts/` but only the `condabin/conda.bat` wrapper sets up the shell
     * the way `activate` expects. Called through `cmd.exe /c`, which is why the
     * path is quoted at every call site.
     */
    fun conda(): String? {
        if (lookedUp) return cachedConda
        lookedUp = true
        val found = listOfNotNull(
            System.getenv("CONDA_EXE"),
            System.getenv("CONDA_PREFIX")?.let { "$it\\condabin\\conda.bat" },
            System.getenv("USERPROFILE")?.let { "$it\\miniconda3\\condabin\\conda.bat" },
            System.getenv("USERPROFILE")?.let { "$it\\anaconda3\\condabin\\conda.bat" },
            System.getenv("ProgramData")?.let { "$it\\miniconda3\\condabin\\conda.bat" },
            System.getenv("ProgramData")?.let { "$it\\anaconda3\\condabin\\conda.bat" },
            // The machine this was written on keeps it here; harmless elsewhere.
            "D:\\ProgramData\\miniconda3\\condabin\\conda.bat",
        ).firstOrNull { File(it).isFile }
            ?: runCatching {
                // Last resort: whatever `where` finds. Cheap and catches installs
                // this list has never heard of.
                val p = ProcessBuilder("cmd", "/c", "where", "conda.bat")
                    .redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText().lineSequence()
                    .map { it.trim() }.firstOrNull { it.endsWith("conda.bat") }
                p.waitFor(4, TimeUnit.SECONDS)
                out
            }.getOrNull()?.takeIf { File(it).isFile }
        cachedConda = found
        if (found == null) Log.i(TAG, "no conda on this machine; sandboxes are unavailable")
        return found
    }

    fun available(): Boolean = conda() != null

    /** The conda root: the directory above `condabin`. */
    private fun root(): String? = conda()?.let { File(it).parentFile?.parentFile?.absolutePath }

    private fun envPrefix(name: String): String? = root()?.let { "$it\\envs\\$name" }

    private fun condaRun(args: List<String>, timeoutSec: Long = 300): Pair<Int, String> {
        val conda = conda() ?: return -1 to "conda is not installed"
        val cmd = buildList { add("cmd"); add("/c"); add("\"$conda\""); addAll(args) }
        return runCatching {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            // Drain on a thread: conda prints progress and can fill a pipe.
            val out = StringBuilder()
            val reader = Thread {
                runCatching {
                    p.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { synchronized(out) { out.append(it).append('\n') } }
                    }
                }
            }.apply { isDaemon = true; start() }
            val done = p.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!done) runCatching { p.destroyForcibly() }
            reader.join(800)
            (if (done) p.exitValue() else -1) to synchronized(out) { out.toString() }
        }.getOrElse { -1 to (it.message ?: "conda failed") }
    }

    // ---- registry ---------------------------------------------------------

    private fun read(): MutableList<Sandbox> {
        val text = runCatching { registryFile.readText() }.getOrNull() ?: return mutableListOf()
        val arr = runCatching { JSONArray(text) }.getOrNull() ?: return mutableListOf()
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                Sandbox(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    prefix = o.optString("prefix"),
                    createdAt = o.optLong("created_at"),
                    lastUsedAt = o.optLong("last_used_at"),
                    uses = o.optInt("uses"),
                    label = o.optString("label"),
                    managed = true,
                )
            }.getOrNull()
        }.toMutableList()
    }

    private fun write(all: List<Sandbox>) {
        val arr = JSONArray()
        for (s in all) {
            arr.put(
                JSONObject().put("id", s.id).put("name", s.name).put("prefix", s.prefix)
                    .put("created_at", s.createdAt).put("last_used_at", s.lastUsedAt)
                    .put("uses", s.uses).put("label", s.label),
            )
        }
        runCatching { registryFile.writeText(arr.toString(2)) }
    }

    /**
     * Every sandbox still on disk.
     *
     * Entries whose directory is gone are dropped **and written back**: a registry
     * that keeps listing a deleted environment is how "clear the cache" ends up
     * reporting success for something that was never there.
     */
    fun list(): List<Sandbox> {
        val all = read()
        val alive = all.filter { it.onDisk }
        if (alive.size != all.size) write(alive)
        val known = alive.map { it.prefix }.toSet()
        // Envs the user already had. They are *usable*, which is the point of
        // listing them, and unmanaged, which is why clearAll walks past them.
        val extra = discovered().filterNot { it.prefix in known }
        return (alive.sortedByDescending { it.lastUsedAt } + extra).distinctBy { it.prefix }
    }

    /**
     * The conda environments that were here before Andee — including `base`'s
     * siblings under `envs/`.
     *
     * Read from the directory rather than `conda env list`: that command starts a
     * Python interpreter and takes a second, and the directory is the same truth.
     * `base` itself lives above `envs/` and is deliberately absent: running in
     * `base` is running in the user's own Python, which is what `sandbox` exists
     * to avoid, and `run_command` without `sandbox` already uses their PATH.
     */
    private fun discovered(): List<Sandbox> {
        val dir = root()?.let { File("$it\\envs") } ?: return emptyList()
        if (!dir.isDirectory) return emptyList()
        return (dir.listFiles() ?: emptyArray())
            .filter { it.isDirectory && File(it, "conda-meta").isDirectory }
            .map { f ->
                Sandbox(
                    id = f.name,
                    name = f.name,
                    prefix = f.absolutePath,
                    createdAt = f.lastModified(),
                    lastUsedAt = f.lastModified(),
                    uses = 0,
                    label = f.name,
                    managed = false,
                )
            }
    }

    fun find(idOrName: String): Sandbox? =
        list().firstOrNull { it.id == idOrName || it.name == idOrName || it.name == "$ENV_PREFIX$idOrName" }

    fun touch(s: Sandbox) {
        val all = read()
        val i = all.indexOfFirst { it.id == s.id }
        if (i < 0) return
        all[i] = all[i].copy(lastUsedAt = System.currentTimeMillis(), uses = all[i].uses + 1)
        write(all)
    }

    // ---- creating and removing -------------------------------------------

    /**
     * A new environment, **empty on purpose**.
     *
     * `conda create -n X` with no packages takes seconds; `python` takes minutes
     * (a download) and `--clone base` takes a gigabyte. A tool call that blocks a
     * turn for three minutes is a worse product than one that returns in five
     * seconds and says "it has no packages yet — install what you need inside".
     */
    fun create(label: String): Result<Sandbox> {
        val conda = conda() ?: return Result.failure(
            IllegalStateException(
                "conda is not installed, so no sandbox can be created. Install Miniconda " +
                    "(https://docs.conda.io/en/latest/miniconda.html) and ask again; until then run " +
                    "commands without a sandbox.",
            ),
        )
        val slug = label.lowercase().replace(Regex("[^a-z0-9_-]"), "-").trim('-').take(24)
            .ifEmpty { "box" }
        val name = "$ENV_PREFIX$slug-${System.currentTimeMillis() % 100000}"
        val started = System.currentTimeMillis()
        val (code, out) = condaRun(listOf("create", "-y", "-n", name), timeoutSec = 240)
        if (code != 0) {
            Log.w(TAG, "conda create failed ($code): ${out.take(300)}")
            return Result.failure(IllegalStateException("conda create failed: ${out.takeLast(400)}"))
        }
        val prefix = envPrefix(name)
            ?: return Result.failure(IllegalStateException("conda has no root to put an environment in"))
        val sandbox = Sandbox(
            id = name.removePrefix(ENV_PREFIX),
            name = name,
            prefix = prefix,
            createdAt = System.currentTimeMillis(),
            lastUsedAt = System.currentTimeMillis(),
            uses = 0,
            label = label.ifBlank { name },
            managed = true,
        )
        write(read() + sandbox)
        Log.i(TAG, "created $name in ${System.currentTimeMillis() - started}ms at $prefix")
        return Result.success(sandbox)
    }

    /**
     * Remove one environment **that Andee made**. `conda env remove` deletes the
     * directory itself.
     *
     * Returns false for an unmanaged environment — not an error the caller should
     * retry, but a refusal: the user's own environments are not Andee's to delete,
     * and `sandbox_clear` must not be a way around that.
     */
    fun remove(idOrName: String): Boolean {
        val s = find(idOrName) ?: return false
        if (!s.managed) {
            Log.i(TAG, "refusing to remove ${s.name}: it was not created by Andee")
            return false
        }
        val (code, out) = condaRun(listOf("env", "remove", "-y", "-n", s.name), timeoutSec = 180)
        val ok = code == 0 || !s.onDisk
        if (!ok) Log.w(TAG, "conda env remove failed for ${s.name}: ${out.take(300)}")
        write(read().filterNot { it.id == s.id })
        return ok
    }

    /**
     * Everything the user means by "clear the sandbox cache": the environments,
     * the registry, and conda's own package cache.
     *
     * `conda clean --all` is the part that actually frees gigabytes — envs are
     * directories of links, while `pkgs/` holds the downloaded tarballs and
     * extractions that no environment owns any more.
     */
    fun clearAll(): JSONObject {
        val mine = read().filter { it.managed }
        val kept = list().count { !it.managed }
        var removed = 0
        val failures = JSONArray()
        for (s in mine) {
            val (code, out) = condaRun(listOf("env", "remove", "-y", "-n", s.name), timeoutSec = 180)
            if (code == 0 || !s.onDisk) removed++ else failures.put("${s.name}: ${out.take(120)}")
        }
        runCatching { registryFile.delete() }
        // `conda clean` only removes downloaded tarballs and unused extractions.
        // It does not touch an environment's installed packages, which is why it is
        // safe to run next to the user's own envs.
        val (cleanCode, cleanOut) = condaRun(listOf("clean", "--all", "-y"), timeoutSec = 300)
        Log.i(TAG, "cleared $removed sandboxes, kept $kept the user's, conda clean exit=$cleanCode")
        return JSONObject()
            .put("removed", removed)
            .put("kept", kept)
            .put("registry_cleared", true)
            .put("package_cache_cleaned", cleanCode == 0)
            .put("conda_clean_output", cleanOut.takeLast(300))
            .put("failures", failures)
            .put(
                "note",
                "Only environments Andee created were removed. The ones that were already on this machine " +
                    "(kept: $kept) are untouched; conda's package cache was cleaned, which does not affect " +
                    "any environment's installed packages.",
            )
    }

    // ---- running inside one ----------------------------------------------

    /**
     * The environment variables that put a command *inside* [s] — what
     * `conda activate` does, done by hand.
     *
     * Not `conda run`: that wraps the command in another process, and then the
     * job's exit code is `conda`'s, its output is `conda`'s formatting, and
     * `stop_command` kills a wrapper whose child keeps running. Prepending the
     * env's directories to PATH gives the same isolation with a direct child,
     * which is what [ShellJob] needs.
     */
    fun envFor(s: Sandbox): Map<String, String>? {
        if (!s.onDisk) return null
        val p = s.prefix
        val path = listOf("$p", "$p\\Scripts", "$p\\Library\\bin", "$p\\condabin").joinToString(";")
        return mapOf(
            "PATH" to "$path;${System.getenv("PATH").orEmpty()}",
            "CONDA_PREFIX" to p,
            "CONDA_DEFAULT_ENV" to s.name,
            "CONDA_SHLVL" to "1",
        )
    }
}
