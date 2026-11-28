//go:build linux
// +build linux

package main

import (
    "bytes"
    "errors"
    "os"
    "os/exec"
    "strings"
    "strconv"
    "syscall"
    "testing"
    "time"
)

func TestDumpableProcessHelper(t *testing.T) {
    if os.Getenv("TUNNEL_DUMPABLE_PROBE") != "1" {
        return
    }
    if os.Getenv("TUNNEL_DUMPABLE_HARDEN") == "1" {
        if err := hardenProcess(); err != nil {
            os.Exit(3)
        }
    }
    time.Sleep(8 * time.Second)
}

func startDumpableProbe(t *testing.T, hardened bool) *exec.Cmd {
    t.Helper()
    cmd := exec.Command(os.Args[0], "-test.run=^TestDumpableProcessHelper$")
    env := append([]string{}, os.Environ()...)
    env = append(env,
        "TUNNEL_DUMPABLE_PROBE=1",
        "TUNNEL_DUMPABLE_SECRET=probe-value",
    )
    if hardened {
        env = append(env, "TUNNEL_DUMPABLE_HARDEN=1")
    } else {
        env = append(env, "TUNNEL_DUMPABLE_HARDEN=0")
    }
    cmd.Env = env
    if err := cmd.Start(); err != nil {
        t.Fatalf("start probe process: %v", err)
    }
    t.Cleanup(func() {
        _ = cmd.Process.Kill()
        _ = cmd.Wait()
    })
    time.Sleep(150 * time.Millisecond)
    return cmd
}

func TestHardenedRuntimeHidesEnvironmentFromSameUidProcess(t *testing.T) {
    normal := startDumpableProbe(t, false)
    normalBytes, err := os.ReadFile("/proc/" + itoa(normal.Process.Pid) + "/environ")
    if err != nil {
        t.Fatalf("control process environment should be readable in this test: %v", err)
    }
    if !bytes.Contains(normalBytes, []byte("TUNNEL_DUMPABLE_SECRET=probe-value")) {
        t.Fatal("control process environment probe was not visible")
    }

    hardened := startDumpableProbe(t, true)
    _, err = os.ReadFile("/proc/" + itoa(hardened.Process.Pid) + "/environ")
    if err == nil {
        t.Fatal("same-UID process could read hardened runtime environment")
    }
    if !errors.Is(err, syscall.EACCES) && !errors.Is(err, syscall.EPERM) && !strings.Contains(strings.ToLower(err.Error()), "permission denied") {
        t.Fatalf("expected procfs permission denial, got: %v", err)
    }
}

func itoa(value int) string {
    return strconv.Itoa(value)
}
