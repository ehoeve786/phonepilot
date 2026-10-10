package ppnet

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
)

const cloudflareAPI = "https://api.cloudflare.com/client/v4"

// cloudflare is the little of Cloudflare's DNS API that certificates and the relay need, called
// with an API token limited to editing DNS.
type cloudflare struct {
	base  string
	token string
}

type cfRecord struct {
	ID      string `json:"id,omitempty"`
	Type    string `json:"type"`
	Name    string `json:"name"`
	Content string `json:"content"`
	TTL     int    `json:"ttl"`
	Proxied *bool  `json:"proxied,omitempty"`
}

// addTXT creates a TXT record and returns its zone and record IDs, for delete.
func (c *cloudflare) addTXT(ctx context.Context, name, value string) (zoneID, recordID string, err error) {
	zoneID, err = c.zoneFor(ctx, name)
	if err != nil {
		return "", "", err
	}
	var rec cfRecord
	err = c.call(ctx, http.MethodPost, "/zones/"+zoneID+"/dns_records", cfRecord{Type: "TXT", Name: name, Content: value, TTL: 60}, &rec)
	return zoneID, rec.ID, err
}

func (c *cloudflare) delete(ctx context.Context, zoneID, recordID string) error {
	return c.call(ctx, http.MethodDelete, "/zones/"+zoneID+"/dns_records/"+recordID, nil, nil)
}

// errDomainProxied means the domain already goes through Cloudflare, by its proxy or a Cloudflare
// Tunnel, so its DNS record is the owner's to manage and is left alone.
var errDomainProxied = errors.New("the domain goes through Cloudflare's proxy or a Cloudflare Tunnel, so its DNS record is left as it is")

// setA points name at ip, replacing an existing A record for it. A proxied record or a CNAME, such
// as a Cloudflare Tunnel's, is never replaced: it gives errDomainProxied.
func (c *cloudflare) setA(ctx context.Context, name, ip string) error {
	zoneID, err := c.zoneFor(ctx, name)
	if err != nil {
		return err
	}
	var existing []cfRecord
	if err := c.call(ctx, http.MethodGet, "/zones/"+zoneID+"/dns_records?name="+url.QueryEscape(name), nil, &existing); err != nil {
		return err
	}
	var current *cfRecord
	for i, r := range existing {
		if r.Type == "CNAME" || (r.Proxied != nil && *r.Proxied) {
			return errDomainProxied
		}
		if r.Type == "A" && current == nil {
			current = &existing[i]
		}
	}
	off := false
	rec := cfRecord{Type: "A", Name: name, Content: ip, TTL: 60, Proxied: &off}
	if current != nil {
		return c.call(ctx, http.MethodPut, "/zones/"+zoneID+"/dns_records/"+current.ID, rec, nil)
	}
	return c.call(ctx, http.MethodPost, "/zones/"+zoneID+"/dns_records", rec, nil)
}

// zoneFor finds the zone holding name by trying its parent domains, longest first.
func (c *cloudflare) zoneFor(ctx context.Context, name string) (string, error) {
	labels := strings.Split(strings.TrimSuffix(name, "."), ".")
	for i := 0; i < len(labels)-1; i++ {
		candidate := strings.Join(labels[i:], ".")
		var zones []struct {
			ID string `json:"id"`
		}
		if err := c.call(ctx, http.MethodGet, "/zones?name="+url.QueryEscape(candidate), nil, &zones); err != nil {
			return "", err
		}
		if len(zones) > 0 {
			return zones[0].ID, nil
		}
	}
	return "", fmt.Errorf("no Cloudflare zone for %s is visible to this token", name)
}

func (c *cloudflare) call(ctx context.Context, method, path string, body, result any) error {
	var reader io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return err
		}
		reader = bytes.NewReader(b)
	}
	req, err := http.NewRequestWithContext(ctx, method, c.base+path, reader)
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+c.token)
	req.Header.Set("Content-Type", "application/json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	var envelope struct {
		Success bool            `json:"success"`
		Result  json.RawMessage `json:"result"`
		Errors  []struct {
			Message string `json:"message"`
		} `json:"errors"`
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(&envelope); err != nil {
		return fmt.Errorf("cloudflare answered %s", resp.Status)
	}
	if !envelope.Success {
		var msgs []string
		for _, e := range envelope.Errors {
			msgs = append(msgs, e.Message)
		}
		if len(msgs) == 0 {
			return fmt.Errorf("cloudflare answered %s", resp.Status)
		}
		return errors.New("cloudflare: " + strings.Join(msgs, "; "))
	}
	if result != nil && len(envelope.Result) > 0 {
		return json.Unmarshal(envelope.Result, result)
	}
	return nil
}
