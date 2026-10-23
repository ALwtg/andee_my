package net.kuafuai.andee.peer

import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey

/**
 * The two handshakes, as state machines with **no network in them**: bytes in, bytes out. That is what lets
 * the whole thing — including the attacks — be tested in one process, and lets any transport carry it.
 *
 * ### Pairing (first time; a human is involved)
 * One device shows a six-digit code; the other has it typed. Neither the code nor anything derived from it
 * only is ever sent. Each side contributes an ephemeral key; both derive the same secret; each then sends an
 * HMAC over **the code and both identity keys** under that secret. A man in the middle who substitutes either
 * key changes what is MACed and fails; one who does not know the code cannot produce the MAC at all.
 * Six digits is a million possibilities, so what makes it safe is the **attempt limit** ([PairingSession.maxTries]):
 * a code that is guessed wrong that many times is dead and a new one has to be shown.
 *
 * ### Connecting (every time after; no human)
 * Each side proves it holds the private key for a public key the other has **pinned** at pairing, by signing
 * both nonces and both ephemeral keys. The session keys come from an ephemeral ECDH, so a stolen device
 * cannot decrypt traffic recorded earlier.
 */
object PeerProtocol {

    /** What each side keeps about the other after a successful pairing. */
    data class Peer(val id: String, val name: String, val publicKey: ByteArray) {
        val fingerprint: String get() = PeerCrypto.fingerprint(publicKey)
        override fun equals(other: Any?) = other is Peer && other.id == id && other.publicKey.contentEquals(publicKey)
        override fun hashCode() = id.hashCode()
    }

    /** Who I am: an id that stays, a name the user can read, and the long-term key pair. */
    class Identity(val id: String, val name: String, val keys: KeyPair) {
        val publicBytes: ByteArray get() = PeerCrypto.publicBytes(keys.public)
        val fingerprint: String get() = PeerCrypto.fingerprint(publicBytes)
    }

    /** Two directions, two keys, two counters; a frame replayed or reordered fails its tag. */
    class Session(private val sendKey: ByteArray, private val recvKey: ByteArray, val peer: Peer) {
        private var sendCounter = 0L
        private var recvCounter = 0L

        @Synchronized fun encrypt(plain: ByteArray): ByteArray {
            val c = sendCounter++
            return PeerCrypto.seal(sendKey, PeerCrypto.nonceFor(c), AAD, plain)
        }

        /** Null when the frame is forged, altered, replayed or out of order. The counter only advances on success. */
        @Synchronized fun decrypt(sealed: ByteArray): ByteArray? {
            val plain = PeerCrypto.open(recvKey, PeerCrypto.nonceFor(recvCounter), AAD, sealed) ?: return null
            recvCounter++
            return plain
        }

        private companion object { val AAD = "andee-peer-frame-v1".toByteArray() }
    }

    // ======================================================================================================
    // Pairing
    // ======================================================================================================

    class PairingFailed(why: String) : Exception(why)

    /**
     * One pairing attempt, from either side. [code] is what the human typed (or was shown).
     * Call order, both sides: [hello] → exchange → [prove] → exchange → [finish].
     */
    class PairingSession(private val me: Identity, private val code: String, val maxTries: Int = 3) {
        private val eph = PeerCrypto.newKeyPair()
        private var theirEph: ByteArray? = null
        private var theirIdentity: ByteArray? = null
        private var theirId: String = ""
        private var theirName: String = ""
        private var secret: ByteArray? = null

        /** Sent first: my identity and an ephemeral key. Public information. */
        fun hello(): Hello = Hello(me.id, me.name, me.publicBytes, PeerCrypto.publicBytes(eph.public))

        /** Take the other side's hello; derive the shared secret. */
        fun receiveHello(h: Hello) {
            theirId = h.id; theirName = h.name; theirIdentity = h.identity; theirEph = h.ephemeral
            secret = PeerCrypto.ecdh(eph.private, PeerCrypto.publicFrom(h.ephemeral))
        }

        /**
         * Proof that I know the code and that I am looking at the same two identity keys as you.
         * [initiator] only fixes the order the two keys are hashed in, so both sides compute the same bytes.
         */
        fun prove(initiator: Boolean): ByteArray {
            val s = secret ?: throw PairingFailed("no hello received")
            return PeerCrypto.hmac(
                PeerCrypto.hkdf(s, SALT, "pair-mac-key"),
                transcript(initiator, mine = true),
            )
        }

        /** Verify their proof. On success returns the peer to pin. Wrong code, swapped key or replay: throws. */
        fun finish(initiator: Boolean, theirProof: ByteArray): Peer {
            val s = secret ?: throw PairingFailed("no hello received")
            val expected = PeerCrypto.hmac(
                PeerCrypto.hkdf(s, SALT, "pair-mac-key"),
                transcript(initiator, mine = false),
            )
            if (!PeerCrypto.equal(expected, theirProof)) throw PairingFailed("the code does not match, or the keys were tampered with")
            return Peer(theirId, theirName, theirIdentity!!)
        }

