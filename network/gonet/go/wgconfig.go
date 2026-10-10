package ppnet

import (
	"bufio"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"net"
	"net/netip"
	"strconv"
	"strings"
)

// wgConfig is a parsed wg-quick configuration file: the phone's [Interface] and its [Peer]s.
type wgConfig struct {
	PrivateKey []byte
	Addresses  []netip.Prefix
	DNS        []netip.Addr
	MTU        int
	ListenPort int
	Peers      []wgPeer
}

type wgPeer struct {
	PublicKey    []byte
	PresharedKey []byte
	// Endpoint is host:port as written; it is resolved when the tunnel starts.
	Endpoint   string
	AllowedIPs []netip.Prefix
	Keepalive  int
}

// parseWgQuick reads a wg-quick file. Keys PocketPilot cannot honour inside the app (PostUp,
// Table, FwMark and the like) are ignored, since there is no kernel interface to run them on.
func parseWgQuick(text string) (*wgConfig, error) {
	cfg := &wgConfig{MTU: 1280}
	var peer *wgPeer
	section := ""
	sc := bufio.NewScanner(strings.NewReader(text))
	lineNo := 0
	for sc.Scan() {
		lineNo++
		line := strings.TrimSpace(sc.Text())
		if i := strings.IndexByte(line, '#'); i >= 0 {
			line = strings.TrimSpace(line[:i])
		}
		if line == "" {
			continue
		}
		if strings.HasPrefix(line, "[") && strings.HasSuffix(line, "]") {
			section = strings.ToLower(strings.TrimSpace(line[1 : len(line)-1]))
			switch section {
			case "interface":
			case "peer":
				cfg.Peers = append(cfg.Peers, wgPeer{})
				peer = &cfg.Peers[len(cfg.Peers)-1]
			default:
				return nil, fmt.Errorf("line %d: unknown section [%s]", lineNo, section)
			}
			continue
		}
		key, value, ok := strings.Cut(line, "=")
		if !ok {
			return nil, fmt.Errorf("line %d: expected key = value", lineNo)
		}
		key = strings.ToLower(strings.TrimSpace(key))
		value = strings.TrimSpace(value)
		var err error
		switch section {
		case "interface":
			err = cfg.set(key, value)
		case "peer":
			err = peer.set(key, value)
		default:
			err = fmt.Errorf("%s is outside a section", key)
		}
		if err != nil {
			return nil, fmt.Errorf("line %d: %w", lineNo, err)
		}
	}
	if err := sc.Err(); err != nil {
		return nil, err
	}
	switch {
	case cfg.PrivateKey == nil:
		return nil, fmt.Errorf("the [Interface] section needs a PrivateKey")
	case len(cfg.Addresses) == 0:
		return nil, fmt.Errorf("the [Interface] section needs an Address")
	case len(cfg.Peers) == 0:
		return nil, fmt.Errorf("the config needs a [Peer] section for the server")
	}
	for i, p := range cfg.Peers {
		if p.PublicKey == nil {
			return nil, fmt.Errorf("peer %d needs a PublicKey", i+1)
		}
	}
	return cfg, nil
}

func (c *wgConfig) set(key, value string) (err error) {
	switch key {
	case "privatekey":
		c.PrivateKey, err = parseKey(value)
	case "address":
		for _, part := range splitList(value) {
			p, perr := parsePrefixOrAddr(part)
			if perr != nil {
				return perr
			}
			c.Addresses = append(c.Addresses, p)
		}
	case "dns":
		for _, part := range splitList(value) {
			if a, aerr := netip.ParseAddr(part); aerr == nil {
				c.DNS = append(c.DNS, a)
			}
			// Search domains are allowed in DNS = and ignored here.
		}
	case "mtu":
		c.MTU, err = strconv.Atoi(value)
	case "listenport":
		c.ListenPort, err = strconv.Atoi(value)
	}
	return err
}

func (p *wgPeer) set(key, value string) (err error) {
	switch key {
	case "publickey":
		p.PublicKey, err = parseKey(value)
	case "presharedkey":
		p.PresharedKey, err = parseKey(value)
	case "endpoint":
		if _, _, err = net.SplitHostPort(value); err != nil {
			return fmt.Errorf("endpoint %q needs a host and port", value)
		}
		p.Endpoint = value
	case "allowedips":
		for _, part := range splitList(value) {
			pr, perr := parsePrefixOrAddr(part)
			if perr != nil {
				return perr
			}
			p.AllowedIPs = append(p.AllowedIPs, pr)
		}
	case "persistentkeepalive":
		if value != "off" {
			p.Keepalive, err = strconv.Atoi(value)
		}
	}
	return err
}

// uapi renders the config in WireGuard's configuration protocol, with endpoints resolved by resolve.
func (c *wgConfig) uapi(resolve func(hostport string) (netip.AddrPort, error)) (string, error) {
	var b strings.Builder
	fmt.Fprintf(&b, "private_key=%s\n", hex.EncodeToString(c.PrivateKey))
	if c.ListenPort != 0 {
		fmt.Fprintf(&b, "listen_port=%d\n", c.ListenPort)
	}
	b.WriteString("replace_peers=true\n")
	for _, p := range c.Peers {
		fmt.Fprintf(&b, "public_key=%s\n", hex.EncodeToString(p.PublicKey))
		if p.PresharedKey != nil {
			fmt.Fprintf(&b, "preshared_key=%s\n", hex.EncodeToString(p.PresharedKey))
		}
		if p.Endpoint != "" {
			ap, err := resolve(p.Endpoint)
			if err != nil {
				return "", fmt.Errorf("could not look up the server %s: %w", p.Endpoint, err)
			}
			fmt.Fprintf(&b, "endpoint=%s\n", ap)
		}
		if p.Keepalive != 0 {
			fmt.Fprintf(&b, "persistent_keepalive_interval=%d\n", p.Keepalive)
		}
		b.WriteString("replace_allowed_ips=true\n")
		for _, a := range p.AllowedIPs {
			fmt.Fprintf(&b, "allowed_ip=%s\n", a)
		}
	}
	return b.String(), nil
}

func parseKey(s string) ([]byte, error) {
	k, err := base64.StdEncoding.DecodeString(s)
	if err != nil || len(k) != 32 {
		return nil, fmt.Errorf("%q is not a WireGuard key", abbreviate(s))
	}
	return k, nil
}

func parsePrefixOrAddr(s string) (netip.Prefix, error) {
	if p, err := netip.ParsePrefix(s); err == nil {
		return p, nil
	}
	a, err := netip.ParseAddr(s)
	if err != nil {
		return netip.Prefix{}, fmt.Errorf("%q is not an address", s)
	}
	return netip.PrefixFrom(a, a.BitLen()), nil
}

func splitList(s string) []string {
	var out []string
	for _, part := range strings.Split(s, ",") {
		if part = strings.TrimSpace(part); part != "" {
			out = append(out, part)
		}
	}
	return out
}

// abbreviate keeps error messages from echoing secrets in full.
func abbreviate(s string) string {
	if len(s) > 6 {
		return s[:6] + "…"
	}
	return s
}
