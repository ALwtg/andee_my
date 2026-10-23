package net.kuafuai.andee.config

/**
 * Which brain answers, and how to reach it if it is the local one.
 *
 * Lives in the shared module because both the phone and the desktop run the
 * same brain and need the same answer to "is this a different backend or the
 * same one asked differently". Android still reaches it as
 * `VoiceConfig.Companion.BrainConfig` through a type alias.
 */
data class BrainConfig(
    /** [BRAIN_HUB], [BRAIN_LOCAL] or [BRAIN_CODEFLYING]. */
    val mode: String,
    val apiKey: String,        // empty = on-device brain cannot run
    val baseUrl: String,
    val model: String,
    val thinking: Boolean,
    val reasoningEffort: String,
) {
    /**
     * The agent loop runs on this device (local DeepSeek or CodeFlying
     * — both are "the brain is here", only the backend differs). Only
     * [BRAIN_HUB] is "run elsewhere, listen on a socket".
     */
    val isLocal: Boolean get() = mode == BRAIN_LOCAL || mode == BRAIN_CODEFLYING

    /**
     * Same backend, possibly asked differently. [mode], [apiKey] and
     * [baseUrl] say *who* answers; [model], [thinking] and
     * [reasoningEffort] only say how they are asked, and a running brain
     * can take those on the next request. A config change that is only
     * the second kind must not rebuild the brain, because rebuilding it
     * is what discards the conversation.
     */
    fun sameBackend(other: BrainConfig): Boolean =
        mode == other.mode && apiKey == other.apiKey && baseUrl == other.baseUrl

    companion object {
        const val BRAIN_HUB = "hub"
        const val BRAIN_LOCAL = "local"
        const val BRAIN_CODEFLYING = "codeflying"
    }
}
