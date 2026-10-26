package net.kuafuai.andee.desktop.win

import net.kuafuai.andee.desktop.AppDirs
import net.kuafuai.andee.desktop.VaultSecrets
import net.kuafuai.andee.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The user's accounts on Windows: one AES-GCM file, the same rules as the phone
 * and the Mac — `get` refuses the password outright, `fill` types it and never
 * returns it.
 *
 * **Where the key lives, and why it is not the same as on a Mac.** macOS keeps the
 * key in the login Keychain, so the file alone is worthless. Windows has an
 * equivalent (`DPAPI`, and full-disk BitLocker in front of it), but DPAPI is a
 * JNA-into-crypt32 job with its own failure modes, and getting it half right is
 * worse than being plain about the trade: here the key is a random 32 bytes in
 * `vault-win.key` beside the data, protected by nothing but the user profile's
 * ACL.
 *
 * What that means in practice, stated where the code can be read rather than in a
 * footnote: **anyone who can read this user's files can read the vault.** They
 * cannot read it from another account, and they cannot read it off a backup of the
 * encrypted file alone. That is weaker than the Mac, and the next step is DPAPI.
 *
 * A separate file name (`vault-win.enc`) on purpose: the Mac's vault is encrypted
 * with a Keychain key and a different container. Sharing a filename would mean a
 * user who syncs one file between machines gets "your vault is corrupt" instead of
 * "this vault belongs to the other machine".
 */
object WinVault {

    private const val TAG = "Vault"
    private const val VERSION: Byte = 1
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private val dataFile: File get() = AppDirs.file("vault-win.enc")
    private val keyFile: File get() = AppDirs.file("vault-win.key")

    private val random = SecureRandom()

    @Volatile private var cached: SecretKeySpec? = null

    init {
        // Same wiring as MacVault: the vault is a secret source, so a stored
        // password is redacted out of logs and prompts without anyone remembering
        // to add it.
        VaultSecrets.source = {
            entries().flatMap { e ->
                listOfNotNull(
                    e.optString("password").takeIf { it.isNotEmpty() }
                        ?.let { "vault «${e.optString("label")}»" to it },
                )
            }
        }
    }

    // ---- storage ----------------------------------------------------------

    private fun key(): SecretKeySpec? {
        cached?.let { return it }
        return runCatching {
            val bytes = if (keyFile.isFile && keyFile.length() == 32L) {
                keyFile.readBytes()
            } else {
                ByteArray(32).also { random.nextBytes(it); keyFile.writeBytes(it) }
            }
            SecretKeySpec(bytes, "AES").also { cached = it }
        }.onFailure { Log.w(TAG, "no vault key; the vault stays closed", it) }.getOrNull()
    }

    private fun readAll(): String? {
        if (!dataFile.isFile) return null
        val k = key() ?: return null
        return runCatching {
            val raw = dataFile.readBytes()
            if (raw.size <= 1 + IV_BYTES || raw[0] != VERSION) return null
            val iv = raw.copyOfRange(1, 1 + IV_BYTES)
            val body = raw.copyOfRange(1 + IV_BYTES, raw.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.onFailure { Log.w(TAG, "the vault will not decrypt (wrong key, or the file is damaged)", it) }
            .getOrNull()
    }

    private fun writeAll(text: String): Boolean {
        val k = key() ?: return false
        return runCatching {
            val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(TAG_BITS, iv))
            val body = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
            // Written through a temp file and moved into place: a crash halfway
            // through an in-place write leaves a vault that opens to nothing.
            val tmp = File(dataFile.absolutePath + ".tmp")
            tmp.writeBytes(byteArrayOf(VERSION) + iv + body)
            java.nio.file.Files.move(
                tmp.toPath(), dataFile.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
            true
        }.onFailure { Log.w(TAG, "could not save the vault", it) }.getOrDefault(false)
    }

    // ---- entries ----------------------------------------------------------

    fun entries(): List<JSONObject> {
        val raw = readAll() ?: return emptyList()
        val a = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return (0 until a.length()).mapNotNull { runCatching { a.getJSONObject(it) }.getOrNull() }
    }

    fun save(all: List<JSONObject>): Boolean = writeAll(JSONArray(all).toString())

    /** 恢复出厂: data and key both go, so a backup of the file opens nothing. */
    fun wipe() {
        runCatching { dataFile.delete() }
        runCatching { keyFile.delete() }
        cached = null
    }

    private fun mask(s: String) = if (s.length <= 4) "••••" else s.take(2) + "•".repeat((s.length - 4).coerceAtMost(6)) + s.takeLast(2)

    fun list(): JSONObject {
        val arr = JSONArray()
        for (e in entries()) {
            arr.put(
                JSONObject().put("entry", e.optString("label"))
                    .put("username", mask(e.optString("username")))
                    .put("phone", mask(e.optString("phone")))
                    .put("email", mask(e.optString("email")))
                    .put("has_password", e.optString("password").isNotEmpty()),
            )
        }
        return JSONObject().put("entries", arr).put("count", arr.length())
    }

    private fun find(label: String) = entries().firstOrNull { it.optString("label") == label }
        ?: throw IllegalArgumentException("No vault entry called \"$label\" — list_vault shows the names.")

    fun get(label: String, field: String): JSONObject {
        val f = field.ifBlank { "username" }
        require(f != "password") { "Passwords are never read out. Use fill_secret with the field focused." }
        val e = find(label)
        return JSONObject().put("entry", label).put("field", f).put("value", e.optString(f))
    }

    /**
     * Type the password into whatever has focus — keystrokes, not a value write.
     *
     * A web form only believes what arrives as input events, and the clipboard is
     * worse: a password left there sits in the user's clipboard history.
     *
     * Windows cannot tell us whether a *password field* has focus (the Mac asks the
     * focused element). What it can tell us is whether anything at all is in front,
     * so that is the check — and the reply says the weaker guarantee out loud
     * instead of implying the stronger one.
     */
    fun fill(label: String): JSONObject {
        val pw = find(label).optString("password")
        require(pw.isNotEmpty()) { "That entry has no password saved." }
        val front = Win32.foregroundWindow()
        require(front != 0L && Win32.isAlive(front)) {
            "There is no window in front to type into. Click into the password field first."
        }
        val ok = Win32.typeText(pw)
        Log.i(TAG, "filled $label (${pw.length} chars) into \"${Win32.windowText(front).take(40)}\"")
        return JSONObject()
            .put("performed", ok)
            .put("len", pw.length)
            .put("typed_into", Win32.windowText(front))
            .put(
                "note",
                "Windows cannot confirm the caret was in a password field, only that this window was in " +
                    "front. If the text landed somewhere visible, say so — the user needs to know.",
            )
    }
}
