package net.kuafuai.andee.peer

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The primitives the pairing and the channel are built from — and nothing else.
 *
 * Only what both a JDK 17 desktop and an Android 11 phone carry in the box: P-256 for key agreement and for
 * signatures, SHA-256, HMAC, AES-GCM. No third-party crypto library and no hand-written cipher, because the
 * one thing worse than a missing feature here is a home-made one: this is the door that lets another device
 * run work on this one.
 *
 * Everything is a pure function of its inputs (apart from key and nonce generation), so the protocol built
 * on top can be tested end to end in one process.
 */
object PeerCrypto {

    private val rng = SecureRandom()

    fun newKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), rng) }.generateKeyPair()

    fun publicBytes(k: PublicKey): ByteArray = k.encoded   // X.509 SubjectPublicKeyInfo

    fun publicFrom(bytes: ByteArray): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))

    /** A short, stable name for a public key: what the user is shown and what a pairing is pinned to. */
    fun fingerprint(publicKey: ByteArray): String {
        val h = sha256(publicKey)
        return h.take(10).joinToString("") { "%02x".format(it) }.chunked(4).joinToString("-")
    }

    fun sign(priv: java.security.PrivateKey, data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").apply { initSign(priv, rng); update(data) }.sign()

    fun verify(pub: PublicKey, data: ByteArray, sig: ByteArray): Boolean = runCatching {
        Signature.getInstance("SHA256withECDSA").apply { initVerify(pub); update(data) }.verify(sig)
    }.getOrDefault(false)

    fun ecdh(priv: java.security.PrivateKey, pub: PublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").apply { init(priv); doPhase(pub, true) }.generateSecret()

    fun sha256(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").apply { for (p in parts) update(p) }.digest()

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")); for (p in parts) update(p) }.doFinal()

    /** HKDF-SHA256 (RFC 5869), one output block of up to 32 bytes — all this protocol ever asks for. */
    fun hkdf(secret: ByteArray, salt: ByteArray, info: String, len: Int = 32): ByteArray {
        require(len in 1..32)
        val prk = hmac(if (salt.isEmpty()) ByteArray(32) else salt, secret)
        return hmac(prk, info.toByteArray(Charsets.UTF_8), byteArrayOf(1)).copyOf(len)
    }

    fun random(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    /** Constant-time compare; a timing difference must not tell an attacker how many bytes matched. */
    fun equal(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    // ---- AEAD ------------------------------------------------------------------------------------

    /** AES-256-GCM. The caller supplies a 12-byte nonce it never reuses under one key. */
    fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(aad)
        }.doFinal(plain)

    /** Null when the tag does not verify: a forged or altered or replayed frame is dropped, never read. */
    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray? = runCatching {
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(aad)
        }.doFinal(sealed)
    }.getOrNull()

    /** A counter as a 12-byte nonce (4 zero bytes, then the counter big-endian). Each direction has its own key and counter. */
    fun nonceFor(counter: Long): ByteArray {
        val n = ByteArray(12)
        for (i in 0 until 8) n[11 - i] = (counter ushr (8 * i)).toByte()
        return n
    }
}
