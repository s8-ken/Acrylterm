//go:build tools

package tsbridge

// Keeps golang.org/x/mobile/bind in go.mod so `go mod tidy` doesn't drop it
// (gomobile bind needs it resolvable from this module).
import _ "golang.org/x/mobile/bind"
