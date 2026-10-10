package app.pocketpilot.network.publicaccess

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class RelayConfigTest {
    private val target = RelayTarget("phone.hoeve.ca", "10.0.0.2")

    @Test
    fun openWrtForwardsPort443ToThePhoneOverWireGuard() {
        val script = RelayConfig.openWrt(target, RelayNetwork.WIREGUARD)
        assertContains(script, "uci set firewall.@redirect[-1].dest='wgserver'")
        assertContains(script, "uci set firewall.@redirect[-1].dest_ip='10.0.0.2'")
        assertContains(script, "uci set firewall.@redirect[-1].src_dport='443'")
        assertContains(script, "uci add_list firewall.@redirect[-1].reflection_zone='lan'")
        assertFalse("masq" in script)
    }

    @Test
    fun openWrtMasqueradesZeroTier() {
        val script = RelayConfig.openWrt(target, RelayNetwork.ZEROTIER)
        assertContains(script, "dest='zerotier'")
        assertContains(script, "masq='1'")
    }

    @Test
    fun nginxRoutesByServerName() {
        val conf = RelayConfig.nginx(target)
        assertContains(conf, "phone.hoeve.ca 10.0.0.2:443;")
        assertContains(conf, "ssl_preread on;")
    }

    @Test
    fun caddyMatchesSni() {
        assertContains(RelayConfig.caddy(target), "tls sni phone.hoeve.ca")
    }

    @Test
    fun wireGuardPeerNamesThePhone() {
        val peer = RelayConfig.wireGuardPeer("abc=", "10.0.0.2")
        assertContains(peer, "PublicKey = abc=")
        assertContains(peer, "AllowedIPs = 10.0.0.2/32")
    }

    @Test
    fun rejectsInputThatWouldBreakTheScript() {
        assertFailsWith<IllegalArgumentException> { RelayTarget("phone.hoeve.ca'; reboot", "10.0.0.2") }
        assertFailsWith<IllegalArgumentException> { RelayTarget("phone.hoeve.ca", "fd00::2") }
    }
}
