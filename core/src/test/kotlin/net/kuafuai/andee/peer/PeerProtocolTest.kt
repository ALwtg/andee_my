package net.kuafuai.andee.peer

import net.kuafuai.andee.peer.PeerProtocol.ConnHello
import net.kuafuai.andee.peer.PeerProtocol.Connection
import net.kuafuai.andee.peer.PeerProtocol.PairingFailed
import net.kuafuai.andee.peer.PeerProtocol.PairingSession
import net.kuafuai.andee.peer.PeerProtocol.Peer
import net.kuafuai.andee.peer.PeerProtocol.Rejected
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PeerProtocolTest {

    private val phone = PeerProtocol.newIdentity("phone-1", "My phone")
    private val mac = PeerProtocol.newIdentity("mac-1", "My Mac")

    /** Run a full pairing between two identities with the given codes; returns what each pinned. */
    private fun pair(a: PeerProtocol.Identity, codeA: String, b: PeerProtocol.Identity, codeB: String): Pair<Peer, Peer> {
        val sa = PairingSession(a, codeA); val sb = PairingSession(b, codeB)
        sa.receiveHello(sb.hello()); sb.receiveHello(sa.hello())
        val proofA = sa.prove(initiator = true)
        val proofB = sb.prove(initiator = false)
        val aPinnedB = sa.finish(initiator = true, theirProof = proofB)
        val bPinnedA = sb.finish(initiator = false, theirProof = proofA)
        return aPinnedB to bPinnedA
    }

    // ---- pairing ----

    @Test fun pairingWithTheSameCodePinsEachOthersKey() {
        val (aPinnedB, bPinnedA) = pair(phone, "123456", mac, "123456")
        assertArrayEquals(mac.publicBytes, aPinnedB.publicKey)
        assertArrayEquals(phone.publicBytes, bPinnedA.publicKey)
        assertEquals("My Mac", aPinnedB.name)
    }

    @Test fun aWrongCodeIsRefused() {
        try { pair(phone, "123456", mac, "654321"); fail("pairing with a wrong code must fail") } catch (e: PairingFailed) { }
    }

    @Test fun aManInTheMiddleWhoSwapsAKeyIsCaught() {
        // Eve sits between them with her own identity, relaying hellos but substituting her keys.
        val eve = PeerProtocol.newIdentity("eve", "Eve")
        val sPhone = PairingSession(phone, "123456")
        val sMac = PairingSession(mac, "123456")
        val sEveToPhone = PairingSession(eve, "000000")     // Eve does not know the code
        val sEveToMac = PairingSession(eve, "000000")
        sPhone.receiveHello(sEveToPhone.hello()); sEveToPhone.receiveHello(sPhone.hello())
        sMac.receiveHello(sEveToMac.hello()); sEveToMac.receiveHello(sMac.hello())
        // Eve can only forward proofs she computed without the code; both ends must reject them.
        try { sPhone.finish(true, sEveToPhone.prove(false)); fail("phone must reject Eve") } catch (e: PairingFailed) { }
        try { sMac.finish(false, sEveToMac.prove(true)); fail("mac must reject Eve") } catch (e: PairingFailed) { }
    }

    @Test fun relayingTheRealProofsThroughASwappedKeyStillFails() {
        // Eve knows nothing, but relays the real proofs while swapping the identity keys in the hellos.
        val eve = PeerProtocol.newIdentity("eve", "Eve")
        val sPhone = PairingSession(phone, "123456")
        val sMac = PairingSession(mac, "123456")
        val realMacHello = sMac.hello()
        val realPhoneHello = sPhone.hello()
        // Each side is told the other's id/name but Eve's identity key.
        sPhone.receiveHello(PeerProtocol.Hello(realMacHello.id, realMacHello.name, eve.publicBytes, realMacHello.ephemeral))
        sMac.receiveHello(PeerProtocol.Hello(realPhoneHello.id, realPhoneHello.name, eve.publicBytes, realPhoneHello.ephemeral))
        try { sPhone.finish(true, sMac.prove(false)); fail("a swapped identity key must change the transcript") } catch (e: PairingFailed) { }
    }

    @Test fun codesAreSixDigitsAndNotConstant() {
        val codes = (1..200).map { PeerProtocol.newCode() }.toSet()
        assertTrue(codes.all { it.length == 6 && it.all(Char::isDigit) })
        assertTrue("codes must vary", codes.size > 150)
    }

    // ---- connecting ----

    private fun connect(
        a: PeerProtocol.Identity, aKnows: Map<String, Peer>,
        b: PeerProtocol.Identity, bKnows: Map<String, Peer>,
    ): Pair<PeerProtocol.Session, PeerProtocol.Session> {
        fun key(k: ByteArray) = k.joinToString("") { "%02x".format(it) }
        val ca = Connection(a, initiator = true) { pk -> aKnows.values.firstOrNull { it.publicKey.contentEquals(pk) } }
        val cb = Connection(b, initiator = false) { pk -> bKnows.values.firstOrNull { it.publicKey.contentEquals(pk) } }
        ca.receiveHello(cb.hello()); cb.receiveHello(ca.hello())
        val pa = ca.proof(); val pb = cb.proof()
        return ca.finish(pb) to cb.finish(pa)
    }

    private val aKnowsMac = mapOf("mac-1" to Peer("mac-1", "My Mac", mac.publicBytes))
    private val macKnowsPhone = mapOf("phone-1" to Peer("phone-1", "My phone", phone.publicBytes))

    @Test fun pairedDevicesConnectAndTalkBothWays() {
        val (sp, sm) = connect(phone, aKnowsMac, mac, macKnowsPhone)
        assertEquals("hello mac", String(sm.decrypt(sp.encrypt("hello mac".toByteArray()))!!))
        assertEquals("hello phone", String(sp.decrypt(sm.encrypt("hello phone".toByteArray()))!!))
    }

    @Test fun aStrangerIsRefusedBeforeAnythingElse() {
        val stranger = PeerProtocol.newIdentity("x", "Stranger")
        val cb = Connection(mac, initiator = false) { pk -> macKnowsPhone.values.firstOrNull { it.publicKey.contentEquals(pk) } }
        val cs = Connection(stranger, initiator = true) { null }
        try { cb.receiveHello(cs.hello()); fail("an unpinned key must be rejected") } catch (e: Rejected) { }
    }

    @Test fun anImpostorWhoClaimsAPinnedKeyButCannotSignIsRefused() {
        // Eve presents the phone's PUBLIC key (she has seen it) but signs with her own private key.
        val eve = PeerProtocol.newIdentity("eve", "Eve")
        val cb = Connection(mac, initiator = false) { pk -> macKnowsPhone.values.firstOrNull { it.publicKey.contentEquals(pk) } }
        // Eve is willing to talk to the Mac (she "knows" it), so the exchange runs far enough to reach the
        // signature check — which is the thing under test, not the hello-time lookup.
        val ce = Connection(eve, initiator = true) { aKnowsMac.values.first() }
        val h = ce.hello()
        cb.receiveHello(ConnHello(phone.publicBytes, h.ephemeral, h.nonce))   // claims to be the phone
        ce.receiveHello(cb.hello())
        try { cb.finish(ce.proof()); fail("a signature from the wrong private key must not verify") } catch (e: Rejected) { }
    }

    @Test fun aRecordedProofCannotBeReplayedOnANewConnection() {
        val (_, _) = connect(phone, aKnowsMac, mac, macKnowsPhone)
        // Capture the phone's proof from a first connection...
        val c1p = Connection(phone, true) { aKnowsMac.values.first() }
        val c1m = Connection(mac, false) { macKnowsPhone.values.first() }
        c1p.receiveHello(c1m.hello()); c1m.receiveHello(c1p.hello())
        val recorded = c1p.proof()
        // ...and replay it to a fresh connection, where the nonces and ephemeral keys are different.
        val c2m = Connection(mac, false) { macKnowsPhone.values.first() }
        val c2p = Connection(phone, true) { aKnowsMac.values.first() }
        c2m.receiveHello(c2p.hello()); c2p.receiveHello(c2m.hello())
        try { c2m.finish(recorded); fail("a proof from another connection must not verify") } catch (e: Rejected) { }
    }

    // ---- the channel ----

    @Test fun aTamperedFrameIsDropped() {
        val (sp, sm) = connect(phone, aKnowsMac, mac, macKnowsPhone)
        val frame = sp.encrypt("run this".toByteArray())
        frame[frame.size / 2] = (frame[frame.size / 2].toInt() xor 1).toByte()
        assertNull(sm.decrypt(frame))
    }

    @Test fun aReplayedFrameIsDropped() {
        val (sp, sm) = connect(phone, aKnowsMac, mac, macKnowsPhone)
        val frame = sp.encrypt("pay 100".toByteArray())
        assertNotNull(sm.decrypt(frame))
        assertNull("the same frame twice must not be accepted", sm.decrypt(frame))
    }

    @Test fun framesMustArriveInOrder() {
        val (sp, sm) = connect(phone, aKnowsMac, mac, macKnowsPhone)
        val f1 = sp.encrypt("one".toByteArray()); val f2 = sp.encrypt("two".toByteArray())
        assertNull("skipping ahead is refused", sm.decrypt(f2))
        assertEquals("one", String(sm.decrypt(f1)!!))
        assertEquals("two", String(sm.decrypt(f2)!!))
    }

    @Test fun eachDirectionHasItsOwnKey() {
        val (sp, sm) = connect(phone, aKnowsMac, mac, macKnowsPhone)
        val fromPhone = sp.encrypt("x".toByteArray())
        // Reflecting my own frame back at me must not decrypt: that is what two keys are for.
        assertNull(sp.decrypt(fromPhone))
        assertFalse(sm.decrypt(fromPhone) == null)
    }

    @Test fun twoConnectionsDeriveDifferentKeys() {
        val (sp1, _) = connect(phone, aKnowsMac, mac, macKnowsPhone)
        val (_, sm2) = connect(phone, aKnowsMac, mac, macKnowsPhone)
        // A frame from one session is garbage to another: forward secrecy starts from fresh ephemerals.
        assertNull(sm2.decrypt(sp1.encrypt("x".toByteArray())))
    }

    @Test fun fingerprintsAreStableAndDistinct() {
        assertEquals(phone.fingerprint, PeerCrypto.fingerprint(phone.publicBytes))
        assertTrue(phone.fingerprint != mac.fingerprint)
        assertTrue(Regex("[0-9a-f]{4}(-[0-9a-f]{4}){4}").matches(phone.fingerprint))
    }
}
