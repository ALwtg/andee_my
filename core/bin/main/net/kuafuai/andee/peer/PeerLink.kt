package net.kuafuai.andee.peer

import org.java_websocket.WebSocket
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.ServerHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The wire for [PeerProtocol]: a WebSocket that carries **binary** frames only, one handshake step per frame,
 * then sealed application frames. The transport is untrusted by construction — everything that matters is
 * decided by the handshake and the AEAD tags — so plain `ws://` is correct here; wrapping it in TLS would be
 * a second, weaker identity system beside the one that pins public keys.
 *
 * Frame layout, first byte = kind:
 *   0x01 PAIR_HELLO   0x02 PAIR_PROOF
 *   0x11 CONN_HELLO   0x12 CONN_PROOF
 *   0x20 DATA         (sealed; payload is UTF-8 JSON once opened)
 *
 * A connection that sends anything else before it has authenticated is closed at once, and one that does
 * not finish the handshake within [HANDSHAKE_MS] is closed too: an unauthenticated socket costs a thread.
 */
object PeerWire {
    const val PAIR_HELLO: Byte = 0x01
    const val PAIR_PROOF: Byte = 0x02
    const val CONN_HELLO: Byte = 0x11
    const val CONN_PROOF: Byte = 0x12
    const val DATA: Byte = 0x20
    const val HANDSHAKE_MS = 8_000L
    const val MAX_FRAME = 4 * 1024 * 1024

    fun frame(kind: Byte, body: ByteArray): ByteArray = ByteArray(1 + body.size).also { it[0] = kind; body.copyInto(it, 1) }

    // length-prefixed fields so a hello is unambiguous bytes
    fun pack(vararg fields: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (f in fields) { out.write(ByteBuffer.allocate(4).putInt(f.size).array()); out.write(f) }
        return out.toByteArray()
    }

    fun unpack(b: ByteArray, count: Int): List<ByteArray> {
        val bb = ByteBuffer.wrap(b)
        val out = ArrayList<ByteArray>(count)
        repeat(count) {
            require(bb.remaining() >= 4) { "short frame" }
            val n = bb.int
            require(n in 0..MAX_FRAME && n <= bb.remaining()) { "bad length" }
            out += ByteArray(n).also { bb.get(it) }
        }
        return out
    }
}

/** What the application layer sees of an authenticated link. */
interface PeerChannel {
    val peer: PeerProtocol.Peer
    /** Send one JSON message. False when the link is gone. */
    fun send(msg: JSONObject): Boolean
    fun close()
    val isOpen: Boolean
    /** The address the other side is at, as this socket sees it. Where to dial it next time (see `PeerStore.address`). */
    val remoteHost: String? get() = null
}

/** Callbacks, always invoked off the socket's IO thread's critical path is not guaranteed — keep them short. */
interface PeerEvents {
    /** An authenticated message arrived. */
    fun onMessage(channel: PeerChannel, msg: JSONObject)
    /** An authenticated link is up. */
    fun onConnected(channel: PeerChannel) {}
    fun onDisconnected(channel: PeerChannel) {}
    /** A device we have not paired with tried to connect. Never an authenticated event: only for the log. */
    fun onRejected(remote: String, why: String) {}
}

/** One code a human is about to read or type; the try limit and lifetime live here, not in the caller. */
class PairingCode(val code: String, private val lifetimeMs: Long = 120_000L, val maxTries: Int = 3) {
    private val born = System.currentTimeMillis()
    @Volatile var tries = 0
        private set

    fun alive(): Boolean = tries < maxTries && System.currentTimeMillis() - born < lifetimeMs

    /** Count one attempt against this code. False once it is spent: the caller must refuse without trying. */
    @Synchronized fun spend(): Boolean {
        if (!alive()) return false
        tries++
        return true
    }
}

/** The shared half: owns identity and pins, and builds a [PeerChannel] from a socket once authenticated. */
internal class Authenticated(
    private val send: (ByteArray) -> Unit,
    private val closeIt: () -> Unit,
    private val isOpenNow: () -> Boolean,
    val session: PeerProtocol.Session,
    override val remoteHost: String? = null,
) : PeerChannel {
    override val peer get() = session.peer
    override val isOpen get() = isOpenNow()
    override fun send(msg: JSONObject): Boolean = runCatching {
        if (!isOpenNow()) return false
        send(PeerWire.frame(PeerWire.DATA, session.encrypt(msg.toString().toByteArray(Charsets.UTF_8))))
        true
    }.getOrDefault(false)
    override fun close() = closeIt()
}

