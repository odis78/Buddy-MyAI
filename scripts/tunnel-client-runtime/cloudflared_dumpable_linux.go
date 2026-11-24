//go:build linux
// +build linux

package main

import "syscall"

const cloudflaredPrSetDumpable = 4

// PR_SET_DUMPABLE=0 prevents same-UID sibling processes from reading the managed tunnel token.
func hardenCloudflaredProcess() error {
    _, _, errno := syscall.Syscall(syscall.SYS_PRCTL, uintptr(cloudflaredPrSetDumpable), uintptr(0), uintptr(0))
    if errno != 0 { return errno }
    return nil
}
