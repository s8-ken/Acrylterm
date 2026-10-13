# Vendored github.com/unixshells/mosh-go v0.5.2 (MIT, see LICENSE)

Only the transport/client files are vendored (server.go, framebuffer.go, tests, cmd/ dropped to
avoid the pty/vt-go dependencies); go.mod is trimmed accordingly.

Patches (shutdown detection; upstream never handles the shutdown state number 2^64-1):
- transport.go: `Transport.Recv` treats `new_num == math.MaxUint64` as the remote shutdown marker:
  sets `shutdown`, acks it, returns before the old_num/dedup checks. New `RemoteShutdown()`.
- client.go: new `remoteClosed` channel + `RemoteClosed()`; closed by `recvLoop` when the transport
  reports shutdown, and `Recv` wakes up on it (buffered output is still returned first).
