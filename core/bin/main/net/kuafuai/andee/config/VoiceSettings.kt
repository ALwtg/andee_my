package net.kuafuai.andee.config

/**
 * Everything the voice engines need to reach their service: endpoints, keys,
 * the speaker and which provider is in use. Built by each platform from its own
 * stored settings; the engines only read it.
 */
data class VoiceSettings(
    val apiKey: String,
    val asrEndpoint: String,
    val asrResourceId: String,
    val asrUid: String,
    val ttsEndpoint: String,
    val ttsResourceId: String,
    val ttsSpeaker: String,
    val ttsSampleRate: Int,
    /**
     * [VOICE_HUOSHAN] or [VOICE_GOOGLE] — which service hears and speaks.
     *
     * Read here rather than at the call site so that the two engines, the
     * meeting recorder and the start-up log cannot disagree about it, the same
     * reason [ttsSpeaker] lives in this object.
     */
    val voiceProvider: String,
    /** Vertex Express key. Empty means the Google path cannot run. */
    val googleApiKey: String,
    val googleBaseUrl: String,
    /**
     * GCP project **number**, and the region the Live API session is opened
     * in. Both are only used by the listening half.
     *
     * The model has to be named by its full resource path —
     * `projects/{n}/locations/{r}/publishers/google/models/{m}` — because the
     * Live API reads a bare name as a project id and answers
     * `1008 Permission denied on resource project …`. The reference gateway
     * found that out the hard way; these two rows are what that costs.
     */
    val googleProject: String,
    val googleLocation: String,
    val googleAsrModel: String,
    val googleTtsModel: String,
    /**
     * A hint for both directions, or blank for "let Google decide".
     *
     * Blank rather than a fixed `zh-CN`, following the reference gateway: a
     * Chinese user says "hello 帮我查一下" and an English one says
     * "帮我查杭州天气", so naming one language does not restrict the other, it
     * only makes it less accurate.
     */
    val googleLanguage: String,
) {
    companion object {
        const val VOICE_HUOSHAN = "huoshan"
        const val VOICE_GOOGLE = "google"
    }
}
