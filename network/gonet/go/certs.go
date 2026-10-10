package ppnet

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"golang.org/x/crypto/acme"
)

const (
	letsEncrypt = "https://acme-v02.api.letsencrypt.org/directory"
	// renewBefore is how long before expiry a certificate is replaced (spec section 7).
	renewBefore = 30 * 24 * time.Hour
	renewCheck  = 12 * time.Hour
)

// CertManager gets and renews a publicly trusted certificate for the owner's own domain from Let's
// Encrypt, proving control of the domain with a DNS-01 TXT record it adds through the Cloudflare
// API. The WireGuard listener presents it. Keys are created on the phone and stay in its
// app-private storage.
type CertManager struct {
	dir  string
	logs *ring

	// Overridden by tests.
	acmeDirectory string
	cloudflareAPI string
	lookupTXT     func(ctx context.Context, name string) ([]string, error)

	mu        sync.Mutex
	domain    string
	email     string
	token     string
	cert      *tls.Certificate
	notAfter  time.Time
	busy      bool
	lastError string
	renewing  bool
}

// NewCertManager keeps the ACME account key and certificates in dir.
func NewCertManager(dir string) *CertManager {
	return &CertManager{
		dir:           dir,
		logs:          newRing(300),
		acmeDirectory: letsEncrypt,
		cloudflareAPI: cloudflareAPI,
		lookupTXT:     lookupTXTAtCloudflare,
	}
}

// Configure sets the domain the certificate is for (such as phone.example.com), the contact email
// for Let's Encrypt and a Cloudflare API token allowed to edit that zone's DNS. It loads a
// certificate saved earlier for the domain, if any.
func (c *CertManager) Configure(domain, email, cloudflareToken string) {
	domain = strings.TrimSuffix(strings.ToLower(strings.TrimSpace(domain)), ".")
	c.mu.Lock()
	defer c.mu.Unlock()
	if domain != c.domain {
		c.cert = nil
		c.notAfter = time.Time{}
		c.lastError = ""
	}
	c.domain, c.email, c.token = domain, strings.TrimSpace(email), strings.TrimSpace(cloudflareToken)
	if c.cert == nil && domain != "" {
		if cert, notAfter, err := c.loadLocked(); err == nil {
			c.cert, c.notAfter = cert, notAfter
		}
	}
}

// Obtain makes sure there is a certificate valid for at least 30 more days, asking Let's Encrypt for
// a new one when needed. It blocks for up to a few minutes while DNS updates; call it off the main
// thread.
func (c *CertManager) Obtain() error {
	c.mu.Lock()
	if c.busy {
		c.mu.Unlock()
		return errors.New("a certificate request is already running")
	}
	if c.domain == "" || c.token == "" {
		c.mu.Unlock()
		return errors.New("set a domain and a Cloudflare API token first")
	}
	if c.cert != nil && time.Until(c.notAfter) > renewBefore {
		c.mu.Unlock()
		return nil
	}
	c.busy = true
	domain, email, token := c.domain, c.email, c.token
	c.mu.Unlock()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
	defer cancel()
	cert, notAfter, err := c.issue(ctx, domain, email, token)

	c.mu.Lock()
	defer c.mu.Unlock()
	c.busy = false
	if err != nil {
		c.lastError = err.Error()
		c.logs.logf("ppnet: certificate for %s failed: %v", domain, err)
		return err
	}
	if c.domain == domain {
		c.cert, c.notAfter, c.lastError = cert, notAfter, ""
	}
	c.logs.logf("ppnet: certificate for %s ready until %s", domain, notAfter.Format(time.DateOnly))
	return nil
}

// StartRenewals checks twice a day and renews the certificate when it is within 30 days of expiry.
func (c *CertManager) StartRenewals() {
	c.mu.Lock()
	if c.renewing {
		c.mu.Unlock()
		return
	}
	c.renewing = true
	c.mu.Unlock()
	go func() {
		for {
			time.Sleep(renewCheck)
			c.mu.Lock()
			due := c.cert != nil && time.Until(c.notAfter) <= renewBefore
			c.mu.Unlock()
			if due {
				_ = c.Obtain()
			}
		}
	}()
}

