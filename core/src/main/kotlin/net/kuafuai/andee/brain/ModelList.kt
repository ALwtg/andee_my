package net.kuafuai.andee.brain

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * `GET <baseUrl>/models` — the ids an OpenAI-shaped gateway says it serves.
 *
 * Here rather than twice because both bodies ask the same question of the same
 * kind of endpoint: the phone's settings card and the desktop's. Blocking on
 * purpose: every caller already owns a thread (the phone's `Thread`, the
 * desktop's IO dispatcher) and wants a list back, not a future.
 *
 * The key is the one in the field the user is editing, not the one that was
 * saved — the usual order is "paste a key, tap 获取模型列表", with nothing
 * committed yet.
 */
fun listModels(baseUrl: String, apiKey: String): List<String> {
    val req = Request.Builder()
        .url("${baseUrl.trim().trimEnd('/')}/models")
        .addHeader("Authorization", "Bearer $apiKey")
        .get()
        .build()
    http.newCall(req).execute().use { resp ->
        val body = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) {
            // The gateway's own words beat a bare status code: a wrong key, no
            // balance and a typo'd address all explain themselves in
            // `error.message`.
            val detail = runCatching {
                JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
            }.getOrDefault("")
            throw IOException("HTTP ${resp.code}" + if (detail.isBlank()) "" else ": $detail")
        }
        val data = runCatching { JSONObject(body).optJSONArray("data") }.getOrNull()
        return (0 until (data?.length() ?: 0))
            .mapNotNull { i -> data?.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() } }
            .distinct()
            .sorted()
    }
}

/** Read timeout is generous for a long catalogue; connect stays short so a dead host says so. */
private val http = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()
