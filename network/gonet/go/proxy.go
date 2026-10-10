package ppnet

import (
	"crypto/tls"
	"fmt"
	"net/http"
	"net/http/httputil"
	"net/url"
	"time"
)

// newProxyServer serves HTTP that has already been decrypted by its listener, forwarding every
// request to the app's loopback-only remote MCP port, 127.0.0.1:targetPort. Each request adds a line
// to logs.
func newProxyServer(targetPort int, logs *ring) *http.Server {
	target := &url.URL{Scheme: "http", Host: fmt.Sprintf("127.0.0.1:%d", targetPort)}
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
	return &http.Server{
		Handler:           logRequests(logs, proxy),
		ReadHeaderTimeout: 30 * time.Second,
		// TLS is already terminated by the listener.
		TLSNextProto: map[string]func(*http.Server, *tls.Conn, http.Handler){},
	}
}

// logRequests adds a line per request to the log (method, path, status, time taken), so the owner
// can copy it when a client such as claude.ai cannot connect. Tokens and bodies are not logged.
func logRequests(logs *ring, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		rec := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
		next.ServeHTTP(rec, r)
		logs.logf("ppnet: %s %s %s -> %d in %v (%s)", r.Method, r.Host, r.URL.Path, rec.status,
			time.Since(start).Round(time.Millisecond), r.UserAgent())
	})
}

type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (r *statusRecorder) WriteHeader(code int) {
	r.status = code
	r.ResponseWriter.WriteHeader(code)
}

// Unwrap lets http.ResponseController reach Flush, which streaming MCP responses need.
func (r *statusRecorder) Unwrap() http.ResponseWriter { return r.ResponseWriter }
