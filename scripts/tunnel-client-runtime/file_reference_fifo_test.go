//go:build linux
// +build linux

package runtimeconfig

import (
    "os"
    "path/filepath"
    "syscall"
    "testing"
)

func TestBuddyFileSecretReferenceReadsOneShotFIFO(t *testing.T) {
    path := filepath.Join(t.TempDir(), "secret.fifo")
    if err := syscall.Mkfifo(path, 0600); err != nil {
        t.Fatalf("create fifo: %v", err)
    }
    writerDone := make(chan error, 1)
    go func() {
        file, err := os.OpenFile(path, os.O_WRONLY, 0600)
        if err != nil {
            writerDone <- err
            return
        }
        _, writeErr := file.Write([]byte("fifo-probe-secret"))
        closeErr := file.Close()
        if writeErr != nil {
            writerDone <- writeErr
            return
        }
        writerDone <- closeErr
    }()

    value, err := getControlPlaneAPIKey("file:"+path, func(string) (string, bool) {
        return "", false
    })
    if err != nil {
        t.Fatalf("read FIFO secret reference: %v", err)
    }
    if value != "fifo-probe-secret" {
        t.Fatalf("unexpected FIFO value: %q", value)
    }
    if err := <-writerDone; err != nil {
        t.Fatalf("write FIFO secret: %v", err)
    }
}