/**
 * Listens for paired devices (and, while a pairing code is showing, for one new device).
 * Binds on all interfaces — that is what a LAN peer needs — and trusts nothing it reads until the handshake
 * has verified a signature from a pinned key.
 */
class PeerServer(
    port: Int,
    private val me: PeerProtocol.Identity,
    private val pins: PeerPins,
    private val events: PeerEvents,
    /** Called when a new device completes pairing; the app stores it in [pins] and tells the user. */
    private val onPaired: (PeerProtocol.Peer) -> Unit = {},
) : WebSocketServer(InetSocketAddress(port)) {

    private class State {
        var conn: PeerProtocol.Connection? = null
        var pairing: PeerProtocol.PairingSession? = null
        var authed: Authenticated? = null
        var theirHelloSeen = false
        val opened = System.currentTimeMillis()
    }

    private val states = ConcurrentHashMap<WebSocket, State>()
    @Volatile private var code: PairingCode? = null

    /** Open a pairing window: while it is alive, ONE new device may pair by typing this code. */
    fun openPairing(c: PairingCode) { code = c }
    fun closePairing() { code = null }
    val pairingOpen: Boolean get() = code?.alive() == true

    override fun onStart() { connectionLostTimeout = 20 }

    override fun onOpen(ws: WebSocket, hs: ClientHandshake) {
        states[ws] = State()
        // Whoever has not finished the handshake in time is dropped.
        Thread {
            Thread.sleep(PeerWire.HANDSHAKE_MS)
            val st = states[ws]
            if (st != null && st.authed == null && ws.isOpen) { events.onRejected(remote(ws), "handshake timed out"); ws.close() }
        }.apply { isDaemon = true; name = "peer-handshake-timeout" }.start()
    }

    override fun onMessage(ws: WebSocket, message: String) { ws.close() }   // text frames are never part of this protocol

    override fun onMessage(ws: WebSocket, bytes: ByteBuffer) {
        val st = states[ws] ?: return
        val b = ByteArray(bytes.remaining()).also { bytes.get(it) }
        if (b.isEmpty() || b.size > PeerWire.MAX_FRAME) { ws.close(); return }
        val body = b.copyOfRange(1, b.size)
        try {
            when (b[0]) {
                PeerWire.CONN_HELLO -> {
                    if (st.conn != null || st.pairing != null) { ws.close(); return }
                    val (id, eph, nonce) = PeerWire.unpack(body, 3)
                    val c = PeerProtocol.Connection(me, initiator = false) { pk -> pins.byKey(pk) }
                    c.receiveHello(PeerProtocol.ConnHello(id, eph, nonce))   // throws Rejected for a stranger
                    st.conn = c
                    val h = c.hello()
                    ws.send(PeerWire.frame(PeerWire.CONN_HELLO, PeerWire.pack(h.identity, h.ephemeral, h.nonce)))
                    ws.send(PeerWire.frame(PeerWire.CONN_PROOF, c.proof()))
                }
                PeerWire.CONN_PROOF -> {
                    val c = st.conn ?: run { ws.close(); return }
                    val session = c.finish(body)
                    val a = Authenticated({ ws.send(it) }, { ws.close() }, { ws.isOpen }, session, ws.remoteSocketAddress?.address?.hostAddress)
                    st.authed = a
                    events.onConnected(a)
                }
                PeerWire.PAIR_HELLO -> {
                    val pc = code
                    if (pc == null || !pc.alive() || st.pairing != null || st.conn != null) { ws.close(); return }
                    val (id, name, identity, eph) = PeerWire.unpack(body, 4)
                    val s = PeerProtocol.PairingSession(me, pc.code)
                    s.receiveHello(PeerProtocol.Hello(String(id), String(name), identity, eph))
                    st.pairing = s
                    val h = s.hello()
                    ws.send(PeerWire.frame(PeerWire.PAIR_HELLO, PeerWire.pack(h.id.toByteArray(), h.name.toByteArray(), h.identity, h.ephemeral)))
                }
                PeerWire.PAIR_PROOF -> {
                    val s = st.pairing ?: run { ws.close(); return }
                    val pc = code
                    // One guess is spent per proof received, win or lose: that is what bounds a brute force.
                    if (pc == null || !pc.spend()) { ws.close(); return }
                    // The responder sends its proof first so the initiator can check it, then checks the initiator's.
                    val theirs = body
                    val peer = s.finish(initiator = false, theirProof = theirs)
                    ws.send(PeerWire.frame(PeerWire.PAIR_PROOF, s.prove(initiator = false)))
                    pins.add(peer)
                    closePairing()   // one device per code
                    onPaired(peer)
                    ws.close()
                }
                PeerWire.DATA -> data(ws, st, body)
                else -> ws.close()
            }
        } catch (e: PeerProtocol.Rejected) {
            events.onRejected(remote(ws), e.message ?: "rejected"); ws.close()
        } catch (e: PeerProtocol.PairingFailed) {
            events.onRejected(remote(ws), "pairing: ${e.message}"); ws.close()
        } catch (e: Exception) {
            events.onRejected(remote(ws), "bad frame: ${e.message}"); ws.close()
        }
    }

    override fun onClose(ws: WebSocket, code: Int, reason: String, remote: Boolean) {
        val st = states.remove(ws) ?: return
        st.authed?.let { events.onDisconnected(it) }
    }

    override fun onError(ws: WebSocket?, ex: Exception) { if (ws != null) events.onRejected(remote(ws), "error: ${ex.message}") }

    private fun remote(ws: WebSocket) = ws.remoteSocketAddress?.toString().orEmpty()

    /** Route a DATA frame. Separate from [onMessage] only so the handshake branch above stays readable. */
    private fun data(ws: WebSocket, st: State, body: ByteArray) {
        val a = st.authed ?: run { ws.close(); return }
        val plain = a.session.decrypt(body) ?: run { ws.close(); return }   // forged, replayed or reordered: drop the link
        val msg = runCatching { JSONObject(String(plain, Charsets.UTF_8)) }.getOrNull() ?: run { ws.close(); return }
        events.onMessage(a, msg)
    }
}

