package net.kuafuai.andee.peer

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Two real nodes over real sockets on loopback: the whole path, including the ways it must say no. */
class PeerLinkTest {

    private class Recorder : PeerEvents {
        val messages = CopyOnWriteArrayList<JSONObject>()
        val rejected = CopyOnWriteArrayList<String>()
        val connected = CountDownLatch(1)
        val gotOne = CountDownLatch(1)
        override fun onMessage(channel: PeerChannel, msg: JSONObject) { messages += msg; gotOne.countDown() }
        override fun onConnected(channel: PeerChannel) { connected.countDown() }
        override fun onRejected(remote: String, why: String) { rejected += why }
    }

    private val servers = mutableListOf<PeerServer>()
    private fun freePort() = ServerSocket(0).use { it.localPort }

    private fun serve(me: PeerProtocol.Identity, pins: PeerPins, ev: PeerEvents, onPaired: (PeerProtocol.Peer) -> Unit = {}): Pair<PeerServer, Int> {
        val port = freePort()
        val s = PeerServer(port, me, pins, ev, onPaired)
        s.isReuseAddr = true
        val started = CountDownLatch(1)
        Thread { runCatching { s.run() } }.apply { isDaemon = true }.start()
        // wait until it accepts
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline) {
            if (runCatching { java.net.Socket("127.0.0.1", port).use { true } }.getOrDefault(false)) break
            Thread.sleep(20)
        }
        servers += s
        return s to port
    }

    @After fun stop() { servers.forEach { runCatching { it.stop(200) } } }

    private val phone = PeerProtocol.newIdentity("phone-1", "My phone")
    private val mac = PeerProtocol.newIdentity("mac-1", "My Mac")

    @Test fun pairThenConnectThenTalk() {
        val macPins = MemoryPins(); val phonePins = MemoryPins()
        val rec = Recorder()
        val pairedOnMac = CountDownLatch(1)
        val (server, port) = serve(mac, macPins, rec) { pairedOnMac.countDown() }

        server.openPairing(PairingCode("424242"))
        val pinned = PeerClient.pair("127.0.0.1", port, phone, "424242", phonePins)
        assertEquals("My Mac", pinned.name)
        assertTrue(pairedOnMac.await(3, TimeUnit.SECONDS))
        assertNotNull("the Mac pinned the phone", macPins.byId("phone-1"))
        assertNotNull("the phone pinned the Mac", phonePins.byId("mac-1"))
        assertFalse("pairing window closes after one device", server.pairingOpen)

        val phoneEvents = Recorder()
        val ch = PeerClient.connect("127.0.0.1", port, phone, phonePins, phoneEvents)
        assertTrue(ch.send(JSONObject().put("type", "ping").put("n", 7)))
        assertTrue(rec.gotOne.await(3, TimeUnit.SECONDS))
        assertEquals(7, rec.messages.first().getInt("n"))
        ch.close()
    }

    @Test fun aWrongCodeIsRefusedAndPinsNothing() {
        val macPins = MemoryPins(); val phonePins = MemoryPins()
        val (server, port) = serve(mac, macPins, Recorder())
        server.openPairing(PairingCode("111111"))
        try { PeerClient.pair("127.0.0.1", port, phone, "999999", phonePins); fail() } catch (e: PeerClient.Failed) { }
        assertNull(macPins.byId("phone-1")); assertNull(phonePins.byId("mac-1"))
    }

    @Test fun aScannedInviteOnlyPinsTheKeyItNamed() {
        val macPins = MemoryPins(); val phonePins = MemoryPins()
        val (server, port) = serve(mac, macPins, Recorder())
        // A QR that names some other key: right code, wrong device answering. Refused, nothing pinned here.
        server.openPairing(PairingCode("424242"))
        try {
            PeerClient.pair("127.0.0.1", port, phone, "424242", phonePins, expectFingerprint = "0000-0000-0000-0000-0000")
            fail("pinned a key the QR did not name")
        } catch (e: PeerClient.Failed) {
            assertTrue(e.message!!.contains("QR"))
        }
        assertNull(phonePins.byId("mac-1"))
        // The QR's own fingerprint: pairs.
        server.openPairing(PairingCode("424243"))
        PeerClient.pair("127.0.0.1", port, phone, "424243", phonePins, expectFingerprint = mac.fingerprint)
        assertNotNull(phonePins.byId("mac-1"))
    }

    @Test fun theCodeDiesAfterItsTries() {
        val macPins = MemoryPins(); val phonePins = MemoryPins()
        val (server, port) = serve(mac, macPins, Recorder())
        server.openPairing(PairingCode("111111", maxTries = 3))
        repeat(3) { try { PeerClient.pair("127.0.0.1", port, phone, "000000", phonePins); fail() } catch (e: PeerClient.Failed) { } }
        // The right code now, but it has been guessed at three times: it is dead.
        try { PeerClient.pair("127.0.0.1", port, phone, "111111", phonePins); fail("a spent code must not pair") } catch (e: PeerClient.Failed) { }
        assertNull(macPins.byId("phone-1"))
    }

    @Test fun noPairingWindowMeansNoPairing() {
        val macPins = MemoryPins(); val phonePins = MemoryPins()
        val (_, port) = serve(mac, macPins, Recorder())
        try { PeerClient.pair("127.0.0.1", port, phone, "123456", phonePins); fail() } catch (e: PeerClient.Failed) { }
        assertNull(macPins.byId("phone-1"))
    }

    @Test fun anUnpairedDeviceCannotConnect() {
        val macPins = MemoryPins()           // knows nobody
        val rec = Recorder()
        val (_, port) = serve(mac, macPins, rec)
        val strangerPins = MemoryPins().also { it.add(PeerProtocol.Peer("mac-1", "My Mac", mac.publicBytes)) }
        try { PeerClient.connect("127.0.0.1", port, phone, strangerPins, Recorder()); fail("an unpinned device must be refused") } catch (e: PeerClient.Failed) { }
        assertTrue("the refusal is logged", rec.rejected.any { it.startsWith("unknown device") })
        assertTrue(rec.messages.isEmpty())
    }

    @Test fun unpairingTakesEffectOnTheNextConnection() {
        val macPins = MemoryPins(); val phonePins = MemoryPins()
        val (server, port) = serve(mac, macPins, Recorder())
        server.openPairing(PairingCode("424242"))
        PeerClient.pair("127.0.0.1", port, phone, "424242", phonePins)
        PeerClient.connect("127.0.0.1", port, phone, phonePins, Recorder()).close()   // works

        assertTrue(macPins.remove("phone-1"))                                        // the user unpairs
        try { PeerClient.connect("127.0.0.1", port, phone, phonePins, Recorder()); fail("an unpaired device must be refused") } catch (e: PeerClient.Failed) { }
    }

    @Test fun aDeviceThatDoesNotTheKeyItClaimsIsRefused() {
        // Pair for real, then have an impostor with another key dial in claiming the phone's id.
        val macPins = MemoryPins(); val phonePins = MemoryPins()
        val (server, port) = serve(mac, macPins, Recorder())
        server.openPairing(PairingCode("424242"))
        PeerClient.pair("127.0.0.1", port, phone, "424242", phonePins)

        val eve = PeerProtocol.Identity("phone-1", "My phone", PeerCrypto.newKeyPair())   // same id, different key
        val evePins = MemoryPins().also { it.add(PeerProtocol.Peer("mac-1", "My Mac", mac.publicBytes)) }
        try { PeerClient.connect("127.0.0.1", port, eve, evePins, Recorder()); fail("same id, different key must be refused") } catch (e: PeerClient.Failed) { }
    }

    @Test fun anUnauthenticatedSocketIsClosedWhenItSendsData() {
        val rec = Recorder()
        val (_, port) = serve(mac, MemoryPins(), rec)
        val closed = CountDownLatch(1)
        val ws = object : org.java_websocket.client.WebSocketClient(java.net.URI("ws://127.0.0.1:$port")) {
            override fun onOpen(h: org.java_websocket.handshake.ServerHandshake) {
                send(PeerWire.frame(PeerWire.DATA, "not sealed".toByteArray()))
            }
            override fun onMessage(m: String) {}
            override fun onClose(c: Int, r: String, remote: Boolean) { closed.countDown() }
            override fun onError(e: Exception) { closed.countDown() }
        }
        ws.connectBlocking()
        assertTrue("data before the handshake must close the socket", closed.await(3, TimeUnit.SECONDS))
        assertTrue(rec.messages.isEmpty())
    }
}
