package net.kuafuai.andee.desktop.shell

/**
 * How dangerous a command line is, decided by reading it — before anything
 * runs. Five tiers, the highest segment wins.
 *
 * Built after reading WorkBuddy's classifier (CodeBuddy Code), but stricter in
 * two places on purpose (see the design doc, §4.1):
 *  - "safe" holds only read-only commands. Anything that writes is at least
 *    LOW, sandbox or not.
 *  - Anything that changes a system other than this Mac — a push, a publish,
 *    a POST — is at least MEDIUM. The sandbox stops nothing that leaves the
 *    machine, so a person has to see it.
 */
object RiskClassifier {

    enum class Tier { SAFE, LOW, MEDIUM, HIGH, CRITICAL }

    data class Verdict(val tier: Tier, val reason: String, val segment: String)

    /** Programs that only read. Their first word decides; arguments are then checked for writes. */
    private val READ_ONLY = setOf(
        "ls", "pwd", "cat", "head", "tail", "less", "more", "wc", "sort", "uniq", "cut", "tr", "grep", "egrep",
        "fgrep", "rg", "find", "which", "where", "whereis", "type", "file", "stat", "du", "df", "date", "cal",
        "echo", "printf", "env", "printenv", "uname", "whoami", "hostname", "id", "uptime", "ps", "top", "jq",
        "diff", "cmp", "basename", "dirname", "realpath", "readlink", "column", "nl", "od", "xxd", "md5",
        "shasum", "sw_vers", "system_profiler", "mdfind", "mdls", "defaults", "plutil", "otool", "lipo",
        "true", "false", "test", "[", "sleep", "seq", "yes", "tree", "fold", "fmt", "expand", "rev",
        "sed", "awk", "paste", "join", "comm", "tac", "strings", "hexdump", "base64", "sha256sum", "md5sum",
        "xargs", "tee",
        "Get-ChildItem", "Get-Content", "Get-Location", "Get-Item", "Get-Process", "Select-String",
    )

    private val GIT_READ = setOf(
        "status", "log", "diff", "show", "branch", "remote", "rev-parse", "describe", "blame", "ls-files",
        "ls-tree", "shortlog", "reflog", "tag", "config", "grep", "cat-file", "stash",
    )

    private val CRITICAL = listOf(
        Regex("""\brm\s+(-[a-z]*r[a-z]*f|-[a-z]*f[a-z]*r|-rf|-fr|--recursive\s+--force|--force\s+--recursive)\s+(/|~|\${'$'}HOME|/\*|~/\*|\.\.?/?\s*$)"""),
        Regex("""\bmkfs(\.\w+)?\b"""), Regex("""\bdd\b.*\bof=/dev/"""), Regex("""\bdiskutil\s+(erase|zero|secureErase|partition)"""),
        Regex("""\bgit\s+reset\s+--hard\b"""), Regex("""\bgit\s+clean\s+-[a-zA-Z]*[fdxX]"""),
        Regex("""\bgit\s+checkout\s+(--\s+)?\.\s*$"""),
        Regex("""\bgit\s+push\b.*(\s-f\b|--force\b).*\b(main|master|develop|release|production)\b"""),
        Regex("""DROP\s+(DATABASE|TABLE)""", RegexOption.IGNORE_CASE), Regex("""\bTRUNCATE\s+TABLE\b""", RegexOption.IGNORE_CASE),
        Regex("""\bterraform\s+destroy\b"""), Regex("""\bkubectl\s+delete\s+(ns|namespace)\b"""),
        Regex(""":\(\)\s*\{\s*:\|:&\s*\};:"""),
        Regex("""\bcsrutil\s+disable\b"""), Regex("""\bnvram\b"""),
    )

