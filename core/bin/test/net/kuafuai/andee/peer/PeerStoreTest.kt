package net.kuafuai.andee.peer

import net.kuafuai.andee.config.NotebookStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerStoreTest {
    private class Mem : NotebookStore {
        var s: String? = null
        override fun read() = s
        override fun write(plain: String): Boolean { s = plain; return true }
        override fun wipe() { s = null }
    }

    @Test fun theIdentitySurvivesARestart() {
        val mem = Mem()
        val a = PeerStore(mem) { "Phone" }.identity()
        val b = PeerStore(mem) { "ignored" }.identity()          // a fresh object over the same storage
        assertEquals(a.id, b.id)
        assertArrayEquals(a.publicBytes, b.publicBytes)
        // The restored private key must still sign for the restored public key.
        val sig = PeerCrypto.sign(b.keys.private, "x".toByteArray())
        assertTrue(PeerCrypto.verify(b.keys.public, "x".toByteArray(), sig))
    }

    @Test fun pairedDevicesAreRememberedAndCanBeRemoved() {
        val mem = Mem()
        val s = PeerStore(mem) { "Phone" }
        val mac = PeerProtocol.newIdentity("mac-1", "My Mac")
        s.add(PeerProtocol.Peer("mac-1", "My Mac", mac.publicBytes))
        assertEquals(1, PeerStore(mem) { "x" }.all().size)
        assertEquals("My Mac", s.byKey(mac.publicBytes)?.name)
        assertTrue(s.remove("mac-1"))
        assertNull(s.byKey(mac.publicBytes))
        assertFalse(s.remove("mac-1"))
    }

    @Test fun pairingAgainReplacesTheKeyUnderTheSameId() {
        val s = PeerStore(Mem()) { "Phone" }
        val old = PeerProtocol.newIdentity("mac-1", "Mac"); val new = PeerProtocol.newIdentity("mac-1", "Mac")
        s.add(PeerProtocol.Peer("mac-1", "Mac", old.publicBytes))
        s.add(PeerProtocol.Peer("mac-1", "Mac", new.publicBytes))
        assertEquals(1, s.all().size)
        assertNull("the old key must no longer be trusted", s.byKey(old.publicBytes))
        assertEquals("Mac", s.byKey(new.publicBytes)?.name)
    }

    @Test fun wipingLeavesAStrangerWithAFreshIdentity() {
        val mem = Mem()
        val s = PeerStore(mem) { "Phone" }
        val before = s.identity()
        s.add(PeerProtocol.Peer("mac-1", "Mac", PeerProtocol.newIdentity("mac-1", "Mac").publicBytes))
        s.wipe()
        assertTrue(s.all().isEmpty())
        assertNotEquals(before.id, s.identity().id)
    }

    @Test fun renamingKeepsTheKeys() {
        val s = PeerStore(Mem()) { "Phone" }
        val before = s.identity()
        s.rename("Kitchen tablet")
        assertEquals("Kitchen tablet", s.identity().name)
        assertArrayEquals(before.publicBytes, s.identity().publicBytes)
    }

    @Test fun thePrivateKeyIsInTheBlobButNeverInAnyApiResult() {
        val mem = Mem()
        val s = PeerStore(mem) { "Phone" }
        val i = s.identity()
        assertTrue("it is persisted (encrypted by the real store)", mem.s!!.contains("\"private\""))
        // What the rest of the app can ask for carries only public material.
        s.add(PeerProtocol.Peer("mac-1", "Mac", PeerProtocol.newIdentity("mac-1", "Mac").publicBytes))
        assertFalse(s.all().joinToString { it.toString() + it.fingerprint }.contains(java.util.Base64.getEncoder().encodeToString(i.keys.private.encoded)))
    }

    @Test fun aNewPairingIsAFriendUntilTheUserSaysOtherwise() {
        val mem = Mem(); val s = PeerStore(mem) { "Phone" }
        val k = PeerProtocol.newIdentity("x-1", "X").publicBytes
        s.add(PeerProtocol.Peer("x-1", "X", k))
        assertEquals(PeerStore.Trust.FRIEND, s.trust("x-1"))
        assertFalse(s.autoAccept("x-1"))
        s.setTrust("x-1", PeerStore.Trust.OWN); s.setAutoAccept("x-1", true)
        // Pairing the same device again keeps what the user decided about it.
        s.add(PeerProtocol.Peer("x-1", "X renamed", k))
        assertEquals(PeerStore.Trust.OWN, PeerStore(mem) { "x" }.trust("x-1"))
        assertTrue(s.autoAccept("x-1"))
        assertEquals(PeerStore.Trust.FRIEND, s.trust("nobody"))
    }

    @Test fun aPairingFromBeforeFriendsExistedIsTheUsersOwn() {
        val mem = Mem(); val s = PeerStore(mem) { "Phone" }
        s.identity()
        val k = java.util.Base64.getEncoder().encodeToString(PeerProtocol.newIdentity("m", "M").publicBytes)
        mem.s = org.json.JSONObject(mem.s!!).put("peers", org.json.JSONArray().put(
            org.json.JSONObject().put("id", "m").put("name", "Mac").put("key", k).put("paired_at", 1))).toString()
        assertEquals(PeerStore.Trust.OWN, s.trust("m"))
    }
}
