// Package tsbridge embeds a Tailscale node (tsnet, userspace networking) into the
// Android app. It is compiled to an AAR with gomobile (see build.sh) and called from
// Kotlin via reflection (TailscaleManager.kt), so the app still builds without it.
//
// The app never uses the Android VPN slot: connections to tailnet peers are made by
// Forward(), which opens a loopback TCP listener that pipes each accepted connection
// through tsnet.Server.Dial. The SSH client then simply connects to 127.0.0.1:<port>.
package tsbridge

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"tailscale.com/net/netmon"
	"tailscale.com/tsnet"
)

var (
	mu       sync.Mutex
	srv      *tsnet.Server
	ifaceDef string // interface list pushed from Kotlin (Android denies net.Interfaces to apps)
	logBuf   = newRing(200)
)

func init() {
	netmon.RegisterInterfaceGetter(getInterfaces)
}

// SetInterfaces receives the device's network interfaces from Kotlin.
// One interface per line: name|index|mtu|flags|addr/prefix,addr/prefix
// flags is a bitmask: 1=up 2=broadcast 4=loopback 8=pointtopoint 16=multicast
func SetInterfaces(def string) {
	mu.Lock()
	ifaceDef = def
	mu.Unlock()
}

func getInterfaces() ([]netmon.Interface, error) {
	mu.Lock()
	def := ifaceDef
	mu.Unlock()
	var out []netmon.Interface
	for _, line := range strings.Split(def, "\n") {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		f := strings.Split(line, "|")
		if len(f) < 5 {
			continue
		}
		idx, _ := strconv.Atoi(f[1])
		mtu, _ := strconv.Atoi(f[2])
		bits, _ := strconv.Atoi(f[3])
		var flags net.Flags
		if bits&1 != 0 {
			flags |= net.FlagUp | net.FlagRunning
		}
		if bits&2 != 0 {
			flags |= net.FlagBroadcast
		}
		if bits&4 != 0 {
			flags |= net.FlagLoopback
		}
		if bits&8 != 0 {
			flags |= net.FlagPointToPoint
		}
		if bits&16 != 0 {
			flags |= net.FlagMulticast
		}
		iface := netmon.Interface{
			Interface: &net.Interface{Index: idx, MTU: mtu, Name: f[0], Flags: flags},
			AltAddrs:  []net.Addr{},
		}
		for _, a := range strings.Split(f[4], ",") {
			a = strings.TrimSpace(a)
			if a == "" {
				continue
			}
			ip, ipnet, err := net.ParseCIDR(a)
			if err != nil {
				continue
			}
			ipnet.IP = ip
			iface.AltAddrs = append(iface.AltAddrs, ipnet)
		}
		out = append(out, iface)
	}
	return out, nil
}

// SetDefaultRoute tells netmon which interface (and gateway, may be empty) currently
// carries the default route. Android hides this from apps, so Kotlin reports it on every
// network change; without it Wi-Fi <-> cellular handoffs are only noticed by slow polling.
func SetDefaultRoute(ifName, gateway string) {
	if ifName != "" {
		netmon.UpdateLastKnownDefaultRouteInterface(ifName)
	}
	if gateway != "" {
		netmon.UpdateLastKnownDefaultGateway(gateway)
	}
}

// Start brings up the embedded Tailscale node. It returns quickly; poll Status()
// for progress (and for the login URL when BackendState is NeedsLogin).
// authKey may be empty (interactive login via the URL in Status()).
func Start(stateDir, hostname, authKey string) error {
	mu.Lock()
	defer mu.Unlock()
	if srv != nil {
		return nil
	}
	s := &tsnet.Server{
		Dir:       stateDir,
		Hostname:  hostname,
		AuthKey:   authKey,
		Ephemeral: false,
		Logf:      func(format string, args ...any) { logBuf.add(fmt.Sprintf(format, args...)) },
		UserLogf:  func(format string, args ...any) { logBuf.add("[user] " + fmt.Sprintf(format, args...)) },
	}
	if err := s.Start(); err != nil {
		return err
	}
	srv = s
	return nil
}

type peerJSON struct {
	HostName string   `json:"hostName"`
	DNSName  string   `json:"dnsName"`
	OS       string   `json:"os"`
	IPs      []string `json:"ips"`
	Online   bool     `json:"online"`
}

