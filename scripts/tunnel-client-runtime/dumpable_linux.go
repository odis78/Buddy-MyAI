//go:build linux
// +build linux

package main

import "syscall"

const prSetDumpable = 4

// hardenProcess prevents same-UID sibling processes from reading this runtime's
// /proc/<pid>/environ, cmdline and memory through ptrace-gated procfs access.
// PR_SET_DUMPABLE=0 blocks same-UID procfs reads of this process.
func hardenProcess() error {
    _, _, errno := syscall.Syscall(syscall.SYS_PRCTL, uintptr(prSetDumpable), uintptr(0), uintptr(0))
    if errno != 0 {
        return errno
    }
    return nil
}
