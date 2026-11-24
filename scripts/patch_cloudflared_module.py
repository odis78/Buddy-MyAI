#!/usr/bin/env python3
"""Patch the exact pinned cloudflared module to protect its managed tunnel token."""
from pathlib import Path
import sys

root = Path(sys.argv[1]).resolve()
main = root / "cmd/cloudflared/main.go"
if not main.is_file():
    raise SystemExit("cloudflared main.go not found")
source = main.read_text(encoding="utf-8")
needle = "func main() {\n"
replacement = """func main() {
    if err := hardenCloudflaredProcess(); err != nil {
        fmt.Fprintln(os.Stderr, "cloudflared process hardening failed")
        os.Exit(1)
    }
"""
if "hardenCloudflaredProcess()" not in source:
    if source.count(needle) != 1:
        raise SystemExit("unexpected cloudflared main entrypoint; refusing to patch")
    source = source.replace(needle, replacement, 1)
    main.write_text(source, encoding="utf-8")

bundle = Path(__file__).resolve().parent / "tunnel-client-runtime"
for name in ("cloudflared_dumpable_linux.go", "cloudflared_dumpable_other.go", "cloudflared_dumpable_linux_test.go"):
    src = bundle / name
    if not src.is_file():
        raise SystemExit("missing cloudflared hardening source: " + name)
    dst = root / "cmd/cloudflared" / name.replace("cloudflared_", "")
    content = src.read_text(encoding="utf-8")
    if dst.exists() and dst.read_text(encoding="utf-8") != content:
        raise SystemExit("refusing to overwrite unexpected cloudflared source: " + str(dst))
    dst.write_text(content, encoding="utf-8")
print("Applied cloudflared PR_SET_DUMPABLE hardening patch")