/** The devices this one has paired with. The only place trust lives; everything else asks it. */
interface PeerPins {
    fun byKey(publicKey: ByteArray): PeerProtocol.Peer?
    fun byId(id: String): PeerProtocol.Peer?
    fun all(): List<PeerProtocol.Peer>
    fun add(peer: PeerProtocol.Peer)
    /** Unpair. The next connection from that device is refused at its hello. */
    fun remove(id: String): Boolean
}

/** In memory. For tests, and as the base a persistent store wraps. */
open class MemoryPins : PeerPins {
    private val map = ConcurrentHashMap<String, PeerProtocol.Peer>()
    override fun byKey(publicKey: ByteArray) = map.values.firstOrNull { it.publicKey.contentEquals(publicKey) }
    override fun byId(id: String) = map[id]
    override fun all() = map.values.sortedBy { it.name }
    override fun add(peer: PeerProtocol.Peer) { map[peer.id] = peer }
    override fun remove(id: String) = map.remove(id) != null
}

/**
 * Dials a device. Either to pair for the first time ([pair]) or to connect to one already pinned ([connect]).
 * Blocks the calling thread for the handshake and returns once it has succeeded or failed — callers run it
 * off the UI thread.
 */
object PeerClient {

    class Failed(why: String) : Exception(why)

