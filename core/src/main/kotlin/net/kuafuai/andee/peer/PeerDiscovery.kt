package net.kuafuai.andee.peer

/**
 * Finding another Andee on the same network. Each platform has its own mDNS stack (Bonjour/JmDNS on the Mac,
 * `NsdManager` on Android), so this is only the contract and the names both must agree on.
 *
 * **What is advertised is public and means nothing by itself.** A device id, a display name, a port. Anyone on
 * the LAN can see it; none of it lets them in — the handshake does. It is enough to answer "which of my
 * paired devices is where right now".
 */
object PeerDiscovery {
    /** The Bonjour service type. Underscore-prefixed, `_tcp`, as the standard requires. */
    const val SERVICE_TYPE = "_andee-peer._tcp"
    const val TXT_ID = "id"
    const val TXT_NAME = "name"
    const val TXT_FP = "fp"       // fingerprint of the public key: lets a phone tell "my Mac" from a lookalike

    /** The port the peer server listens on. Fixed so a firewall rule can name it; moves only if it is taken. */
    const val DEFAULT_PORT = 9120

    data class Found(val id: String, val name: String, val host: String, val port: Int, val fingerprint: String)

    interface Listener {
        fun onFound(f: Found)
        fun onLost(id: String)
    }
}
