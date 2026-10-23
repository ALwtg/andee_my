package net.kuafuai.andee.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PeerQrTest {

    private val invite = PeerQr.Invite("192.168.1.20", 9120, "e0d8706a-884e", "BB 的 MacBook & Pro", "dbbc-0244-59a6-fa6b-68b6", "012345")

    @Test fun roundTrips() {
        assertEquals(invite, PeerQr.decode(PeerQr.encode(invite)))
    }

    @Test fun somebodyElsesQrIsNotAnInvite() {
        assertNull(PeerQr.decode("https://example.com/?h=1&p=2"))
        assertNull(PeerQr.decode("WIFI:S:home;T:WPA;P:secret;;"))
    }

    @Test fun anInviteMissingAPartIsRefused() {
        val ok = PeerQr.encode(invite)
        assertNull(PeerQr.decode(ok.replace(Regex("&fp=[^&]*"), "")))
        assertNull(PeerQr.decode(ok.replace("c=012345", "c=12345")))
        assertNull(PeerQr.decode(ok.replace("p=9120", "p=99999")))
    }
}
