package net.kuafuai.andee.peer

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * What the Mac's pairing QR code carries, and the phone reads back: where to dial, the one-time code, and
 * **the fingerprint of the key that must answer**.
 *
 * The fingerprint is what makes scanning stronger than typing rather than just quicker. With a typed code,
 * a device in the middle that also saw the code could pose as the Mac; here the phone refuses to pin any key
 * but the one the QR named ([PeerClient.pair]'s `expectFingerprint`), and the QR came off the screen the user
 * is looking at. Nothing in it is a long-term secret: the code dies in two minutes or three tries.
 */
object PeerQr {

    private const val PREFIX = "andee-pair:1?"

    data class Invite(val host: String, val port: Int, val id: String, val name: String, val fingerprint: String, val code: String)

    fun encode(i: Invite): String = PREFIX + listOf(
        "h" to i.host, "p" to i.port.toString(), "id" to i.id, "n" to i.name, "fp" to i.fingerprint, "c" to i.code,
    ).joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }

    /** Null for anything that is not one of ours, or is missing a part — a stranger's QR is just not an invite. */
    fun decode(raw: String): Invite? {
        val s = raw.trim()
        if (!s.startsWith(PREFIX)) return null
        val m = s.removePrefix(PREFIX).split('&').mapNotNull { kv ->
            val i = kv.indexOf('=')
            if (i <= 0) null else kv.substring(0, i) to runCatching { URLDecoder.decode(kv.substring(i + 1), "UTF-8") }.getOrNull()
        }.toMap()
        val port = m["p"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val code = m["c"]?.takeIf { it.length == 6 && it.all(Char::isDigit) } ?: return null
        return Invite(
            host = m["h"]?.ifBlank { null } ?: return null,
            port = port,
            id = m["id"]?.ifBlank { null } ?: return null,
            name = m["n"].orEmpty(),
            fingerprint = m["fp"]?.ifBlank { null } ?: return null,
            code = code,
        )
    }
}
