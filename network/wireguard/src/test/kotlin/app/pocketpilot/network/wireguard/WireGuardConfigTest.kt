package app.pocketpilot.network.wireguard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WireGuardConfigTest {
    @Test
    fun `takes the host of an IPv4 endpoint without the port`() {
        val config =
            """
            [Interface]
            PrivateKey = a
            [Peer]
            PublicKey = b
            Endpoint = 203.0.113.7:51820
            """.trimIndent()
        assertEquals("203.0.113.7", serverHostOf(config))
    }

    @Test
    fun `takes the host of a bracketed IPv6 endpoint`() {
        assertEquals("2001:db8::1", serverHostOf("[Peer]\nEndpoint = [2001:db8::1]:51820"))
    }

    @Test
    fun `matches the Endpoint key whatever its case`() {
        assertEquals("phone.hoeve.ca", serverHostOf("endpoint=phone.hoeve.ca:443"))
    }

    @Test
    fun `a config without an endpoint has no server host`() {
        assertNull(serverHostOf("[Interface]\nPrivateKey = a"))
    }
}
