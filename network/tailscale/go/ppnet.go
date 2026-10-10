// Package ppnet runs PocketPilot's embedded Tailscale node (tsnet) and forwards HTTPS requests that
// reach it, from the tailnet or through Funnel, to the app's loopback-only remote MCP port.
//
// It is built into an AAR with gomobile, so its exported API sticks to types gomobile can bind:
// strings, ints, bools and errors. Status is returned as JSON for the Kotlin side to parse.
package ppnet

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"path/filepath"
	"runtime/debug"
	"strings"
	"sync"
	"time"

	"tailscale.com/ipn"
	"tailscale.com/tsnet"
)

// Node is one embedded Tailscale node. Create it with NewNode; all methods are safe to call from any
// thread.
type Node struct {
	stateDir   string
	hostname   string
	targetPort int

	mu        sync.Mutex
	srv       *tsnet.Server
	listener  net.Listener
	httpSrv   *http.Server
	public    bool
	lastError string
	logs      *ring
}

// NewNode prepares a node that keeps its state in stateDir, asks for hostname on the tailnet and
// forwards to 127.0.0.1:targetPort. Nothing starts until Start.
func NewNode(stateDir, hostname string, targetPort int) *Node {
	return &Node{stateDir: stateDir, hostname: hostname, targetPort: targetPort, logs: newRing(200)}
}

// Start brings the node up in the background. authKey may be empty, in which case Status reports a
// login URL for the owner to open. Calling Start on a running node does nothing.
func (n *Node) Start(authKey string) error {
	n.mu.Lock()
	defer n.mu.Unlock()
	if n.srv != nil {
		return nil
	}
	registerInterfaceGetter()
	n.recordCrashes()
	n.setDirsLocked()
	n.lastError = ""
	n.srv = &tsnet.Server{
		Dir:      n.stateDir,
		Hostname: n.hostname,
		AuthKey:  authKey,
		Logf:     n.logs.logf,
		UserLogf: n.logs.logf,
	}
	if err := n.srv.Start(); err != nil {
		n.srv = nil
		n.lastError = err.Error()
		return err
	}
	go n.serveWhenRunning(n.srv)
	return nil
}

// CrashFile and LogFile, in the state directory, hold the Go runtime's report of a fatal error and
// the node's log lines from the run that hit it. The app reads them after a restart.
const (
	CrashFile = "crash.txt"
	LogFile   = "node.log"
)

// recordCrashes points the Go runtime's fatal-error output, and a copy of the log, at the state
// directory, replacing what an earlier run left there.
func (n *Node) recordCrashes() {
	if err := os.MkdirAll(n.stateDir, 0o700); err != nil {
		return
	}
	n.logs.writeTo(filepath.Join(n.stateDir, LogFile))
	f, err := os.OpenFile(filepath.Join(n.stateDir, CrashFile), os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o600)
	if err != nil {
		return
	}
	defer f.Close()
	_ = debug.SetCrashOutput(f, debug.CrashOptions{})
}

// setDirsLocked gives Tailscale writable directories. An Android app has no $HOME, cache directory
// or writable temp directory, and Tailscale's log setup panics when it finds none of them.
func (n *Node) setDirsLocked() {
	dirs := map[string]string{
		"TS_LOGS_DIR":    "logs",
		"HOME":           "home",
		"XDG_CACHE_HOME": "cache",
		"TMPDIR":         "tmp",
	}
	for env, sub := range dirs {
		if os.Getenv(env) != "" {
			continue
		}
		dir := filepath.Join(n.stateDir, sub)
		if os.MkdirAll(dir, 0o700) == nil {
			_ = os.Setenv(env, dir)
		}
	}
}

// serveWhenRunning waits until the node is logged in, then opens the HTTPS listener.
func (n *Node) serveWhenRunning(srv *tsnet.Server) {
	if _, err := srv.Up(context.Background()); err != nil {
		n.setError(fmt.Sprintf("Tailscale did not come up: %v", err))
		return
	}
	n.mu.Lock()
	defer n.mu.Unlock()
	if n.srv != srv {
		return
	}
	if err := n.listenLocked(); err != nil {
		n.lastError = err.Error()
	}
}

// SetPublic turns Funnel on or off. With Funnel on, the node accepts connections from the public
// internet as well as the tailnet on port 443; with it off, only tailnet devices can connect.
func (n *Node) SetPublic(on bool) error {
	n.mu.Lock()
	defer n.mu.Unlock()
	n.public = on
	if n.srv == nil || n.httpSrv == nil {
		// Applied when the node comes up.
		return nil
	}
	n.closeListenerLocked()
	err := n.listenLocked()
	if err != nil {
		n.lastError = err.Error()
	}
	return err
}

// Stop shuts the node down but keeps its login, so the next Start reconnects without signing in.
func (n *Node) Stop() {
	n.mu.Lock()
	defer n.mu.Unlock()
	n.closeListenerLocked()
	if n.srv != nil {
		_ = n.srv.Close()
		n.srv = nil
	}
}