    private val HIGH = listOf(
        Regex("""^\s*sudo\b"""), Regex("""^\s*su\b"""), Regex("""\bdoas\b"""),
        Regex("""\brm\s+(-[a-zA-Z]*[rR]|--recursive)"""), Regex("""\brm\b[^|;&]*[*?]"""),
        Regex("""\bfind\b.*\s-delete\b"""), Regex("""\bfind\b.*-exec\s+rm\b"""),
        Regex("""\bchmod\s+(-R\s+)?777\b"""), Regex("""\bchown\s+-R\b"""),
        Regex("""\bgit\s+push\b.*(\s-f\b|--force\b|--force-with-lease\b|--delete\b|\s:\S)"""),
        Regex("""\bgit\s+branch\s+-D\b"""), Regex("""\bgit\s+(restore|filter-branch|filter-repo)\b"""),
        Regex("""\bgit\s+stash\s+(drop|clear)\b"""), Regex("""\bgit\s+checkout\s+--\s+\S"""),
        Regex("""\bshred\b"""), Regex("""\bsrm\b"""), Regex("""\btruncate\b.*-s\s*0"""),
        Regex("""\bdocker\s+(system|volume|image)\s+prune\b"""), Regex("""\bkubectl\s+(delete|drain)\b"""),
        Regex("""\blaunchctl\s+(load|bootstrap|enable|submit)\b"""), Regex("""\bcrontab\b"""),
        Regex("""\bdefaults\s+(write|delete)\b"""), Regex("""\bspctl\b"""), Regex("""\bsecurity\s+(delete|add|import|set)"""),
        Regex("""\bkillall\b"""), Regex("""\bpkill\b"""), Regex("""\bkill\s+-9\b"""),
        Regex("""\bosascript\b"""), Regex("""Remove-Item\b.*-Recurse""", RegexOption.IGNORE_CASE),
    )

    /** Changes something that is not this Mac. */
    private val REMOTE = listOf(
        Regex("""\bgit\s+push\b"""), Regex("""\b(npm|pnpm|yarn)\s+publish\b"""), Regex("""\bdocker\s+push\b"""),
        Regex("""\bgh\s+(pr|issue|release|repo)\s+(create|merge|close|delete|edit|comment)\b"""),
        Regex("""\bcurl\b.*\s(-X\s*(POST|PUT|PATCH|DELETE)|--request\s+(POST|PUT|PATCH|DELETE)|-d\b|--data|-F\b|--form|-T\b|--upload-file)""", RegexOption.IGNORE_CASE),
        Regex("""\bwget\b.*--post"""), Regex("""\bscp\b"""), Regex("""\brsync\b.*\S+:\S*"""), Regex("""\bssh\b"""),
        Regex("""\bkubectl\s+(apply|create|patch|scale|rollout)\b"""), Regex("""\bterraform\s+apply\b"""),
        Regex("""\b(aws|gcloud|az)\s+\S+\s+(create|delete|put|update|deploy|rm)\b"""),
    )

    private val MEDIUM = listOf(
        Regex("""\brm\b"""), Regex("""\bunlink\b"""), Regex("""\brmdir\b"""), Regex("""\bgit\s+rm\b"""),
        Regex("""\bgit\s+(commit|merge|rebase|reset|checkout\s+-B|tag\s+-d|branch\s+-d)\b"""),
        Regex("""\bdocker\s+(rm|rmi|stop|kill)\b"""), Regex("""\bkill\b"""),
        Regex("""\b(npm|pnpm|yarn|pip3?|brew|gem|cargo)\s+(uninstall|remove|rm)\b"""),
        Regex("""\bbrew\s+(install|upgrade|reinstall|tap)\b"""),
        Regex("""\b(npm|pnpm|yarn)\s+(install|add|i)\s+-g\b"""), Regex("""\bpip3?\s+install\b"""),
        Regex("""\bcurl\b.*\|\s*(sh|bash|zsh)\b"""), Regex("""\bwget\b.*\|\s*(sh|bash|zsh)\b"""),
        Regex("""\bmv\b"""), Regex("""\bchmod\b"""), Regex("""\bchown\b"""),
    )

    /** Arguments that make "always allow" not apply, whatever the prefix. */
    val DANGEROUS_FLAGS = listOf(
        Regex("""(^|\s)(--force|-f|--hard|-rf|-fr|-Recurse|--no-verify|--delete|-D)(\s|$)"""),
    )

    fun classify(command: String): Verdict {
        val text = stripHeredocs(command)
        crossSegment(text)?.let { return it }
        var worst = Verdict(Tier.SAFE, "read-only", text.trim())
        for (seg in segments(text)) {
            val v = single(seg)
            if (v.tier > worst.tier) worst = v
        }
        if (Regex("""\$\(|`""").containsMatchIn(unquotedSingle(text)) && worst.tier < Tier.LOW) {
            worst = Verdict(Tier.LOW, "runs a nested command", text.trim())
        }
        return worst
    }

