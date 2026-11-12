package tailscalebridge

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"os"
	"strings"
	"sync"
	"time"

	"tailscale.com/ipn/ipnstate"
	"tailscale.com/net/netmon"
	"tailscale.com/tsnet"
)

var (
	mu      sync.Mutex
	server  *tsnet.Server
	ifMu    sync.Mutex
	ifaces  []netmon.Interface
	ifReady bool
)

type result struct {
	OK     bool   `json:"ok"`
	Error  string `json:"error,omitempty"`
	Status any    `json:"status,omitempty"`
}

// SetNetworkSnapshot supplies Android's permitted interface view. Android 11+
// blocks Go's netlink interface enumeration for ordinary apps.
func SetNetworkSnapshot(defaultInterface, rawInterfaces string) string {
	var in []struct {
		Name      string   `json:"name"`
		Index     int      `json:"index"`
		MTU       int      `json:"mtu"`
		Up        bool     `json:"up"`
		Loopback  bool     `json:"loopback"`
		P2P       bool     `json:"p2p"`
		Multicast bool     `json:"multicast"`
		Broadcast bool     `json:"broadcast"`
		Addrs     []string `json:"addrs"`
	}
	if err := json.Unmarshal([]byte(rawInterfaces), &in); err != nil {
		return encode(result{Error: err.Error()})
	}
	if len(in) == 0 {
		return encode(result{Error: "Android reported no network interfaces"})
	}
	list := make([]netmon.Interface, 0, len(in))
	for _, a := range in {
		var flags net.Flags
		if a.Up { flags |= net.FlagUp | net.FlagRunning }
		if a.Loopback { flags |= net.FlagLoopback }
		if a.P2P { flags |= net.FlagPointToPoint }
		if a.Multicast { flags |= net.FlagMulticast }
		if a.Broadcast { flags |= net.FlagBroadcast }
		var addrs []net.Addr
		for _, raw := range a.Addrs {
			p, err := netip.ParsePrefix(raw)
			if err != nil { continue }
			addrs = append(addrs, &net.IPNet{IP: p.Addr().AsSlice(), Mask: net.CIDRMask(p.Bits(), p.Addr().BitLen())})
		}
		list = append(list, netmon.Interface{
			Interface: &net.Interface{Index: a.Index, MTU: a.MTU, Name: a.Name, Flags: flags},
			AltAddrs: addrs,
		})
	}
	ifMu.Lock()
	ifaces = list
	register := !ifReady
	ifReady = true
	ifMu.Unlock()
	if register {
		netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
			ifMu.Lock()
			defer ifMu.Unlock()
			out := make([]netmon.Interface, len(ifaces))
			copy(out, ifaces)
			return out, nil
		})
	}
	netmon.UpdateLastKnownDefaultRouteInterface(defaultInterface)
	mu.Lock()
	s := server
	mu.Unlock()
	if s != nil {
		if nm, ok := s.Sys().NetMon.GetOK(); ok && nm != nil { nm.InjectEvent() }
	}
	return encode(result{OK: true})
}

// Start creates an in-process tsnet node. State remains under the app-private
// directory so the node can be stopped to release RAM and resumed later.
func Start(authKey, hostname, stateDir string) string {
	mu.Lock()
	defer mu.Unlock()
	if server != nil { return statusLocked() }
	if err := os.MkdirAll(stateDir, 0700); err != nil { return encode(result{Error: err.Error()}) }
	// Tailscale needs a writable log-policy directory on Android; logs stay local.
	if err := os.Setenv("TS_LOGS_DIR", stateDir); err != nil { return encode(result{Error: err.Error()}) }
	s := &tsnet.Server{
		Dir: stateDir,
		Hostname: hostname,
		AuthKey: authKey,
		UserLogf: func(format string, args ...any) {},
		Logf: func(format string, args ...any) {},
	}
	if err := s.Start(); err != nil {
		_ = s.Close()
		return encode(result{Error: err.Error()})
	}
	server = s
	return statusLocked()
}

// Status returns the current login / connectivity state without blocking for login.
func Status() string {
	mu.Lock()
	defer mu.Unlock()
	if server == nil { return encode(result{Error: "Tailscale node is stopped"}) }
	return statusLocked()
}

func statusLocked() string {
	if server == nil { return encode(result{Error: "Tailscale node is stopped"}) }
	lc, err := server.LocalClient()
	if err != nil { return encode(result{Error: err.Error()}) }
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	st, err := lc.Status(ctx)
	if err != nil { return encode(result{Error: err.Error()}) }
	return encode(result{OK: true, Status: compactStatus(st)})
}

type statusView struct {
	Running bool     `json:"running"`
	State   string   `json:"state"`
	AuthURL string   `json:"authUrl,omitempty"`
	IPs     []string `json:"ips,omitempty"`
	DNSName string   `json:"dnsName,omitempty"`
	Health  []string `json:"health,omitempty"`
}

func compactStatus(st *ipnstate.Status) statusView {
	v := statusView{State: st.BackendState, AuthURL: st.AuthURL, Health: st.Health}
	v.Running = st.BackendState == "Running"
	for _, ip := range st.TailscaleIPs { v.IPs = append(v.IPs, ip.String()) }
	if st.Self != nil { v.DNSName = st.Self.DNSName }
	return v
}

// Get performs an HTTP GET through the Tailscale userspace network stack.
// Audio URLs returned by the API are played directly by Pilot's MediaPlayer.
func Get(url, cookie string, timeoutMillis int64) string {
	mu.Lock()
	s := server
	mu.Unlock()
	if s == nil { return encode(result{Error: "Tailscale node is stopped"}) }
	timeout := time.Duration(timeoutMillis) * time.Millisecond
	if timeout <= 0 || timeout > 60*time.Second { timeout = 15 * time.Second }
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil { return encode(result{Error: err.Error()}) }
	if cookie != "" { req.Header.Set("Cookie", cookie) }
	transport := &http.Transport{
		DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
			return s.Dial(ctx, network, address)
		},
	}
	defer transport.CloseIdleConnections()
	resp, err := (&http.Client{Transport: transport}).Do(req)
	if err != nil { return encode(result{Error: err.Error()}) }
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 8<<20))
	if err != nil { return encode(result{Error: err.Error()}) }
	return encode(map[string]any{"ok": true, "code": resp.StatusCode, "body": string(body)})
}

// Stop closes the embedded node. The calling Android service can then exit and
// release the Go runtime's resident memory.
func Stop() string {
	mu.Lock()
	defer mu.Unlock()
	if server == nil { return encode(result{OK: true}) }
	s := server
	server = nil
	if err := s.Close(); err != nil && !errors.Is(err, net.ErrClosed) {
		return encode(result{Error: fmt.Sprintf("close tsnet: %v", err)})
	}
	return encode(result{OK: true})
}

func encode(v any) string {
	b, err := json.Marshal(v)
	if err != nil { return `{"ok":false,"error":"encode failure"}` }
	return strings.TrimSpace(string(b))
}