        /** What is MACed: the code, and both identity keys in a fixed order (initiator's first). */
        private fun transcript(initiator: Boolean, mine: Boolean): ByteArray {
            val mineKey = me.publicBytes
            val theirKey = theirIdentity!!
            // The proof I send is labelled with my role; the proof I check is labelled with theirs.
            val iAmInitiatorInThisProof = if (mine) initiator else !initiator
            val first = if (iAmInitiatorInThisProof == mine) mineKey else theirKey
            val second = if (iAmInitiatorInThisProof == mine) theirKey else mineKey
            val role = if (iAmInitiatorInThisProof) "initiator" else "responder"
            return PeerCrypto.sha256(code.toByteArray(), first, second, role.toByteArray())
        }

        private companion object { val SALT = "andee-peer-pair-v1".toByteArray() }
    }

    class Hello(val id: String, val name: String, val identity: ByteArray, val ephemeral: ByteArray)

    /**
     * The code a human reads and types. Six digits, from a CSPRNG, never from a clock or a counter.
     * Held in memory for one pairing attempt with a try limit and a lifetime; see [PairingCode].
     */
    fun newCode(): String {
        val n = java.math.BigInteger(1, PeerCrypto.random(8)).mod(java.math.BigInteger.valueOf(1_000_000)).toInt()
        return "%06d".format(n)
    }

    // ======================================================================================================
    // Connecting
    // ======================================================================================================

    class Rejected(why: String) : Exception(why)

    /**
     * One connection between two already-paired devices. Both sides run the same code; [initiator] only
     * decides who dialled, which fixes the order of things that must be hashed identically on both ends.
     *
     * [lookup] answers "is this public key one I paired with?" — the only place trust lives. Return null and
     * the connection is dropped before anything but the hello has been read.
     */
    class Connection(
        private val me: Identity,
        private val initiator: Boolean,
        private val lookup: (publicKey: ByteArray) -> Peer?,
    ) {
        private val eph = PeerCrypto.newKeyPair()
        private val nonce = PeerCrypto.random(16)
        private var theirHello: ConnHello? = null
        private var peer: Peer? = null

        fun hello(): ConnHello = ConnHello(me.publicBytes, PeerCrypto.publicBytes(eph.public), nonce)

        /** Their hello. Refuses at once if the identity is not one we pinned. */
        fun receiveHello(h: ConnHello) {
            peer = lookup(h.identity) ?: throw Rejected("unknown device ${PeerCrypto.fingerprint(h.identity)}")
            theirHello = h
        }

        /** My signature over the whole exchange: proves I hold the private key, and binds it to *this* connection. */
        fun proof(): ByteArray = PeerCrypto.sign(me.keys.private, signedBytes(mine = true))

        /** Verify theirs against the pinned key and, if good, derive the two session keys. */
        fun finish(theirProof: ByteArray): Session {
            val p = peer ?: throw Rejected("no hello received")
            val th = theirHello!!
            if (!PeerCrypto.verify(PeerCrypto.publicFrom(p.publicKey), signedBytes(mine = false), theirProof)) {
                throw Rejected("signature does not verify")
            }
            val shared = PeerCrypto.ecdh(eph.private, PeerCrypto.publicFrom(th.ephemeral))
            val salt = PeerCrypto.sha256(nonceFirst(), nonceSecond())
            val toResponder = PeerCrypto.hkdf(shared, salt, "initiator->responder")
            val toInitiator = PeerCrypto.hkdf(shared, salt, "responder->initiator")
            return if (initiator) Session(sendKey = toResponder, recvKey = toInitiator, peer = p)
            else Session(sendKey = toInitiator, recvKey = toResponder, peer = p)
        }

        // Everything both sides must agree on, in an order both can reproduce: initiator's material first.
        private fun nonceFirst() = if (initiator) nonce else theirHello!!.nonce
        private fun nonceSecond() = if (initiator) theirHello!!.nonce else nonce

        private fun signedBytes(mine: Boolean): ByteArray {
            val th = theirHello!!
            val myEph = PeerCrypto.publicBytes(eph.public)
            // The signer is `mine` ? me : them. Their view of the same bytes has the roles swapped.
            val signerIsInitiator = if (mine) initiator else !initiator
            val iEph = if (initiator) myEph else th.ephemeral
            val rEph = if (initiator) th.ephemeral else myEph
            val iId = if (initiator) me.publicBytes else th.identity
            val rId = if (initiator) th.identity else me.publicBytes
            return PeerCrypto.sha256(
                "andee-peer-connect-v1".toByteArray(),
                iId, rId, iEph, rEph, nonceFirst(), nonceSecond(),
                (if (signerIsInitiator) "I" else "R").toByteArray(),
            )
        }
    }

    class ConnHello(val identity: ByteArray, val ephemeral: ByteArray, val nonce: ByteArray)

    /** For tests and for stores: a fresh identity. */
    fun newIdentity(id: String, name: String): Identity = Identity(id, name, PeerCrypto.newKeyPair())
}