type statusJSON struct {
	Running      bool       `json:"running"`
	BackendState string     `json:"backendState"`
	AuthURL      string     `json:"authURL"`
	SelfName     string     `json:"selfName"`
	SelfDNSName  string     `json:"selfDNSName"`
	TailscaleIPs []string   `json:"tailscaleIPs"`
	Peers        []peerJSON `json:"peers"`
	Health       []string   `json:"health"`
	Error        string     `json:"error,omitempty"`
}

// Status returns the node state as JSON (see statusJSON).
func Status() string {
	mu.Lock()
	s := srv
	mu.Unlock()
	out := statusJSON{}
	if s == nil {
		out.BackendState = "Stopped"
		return mustJSON(out)
	}
	out.Running = true
	lc, err := s.LocalClient()
	if err != nil {
		out.Error = err.Error()
		return mustJSON(out)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	st, err := lc.Status(ctx)
	if err != nil {
		out.Error = err.Error()
		return mustJSON(out)
	}
	out.BackendState = st.BackendState
	out.AuthURL = st.AuthURL
	out.Health = st.Health
	for _, ip := range st.TailscaleIPs {
		out.TailscaleIPs = append(out.TailscaleIPs, ip.String())
	}
	if st.Self != nil {
		out.SelfName = st.Self.HostName
		out.SelfDNSName = st.Self.DNSName
	}
	for _, p := range st.Peer {
		pj := peerJSON{HostName: p.HostName, DNSName: strings.TrimSuffix(p.DNSName, "."), OS: p.OS, Online: p.Online}
		for _, ip := range p.TailscaleIPs {
			pj.IPs = append(pj.IPs, ip.String())
		}
		out.Peers = append(out.Peers, pj)
	}
	sort.Slice(out.Peers, func(i, j int) bool { return out.Peers[i].HostName < out.Peers[j].HostName })
	return mustJSON(out)
}

// Forward starts a loopback TCP listener; every connection accepted on it is piped
// to target ("host:port", host may be a MagicDNS name or a tailnet IP) through the
// tailnet. Returns the local port. The listener lives until StopForward(port) or Stop().
func Forward(target string) (int32, error) {
	mu.Lock()
	s := srv
	mu.Unlock()
	if s == nil {
		return 0, errors.New("tailscale is not started")
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, err
	}
	port := ln.Addr().(*net.TCPAddr).Port
	fwdMu.Lock()
	forwards[int32(port)] = ln
	fwdMu.Unlock()
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go pipe(s, c, target)
		}
	}()
	return int32(port), nil
}

var (
	fwdMu    sync.Mutex
	forwards = map[int32]net.Listener{}
)

// StopForward closes a listener created by Forward. Established connections stay up.
func StopForward(port int32) {
	fwdMu.Lock()
	ln := forwards[port]
	delete(forwards, port)
	fwdMu.Unlock()
	if ln != nil {
		ln.Close()
	}
}

func pipe(s *tsnet.Server, local net.Conn, target string) {
	defer local.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	remote, err := s.Dial(ctx, "tcp", target)
	cancel()
	if err != nil {
		logBuf.add("dial " + target + ": " + err.Error())
		return
	}
	defer remote.Close()
	done := make(chan struct{}, 2)
	go func() { io.Copy(remote, local); done <- struct{}{} }()
	go func() { io.Copy(local, remote); done <- struct{}{} }()
	<-done
}

// Logout logs the node out of the tailnet (the state dir keeps the node key).
func Logout() error {
	mu.Lock()
	s := srv
	mu.Unlock()
	if s == nil {
		return nil
	}
	lc, err := s.LocalClient()
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	return lc.Logout(ctx)
}

// Stop shuts the node down and closes all forwards.
func Stop() {
	fwdMu.Lock()
	for p, ln := range forwards {
		ln.Close()
		delete(forwards, p)
	}
	fwdMu.Unlock()
	mu.Lock()
	s := srv
	srv = nil
	mu.Unlock()
	if s != nil {
		s.Close()
	}
}

// RecentLogs returns the last log lines (for the in-app diagnostics screen).
func RecentLogs() string { return logBuf.String() }

func mustJSON(v any) string {
	b, err := json.Marshal(v)
	if err != nil {
		return `{"error":` + strconv.Quote(err.Error()) + `}`
	}
	return string(b)
}

type ring struct {
	mu    sync.Mutex
	lines []string
	max   int
}

func newRing(n int) *ring { return &ring{max: n} }

func (r *ring) add(s string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.lines = append(r.lines, time.Now().Format("15:04:05 ")+s)
	if len(r.lines) > r.max {
		r.lines = r.lines[len(r.lines)-r.max:]
	}
}

func (r *ring) String() string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return strings.Join(r.lines, "\n")
}
