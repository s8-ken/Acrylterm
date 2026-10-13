//go:build moshlive

package moshclient

import (
	"bufio"
	"bytes"
	"os"
	"os/exec"
	"regexp"
	"strconv"
	"testing"
	"time"
)

func TestLive(t *testing.T) {
	cmd := exec.Command("/opt/homebrew/bin/mosh-server", "new", "-i", "127.0.0.1", "-c", "256",
		"-l", "LANG=en_US.UTF-8", "--", "bash", "--norc", "-i")
	cmd.Env = append(os.Environ(), "LANG=en_US.UTF-8")
	so, _ := cmd.StdoutPipe()
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	defer func() { exec.Command("pkill", "-f", "mosh-server new -i 127.0.0.1").Run() }()
	re := regexp.MustCompile(`MOSH CONNECT (\d+) (\S+)`)
	var port int
	var key string
	sc := bufio.NewScanner(so)
	for sc.Scan() {
		if m := re.FindStringSubmatch(sc.Text()); m != nil {
			port, _ = strconv.Atoi(m[1])
			key = m[2]
			break
		}
	}
	h, err := Open("127.0.0.1", int32(port), key, DialUDP)
	if err != nil {
		t.Fatal(err)
	}
	defer Close(h)
	Resize(h, 80, 24)
	time.Sleep(time.Second)
	Send(h, []byte("echo hi-$((20+3)); exit\r"))
	var all bytes.Buffer
	start := time.Now()
	for {
		b, err := Recv(h, 500)
		all.Write(b)
		if err != nil {
			t.Logf("after %v: err=%v; output contains marker=%v", time.Since(start), err, bytes.Contains(all.Bytes(), []byte("hi-23")))
			if err != ErrClosed || err.Error() != "mosh: closed" || !bytes.Contains(all.Bytes(), []byte("hi-23")) {
				t.Fatalf("bad end: %v\n%q", err, all.String())
			}
			break
		}
		if time.Since(start) > 8*time.Second {
			t.Fatalf("timeout, no close: %q", all.String())
		}
	}
	if _, err := Recv(h, 10); err != ErrClosed {
		t.Fatal("second recv", err)
	}
	Close(h)
	Close(h)
}
