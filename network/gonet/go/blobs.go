package ppnet

// Blobs holds the certificate material: the ACME account key, and each domain's certificate and key. The phone
// implements it with its Keystore-backed secret store, so private keys are never written as plain files.
type Blobs interface {
	// Get returns what was saved under name, or nil when nothing was.
	Get(name string) []byte
	// Put saves value under name.
	Put(name string, value []byte) error
	// Remove deletes name; a missing name is not an error.
	Remove(name string)
}

const accountKeyName = "account.key"

func keyName(domain string) string { return domain + ".key" }

func certName(domain string) string { return domain + ".crt" }
