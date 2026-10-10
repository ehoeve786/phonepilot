//go:build android

package ppnet

import (
	"fmt"
	"net/http"
	"strings"
	"time"

	"tailscale.com/ipn/localapi"
)

// Tailscale leaves its LocalAPI certificate endpoint out of Android builds, because its own Android
// app gets certificates another way. tsnet's HTTPS and Funnel listeners fetch their certificate
// through that endpoint, so without it every TLS handshake fails. This registers the same endpoint,
// adapted from tailscale.com/ipn/localapi/cert.go (BSD-3-Clause, Copyright (c) Tailscale Inc &
// contributors). ACME itself (feature/acme) is built for Android.
func init() {
	localapi.Register("cert/", serveCert)
}

func serveCert(h *localapi.Handler, w http.ResponseWriter, r *http.Request) {
	if !h.PermitWrite && !h.PermitCert {
		http.Error(w, "cert access denied", http.StatusForbidden)
		return
	}
	domain, ok := strings.CutPrefix(r.URL.Path, "/localapi/v0/cert/")
	if !ok {
		http.Error(w, "internal handler config wired wrong", http.StatusInternalServerError)
		return
	}
	var minValidity time.Duration
	if s := r.URL.Query().Get("min_validity"); s != "" {
		d, err := time.ParseDuration(s)
		if err != nil {
			http.Error(w, fmt.Sprintf("invalid validity parameter: %v", err), http.StatusBadRequest)
			return
		}
		minValidity = d
	}
	pair, err := h.LocalBackend().GetCertPEMWithValidity(r.Context(), domain, minValidity)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "text/plain")
	switch r.URL.Query().Get("type") {
	case "", "crt", "cert":
		_, _ = w.Write(pair.CertPEM)
	case "key":
		_, _ = w.Write(pair.KeyPEM)
	case "pair":
		_, _ = w.Write(pair.KeyPEM)
		_, _ = w.Write(pair.CertPEM)
	default:
		http.Error(w, `invalid type; want "cert" (default), "key", or "pair"`, http.StatusBadRequest)
	}
}
