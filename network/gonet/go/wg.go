package ppnet

import (
	"bufio"
	"crypto/rand"
	"crypto/tls"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/netip"
	"strconv"
	"strings"
	"sync"

	"app.pocketpilot/ppnet/internal/wgnetstack"
	"github.com/tailscale/wireguard-go/conn"
	"github.com/tailscale/wireguard-go/device"
	"golang.org/x/crypto/curve25519"
)

// Tunnel is a userspace WireGuard interface on its own network stack inside the app: no VpnService,
// and the rest of the phone's traffic is untouched. It listens for HTTPS on port 443 of its tunnel
// address, presents the owner's certificate and forwards requests to the remote MCP port.
type Tunnel struct {
	targetPort int
	certs      *CertManager
	logs       *ring

	mu        sync.Mutex
	dev       *device.Device
	addresses []netip.Prefix
	httpSrv   *http.Server
	lastError string
}

// NewTunnel prepares a tunnel that forwards to 127.0.0.1:targetPort and serves certs' certificate.
func NewTunnel(targetPort int, certs *CertManager) *Tunnel {
	return &Tunnel{targetPort: targetPort, certs: certs, logs: newRing(500)}
}

// Start brings the tunnel up from a wg-quick config. A running tunnel is replaced.
func (t *Tunnel) Start(config string) error {
	cfg, err := parseWgQuick(config)
	if err != nil {
		t.setError(err.Error())
		return err
	}
	routeRepliesThroughServer(cfg)
	uapi, err := cfg.uapi(resolveUDP)
	if err != nil {
		t.setError(err.Error())
		return err
	}
	t.Stop()
	t.mu.Lock()
	defer t.mu.Unlock()
	t.lastError = ""

	addrs := make([]netip.Addr, 0, len(cfg.Addresses))
	for _, p := range cfg.Addresses {
		addrs = append(addrs, p.Addr())
	}
	tunDev, tnet, err := wgnetstack.CreateNetTUN(addrs, cfg.DNS, cfg.MTU)
	if err != nil {
		t.lastError = err.Error()
		return err
	}
	logger := &device.Logger{
		Verbosef: func(format string, args ...any) { t.logs.logf("wg: "+format, args...) },
		Errorf:   func(format string, args ...any) { t.logs.logf("wg error: "+format, args...) },
	}
	dev := device.NewDevice(tunDev, conn.NewDefaultBind(), logger)
	if err := dev.IpcSet(uapi); err != nil {
		dev.Close()
		t.lastError = fmt.Sprintf("WireGuard rejected the config: %v", err)
		return errors.New(t.lastError)
	}
	if err := dev.Up(); err != nil {
		dev.Close()
		t.lastError = err.Error()
		return err
	}
	ln, err := tnet.ListenTCP(&net.TCPAddr{Port: 443})
	if err != nil {
		dev.Close()
		t.lastError = fmt.Sprintf("could not listen on port 443 in the tunnel: %v", err)
		return errors.New(t.lastError)
	}
	t.dev = dev
	t.addresses = cfg.Addresses
	t.httpSrv = newProxyServer(t.targetPort, t.logs)
	srv := t.httpSrv
	tlsLn := tls.NewListener(ln, t.certs.tlsConfig())
	go func() {
		if err := srv.Serve(tlsLn); err != nil && !errors.Is(err, http.ErrServerClosed) && !errors.Is(err, net.ErrClosed) {
			t.setError(fmt.Sprintf("HTTPS listener stopped: %v", err))
		}
	}()
	t.logs.logf("ppnet: WireGuard up as %s, serving HTTPS on port 443", addrs[0])
	return nil
}

// routeRepliesThroughServer sends every reply to the one server peer. The tunnel carries only
// connections the relay forwards to the phone, from any address on the internet, so a config that
// lists just the VPN's subnet (as router exports often do) would otherwise drop the replies. The
// phone's own traffic never uses this network stack, so nothing else is routed through the tunnel.
func routeRepliesThroughServer(cfg *wgConfig) {
	if len(cfg.Peers) != 1 {
		return
	}
	p := &cfg.Peers[0]
	for _, def := range []netip.Prefix{netip.MustParsePrefix("0.0.0.0/0"), netip.MustParsePrefix("::/0")} {
		found := false
		for _, a := range p.AllowedIPs {
			if a == def {
				found = true
			}
		}
		if !found {
			p.AllowedIPs = append(p.AllowedIPs, def)
		}
	}
}