// PointDomainAt makes the domain's DNS A record point at ip (the relay server), creating or
// replacing it. The record is DNS only: Cloudflare's proxy would end TLS before the phone does. A
// domain the owner routes through Cloudflare's proxy or a Cloudflare Tunnel is left as it is.
func (c *CertManager) PointDomainAt(ip string) error {
	addr := net.ParseIP(strings.TrimSpace(ip))
	if addr == nil || addr.To4() == nil {
		return fmt.Errorf("%q is not an IPv4 address", ip)
	}
	c.mu.Lock()
	domain, token := c.domain, c.token
	c.mu.Unlock()
	if domain == "" || token == "" {
		return errors.New("set a domain and a Cloudflare API token first")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	cf := &cloudflare{base: c.cloudflareAPI, token: token}
	if err := cf.setA(ctx, domain, addr.String()); errors.Is(err, errDomainProxied) {
		c.logs.logf("ppnet: not pointing %s at %s: %v", domain, ip, err)
		return nil
	} else if err != nil {
		c.logs.logf("ppnet: pointing %s at %s failed: %v", domain, ip, err)
		return err
	}
	c.logs.logf("ppnet: %s now points at %s", domain, ip)
	return nil
}

// Forget deletes the saved certificates and account key.
func (c *CertManager) Forget() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.cert, c.notAfter, c.lastError = nil, time.Time{}, ""
	_ = os.RemoveAll(c.dir)
}

type certStatus struct {
	Domain string `json:"domain,omitempty"`
	Ready  bool   `json:"ready"`
	// NotAfter is the expiry, in Unix milliseconds, while Ready.
	NotAfter int64  `json:"notAfter,omitempty"`
	Busy     bool   `json:"busy"`
	Error    string `json:"error,omitempty"`
}

// Status reports the certificate's state as JSON.
func (c *CertManager) Status() string {
	c.mu.Lock()
	st := certStatus{Domain: c.domain, Ready: c.cert != nil, Busy: c.busy, Error: c.lastError}
	if c.cert != nil {
		st.NotAfter = c.notAfter.UnixMilli()
	}
	c.mu.Unlock()
	b, _ := json.Marshal(st)
	return string(b)
}

// Logs returns recent certificate log lines, newest last.
func (c *CertManager) Logs() string { return c.logs.keyFirst() }

// Domain is the configured domain, or empty.
func (c *CertManager) Domain() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.domain
}

func (c *CertManager) tlsConfig() *tls.Config {
	return &tls.Config{
		GetCertificate: c.getCertificate,
		NextProtos:     []string{"http/1.1"},
		MinVersion:     tls.VersionTLS12,
	}
}

func (c *CertManager) getCertificate(*tls.ClientHelloInfo) (*tls.Certificate, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.cert == nil {
		return nil, errors.New("no certificate yet: get one under Network, Certificate")
	}
	return c.cert, nil
}

func (c *CertManager) issue(ctx context.Context, domain, email, token string) (*tls.Certificate, time.Time, error) {
	if err := os.MkdirAll(c.dir, 0o700); err != nil {
		return nil, time.Time{}, err
	}
	accountKey, err := loadOrCreateKey(filepath.Join(c.dir, "account.key"))
	if err != nil {
		return nil, time.Time{}, err
	}
	client := &acme.Client{Key: accountKey, DirectoryURL: c.acmeDirectory}
	account := &acme.Account{}
	if email != "" {
		account.Contact = []string{"mailto:" + email}
	}
	if _, err := client.Register(ctx, account, acme.AcceptTOS); err != nil && !errors.Is(err, acme.ErrAccountAlreadyExists) {
		return nil, time.Time{}, fmt.Errorf("registering with Let's Encrypt: %w", err)
	}
	c.logs.logf("ppnet: asking Let's Encrypt for a certificate for %s", domain)
	order, err := client.AuthorizeOrder(ctx, acme.DomainIDs(domain))
	if err != nil {
		return nil, time.Time{}, fmt.Errorf("starting the order: %w", err)
	}
	cf := &cloudflare{base: c.cloudflareAPI, token: token}
	for _, authzURL := range order.AuthzURLs {
		if err := c.authorize(ctx, client, cf, authzURL); err != nil {
			return nil, time.Time{}, err
		}
	}
	order, err = client.WaitOrder(ctx, order.URI)
	if err != nil {
		return nil, time.Time{}, fmt.Errorf("waiting for the order: %w", err)
	}
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, time.Time{}, err
	}
	csr, err := x509.CreateCertificateRequest(rand.Reader, &x509.CertificateRequest{DNSNames: []string{domain}}, key)
	if err != nil {
		return nil, time.Time{}, err
	}
	chain, _, err := client.CreateOrderCert(ctx, order.FinalizeURL, csr, true)
	if err != nil {
		return nil, time.Time{}, fmt.Errorf("finishing the order: %w", err)
	}
	if err := c.save(domain, chain, key); err != nil {
		return nil, time.Time{}, err
	}
	return c.loadFor(domain)
}

