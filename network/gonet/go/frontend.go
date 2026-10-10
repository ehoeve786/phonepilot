package ppnet

import (
	"crypto/tls"
	"errors"
	"fmt"
	"net"
	"net/http"
	"sync"
)

// TLSFrontend terminates HTTPS on a loopback port for a network whose library hands the app plain
// TCP streams rather than a Go listener: the ZeroTier provider accepts a connection on the ZeroTier
// network and pipes it here. It presents certs' certificate and forwards requests to the remote MCP
// port, like the WireGuard and Tailscale listeners.
type TLSFrontend struct {
	targetPort int
	certs      *CertManager
	logs       *ring

	mu      sync.Mutex
	ln      net.Listener
	httpSrv *http.Server
}

// NewTLSFrontend prepares a frontend that forwards to 127.0.0.1:targetPort.
func NewTLSFrontend(targetPort int, certs *CertManager) *TLSFrontend {
	return &TLSFrontend{targetPort: targetPort, certs: certs, logs: newRing(500)}
}

// Start opens the loopback port, if it is not open yet, and returns its number.
func (f *TLSFrontend) Start() (int, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.ln != nil {
		return f.ln.Addr().(*net.TCPAddr).Port, nil
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, err
	}
	srv := newProxyServer(f.targetPort, f.logs)
	f.ln, f.httpSrv = ln, srv
	go func() {
		if err := srv.Serve(tls.NewListener(ln, f.certs.tlsConfig())); err != nil &&
			!errors.Is(err, http.ErrServerClosed) && !errors.Is(err, net.ErrClosed) {
			f.logs.logf("ppnet: HTTPS frontend stopped: %v", err)
		}
	}()
	port := ln.Addr().(*net.TCPAddr).Port
	f.logs.logf("ppnet: HTTPS frontend on %s", fmt.Sprintf("127.0.0.1:%d", port))
	return port, nil
}

// Stop closes the loopback port.
func (f *TLSFrontend) Stop() {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.httpSrv != nil {
		_ = f.httpSrv.Close()
	}
	f.ln, f.httpSrv = nil, nil
}

// Logs returns the frontend's recent log lines, newest last.
func (f *TLSFrontend) Logs() string { return f.logs.keyFirst() }
