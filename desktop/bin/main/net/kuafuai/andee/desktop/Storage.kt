package net.kuafuai.andee.desktop

import net.kuafuai.andee.config.NotebookStore
import net.kuafuai.andee.util.Log
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A secret kept in the user's login Keychain, read and written with the
 * system's own `security` tool — so the key never sits in a file next to the
 * data it protects, the same promise the phone's Keystore makes.
 *
 * **Windows has no `security` binary**, and the first symptom was not "the
 * Keychain is missing" but this: `Cannot run program "/usr/bin/security"`,
 * thrown from a notebook save, from inside a gear scan, which then reported the
 * whole scan as failed. One missing tool made an unrelated feature look broken.
 *
 * So the key source is asked per platform. Where the Keychain exists the promise
 * above holds unchanged; where it does not, the key is a 32-byte file beside the
 * data, and that is weaker in exactly one way — anyone who can read this user's
 * files can read it. The alternative (`DPAPI` through JNA) is a real option and
 * the next step; a notebook that cannot save at all is not.
 */
object Keychain {

    private const val TAG = "Keychain"
    private const val SERVICE = "net.kuafuai.andee.mac"

    /** The stored value for [account], or null when there is none. */
    fun get(account: String): String? {
        val p = ProcessBuilder("/usr/bin/security", "find-generic-password", "-s", SERVICE, "-a", account, "-w")
            .redirectErrorStream(false).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        return if (p.waitFor() == 0 && out.isNotEmpty()) out else null
    }

    fun put(account: String, value: String): Boolean {
        val p = ProcessBuilder(
            "/usr/bin/security", "add-generic-password", "-U", "-s", SERVICE, "-a", account, "-w", value,
        ).redirectErrorStream(true).start()
        val err = p.inputStream.bufferedReader().readText()
        val ok = p.waitFor() == 0
        if (!ok) Log.e(TAG, "could not write $account: ${err.trim()}")
        return ok
    }

    fun delete(account: String) {
        ProcessBuilder("/usr/bin/security", "delete-generic-password", "-s", SERVICE, "-a", account)
            .redirectErrorStream(true).start().waitFor()
    }

    /** A 256-bit AES key under [account], created on first use. */
    fun aesKey(account: String): ByteArray? {
        if (!Body.isMac) return fileKey(account)
        get(account)?.let { return runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
        val k = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return if (put(account, Base64.getEncoder().encodeToString(k))) k else null
    }

    /**
     * The key on a platform without a Keychain: 32 random bytes in the data
     * directory, created on first use.
     *
     * Named per account so two stores cannot share (and silently decrypt each
     * other's) key.
     */
    private fun fileKey(account: String): ByteArray? = runCatching {
        val f = AppDirs.file("key-${account.replace(Regex("[^A-Za-z0-9_.-]"), "_")}.bin")
        if (f.isFile && f.length() == 32L) return f.readBytes()
        val k = ByteArray(32).also { SecureRandom().nextBytes(it) }
        f.writeBytes(k)
        Log.i(TAG, "created a local key for '$account' (no OS keychain on this platform)")
        k
    }.getOrElse {
        Log.e(TAG, "could not create a local key for '$account'", it)
        null
    }
}

/**
 * The notebook's blob on disk, AES/GCM with the key in the Keychain:
 * `base64(iv || ciphertext)`, the same construction the phone uses.
 *
 * A blob that will not decrypt reads as null and is logged loudly — an
 * unreadable notebook and an empty one look the same from the outside.
 */
class EncryptedFileStore(private val file: File, private val keyAccount: String) : NotebookStore {

    private val key: ByteArray? by lazy { Keychain.aesKey(keyAccount) }

    override fun read(): String? {
        if (!file.exists()) return null
        val k = key ?: return null
        return runCatching {
            val raw = Base64.getDecoder().decode(file.readText().trim())
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, raw, 0, IV))
            String(c.doFinal(raw, IV, raw.size - IV), Charsets.UTF_8)
        }.getOrElse {
            Log.e(TAG, "${file.name}: decrypt failed — unreadable on this install", it)
            null
        }
    }

    override fun write(plain: String): Boolean {
        val k = key ?: return false
        return runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES"))
            val blob = Base64.getEncoder().encodeToString(c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8)))
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(blob)
            // `File.renameTo` does **not** overwrite on Windows — it returns false
            // when the target exists, which turns "save the notebook again" into
            // "the notebook could not be saved on this device" from the second save
            // onwards. `Files.move` with REPLACE_EXISTING is the same atomic
            // rename on both platforms.
            java.nio.file.Files.move(
                tmp.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
            true
        }.getOrElse {
            Log.e(TAG, "${file.name}: write failed", it)
            false
        }
    }

    override fun wipe() {
        file.delete()
    }

    private companion object {
        const val TAG = "Store"
        const val IV = 12
    }
}
