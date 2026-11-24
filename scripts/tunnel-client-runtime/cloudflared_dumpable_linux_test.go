//go:build linux
// +build linux

package main

import (
    "bytes"
    "errors"
    "os"
    "os/exec"
    "strconv"
    "strings"
    "syscall"
    "testing"
    "time"
)

func TestCloudflaredDumpableHelper(t *testing.T) {
    if os.Getenv("CLOUDFLARED_DUMPABLE_PROBE") != "1" { return }
    if os.Getenv("CLOUDFLARED_DUMPABLE_HARDEN") == "1" {
        if err := hardenCloudflaredProcess(); err != nil { os.Exit(3) }
    }
    time.Sleep(8 * time.Second)
}

func startCloudflaredProbe(t *testing.T, hardened bool) *exec.Cmd {
    t.Helper()
    cmd := exec.Command(os.Args[0], "-test.run=^TestCloudflaredDumpableHelper$")
    env := append([]string{}, os.Environ()...)
    env = append(env, "CLOUDFLARED_DUMPABLE_PROBE=1", "CLOUDFLARED_DUMPABLE_SECRET=probe-value")
    if hardened { env = append(env, "CLOUDFLARED_DUMPABLE_HARDEN=1") } else {
        env = append(env, "CLOUDFLARED_DUMPABLE_HARDEN=0")
    }
    cmd.Env = env
    if err := cmd.Start(); err != nil { t.Fatalf("start probe: %v", err) }
    t.Cleanup(func() { _ = cmd.Process.Kill(); _ = cmd.Wait() })
    time.Sleep(150 * time.Millisecond)
    return cmd
}

func TestCloudflaredHidesEnvironmentFromSameUidProcess(t *testing.T) {
    normal := startCloudflaredProbe(t, false)
    raw, err := os.ReadFile("/proc/" + strconv.Itoa(normal.Process.Pid) + "/environ")
    if err != nil { t.Fatalf("control environment unreadable: %v", err) }
    if !bytes.Contains(raw, []byte("CLOUDFLARED_DUMPABLE_SECRET=probe-value")) {
        t.Fatal("control environment probe not visible")
    }
    hardened := startCloudflaredProbe(t, true)
    _, err = os.ReadFile("/proc/" + strconv.Itoa(hardened.Process.Pid) + "/environ")
    if err == nil { t.Fatal("same-UID process read hardened cloudflared environment") }
    if !errors.Is(err, syscall.EACCES) && !errors.Is(err, syscall.EPERM) && !strings.Contains(strings.ToLower(err.Error()), "permission denied") {
        t.Fatalf("expected procfs permission denial, got: %v", err)
    }
}
