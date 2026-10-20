package ppnet

// memBlobs keeps certificate material in memory, for tests.
type memBlobs map[string][]byte

func newMemBlobs() memBlobs { return memBlobs{} }

func (m memBlobs) Get(name string) []byte { return m[name] }

func (m memBlobs) Put(name string, value []byte) error {
	m[name] = append([]byte(nil), value...)
	return nil
}

func (m memBlobs) Remove(name string) { delete(m, name) }
