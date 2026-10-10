package ppnet

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestCloudflareTXTAndA(t *testing.T) {
	var calls []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer tok" {
			w.WriteHeader(http.StatusForbidden)
			io.WriteString(w, `{"success":false,"errors":[{"message":"Invalid API Token"}]}`)
			return
		}
		body, _ := io.ReadAll(r.Body)
		calls = append(calls, r.Method+" "+r.URL.RequestURI()+" "+string(body))
		switch {
		case r.URL.Path == "/zones" && r.URL.Query().Get("name") == "hoeve.ca":
			io.WriteString(w, `{"success":true,"result":[{"id":"z1"}]}`)
		case r.URL.Path == "/zones":
			io.WriteString(w, `{"success":true,"result":[]}`)
		case r.Method == http.MethodGet && r.URL.Path == "/zones/z1/dns_records":
			io.WriteString(w, `{"success":true,"result":[{"id":"a1","type":"A","name":"phone.hoeve.ca","content":"1.2.3.4","ttl":60}]}`)
		case r.Method == http.MethodPost:
			io.WriteString(w, `{"success":true,"result":{"id":"r1"}}`)
		default:
			io.WriteString(w, `{"success":true,"result":null}`)
		}
	}))
	defer srv.Close()

	cf := &cloudflare{base: srv.URL, token: "tok"}
	zone, rec, err := cf.addTXT(context.Background(), "_acme-challenge.phone.hoeve.ca", "v")
	if err != nil || zone != "z1" || rec != "r1" {
		t.Fatalf("addTXT = %s %s %v", zone, rec, err)
	}
	if err := cf.setA(context.Background(), "phone.hoeve.ca", "203.0.113.9"); err != nil {
		t.Fatal(err)
	}
	last := calls[len(calls)-1]
	if !strings.HasPrefix(last, "PUT /zones/z1/dns_records/a1 ") {
		t.Fatalf("last call = %s", last)
	}
	var a cfRecord
	_ = json.Unmarshal([]byte(last[strings.Index(last, "{"):]), &a)
	if a.Content != "203.0.113.9" || a.Proxied == nil || *a.Proxied {
		t.Errorf("A record = %+v", a)
	}

	bad := &cloudflare{base: srv.URL, token: "wrong"}
	if _, _, err := bad.addTXT(context.Background(), "x.hoeve.ca", "v"); err == nil || !strings.Contains(err.Error(), "Invalid API Token") {
		t.Errorf("bad token error = %v", err)
	}
}

func TestSetALeavesTunnelRecordAlone(t *testing.T) {
	for name, record := range map[string]string{
		"tunnel":  `{"id":"c1","type":"CNAME","name":"phone.hoeve.ca","content":"x.cfargotunnel.com","ttl":1,"proxied":true}`,
		"proxied": `{"id":"a1","type":"A","name":"phone.hoeve.ca","content":"1.2.3.4","ttl":1,"proxied":true}`,
	} {
		var writes int
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			switch {
			case r.URL.Path == "/zones" && r.URL.Query().Get("name") == "hoeve.ca":
				io.WriteString(w, `{"success":true,"result":[{"id":"z1"}]}`)
			case r.URL.Path == "/zones":
				io.WriteString(w, `{"success":true,"result":[]}`)
			case r.Method == http.MethodGet:
				io.WriteString(w, `{"success":true,"result":[`+record+`]}`)
			default:
				writes++
				io.WriteString(w, `{"success":true,"result":null}`)
			}
		}))
		cf := &cloudflare{base: srv.URL, token: "tok"}
		if err := cf.setA(context.Background(), "phone.hoeve.ca", "203.0.113.9"); !errors.Is(err, errDomainProxied) || writes != 0 {
			t.Errorf("%s: err = %v, writes = %d", name, err, writes)
		}
		srv.Close()
	}
}

func TestZoneNotVisible(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		io.WriteString(w, `{"success":true,"result":[]}`)
	}))
	defer srv.Close()
	cf := &cloudflare{base: srv.URL, token: "tok"}
	if _, err := cf.zoneFor(context.Background(), "phone.example.org"); err == nil {
		t.Error("found a zone")
	}
}
