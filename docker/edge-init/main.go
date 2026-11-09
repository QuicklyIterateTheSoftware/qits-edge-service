// qits-edge-init is the only code in the qits-edge image that runs as root, and it runs for
// milliseconds: it brings up the private edge's WireGuard device and then becomes the edge, as uid
// 1001, by exec. See README.md, "The private edge".
//
//	qits-edge-init [--wireguard] -- <command> [args...]
//
// With --wireguard (the private edge, QITS_VARIANT=private) it refuses a namespace that forwards
// IPv4, makes /dev/net/tun (swarm services have no --device), starts wireguard-go wg0 (which
// daemonises and stays root: it holds the TUN and NET_ADMIN), gives wg0 its address, MTU and IFF_UP,
// and hands the UAPI socket and the key directory to uid 1001. Always, it drops to uid 1001, gid 0,
// no supplementary groups, and execs the command with the current environment. The edge itself never
// holds a capability: setuid away from root clears them.
package main

import (
	"errors"
	"fmt"
	"net"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"syscall"
	"time"

	"golang.org/x/sys/unix"
)

const (
	edgeUID        = 1001
	edgeGID        = 0
	iface          = "wg0"
	wireguardGo    = "/usr/local/bin/wireguard-go"
	tunPath        = "/dev/net/tun"
	uapiSocket     = "/var/run/wireguard/wg0.sock"
	keyDir         = "/work/.wireguard"
	ipForward      = "/proc/sys/net/ipv4/ip_forward"
	defaultAddress = "100.96.0.1/24"
	defaultMTU     = "1380"
	socketWait     = 10 * time.Second
)

func main() {
	wireguard, command, err := parseArgs(os.Args[1:])
	if err == nil && wireguard {
		err = bringUp()
	}
	if err == nil {
		err = dropPrivileges()
	}
	if err == nil {
		var path string
		if path, err = exec.LookPath(command[0]); err == nil {
			err = syscall.Exec(path, command, os.Environ())
		}
	}
	fmt.Fprintf(os.Stderr, "qits-edge-init: %v\n", err)
	os.Exit(1)
}

// parseArgs reads `[--wireguard] -- <command> [args...]`.
func parseArgs(args []string) (wireguard bool, command []string, err error) {
	for i, arg := range args {
		switch {
		case arg == "--":
			if i+1 >= len(args) {
				return false, nil, errors.New("no command after --")
			}
			return wireguard, args[i+1:], nil
		case arg == "--wireguard":
			wireguard = true
		default:
			return false, nil, fmt.Errorf("unknown option %q; usage: qits-edge-init [--wireguard] -- <command> [args...]", arg)
		}
	}
	return false, nil, errors.New("usage: qits-edge-init [--wireguard] -- <command> [args...]")
}

// parseAddress reads an IPv4 CIDR into the address and netmask the ioctls take.
func parseAddress(cidr string) (ip, mask [4]byte, err error) {
	addr, network, err := net.ParseCIDR(strings.TrimSpace(cidr))
	if err != nil || addr.To4() == nil || len(network.Mask) != net.IPv4len {
		return ip, mask, fmt.Errorf("QITS_EDGE_WIREGUARD_ADDRESS %q is not an IPv4 CIDR like %s", cidr, defaultAddress)
	}
	copy(ip[:], addr.To4())
	copy(mask[:], network.Mask)
	return ip, mask, nil
}

// parseMTU reads an MTU between IPv4's minimum datagram and the largest a link can carry.
func parseMTU(value string) (uint32, error) {
	mtu, err := strconv.Atoi(strings.TrimSpace(value))
	if err != nil || mtu < 576 || mtu > 65535 {
		return 0, fmt.Errorf("QITS_EDGE_WIREGUARD_MTU %q is not an MTU between 576 and 65535", value)
	}
	return uint32(mtu), nil
}

func env(name, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}