// Stop takes the tunnel down.
func (t *Tunnel) Stop() {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.httpSrv != nil {
		_ = t.httpSrv.Close()
		t.httpSrv = nil
	}
	if t.dev != nil {
		t.dev.Close()
		t.dev = nil
	}
	t.addresses = nil
}

type wgStatus struct {
	Running bool     `json:"running"`
	Address []string `json:"addresses,omitempty"`
	// LastHandshake is the newest handshake with any peer, in Unix milliseconds, or 0 if none yet.
	LastHandshake int64  `json:"lastHandshake"`
	RxBytes       int64  `json:"rxBytes"`
	TxBytes       int64  `json:"txBytes"`
	Error         string `json:"error,omitempty"`
}

// Status reports the tunnel's state as JSON.
func (t *Tunnel) Status() string {
	t.mu.Lock()
	st := wgStatus{Running: t.dev != nil, Error: t.lastError}
	for _, a := range t.addresses {
		st.Address = append(st.Address, a.Addr().String())
	}
	dev := t.dev
	t.mu.Unlock()
	if dev != nil {
		if get, err := dev.IpcGet(); err == nil {
			st.LastHandshake, st.RxBytes, st.TxBytes = peerTotals(get)
		}
	}
	b, _ := json.Marshal(st)
	return string(b)
}

// Logs returns the tunnel's recent log lines, newest last.
func (t *Tunnel) Logs() string { return t.logs.keyFirst() }

func (t *Tunnel) setError(msg string) {
	t.mu.Lock()
	t.lastError = msg
	t.mu.Unlock()
	t.logs.logf("ppnet: WireGuard error: %s", msg)
}

// peerTotals sums transfer counters over all peers in an IpcGet dump and finds the newest handshake.
func peerTotals(uapi string) (lastHandshakeMs, rx, tx int64) {
	var sec, nsec int64
	sc := bufio.NewScanner(strings.NewReader(uapi))
	flush := func() {
		if ms := sec*1000 + nsec/1_000_000; ms > lastHandshakeMs {
			lastHandshakeMs = ms
		}
		sec, nsec = 0, 0
	}
	for sc.Scan() {
		key, value, _ := strings.Cut(sc.Text(), "=")
		n, _ := strconv.ParseInt(value, 10, 64)
		switch key {
		case "public_key":
			flush()
		case "last_handshake_time_sec":
			sec = n
		case "last_handshake_time_nsec":
			nsec = n
		case "rx_bytes":
			rx += n
		case "tx_bytes":
			tx += n
		}
	}
	flush()
	return lastHandshakeMs, rx, tx
}

func resolveUDP(hostport string) (netip.AddrPort, error) {
	addr, err := net.ResolveUDPAddr("udp", hostport)
	if err != nil {
		return netip.AddrPort{}, err
	}
	// ResolveUDPAddr gives IPv4 as an IPv4-mapped IPv6 address, which WireGuard would send over IPv6.
	ap := addr.AddrPort()
	return netip.AddrPortFrom(ap.Addr().Unmap(), ap.Port()), nil
}

// GeneratePrivateKey returns a new WireGuard private key, base64 as wg-quick writes it.
func GeneratePrivateKey() (string, error) {
	var k [32]byte
	if _, err := rand.Read(k[:]); err != nil {
		return "", err
	}
	// Clamp as wg genkey does.
	k[0] &= 248
	k[31] = (k[31] & 127) | 64
	return base64.StdEncoding.EncodeToString(k[:]), nil
}

// PublicKey derives the public key, base64, that goes in the server's [Peer] for privateKey.
func PublicKey(privateKey string) (string, error) {
	k, err := parseKey(strings.TrimSpace(privateKey))
	if err != nil {
		return "", err
	}
	pub, err := curve25519.X25519(k, curve25519.Basepoint)
	if err != nil {
		return "", err
	}
	return base64.StdEncoding.EncodeToString(pub), nil
}

// PublicKeyOfConfig returns the public key for the PrivateKey in a wg-quick config.
func PublicKeyOfConfig(config string) (string, error) {
	cfg, err := parseWgQuick(config)
	if err != nil {
		return "", err
	}
	return PublicKey(base64.StdEncoding.EncodeToString(cfg.PrivateKey))
}
