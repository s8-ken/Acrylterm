package tsbridge

import (
	"context"
	"errors"
	"net"
	"time"

	"github.com/shibaken/androidterminal/tsbridge/internal/moshclient"
)

// MoshOpen dials mosh-server at host:port (UDP) with the base64 key printed in
// "MOSH CONNECT <port> <key>". viaTailscale=true dials through the embedded tsnet node.
func MoshOpen(host string, port int32, key string, viaTailscale bool) (int32, error) {
	dial := moshclient.DialUDP
	if viaTailscale {
		mu.Lock()
		s := srv
		mu.Unlock()
		if s == nil {
			return 0, errors.New("tailscale is not started")
		}
		dial = func(addr string) (net.Conn, error) {
			ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
			defer cancel()
			return s.Dial(ctx, "udp", addr)
		}
	}
	return moshclient.Open(host, port, key, dial)
}

// MoshSend sends keystrokes / pasted bytes.
func MoshSend(h int32, data []byte) error { return moshclient.Send(h, data) }

func MoshResize(h int32, cols, rows int32) error { return moshclient.Resize(h, cols, rows) }

// MoshRecv blocks up to timeoutMs and returns screen-update ANSI bytes (empty on timeout).
// Error "mosh: closed" once the server shut down or after MoshClose.
func MoshRecv(h int32, timeoutMs int32) ([]byte, error) { return moshclient.Recv(h, timeoutMs) }

// MoshClose is idempotent.
func MoshClose(h int32) { moshclient.Close(h) }
