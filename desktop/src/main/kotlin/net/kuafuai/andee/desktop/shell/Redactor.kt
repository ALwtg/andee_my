package net.kuafuai.andee.desktop.shell

/**
 * Scrubs secrets out of command output before the model or the log sees it.
 *
 * Two layers. Exact values — every vault password and Andee's own keys — are
 * replaced wherever they appear, because a value we know is a value we can
 * always catch. Then common credential shapes (the kinds gitleaks looks for)
 * are masked, for secrets Andee never knew about.
 */
class Redactor(private val known: () -> List<Pair<String, String>>) {

    private val shapes: List<Pair<String, Regex>> = listOf(
        "private-key" to Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"),
        "aws-access-key" to Regex("\\b(AKIA|ASIA)[0-9A-Z]{16}\\b"),
        "github-token" to Regex("\\b(ghp|gho|ghu|ghs|ghr|github_pat)_[A-Za-z0-9_]{20,}\\b"),
        "openai-key" to Regex("\\bsk-(proj-)?[A-Za-z0-9_-]{20,}\\b"),
        "slack-token" to Regex("\\bxox[abprs]-[A-Za-z0-9-]{10,}\\b"),
        "google-api-key" to Regex("\\bAIza[0-9A-Za-z_-]{35}\\b"),
        "stripe-key" to Regex("\\b(sk|rk)_(live|test)_[0-9a-zA-Z]{20,}\\b"),
        "jwt" to Regex("\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\b"),
        "url-credentials" to Regex("(?<=://)[^/\\s:@]+:[^/\\s@]+(?=@)"),
        "secret-assignment" to Regex(
            "(?i)(?<=\\b(password|passwd|secret|api[_-]?key|access[_-]?token|auth[_-]?token)\\s{0,3}[=:]\\s{0,3}[\"']?)[^\\s\"']{6,}",
        ),
    )

    fun scrub(text: String): String {
        var out = text
        for ((label, value) in known()) {
            if (value.length >= 4) out = out.replace(value, "«redacted: $label»")
        }
        for ((label, re) in shapes) out = re.replace(out, "«redacted: $label»")
        return out
    }

    /** True when [command] itself carries a known secret value — such a command is refused outright. */
    fun containsKnown(command: String): String? =
        known().firstOrNull { (_, v) -> v.length >= 4 && command.contains(v) }?.first
}