// Logout signs the node out of the tailnet and stops it; the next Start needs a new login.
func (n *Node) Logout() error {
	n.mu.Lock()
	srv := n.srv
	n.mu.Unlock()
	if srv != nil {
		if lc, err := srv.LocalClient(); err == nil {
			ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			defer cancel()
			_ = lc.Logout(ctx)
		}
	}
	n.Stop()
	return nil
}

// status is what Status returns, as JSON.
type status struct {
	// Running is true while the node is started (whatever its backend state).
	Running bool `json:"running"`
	// BackendState is Tailscale's own: NoState, NeedsLogin, NeedsMachineAuth, Starting, Running, Stopped.
	BackendState string `json:"backendState"`
	AuthURL      string `json:"authUrl,omitempty"`
	// DNSName is the node's MagicDNS name without the trailing dot, e.g. pocketpilot.tail1234.ts.net.
	DNSName string   `json:"dnsName,omitempty"`
	IPs     []string `json:"ips,omitempty"`
	// Serving is true while the HTTPS listener is open.
	Serving bool `json:"serving"`
	Public  bool `json:"public"`
	// FunnelProblem names the tailnet setting Funnel still needs, or is empty when Funnel is allowed.
	FunnelProblem string `json:"funnelProblem,omitempty"`
	Error         string `json:"error,omitempty"`
}

// Status reports the node's state as JSON.
func (n *Node) Status() string {
	n.mu.Lock()
	srv := n.srv
	st := status{Running: srv != nil, Serving: n.httpSrv != nil, Public: n.public, Error: n.lastError}
	n.mu.Unlock()
	if srv != nil {
		if lc, err := srv.LocalClient(); err == nil {
			ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			defer cancel()
			if s, err := lc.StatusWithoutPeers(ctx); err == nil {
				st.BackendState = s.BackendState
				st.AuthURL = s.AuthURL
				if s.Self != nil {
					st.DNSName = strings.TrimSuffix(s.Self.DNSName, ".")
					if err := ipn.CheckFunnelAccess(443, s.Self); err != nil {
						st.FunnelProblem = err.Error()
					}
				}
				if len(s.CertDomains) == 0 && s.BackendState == ipn.Running.String() {
					st.FunnelProblem = "HTTPS certificates are off for this tailnet. Turn them on under DNS in the Tailscale admin console."
				}
				for _, ip := range s.TailscaleIPs {
					st.IPs = append(st.IPs, ip.String())
				}
			}
		}
	}
	b, _ := json.Marshal(st)
	return string(b)
}

// Logs returns the node's recent log lines, newest last, for the Doctor screen.
func (n *Node) Logs() string {
	return n.logs.String()
}

func (n *Node) setError(msg string) {
	n.mu.Lock()
	n.lastError = msg
	n.mu.Unlock()
}

// listenLocked opens :443 on the node, through Funnel when public, and serves the proxy on it.
func (n *Node) listenLocked() error {
	var (
		ln  net.Listener
		err error
	)
	if n.public {
		ln, err = n.srv.ListenFunnel("tcp", ":443")
	} else {
		ln, err = n.srv.ListenTLS("tcp", ":443")
	}
	if err != nil {
		if n.public {
			// Stay reachable on the tailnet while Funnel is blocked.
			if fallback, ferr := n.srv.ListenTLS("tcp", ":443"); ferr == nil {
				n.serveLocked(fallback)
			}
		}
		return err
	}
	n.serveLocked(ln)
	n.lastError = ""
	return nil
}

func (n *Node) serveLocked(ln net.Listener) {
	target := &url.URL{Scheme: "http", Host: fmt.Sprintf("127.0.0.1:%d", n.targetPort)}
	proxy := &httputil.ReverseProxy{
		Rewrite: func(r *httputil.ProxyRequest) {
			r.SetURL(target)
			// Keep the public host name: the app checks it and builds OAuth URLs from it.
			r.Out.Host = r.In.Host
			r.SetXForwarded()
		},
		// Stream MCP's server-sent events without buffering.
		FlushInterval: -1,
		ErrorHandler: func(w http.ResponseWriter, _ *http.Request, err error) {
			http.Error(w, "PocketPilot's server is not running on the phone", http.StatusBadGateway)
		},
	}
	httpSrv := &http.Server{
		Handler:           proxy,
		ReadHeaderTimeout: 30 * time.Second,
		// TLS is already terminated by the tsnet listener.
		TLSNextProto: map[string]func(*http.Server, *tls.Conn, http.Handler){},
	}
	n.listener = ln
	n.httpSrv = httpSrv
	go func() {
		if err := httpSrv.Serve(ln); err != nil && !errors.Is(err, http.ErrServerClosed) && !errors.Is(err, net.ErrClosed) {
			n.setError(fmt.Sprintf("HTTPS listener stopped: %v", err))
		}
	}()
}

func (n *Node) closeListenerLocked() {
	if n.httpSrv != nil {
		_ = n.httpSrv.Close()
		n.httpSrv = nil
	}
	if n.listener != nil {
		_ = n.listener.Close()
		n.listener = nil
	}
}