func bringUp() error {
	ip, mask, err := parseAddress(env("QITS_EDGE_WIREGUARD_ADDRESS", defaultAddress))
	if err != nil {
		return err
	}
	mtu, err := parseMTU(env("QITS_EDGE_WIREGUARD_MTU", defaultMTU))
	if err != nil {
		return err
	}
	// First, before anything is started: the tunnel must never route a peer onward.
	forward, err := os.ReadFile(ipForward)
	if err != nil {
		return fmt.Errorf("refusing to bring up %s: cannot read %s: %v", iface, ipForward, err)
	}
	if value := strings.TrimSpace(string(forward)); value != "0" {
		return fmt.Errorf("refusing to bring up %s: %s is %q, and a private edge requires 0 so the tunnel can never route a peer onward", iface, ipForward, value)
	}
	if err := ensureTun(); err != nil {
		return err
	}
	// wireguard-go forks its foreground self and returns once the TUN and the UAPI socket exist.
	start := exec.Command(wireguardGo, iface)
	start.Stdout, start.Stderr = os.Stdout, os.Stderr
	start.Env = os.Environ()
	if os.Getenv("LOG_LEVEL") == "" {
		start.Env = append(start.Env, "LOG_LEVEL=error") // its errors reach the container log
	}
	if err := start.Run(); err != nil {
		return fmt.Errorf("%s %s: %v", wireguardGo, iface, err)
	}
	deadline := time.Now().Add(socketWait)
	for {
		if _, err := os.Stat(uapiSocket); err == nil {
			break
		}
		if time.Now().After(deadline) {
			return fmt.Errorf("%s did not appear within %s of starting wireguard-go", uapiSocket, socketWait)
		}
		time.Sleep(100 * time.Millisecond)
	}
	if err := configure(iface, ip, mask, mtu); err != nil {
		return err
	}
	if err := os.Chown(uapiSocket, edgeUID, edgeGID); err != nil {
		return fmt.Errorf("chown %s: %v", uapiSocket, err)
	}
	// A fresh volume is root's; the edge writes its private key here.
	if err := os.MkdirAll(keyDir, 0o700); err != nil {
		return fmt.Errorf("mkdir %s: %v", keyDir, err)
	}
	if err := os.Chown(keyDir, edgeUID, edgeGID); err != nil {
		return fmt.Errorf("chown %s: %v", keyDir, err)
	}
	fmt.Fprintf(os.Stderr, "qits-edge-init: %s up, %s mtu %d\n", iface, env("QITS_EDGE_WIREGUARD_ADDRESS", defaultAddress), mtu)
	return nil
}

func ensureTun() error {
	if info, err := os.Stat(tunPath); err == nil && info.Mode()&os.ModeCharDevice != 0 {
		return nil
	}
	if err := os.MkdirAll("/dev/net", 0o755); err != nil {
		return fmt.Errorf("mkdir /dev/net: %v", err)
	}
	if err := unix.Mknod(tunPath, unix.S_IFCHR|0o666, int(unix.Mkdev(10, 200))); err != nil {
		return fmt.Errorf("mknod %s c 10 200: %v", tunPath, err)
	}
	return os.Chmod(tunPath, 0o666) // mknod's mode is masked by the umask
}

// configure sets the IPv4 address, netmask and MTU of name and raises IFF_UP, as ifconfig does.
func configure(name string, ip, mask [4]byte, mtu uint32) error {
	fd, err := unix.Socket(unix.AF_INET, unix.SOCK_DGRAM|unix.SOCK_CLOEXEC, 0)
	if err != nil {
		return fmt.Errorf("socket: %v", err)
	}
	defer unix.Close(fd)
	ioctl := func(what string, request uint, set func(*unix.Ifreq) error) (*unix.Ifreq, error) {
		ifr, err := unix.NewIfreq(name)
		if err == nil && set != nil {
			err = set(ifr)
		}
		if err == nil {
			err = unix.IoctlIfreq(fd, request, ifr)
		}
		if err != nil {
			return nil, fmt.Errorf("%s on %s: %v", what, name, err)
		}
		return ifr, nil
	}
	if _, err := ioctl("SIOCSIFADDR", unix.SIOCSIFADDR, func(r *unix.Ifreq) error { return r.SetInet4Addr(ip[:]) }); err != nil {
		return err
	}
	if _, err := ioctl("SIOCSIFNETMASK", unix.SIOCSIFNETMASK, func(r *unix.Ifreq) error { return r.SetInet4Addr(mask[:]) }); err != nil {
		return err
	}
	if _, err := ioctl("SIOCSIFMTU", unix.SIOCSIFMTU, func(r *unix.Ifreq) error { r.SetUint32(mtu); return nil }); err != nil {
		return err
	}
	flags, err := ioctl("SIOCGIFFLAGS", unix.SIOCGIFFLAGS, nil)
	if err != nil {
		return err
	}
	up := flags.Uint16() | unix.IFF_UP
	_, err = ioctl("SIOCSIFFLAGS", unix.SIOCSIFFLAGS, func(r *unix.Ifreq) error { r.SetUint16(up); return nil })
	return err
}

// dropPrivileges becomes uid 1001, gid 0, with no supplementary groups. The syscall package's
// calls apply to every thread of the process (Go >= 1.16), so the exec cannot land on a thread that
// kept root. Already 1001 (an image run with --user) is fine; any other non-root user is refused.
func dropPrivileges() error {
	if os.Geteuid() != 0 {
		if os.Getuid() == edgeUID {
			return nil
		}
		return fmt.Errorf("running as uid %d, which is neither root nor %d", os.Getuid(), edgeUID)
	}
	if err := syscall.Setgroups([]int{}); err != nil {
		return fmt.Errorf("setgroups: %v", err)
	}
	if err := syscall.Setgid(edgeGID); err != nil {
		return fmt.Errorf("setgid %d: %v", edgeGID, err)
	}
	if err := syscall.Setuid(edgeUID); err != nil {
		return fmt.Errorf("setuid %d: %v", edgeUID, err)
	}
	return nil
}
