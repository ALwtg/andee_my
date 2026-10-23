package net.kuafuai.andee.desktop

/**
 * The vault's values, for scrubbing command output. Empty until the Mac vault
 * exists; kept as its own seam so the shell's redaction does not have to
 * change when it does.
 */
object VaultSecrets {
    @Volatile var source: () -> List<Pair<String, String>> = { emptyList() }
    fun values(): List<Pair<String, String>> = runCatching { source() }.getOrDefault(emptyList())
}
