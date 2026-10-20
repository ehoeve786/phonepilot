package ppnet

import (
	"fmt"
	"io"
	"os"
	"strings"
	"sync"
	"time"
)

// ring keeps the last few log lines in memory instead of uploading them anywhere. It also copies
// them to a file, when one is set, so the lines before a crash survive it.
type ring struct {
	mu    sync.Mutex
	lines []string
	max   int
	file  *os.File
	// written counts the bytes sent to file since it was last started over.
	written int64
}

// maxFileBytes bounds the log file, so a long run cannot fill the phone's storage. The lines in memory are unaffected.
const maxFileBytes = 256 << 10

// writeTo starts copying lines to path, replacing what it held.
func (r *ring) writeTo(path string) {
	f, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o600)
	if err != nil {
		return
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.file != nil {
		_ = r.file.Close()
	}
	r.file = f
	r.written = 0
}

func newRing(max int) *ring { return &ring{max: max} }

func (r *ring) logf(format string, args ...any) {
	line := time.Now().Format("15:04:05 ") + strings.TrimRight(fmt.Sprintf(format, args...), "\n")
	r.mu.Lock()
	defer r.mu.Unlock()
	r.lines = append(r.lines, line)
	if r.file != nil {
		n, _ := r.file.WriteString(line + "\n")
		r.written += int64(n)
		if r.written > maxFileBytes {
			// Start the file over rather than grow it without limit.
			_ = r.file.Truncate(0)
			_, _ = r.file.Seek(0, io.SeekStart)
			r.written = 0
		}
	}
	if len(r.lines) > r.max {
		r.lines = r.lines[len(r.lines)-r.max:]
	}
}

func (r *ring) String() string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return strings.Join(r.lines, "\n")
}

// keyFirst returns the log with lines about certificates, Funnel, handshakes and incoming requests
// first, so they survive when the owner pastes the log somewhere that cuts long messages.
func (r *ring) keyFirst() string {
	all := r.String()
	var key []string
	for _, line := range strings.Split(all, "\n") {
		l := strings.ToLower(line)
		if strings.Contains(l, "ppnet:") || strings.Contains(l, "cert") || strings.Contains(l, "acme") ||
			strings.Contains(l, "funnel") || strings.Contains(l, "handshake") || strings.Contains(l, "error") {
			key = append(key, line)
		}
	}
	if len(key) == 0 {
		return all
	}
	return "--- Key lines ---\n" + strings.Join(key, "\n") + "\n\n--- Full log ---\n" + all
}
