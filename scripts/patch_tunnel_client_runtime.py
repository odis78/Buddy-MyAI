#!/usr/bin/env python3
"""Apply the audited Buddy runtime hardening patch to the pinned Tunnel Client checkout."""
from pathlib import Path
import sys

root = Path(sys.argv[1]).resolve()
target = root / "cmd/client-runtime-cloudflared"
main = target / "main.go"
if not main.is_file():
    raise SystemExit("Tunnel Client cloudflared runtime entrypoint not found")

source = main.read_text(encoding="utf-8")
needle = "func main() {\n"
injected = """func main() {
    if err := hardenProcess(); err != nil {
        fmt.Fprintln(os.Stderr, "runtime process hardening failed")
        os.Exit(1)
    }
"""
if "hardenProcess()" not in source:
    if source.count(needle) != 1:
        raise SystemExit("unexpected cloudflared runtime entrypoint shape; refusing to patch")
    source = source.replace(needle, injected, 1)
    main.write_text(source, encoding="utf-8")

bundle = Path(__file__).resolve().parent / "tunnel-client-runtime"
for name in ("dumpable_linux.go", "dumpable_other.go", "dumpable_linux_test.go"):
    src = bundle / name
    if not src.is_file():
        raise SystemExit("missing hardening source: " + name)
    dst = target / name
    content = src.read_text(encoding="utf-8")
    if dst.exists() and dst.read_text(encoding="utf-8") != content:
        raise SystemExit("refusing to overwrite unexpected source: " + str(dst))
    dst.write_text(content, encoding="utf-8")
fifo_test = Path(__file__).resolve().parent / "tunnel-client-runtime" / "file_reference_fifo_test.go"
fifo_target = root / "pkg/runtimeconfig" / "buddy_file_reference_fifo_test.go"
if not fifo_test.is_file():
    raise SystemExit("missing FIFO reference test")
fifo_content = fifo_test.read_text(encoding="utf-8")
if fifo_target.exists() and fifo_target.read_text(encoding="utf-8") != fifo_content:
    raise SystemExit("refusing to overwrite unexpected FIFO test")
fifo_target.write_text(fifo_content, encoding="utf-8")
print("Applied PR_SET_DUMPABLE hardening patch")
