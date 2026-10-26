package net.kuafuai.andee.desktop.shell

import java.io.File

/**
 * The `sandbox-exec` profile a command runs under. Everything is readable
 * except credentials; nothing is writable except the work folders, temp and
 * tool caches; nothing is deletable outside the places that can be rebuilt;
 * and the only network is localhost — where Andee's proxy and ssh-agent are.
 *
 * Same shape WorkBuddy's sandbox uses (read all, deny writes, allow some back),
 * with credentials denied by name and deletion handled on its own rule.
 */
object SandboxProfile {

    private val home: String = System.getProperty("user.home")

    /** Read and write denied. Credentials and the user's private data. */
    val SECRET_PATHS: List<String> = listOf(
        ".ssh", ".gnupg", ".aws", ".azure", ".config/gcloud", ".kube", ".docker/config.json", ".netrc",
        ".git-credentials", ".npmrc", ".pypirc", "Library/Keychains", "Library/Cookies",
        "Library/Application Support/Google/Chrome", "Library/Application Support/Microsoft Edge",
        "Library/Application Support/Firefox", "Library/Safari", "Library/Messages", "Library/Mail",
        "Library/Application Support/Andee",
    ).map { "$home/$it" }

    /** Readable but never writable: things that run again by themselves. */
    val PROTECTED_WRITE: List<String> = listOf(
        ".zshrc", ".zprofile", ".zshenv", ".zlogin", ".bashrc", ".bash_profile", ".profile",
        "Library/LaunchAgents", "Library/LaunchDaemons",
        // The rm shims and the helper sockets: replacing a shim would bypass the Trash.
        "Library/Andee-shell",
    ).map { "$home/$it" } + listOf("/Library/LaunchAgents", "/Library/LaunchDaemons")

    /** Tool caches a build needs to write. */
    val CACHES: List<String> = listOf(
        ".npm", ".pnpm-store", "Library/pnpm", ".yarn", "Library/Caches", ".cache", ".gradle", ".m2",
        ".cargo/registry", ".cargo/git", ".rustup", "go/pkg", ".bun", ".deno", ".cocoapods",
        "Library/Developer/Xcode/DerivedData", ".nvm", ".pyenv", ".conda",
    ).map { "$home/$it" }

    /** Names of directories inside a work folder that may be deleted outright — they rebuild. */
    val REBUILDABLE: List<String> = listOf(
        "node_modules", "build", "dist", "out", "target", ".gradle", ".next", ".nuxt", ".cache",
        "__pycache__", ".pytest_cache", ".venv", "venv", "DerivedData", ".turbo", "coverage",
    )

    /**
     * Folders that may not be made a work folder: everything below them would
     * become writable.
     */
    fun isDangerousWorkDir(path: String): Boolean {
        val p = File(path).canonicalPath.trimEnd('/')
        val bad = listOf("", "/System", "/Library", "/Applications", "/usr", "/bin", "/sbin", "/etc", "/private",
            "/Users", home, "$home/Library", "$home/Desktop", "$home/Documents", "$home/Downloads")
        return p in bad || p.startsWith("/System/") || p.startsWith("/usr/") || p.startsWith("$home/Library/")
    }

    /** A Scheme string literal for SBPL. */
    private fun lit(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /**
     * @param workDirs writable, and deletable where [deleteAllowed] says so
     * @param extraWrite paths the user allowed for this one command only
     * @param tmp the per-job temp directory
     */
    fun build(workDirs: List<String>, extraWrite: List<String>, tmp: String, deleteAllowed: Boolean = false): String {
        val real = { s: String -> runCatching { File(s).canonicalPath }.getOrDefault(s) }
        val sb = StringBuilder()
        sb.append("(version 1)\n(allow default)\n")
        // Writes: none, then the allowed places back.
        sb.append("(deny file-write* (subpath \"/\"))\n")
        sb.append("(allow file-write* (literal \"/dev/null\") (literal \"/dev/tty\") (regex #\"^/dev/ttys[0-9]+$\") (regex #\"^/dev/fd/\"))\n")
        val writable = (workDirs + extraWrite + CACHES + listOf(tmp, "/private/tmp", "/private/var/folders"))
            .map(real).distinct()
        sb.append("(allow file-write*").apply { writable.forEach { append(" (subpath ${lit(it)})") } }.append(")\n")
        // Deletion: denied everywhere, then allowed in temp, caches and rebuildable folders.
        if (!deleteAllowed) {
            sb.append("(deny file-write-unlink (subpath \"/\"))\n")
            val deletable = (CACHES + listOf(tmp, "/private/tmp", "/private/var/folders")).map(real)
            sb.append("(allow file-write-unlink").apply { deletable.forEach { append(" (subpath ${lit(it)})") } }
            for (w in workDirs.map(real)) for (r in REBUILDABLE) sb.append(" (subpath ${lit("$w/$r")})")
            sb.append(")\n")
        }
        // Never writable, even inside an allowed folder.
        sb.append("(deny file-write*").apply { PROTECTED_WRITE.forEach { append(" (subpath ${lit(it)})") } }.append(")\n")
        // Secrets: no reading, no writing.
        sb.append("(deny file-read* file-write*").apply { SECRET_PATHS.forEach { append(" (subpath ${lit(it)})") } }.append(")\n")
        // Network: localhost only (the proxy and the agent live there).
        sb.append("(deny network-outbound)\n")
        sb.append("(allow network-outbound (remote ip \"localhost:*\") (remote unix-socket))\n")
        return sb.toString()
    }
}