    private fun crossSegment(t: String): Verdict? = when {
        Regex("""\|\s*xargs\s+(-\S+\s+)*(rm|unlink)\b""").containsMatchIn(t) ->
            Verdict(Tier.HIGH, "deletes in bulk through xargs", t.trim())
        Regex("""\b(curl|wget)\b[^|]*\|\s*(sh|bash|zsh|python3?)\b""").containsMatchIn(t) ->
            Verdict(Tier.HIGH, "runs a script straight off the network", t.trim())
        else -> null
    }

    private fun single(raw: String): Verdict {
        val shown = raw.trim()
        if (shown.isEmpty()) return Verdict(Tier.SAFE, "empty", shown)
        // Match against the line with quoted text blanked, so `echo 'rm -rf /'`
        // is an echo, not a deletion.
        val seg = unquoted(shown)
        CRITICAL.firstOrNull { it.containsMatchIn(seg) }?.let {
            return Verdict(Tier.CRITICAL, "irreversible, and it reaches far", shown)
        }
        HIGH.firstOrNull { it.containsMatchIn(seg) }?.let { return Verdict(Tier.HIGH, "destructive or system-wide", shown) }
        REMOTE.firstOrNull { it.containsMatchIn(seg) }?.let {
            return Verdict(Tier.MEDIUM, "changes something outside this Mac", shown)
        }
        MEDIUM.firstOrNull { it.containsMatchIn(seg) }?.let { return Verdict(Tier.MEDIUM, "changes or removes files", shown) }

        val words = seg.split(Regex("\\s+"))
        val prog = words.first().substringAfterLast('/')
        val writesFile = Regex("""(^|[^<>&0-9])>{1,2}\s*([^&\s]+)""").findAll(seg)
            .any { it.groupValues[2] != "/dev/null" }
        if (prog == "git") {
            val sub = words.getOrNull(1).orEmpty()
            return if (sub in GIT_READ && !writesFile) Verdict(Tier.SAFE, "read-only git", shown)
            else Verdict(Tier.LOW, "git $sub", shown)
        }
        if (prog in READ_ONLY && !writesFile && !(prog == "find" && Regex("""-(exec|ok|delete)""").containsMatchIn(seg)) &&
            !(prog == "defaults" && words.getOrNull(1) != "read") &&
            // These read unless told to write: sed -i edits in place, tee writes its arguments,
            // awk can redirect inside its program, xargs runs whatever follows it.
            !(prog == "sed" && Regex("""(^|\s)-[a-zA-Z]*i""").containsMatchIn(seg)) &&
            !(prog == "tee" && words.drop(1).any { !it.startsWith("-") }) &&
            !(prog == "awk" && Regex("""print[^|;]*>|system\(""").containsMatchIn(raw)) &&
            !(prog == "xargs")
        ) {
            return Verdict(Tier.SAFE, "read-only", shown)
        }
        return Verdict(Tier.LOW, if (writesFile) "writes a file" else "not known to be read-only", shown)
    }

    /** Split on ; && || | & outside quotes. */
    fun segments(s: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var sq = false
        var dq = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && !sq && i + 1 < s.length -> { cur.append(c).append(s[i + 1]); i += 2; continue }
                c == '\'' && !dq -> sq = !sq
                c == '"' && !sq -> dq = !dq
                !sq && !dq && (c == ';' || c == '|' || c == '&' || c == '\n') -> {
                    // `2>&1` and `&>` are redirections, not separators.
                    val prev = if (i > 0) s[i - 1] else ' '
                    if (c == '&' && (prev == '>' || (i + 1 < s.length && s[i + 1] == '>'))) {
                        cur.append(c); i++; continue
                    }
                    if (cur.isNotBlank()) out += cur.toString()
                    cur.setLength(0)
                    while (i + 1 < s.length && s[i + 1] in ";|&") i++
                    i++
                    continue
                }
            }
            cur.append(c)
            i++
        }
        if (cur.isNotBlank()) out += cur.toString()
        return out
    }

    /** Quoted text replaced by `Q`, so patterns see only the command's own words. */
    private fun unquoted(s: String): String =
        s.replace(Regex("""'[^']*'"""), "Q").replace(Regex(""""(\\.|[^"\\])*""""), "Q")

    /** Single quotes only: inside double quotes `$(…)` still runs. */
    private fun unquotedSingle(s: String): String = s.replace(Regex("""'[^']*'"""), "Q")

    private fun stripHeredocs(s: String): String =
        s.replace(Regex("""<<-?\s*'?(\w+)'?\n[\s\S]*?\n\1\b"""), "<<HEREDOC")
}
