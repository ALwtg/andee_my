package net.kuafuai.andee.desktop

import net.kuafuai.andee.peer.PeerDiscovery
import net.kuafuai.andee.peer.PeerProtocol
import net.kuafuai.andee.util.Log
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/**
 * Bonjour on the Mac via JmDNS: announces this device while the peer server runs, and reports the others.
 * Nothing here is trusted; see [PeerDiscovery]. A failure to start mDNS (no network, a firewall) is logged
 * and leaves the app working — pairing can still be done by typing an address.
 */
class PeerDiscoveryMac(private val listener: PeerDiscovery.Listener) {

    private var jmdns: JmDNS? = null
    private var mine: ServiceInfo? = null
    private val byName = ConcurrentHashMap<String, String>()   // service name -> device id, to report loss

    private val serviceListener = object : ServiceListener {
        override fun serviceAdded(e: ServiceEvent) { jmdns?.requestServiceInfo(e.type, e.name, true) }
        override fun serviceRemoved(e: ServiceEvent) { byName.remove(e.name)?.let { listener.onLost(it) } }
        override fun serviceResolved(e: ServiceEvent) {
            val i = e.info
            val id = i.getPropertyString(PeerDiscovery.TXT_ID) ?: return
            val host = i.inet4Addresses.firstOrNull()?.hostAddress ?: i.hostAddresses.firstOrNull() ?: return
            byName[e.name] = id
            listener.onFound(
                PeerDiscovery.Found(id, i.getPropertyString(PeerDiscovery.TXT_NAME).orEmpty(), host, i.port, i.getPropertyString(PeerDiscovery.TXT_FP).orEmpty()),
            )
        }
    }

    @Synchronized
    fun start(me: PeerProtocol.Identity, port: Int) {
        stop()
        runCatching {
            val j = JmDNS.create(lanAddress())
            val info = ServiceInfo.create(
                PeerDiscovery.SERVICE_TYPE + ".local.", "andee-${me.id.take(8)}", port, 0, 0,
                mapOf(PeerDiscovery.TXT_ID to me.id, PeerDiscovery.TXT_NAME to me.name, PeerDiscovery.TXT_FP to me.fingerprint),
            )
            j.registerService(info)
            j.addServiceListener(PeerDiscovery.SERVICE_TYPE + ".local.", serviceListener)
            jmdns = j; mine = info
            Log.i(TAG, "announcing ${me.name} on ${j.inetAddress?.hostAddress}:$port")
        }.onFailure { Log.w(TAG, "mDNS could not start: ${it.message}") }
    }

    @Synchronized
    fun stop() {
        runCatching { jmdns?.unregisterAllServices(); jmdns?.close() }
        jmdns = null; mine = null; byName.clear()
    }

    companion object {
        private const val TAG = "PeerDiscovery"

        /**
         * The address the Wi-Fi is on. Not `InetAddress.getLocalHost()`: on a Mac that resolves the hostname,
         * which `/etc/hosts`-style lookup answers with 127.0.0.1 first — and JmDNS bound to loopback still
         * gets its own announcement out (mDNSResponder relays it) but never hears anyone else's, so the
         * phone was visible to `dns-sd` and invisible to us. Measured: Nearby stayed at 0 with the phone
         * announcing on the same /24. Prefers en0 (the built-in Wi-Fi), then any up, multicast-capable,
         * non-virtual interface with a private IPv4.
         */
        fun lanAddress(): InetAddress {
            val candidates = java.net.NetworkInterface.networkInterfaces().toList()
                .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual && it.supportsMulticast() && !it.isPointToPoint }.getOrDefault(false) }
                .flatMap { nif -> nif.inetAddresses.toList().filter { it is java.net.Inet4Address && it.isSiteLocalAddress }.map { nif.name to it } }
            return (candidates.firstOrNull { it.first == "en0" } ?: candidates.firstOrNull())?.second
                ?: InetAddress.getLocalHost()
        }
    }
}
