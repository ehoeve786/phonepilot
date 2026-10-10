package app.pocketpilot.network.publicaccess

/** Where the relay sends connections for [domain]: port [port] of the phone's private [address]. */
data class RelayTarget(
    val domain: String,
    val address: String,
    val port: Int = HTTPS_PORT,
) {
    init {
        require(DOMAIN.matches(domain)) { "Not a domain name: $domain" }
        require(IPV4.matches(address)) { "Not an IPv4 address: $address" }
        require(port in 1..MAX_PORT) { "Not a port: $port" }
    }

    private companion object {
        val DOMAIN = Regex("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+")
        val IPV4 = Regex("(\\d{1,3}\\.){3}\\d{1,3}")
        const val MAX_PORT = 65535
    }
}

/** The private network the relay reaches the phone over, which decides the router's firewall zone. */
enum class RelayNetwork(
    /** The zone GL.iNet and stock OpenWrt name for it. */
    val openWrtZone: String,
) {
    WIREGUARD("wgserver"),
    ZEROTIER("zerotier"),
}

/**
 * Ready-to-paste relay configurations (spec section 7, Self-hosted relay). Every one forwards raw TCP,
 * so TLS still ends on the phone and the relay never sees requests or tokens.
 */
object RelayConfig {
    /**
     * Shell commands for an OpenWrt router (GL.iNet included) that forward the router's public port 443
     * to the phone. For ZeroTier the zone also masquerades, so the phone's replies return through the
     * router; a WireGuard phone already sends every reply to the router.
     */
    fun openWrt(
        target: RelayTarget,
        network: RelayNetwork,
    ): String =
        buildString {
            appendLine("# PocketPilot: forward port 443 to ${target.domain} on the phone (${target.address})")
            appendLine("# If the zone name is wrong, list zones with: uci show firewall | grep '\\.name='")
            appendLine("uci add firewall redirect")
            for ((key, value) in redirectOptions(target, network)) {
                appendLine("uci set firewall.@redirect[-1].$key='$value'")
            }
            if (network == RelayNetwork.ZEROTIER) {
                appendLine(
                    "for z in \$(uci show firewall | sed -n \"s/^firewall\\.\\([^.]*\\)\\.name='${network.openWrtZone}'\$/\\1/p\"); " +
                        "do uci set firewall.\$z.masq='1'; done",
                )
            }
            appendLine("uci commit firewall")
            append("/etc/init.d/firewall reload")
        }

    /** An nginx `stream` block for a server that relays by name (SNI), next to any other sites. */
    fun nginx(target: RelayTarget): String =
        """
        |# In nginx.conf, at the top level (not inside http). Needs the stream module
        |# (Debian and Ubuntu: apt install libnginx-mod-stream).
        |stream {
        |    map ${'$'}ssl_preread_server_name ${'$'}pocketpilot_upstream {
        |        ${target.domain} ${target.address}:${target.port};
        |        default 127.0.0.1:8443;
        |    }
        |    server {
        |        listen 443;
        |        ssl_preread on;
        |        proxy_pass ${'$'}pocketpilot_upstream;
        |        proxy_timeout 1h;
        |    }
        |}
        """.trimMargin()

    /** A Caddyfile for Caddy built with the layer4 app (github.com/mholt/caddy-l4). */
    fun caddy(target: RelayTarget): String =
        """
        |{
        |    layer4 {
        |        :443 {
        |            @pocketpilot tls sni ${target.domain}
        |            route @pocketpilot {
        |                proxy ${target.address}:${target.port}
        |            }
        |        }
        |    }
        |}
        """.trimMargin()

    /** The `[Peer]` section to add to the WireGuard server for the phone. */
    fun wireGuardPeer(
        phonePublicKey: String,
        phoneAddress: String,
    ): String =
        """
        |[Peer]
        |# PocketPilot phone
        |PublicKey = $phonePublicKey
        |AllowedIPs = $phoneAddress/32
        """.trimMargin()

    private fun redirectOptions(
        target: RelayTarget,
        network: RelayNetwork,
    ): List<Pair<String, String>> =
        listOf(
            "name" to "PocketPilot",
            "target" to "DNAT",
            "proto" to "tcp",
            "src" to "wan",
            "src_dport" to HTTPS_PORT.toString(),
            "dest" to network.openWrtZone,
            "dest_ip" to target.address,
            "dest_port" to target.port.toString(),
        )
}

private const val HTTPS_PORT = 443
