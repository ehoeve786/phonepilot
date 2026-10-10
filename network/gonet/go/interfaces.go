package ppnet

import (
	"errors"
	"net"
	"net/netip"
	"sync"
	"time"

	"tailscale.com/net/netmon"
)

var registerOnce sync.Once

// registerInterfaceGetter works around Android denying apps the netlink socket that Go's
// net.Interfaces needs, which would stop Tailscale's network monitor from starting. It reports one
// synthetic interface carrying the addresses the kernel picks for outbound traffic, which is enough
// for Tailscale to find its endpoints.
//
// Adapted from tailscale.com/feature/androidbin (BSD-3-Clause, Copyright (c) Tailscale Inc &
// contributors), which registers the same fallback only in builds without cgo, while gomobile
// builds use cgo.
func registerInterfaceGetter() {
	registerOnce.Do(func() {
		netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
			if ifs, err := net.Interfaces(); err == nil && len(ifs) > 0 {
				out := make([]netmon.Interface, 0, len(ifs))
				for i := range ifs {
					out = append(out, netmon.Interface{Interface: &ifs[i]})
				}
				return out, nil
			}
			return syntheticInterfaces()
		})
	})
}

func syntheticInterfaces() ([]netmon.Interface, error) {
	var addrs []net.Addr
	// Dialing UDP sends no packets; it only makes the kernel choose a route and source address.
	if ip, ok := outboundIP("udp4", "8.8.8.8:53"); ok {
		addrs = append(addrs, &net.IPNet{IP: ip.AsSlice(), Mask: net.CIDRMask(32, 32)})
	}
	if ip, ok := outboundIP("udp6", "[2001:4860:4860::8888]:53"); ok {
		addrs = append(addrs, &net.IPNet{IP: ip.AsSlice(), Mask: net.CIDRMask(128, 128)})
	}
	if len(addrs) == 0 {
		return nil, errors.New("ppnet: no network connection")
	}
	return []netmon.Interface{{
		Interface: &net.Interface{Index: 1, MTU: 1500, Name: "android", Flags: net.FlagUp | net.FlagRunning},
		AltAddrs:  addrs,
	}}, nil
}

func outboundIP(network, addr string) (netip.Addr, bool) {
	d := net.Dialer{Timeout: 2 * time.Second}
	c, err := d.Dial(network, addr)
	if err != nil {
		return netip.Addr{}, false
	}
	defer c.Close()
	ua, ok := c.LocalAddr().(*net.UDPAddr)
	if !ok {
		return netip.Addr{}, false
	}
	ip, ok := netip.AddrFromSlice(ua.IP)
	if !ok {
		return netip.Addr{}, false
	}
	ip = ip.Unmap()
	if ip.IsLoopback() || ip.IsUnspecified() {
		return netip.Addr{}, false
	}
	return ip, true
}
