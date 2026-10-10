package ppnet

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"fmt"
	"io"
	"math/big"
	"net"
	"net/http"
	"net/netip"
	"strings"
	"testing"
	"time"

	"app.pocketpilot/ppnet/internal/wgnetstack"
	"github.com/tailscale/wireguard-go/conn"
	"github.com/tailscale/wireguard-go/device"
)

func TestParseWgQuick(t *testing.T) {
	priv, _ := GeneratePrivateKey()
	server, _ := GeneratePrivateKey()
	serverPub, _ := PublicKey(server)
	cfg, err := parseWgQuick(fmt.Sprintf(`
# phone
[Interface]
PrivateKey = %s
Address = 10.66.0.2/32, fd00::2/128
DNS = 1.1.1.1, home.lan
PostUp = iptables -A FORWARD   # ignored

[Peer]
PublicKey = %s
Endpoint = relay.example.com:51820
AllowedIPs = 10.66.0.0/24
PersistentKeepalive = 25
`, priv, serverPub))
	if err != nil {
		t.Fatal(err)
	}
	if len(cfg.Addresses) != 2 || cfg.Addresses[0] != netip.MustParsePrefix("10.66.0.2/32") {
		t.Errorf("addresses = %v", cfg.Addresses)
	}
	if len(cfg.DNS) != 1 || cfg.MTU != 1280 {
		t.Errorf("dns = %v, mtu = %d", cfg.DNS, cfg.MTU)
	}
	p := cfg.Peers[0]
	if p.Endpoint != "relay.example.com:51820" || p.Keepalive != 25 || len(p.AllowedIPs) != 1 {
		t.Errorf("peer = %+v", p)
	}
	uapi, err := cfg.uapi(func(string) (netip.AddrPort, error) { return netip.MustParseAddrPort("203.0.113.7:51820"), nil })
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"endpoint=203.0.113.7:51820\n", "allowed_ip=10.66.0.0/24\n", "persistent_keepalive_interval=25\n"} {
		if !strings.Contains(uapi, want) {
			t.Errorf("uapi lacks %q:\n%s", want, uapi)
		}
	}
}

func TestParseWgQuickErrors(t *testing.T) {
	key, _ := GeneratePrivateKey()
	for name, text := range map[string]string{
		"no peer":    "[Interface]\nPrivateKey = " + key + "\nAddress = 10.0.0.2/32\n",
		"bad key":    "[Interface]\nPrivateKey = nope\nAddress = 10.0.0.2/32\n[Peer]\nPublicKey = " + key + "\n",
		"no address": "[Interface]\nPrivateKey = " + key + "\n[Peer]\nPublicKey = " + key + "\n",
		"endpoint":   "[Interface]\nPrivateKey = " + key + "\nAddress = 10.0.0.2\n[Peer]\nPublicKey = " + key + "\nEndpoint = host\n",
	} {
		if _, err := parseWgQuick(text); err == nil {
			t.Errorf("%s: parsed", name)
		}
	}
}

func TestPublicKeyMatchesWgPubkey(t *testing.T) {
	// From wg(8): echo yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk= | wg pubkey
	got, err := PublicKey("yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=")
	if err != nil {
		t.Fatal(err)
	}
	if got != "HIgo9xNzJMWLKASShiTqIybxZ0U3wGLiUeJ1PKf8ykw=" {
		t.Errorf("public key = %s", got)
	}
}

func TestPeerTotals(t *testing.T) {
	hs, rx, tx := peerTotals("private_key=aa\npublic_key=bb\nlast_handshake_time_sec=100\nlast_handshake_time_nsec=5000000\nrx_bytes=10\ntx_bytes=20\npublic_key=cc\nlast_handshake_time_sec=50\nrx_bytes=1\ntx_bytes=2\n")
	if hs != 100_005 || rx != 11 || tx != 22 {
		t.Errorf("got %d %d %d", hs, rx, tx)
	}
}

// TestTunnelEndToEnd runs the phone's tunnel against a server peer in the same process, over real
// UDP on loopback, and fetches a page through it with TLS, as a relay would forward a client.
func TestTunnelEndToEnd(t *testing.T) {
	backend := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprintf(w, "mcp at %s via %s", r.URL.Path, r.Host)
	})}
	backendLn, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	go backend.Serve(backendLn)
	defer backend.Close()

	phoneKey, _ := GeneratePrivateKey()
	phonePub, _ := PublicKey(phoneKey)
	serverKey, _ := GeneratePrivateKey()
	serverPub, _ := PublicKey(serverKey)

	// The server peer: listens on a loopback UDP port and routes 10.66.0.2 to the phone.
	serverTun, serverNet, err := wgnetstack.CreateNetTUN([]netip.Addr{netip.MustParseAddr("10.66.0.1")}, nil, 1280)
	if err != nil {
		t.Fatal(err)
	}
	serverDev := device.NewDevice(serverTun, conn.NewDefaultBind(), device.NewLogger(device.LogLevelSilent, ""))
	defer serverDev.Close()
	serverPort, phonePort := freeUDPPort(t), freeUDPPort(t)
	// Tailscale's WireGuard fork does not learn a peer's endpoint from its packets, as a stock
	// server would, so the test server is told where the phone is.
	if err := serverDev.IpcSet(fmt.Sprintf("private_key=%s\nlisten_port=%d\npublic_key=%s\nendpoint=127.0.0.1:%d\nallowed_ip=10.66.0.2/32\n",
		hexKey(serverKey), serverPort, hexKey(phonePub), phonePort)); err != nil {
		t.Fatal(err)
	}
	if err := serverDev.Up(); err != nil {
		t.Fatal(err)
	}

	certs := NewCertManager(t.TempDir())
	certs.cert = selfSigned(t, "phone.example.com")
	tunnel := NewTunnel(backendLn.Addr().(*net.TCPAddr).Port, certs)
	err = tunnel.Start(fmt.Sprintf("[Interface]\nPrivateKey = %s\nAddress = 10.66.0.2/32\nListenPort = %d\n[Peer]\nPublicKey = %s\nEndpoint = 127.0.0.1:%d\nAllowedIPs = 10.66.0.0/24\nPersistentKeepalive = 1\n",
		phoneKey, phonePort, serverPub, serverPort))
	if err != nil {
		t.Fatal(err)
	}
	defer tunnel.Stop()

	client := &http.Client{
		Timeout: 40 * time.Second,
		Transport: &http.Transport{
			DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
				return serverNet.DialContextTCPAddrPort(ctx, netip.MustParseAddrPort("10.66.0.2:443"))
			},
			TLSClientConfig: &tls.Config{InsecureSkipVerify: true, ServerName: "phone.example.com"},
		},
	}
	resp, err := client.Get("https://phone.example.com/mcp")
	if err != nil {
		t.Fatalf("%v\n%s", err, tunnel.Logs())
	}
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if string(body) != "mcp at /mcp via phone.example.com" {
		t.Errorf("body = %q", body)
	}
	if st := tunnel.Status(); !strings.Contains(st, `"running":true`) || strings.Contains(st, `"lastHandshake":0`) {
		t.Errorf("status = %s", st)
	}
}

func hexKey(b64 string) string {
	k, _ := base64.StdEncoding.DecodeString(b64)
	return fmt.Sprintf("%x", k)
}

func freeUDPPort(t *testing.T) int {
	c, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	return c.LocalAddr().(*net.UDPAddr).Port
}

func selfSigned(t *testing.T, host string) *tls.Certificate {
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		DNSNames:     []string{host},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(time.Hour),
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	return &tls.Certificate{Certificate: [][]byte{der}, PrivateKey: key}
}
