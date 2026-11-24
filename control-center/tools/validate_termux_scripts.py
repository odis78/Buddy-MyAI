#!/usr/bin/env python3
"""Compile and exercise embedded Termux scripts against isolated temporary fixtures."""
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

SOURCE = Path("control-center/app/src/main/java/app/jiti/controlcenter/termux/TermuxCommandSpec.java")
source = SOURCE.read_text(encoding="utf-8")
names = ("BUDDY_MCP_AUTH_PROBE_SCRIPT", "BUDDY_LAUNCH_SCRIPT", "BUDDY_STOP_SCRIPT")
scripts = {}
for name in names:
    match = re.search(rf'{name} = """\n(.*?)\n""";', source, re.S)
    if not match:
        raise SystemExit(f"{name} text block not found")
    # Match Java text-block escaping before handing the text to Python.
    script = match.group(1).replace("\\\\", "\\")
    compile(script, name + ".py", "exec")
    scripts[name] = script

auth = scripts["BUDDY_MCP_AUTH_PROBE_SCRIPT"]
launch = scripts["BUDDY_LAUNCH_SCRIPT"]
stop = scripts["BUDDY_STOP_SCRIPT"]

assert "BUDDY_AUTH_HEADER" in auth and "BUDDY_TOKEN" in auth
assert "ProxyHandler({})" in auth and "NoRedirect" in auth
assert "print(auth)" not in auth
assert auth.index("if not process_identity(pid):") < auth.index('raw_env = open("/proc/%d/environ"')
assert auth.count("if not process_identity(pid):") >= 2
assert "CONTROL_PLANE_API_KEY" in launch and "BUDDY_TOKEN" in launch
assert "EXPECTED_TUNNEL_ID" in launch
assert "signal.SIGTERM" in stop and "signal.SIGKILL" not in stop
assert 'if mode == "stop":\n        write_status(value)' in stop

def execute(script, home, *args):
    env = os.environ.copy()
    env["HOME"] = str(home)
    return subprocess.run(
        [sys.executable, "-c", script, *args],
        env=env, text=True, capture_output=True, timeout=15
    )

def report(process):
    try:
        return json.loads(process.stdout.strip().splitlines()[-1])
    except Exception as exc:
        raise AssertionError(
            f"script did not emit one JSON report: rc={process.returncode}, "
            f"stdout={process.stdout!r}, stderr={process.stderr!r}"
        ) from exc

