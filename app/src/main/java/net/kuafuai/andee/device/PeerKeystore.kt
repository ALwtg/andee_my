package net.kuafuai.andee.device

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import net.kuafuai.andee.config.NotebookStore
import net.kuafuai.andee.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Where the phone keeps its pairing identity and the devices it trusts: one AES/GCM blob in its own
 * SharedPreferences, encrypted under a key that lives in the Android Keystore and never leaves it.
 *
 * Its own file and its own key, not the notebook's, on purpose: unpairing everything and clearing the
 * notebook are different decisions, and the private key here is the one secret on this device that, if it
 * leaked, would let someone be this phone to every device that trusts it.
 */
class PeerKeystore(context: Context) : NotebookStore {

    private val app = context.applicationContext
    private val prefs get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun read(): String? = prefs.getString(KEY_BLOB, null)?.let { decrypt(it) }

    override fun write(plain: String): Boolean {
        val blob = encrypt(plain) ?: return false
        return prefs.edit().putString(KEY_BLOB, blob).commit()
    }

    override fun wipe() {
        prefs.edit().clear().commit()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String? = runCatching {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secretKey())
        Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }.getOrNull()

    private fun decrypt(blob: String): String? = runCatching {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, raw, 0, IV))
        String(c.doFinal(raw, IV, raw.size - IV), Charsets.UTF_8)
    }.getOrElse {
        // A restored backup or a wiped Keystore leaves a blob nobody can open; from outside that looks like "not paired".
        Log.e(TAG, "peer store: decrypt failed — pairing is unreadable on this install", it)
        null
    }

    private companion object {
        const val PREFS = "peer_store"
        const val KEY_BLOB = "blob"
        const val ALIAS = "body_peer_key"
        const val IV = 12
        const val TAG = "Peers"
    }
}
