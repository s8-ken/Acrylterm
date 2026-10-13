// Package moshclient is the handle-based glue around the vendored mosh-go client.
// It has no tailscale imports so it builds (and is testable) on any GOOS.
package moshclient

import (
	"errors"
	"fmt"
	"net"
	"sync"
	"time"

	mosh "github.com/unixshells/mosh-go"
)

// ErrClosed is returned by Recv/Send once the session ended (server shutdown or Close).
var ErrClosed = errors.New("mosh: closed")

// Dialer opens the datagram connection (plain UDP or via tsnet).
type Dialer func(addr string) (net.Conn, error)

type session struct {
	c    *mosh.Client
	once sync.Once
}

var (
	mu      sync.Mutex
	next    int32 = 1
	handles       = map[int32]*session{}
)

func get(h int32) (*session, error) {
	mu.Lock()
	defer mu.Unlock()
	s, ok := handles[h]
	if !ok {
		return nil, ErrClosed
	}
	return s, nil
}

// Open dials host:port with dial and returns a handle > 0.
func Open(host string, port int32, key string, dial Dialer) (int32, error) {
	for len(key)%4 != 0 { // mosh prints the key without base64 padding
		key += "="
	}
	ocb, err := mosh.NewOCBFromBase64(key)
	if err != nil {
		return 0, fmt.Errorf("mosh: bad key: %w", err)
	}
	conn, err := dial(net.JoinHostPort(host, fmt.Sprint(port)))
	if err != nil {
		return 0, err
	}
	c, err := mosh.DialConn(conn, ocb)
	if err != nil {
		conn.Close()
		return 0, err
	}
	mu.Lock()
	defer mu.Unlock()
	h := next
	next++
	handles[h] = &session{c: c}
	return h, nil
}

// DialUDP is the plain-UDP Dialer (IPv4 or IPv6).
func DialUDP(addr string) (net.Conn, error) {
	ua, err := net.ResolveUDPAddr("udp", addr)
	if err != nil {
		return nil, err
	}
	return net.DialUDP("udp", nil, ua)
}

func Send(h int32, data []byte) error {
	s, err := get(h)
	if err != nil {
		return err
	}
	select {
	case <-s.c.RemoteClosed():
		return ErrClosed
	default:
	}
	s.c.Send(data)
	return nil
}

func Resize(h int32, cols, rows int32) error {
	s, err := get(h)
	if err != nil {
		return err
	}
	s.c.Resize(uint16(cols), uint16(rows))
	return nil
}

// Recv blocks up to timeoutMs; returns empty slice on timeout, ErrClosed after shutdown/Close.
// Output received before the shutdown is delivered before ErrClosed.
func Recv(h int32, timeoutMs int32) ([]byte, error) {
	s, err := get(h)
	if err != nil {
		return nil, err
	}
	out := s.c.Recv(time.Duration(timeoutMs) * time.Millisecond)
	if len(out) > 0 {
		return out, nil
	}
	select {
	case <-s.c.RemoteClosed():
		// Drain anything that raced in with the shutdown.
		if out = s.c.Recv(0); len(out) > 0 {
			return out, nil
		}
		return nil, ErrClosed
	default:
	}
	if _, err := get(h); err != nil {
		return nil, err
	}
	return []byte{}, nil
}

// Close is idempotent. (mosh-go has no cheap way to send state 2^64-1, so it just closes.)
func Close(h int32) {
	mu.Lock()
	s, ok := handles[h]
	delete(handles, h)
	mu.Unlock()
	if ok {
		s.once.Do(s.c.Close)
	}
}