with tempfile.TemporaryDirectory(prefix="jiti-termux-script-test-") as root:
    home = Path(root)
    profile_dir = home / ".config" / "tunnel-client-buddy"
    env_dir = home / ".config" / "termux-bridge"
    binary = home / "tunnel-client-android-build-v0.0.19" / "bin" / "tunnel-client-android-arm64"
    config = profile_dir / "buddy-myai.yaml"
    env_file = env_dir / "runtime.env"
    status_file = profile_dir / "jiti-launcher.status"
    pid_file = profile_dir / "buddy-myai.pid"

    profile_dir.mkdir(parents=True)
    env_dir.mkdir(parents=True)
    binary.parent.mkdir(parents=True)
    binary.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
    binary.chmod(0o700)
    config.write_text(
        "tunnel_id: tunnel_6b02e7e420948191a1829df4a5373d20\n"
        "api_key: env:CONTROL_PLANE_API_KEY\n"
        "Authorization: env:BUDDY_AUTH_HEADER\n"
        "discovery_Authorization: env:BUDDY_AUTH_HEADER\n",
        encoding="utf-8"
    )
    env_file.write_text(
        "CONTROL_PLANE_API_KEY=fixture-control-plane-key\n"
        "BUDDY_TOKEN=fixture-buddy-token\n",
        encoding="utf-8"
    )
    env_file.chmod(0o600)

    # A clean preflight must be read-only and must accept only the fixture's
    # expected config, binary, and restrictive environment file.
    checked = execute(launch, home, "preflight")
    checked_report = report(checked)
    assert checked.returncode == 0 and checked_report["result"] == "ready_to_start", checked_report
    assert not status_file.exists(), "launch preflight wrote a status file"
    assert "fixture-control-plane-key" not in checked.stdout
    assert "fixture-buddy-token" not in checked.stdout

    # Missing auth source must fail closed without exposing any secret value.
    env_file.write_text("CONTROL_PLANE_API_KEY=fixture-control-plane-key\n", encoding="utf-8")
    env_file.chmod(0o600)
    missing = report(execute(launch, home, "preflight"))
    assert missing["result"] == "blocked" and missing["reason"] == "missing_required_env_keys", missing

    # Group/other-readable env files are rejected.
    env_file.write_text(
        "CONTROL_PLANE_API_KEY=fixture-control-plane-key\nBUDDY_TOKEN=fixture-buddy-token\n",
        encoding="utf-8"
    )
    env_file.chmod(0o644)
    insecure = report(execute(launch, home, "preflight"))
    assert insecure["reason"] == "insecure_env_file_permissions", insecure

    # Shell expansion syntax is data, never executed.
    marker = home / "should-not-exist"
    env_file.write_text(
        "CONTROL_PLANE_API_KEY=fixture-control-plane-key\n"
        f"BUDDY_TOKEN=$(touch {marker})\n",
        encoding="utf-8"
    )
    env_file.chmod(0o600)
    expansion = report(execute(launch, home, "preflight"))
    assert expansion["reason"] == "unsupported_env_syntax", expansion
    assert not marker.exists(), "environment parser executed shell syntax"

    # The launch mode uses execve on the fixed fake binary and records a
    # non-secret request state. It does not touch any real Tunnel Client.
    env_file.write_text(
        "CONTROL_PLANE_API_KEY=fixture-control-plane-key\nBUDDY_TOKEN=fixture-buddy-token\n",
        encoding="utf-8"
    )
    env_file.chmod(0o600)
    started = execute(launch, home, "start")
    assert started.returncode == 0, (started.returncode, started.stdout, started.stderr)
    start_status = json.loads(status_file.read_text(encoding="utf-8"))
    assert start_status["result"] == "launch_requested", start_status

    # Stop preflight must not change a pre-existing status file for any
    # no-process, stale-PID, or mismatched-process outcome.
    sentinel = "preflight-must-not-write"
    status_file.write_text(sentinel, encoding="utf-8")
    no_pid = report(execute(stop, home, "preflight"))
    assert no_pid["result"] == "already_stopped", no_pid
    assert status_file.read_text(encoding="utf-8") == sentinel

    pid_file.write_text("2147483647", encoding="utf-8")
    stale = report(execute(stop, home, "preflight"))
    assert stale["result"] == "stale_pid_file", stale
    assert status_file.read_text(encoding="utf-8") == sentinel

    pid_file.write_text("1", encoding="utf-8")
    mismatch = report(execute(stop, home, "preflight"))
    assert mismatch["result"] == "pid_identity_mismatch", mismatch
    assert status_file.read_text(encoding="utf-8") == sentinel

    # The mutating stop mode may record a result, but still must not signal a
    # process whose executable/config identity does not match.
    mismatch_stop = execute(stop, home, "stop")
    mismatch_stop_report = report(mismatch_stop)
    assert mismatch_stop.returncode == 2
    assert mismatch_stop_report["result"] == "pid_identity_mismatch"
    persisted = json.loads(status_file.read_text(encoding="utf-8"))
    assert persisted["result"] == "pid_identity_mismatch"

    # The MCP probe independently checks PID identity before reading environ.
    auth_probe = report(execute(auth, home, "1"))
    assert auth_probe["result"] == "pid_identity_mismatch", auth_probe
    assert "fixture-buddy-token" not in json.dumps(auth_probe)

print("Embedded Termux Python scripts compile; isolated launch/stop/MCP safety tests passed")
