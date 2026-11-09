package main

import (
	"reflect"
	"testing"
)

func TestParseArgs(t *testing.T) {
	wg, cmd, err := parseArgs([]string{"--wireguard", "--", "/work/edge", "-Da=b"})
	if err != nil || !wg || !reflect.DeepEqual(cmd, []string{"/work/edge", "-Da=b"}) {
		t.Fatalf("got %v %v %v", wg, cmd, err)
	}
	wg, cmd, err = parseArgs([]string{"--", "/work/edge", "--wireguard"})
	if err != nil || wg || !reflect.DeepEqual(cmd, []string{"/work/edge", "--wireguard"}) {
		t.Fatalf("an option after -- belongs to the command: %v %v %v", wg, cmd, err)
	}
	for _, bad := range [][]string{{}, {"--"}, {"--wireguard"}, {"--nope", "--", "x"}, {"/work/edge"}} {
		if _, _, err := parseArgs(bad); err == nil {
			t.Errorf("%q: want an error", bad)
		}
	}
}

func TestParseAddress(t *testing.T) {
	ip, mask, err := parseAddress(" 100.96.0.1/24 ")
	if err != nil || ip != [4]byte{100, 96, 0, 1} || mask != [4]byte{255, 255, 255, 0} {
		t.Fatalf("got %v %v %v", ip, mask, err)
	}
	ip, mask, err = parseAddress("10.1.2.3/20")
	if err != nil || ip != [4]byte{10, 1, 2, 3} || mask != [4]byte{255, 255, 240, 0} {
		t.Fatalf("got %v %v %v", ip, mask, err)
	}
	for _, bad := range []string{"", "100.96.0.1", "fd00::1/64", "100.96.0.1/33", "wg0"} {
		if _, _, err := parseAddress(bad); err == nil {
			t.Errorf("%q: want an error", bad)
		}
	}
}

func TestParseMTU(t *testing.T) {
	if mtu, err := parseMTU("1380"); err != nil || mtu != 1380 {
		t.Fatalf("got %v %v", mtu, err)
	}
	for _, bad := range []string{"", "abc", "575", "65536", "-1"} {
		if _, err := parseMTU(bad); err == nil {
			t.Errorf("%q: want an error", bad)
		}
	}
}
