package net.kuafuai.andee.desktop.shell

import net.kuafuai.andee.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * How a command is started, per platform.
 *
 * The Mac path is unchanged: `script` for a real terminal, `sandbox-exec` for the
 * SBPL profile, `zsh -f` so no rc file can change what the user approved.
 *
 * **Windows has none of those three.** There is no `script`, no `sandbox-exec`,
 * and no SBPL. So the same three promises are kept with different machinery, and
 * the differences are stated rather than papered over:
 *
 * | promise | macOS | Windows |
 * |---|---|---|
 * | a real terminal | `script -q /dev/null` | **none** — no ConPTY without extra bindings, so `-NonInteractive` |
 * | no rc file can lie | `zsh -f` | `-NoProfile -NonInteractive` |
 * | writes confined to work folders | SBPL profile | cwd check + env scrub + optional **conda** env |
 *
 * The `-NonInteractive` flag is load-bearing: PowerShell will otherwise sit on a
 * `Read-Host` prompt forever, and the difference between "waiting for input" and
 * "hung" is the whole reason [ShellTools] reports the former.
 */
object ShellRunner {

    val isWindows: Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("windows")

    /**
     * The command line to launch.
     *
     * [profile] is a `sandbox-exec` policy file and is only ever passed on macOS;
     * on Windows the caller has already refused to build one.
     */
    fun argv(command: String, profile: File?): List<String> = if (isWindows) {
        listOf(pwsh(), "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", command)
    } else {
        val inner = mutableListOf<String>()
        if (profile != null) inner += listOf("/usr/bin/sandbox-exec", "-f", profile.absolutePath)
        // -f: no .zshrc, so aliases and functions cannot change what the user approved.
        inner += listOf("/bin/zsh", "-f", "-c", command)
        listOf("/usr/bin/script", "-q", "/dev/null") + inner
    }

    /**
     * PowerShell 7 when it is installed, Windows PowerShell otherwise.
     *
     * 7 matters here: it writes UTF-8 by default, where 5.1's console encoding is
     * the ANSI code page and turns any non-ASCII output into `?`. Measured on this
     * machine, Chinese output from 5.1 arrives mangled.
     */
    fun pwsh(): String {
        val candidates = listOfNotNull(
            System.getenv("ProgramFiles")?.let { "$it\\PowerShell\\7\\pwsh.exe" },
            System.getenv("ProgramW6432")?.let { "$it\\PowerShell\\7\\pwsh.exe" },
            "C:\\Program Files\\PowerShell\\7\\pwsh.exe",
        )
        return candidates.firstOrNull { File(it).isFile } ?: "powershell.exe"
    }

    /**
     * End a job and everything it started.
     *
     * macOS signals the process *group* (`script` called setsid), TERM then KILL.
     * Windows has no group signal: `taskkill /T` walks the tree by parent pid and
     * `/F` is the only reliable stop, so a program that would have shut down
     * cleanly does not get the chance. That is a real difference, and the reply
     * for a stopped job says `stopped` rather than implying a graceful shutdown.
     */
    fun stop(process: Process) {
        val pid = runCatching { process.pid() }.getOrNull() ?: return
        if (isWindows) {
            runCatching {
                ProcessBuilder("taskkill", "/PID", pid.toString(), "/T", "/F")
                    .redirectErrorStream(true).start().waitFor(4, TimeUnit.SECONDS)
            }.onFailure { Log.w("Shell", "taskkill failed for $pid", it) }
            return
        }
        // Negative pid = the process group `script` created with setsid.
        signal("-TERM", pid)
        runCatching { process.descendants().forEach { d -> signal("-TERM", d.pid()) } }
        if (!runCatching { process.waitFor(3, TimeUnit.SECONDS) }.getOrDefault(false)) {
            signal("-KILL", pid)
            runCatching { process.descendants().forEach { d -> signal("-KILL", d.pid()) } }
            runCatching { process.destroyForcibly() }
        }
    }

    private fun signal(sig: String, pid: Long) {
        runCatching { ProcessBuilder("/bin/kill", sig, "-$pid").start().waitFor(2, TimeUnit.SECONDS) }
    }
}