// authorize proves control of one name with a DNS-01 TXT record, removing the record afterwards.
func (c *CertManager) authorize(ctx context.Context, client *acme.Client, cf *cloudflare, authzURL string) error {
	authz, err := client.GetAuthorization(ctx, authzURL)
	if err != nil {
		return err
	}
	if authz.Status == acme.StatusValid {
		return nil
	}
	var chal *acme.Challenge
	for _, ch := range authz.Challenges {
		if ch.Type == "dns-01" {
			chal = ch
		}
	}
	if chal == nil {
		return errors.New("let's Encrypt offered no DNS challenge")
	}
	value, err := client.DNS01ChallengeRecord(chal.Token)
	if err != nil {
		return err
	}
	name := "_acme-challenge." + authz.Identifier.Value
	zoneID, recordID, err := cf.addTXT(ctx, name, value)
	if err != nil {
		return fmt.Errorf("adding the DNS record on Cloudflare: %w", err)
	}
	defer func() {
		cleanup, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		_ = cf.delete(cleanup, zoneID, recordID)
	}()
	c.logs.logf("ppnet: added %s, waiting for DNS", name)
	if err := c.waitForTXT(ctx, name, value); err != nil {
		return err
	}
	if _, err := client.Accept(ctx, chal); err != nil {
		return fmt.Errorf("asking Let's Encrypt to check DNS: %w", err)
	}
	if _, err := client.WaitAuthorization(ctx, authz.URI); err != nil {
		return fmt.Errorf("let's Encrypt could not verify %s: %w", name, err)
	}
	return nil
}

func (c *CertManager) waitForTXT(ctx context.Context, name, value string) error {
	for {
		if records, err := c.lookupTXT(ctx, name); err == nil {
			for _, r := range records {
				if r == value {
					return nil
				}
			}
		}
		select {
		case <-ctx.Done():
			return fmt.Errorf("the DNS record %s never appeared", name)
		case <-time.After(5 * time.Second):
		}
	}
}

// lookupTXTAtCloudflare asks Cloudflare's resolver directly, so a phone's caching resolver does not
// hide a record that was just added.
func lookupTXTAtCloudflare(ctx context.Context, name string) ([]string, error) {
	r := &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
			var d net.Dialer
			return d.DialContext(ctx, network, "1.1.1.1:53")
		},
	}
	return r.LookupTXT(ctx, name)
}

func (c *CertManager) save(domain string, chain [][]byte, key *ecdsa.PrivateKey) error {
	var certPEM []byte
	for _, der := range chain {
		certPEM = append(certPEM, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})...)
	}
	keyDER, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		return err
	}
	keyPEM := pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER})
	if err := os.WriteFile(filepath.Join(c.dir, domain+".key"), keyPEM, 0o600); err != nil {
		return err
	}
	return os.WriteFile(filepath.Join(c.dir, domain+".crt"), certPEM, 0o600)
}

func (c *CertManager) loadLocked() (*tls.Certificate, time.Time, error) { return c.loadFor(c.domain) }

func (c *CertManager) loadFor(domain string) (*tls.Certificate, time.Time, error) {
	cert, err := tls.LoadX509KeyPair(filepath.Join(c.dir, domain+".crt"), filepath.Join(c.dir, domain+".key"))
	if err != nil {
		return nil, time.Time{}, err
	}
	leaf, err := x509.ParseCertificate(cert.Certificate[0])
	if err != nil {
		return nil, time.Time{}, err
	}
	if leaf.VerifyHostname(domain) != nil || time.Now().After(leaf.NotAfter) {
		return nil, time.Time{}, errors.New("saved certificate does not cover the domain")
	}
	cert.Leaf = leaf
	return &cert, leaf.NotAfter, nil
}

func loadOrCreateKey(path string) (*ecdsa.PrivateKey, error) {
	if b, err := os.ReadFile(path); err == nil {
		if block, _ := pem.Decode(b); block != nil {
			return x509.ParseECPrivateKey(block.Bytes)
		}
	}
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, err
	}
	der, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		return nil, err
	}
	return key, os.WriteFile(path, pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: der}), 0o600)
}