    /**
     * Dial [host]:[port] and pair using the code from the other device. Pins the other device on success.
     * [expectFingerprint] (from a scanned [PeerQr]) is the key that must answer: any other is refused and not
     * pinned, even if it somehow knew the code.
     */
    fun pair(
        host: String, port: Int, me: PeerProtocol.Identity, typedCode: String, pins: PeerPins,
        timeoutMs: Long = 10_000, expectFingerprint: String? = null,
    ): PeerProtocol.Peer {
        val s = PeerProtocol.PairingSession(me, typedCode)
        val done = CountDownLatch(1)
        var result: PeerProtocol.Peer? = null
        var failure: String? = null
        var myProof: ByteArray? = null

        val ws = object : WebSocketClient(URI("ws://$host:$port")) {
            override fun onOpen(h: ServerHandshake) {
                val hello = s.hello()
                send(PeerWire.frame(PeerWire.PAIR_HELLO, PeerWire.pack(hello.id.toByteArray(), hello.name.toByteArray(), hello.identity, hello.ephemeral)))
            }
            override fun onMessage(message: String) { failure = "unexpected text"; close() }
            override fun onMessage(bytes: ByteBuffer) {
                try {
                    val b = ByteArray(bytes.remaining()).also { bytes.get(it) }
                    val body = b.copyOfRange(1, b.size)
                    when (b[0]) {
                        PeerWire.PAIR_HELLO -> {
                            val (id, name, identity, eph) = PeerWire.unpack(body, 4)
                            s.receiveHello(PeerProtocol.Hello(String(id), String(name), identity, eph))
                            myProof = s.prove(initiator = true)
                            send(PeerWire.frame(PeerWire.PAIR_PROOF, myProof!!))
                        }
                        PeerWire.PAIR_PROOF -> {
                            val peer = s.finish(initiator = true, theirProof = body)
                            if (expectFingerprint != null && peer.fingerprint != expectFingerprint) {
                                failure = "the device that answered is not the one in the QR code"
                                close(); return
                            }
                            result = peer
                            pins.add(peer)
                            close()
                        }
                        else -> { failure = "unexpected frame"; close() }
                    }
                } catch (e: PeerProtocol.PairingFailed) { failure = e.message; close() }
                catch (e: Exception) { failure = "bad frame: ${e.message}"; close() }
            }
            override fun onClose(code: Int, reason: String, remote: Boolean) { done.countDown() }
            override fun onError(ex: Exception) { failure = failure ?: ex.message; done.countDown() }
        }
        ws.connectionLostTimeout = 0
        if (!ws.connectBlocking(timeoutMs, TimeUnit.MILLISECONDS)) throw Failed("could not reach $host:$port")
        if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) { ws.close(); throw Failed("pairing timed out") }
        return result ?: throw Failed(failure ?: "the other device refused the code (wrong, expired, or used up)")
    }

    /** Dial an already-paired device. Returns an open, authenticated channel; [events] receives its messages. */
    fun connect(host: String, port: Int, me: PeerProtocol.Identity, pins: PeerPins, events: PeerEvents, timeoutMs: Long = 10_000): PeerChannel {
        val c = PeerProtocol.Connection(me, initiator = true) { pk -> pins.byKey(pk) }
        val ready = CountDownLatch(1)
        var channel: Authenticated? = null
        var failure: String? = null
        var sentProof = false

        val ws = object : WebSocketClient(URI("ws://$host:$port")) {
            override fun onOpen(h: ServerHandshake) {
                val hello = c.hello()
                send(PeerWire.frame(PeerWire.CONN_HELLO, PeerWire.pack(hello.identity, hello.ephemeral, hello.nonce)))
            }
            override fun onMessage(message: String) { failure = "unexpected text"; close() }
            override fun onMessage(bytes: ByteBuffer) {
                try {
                    val b = ByteArray(bytes.remaining()).also { bytes.get(it) }
                    val body = b.copyOfRange(1, b.size)
                    when (b[0]) {
                        PeerWire.CONN_HELLO -> {
                            val (id, eph, nonce) = PeerWire.unpack(body, 3)
                            c.receiveHello(PeerProtocol.ConnHello(id, eph, nonce))
                            if (!sentProof) { sentProof = true; send(PeerWire.frame(PeerWire.CONN_PROOF, c.proof())) }
                        }
                        PeerWire.CONN_PROOF -> {
                            val session = c.finish(body)
                            val a = Authenticated({ send(it) }, { close() }, { isOpen }, session, host)
                            channel = a
                            events.onConnected(a)
                            ready.countDown()
                        }
                        PeerWire.DATA -> {
                            val a = channel ?: run { close(); return }
                            val plain = a.session.decrypt(body) ?: run { close(); return }
                            events.onMessage(a, JSONObject(String(plain, Charsets.UTF_8)))
                        }
                        else -> { failure = "unexpected frame"; close() }
                    }
                } catch (e: PeerProtocol.Rejected) { failure = e.message; close() }
                catch (e: Exception) { failure = "bad frame: ${e.message}"; close() }
            }
            override fun onClose(code: Int, reason: String, remote: Boolean) {
                channel?.let { events.onDisconnected(it) }
                ready.countDown()
            }
            override fun onError(ex: Exception) { failure = failure ?: ex.message; ready.countDown() }
        }
        ws.connectionLostTimeout = 20
        if (!ws.connectBlocking(timeoutMs, TimeUnit.MILLISECONDS)) throw Failed("could not reach $host:$port")
        if (!ready.await(timeoutMs, TimeUnit.MILLISECONDS)) { ws.close(); throw Failed("handshake timed out") }
        return channel ?: throw Failed(failure ?: "the other device did not accept the connection")
    }
}
