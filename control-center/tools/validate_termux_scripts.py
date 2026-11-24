#!/usr/bin/env python3
"""Compile and exercise embedded Termux scripts against isolated temporary fixtures."""
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

SOURCE = Path("control-center/app/src/main/java/app/jiti/controlcenter/termux/TermuxCommandSpec.java")
source = SOURCE.read_text(encoding="utf-8")
names = ("BUDDY_MCP_AUTH_PROBE_SCRIPT", "BUDDY_LAUNCH_SCRIPT", "BUDDY_STOP_SCRIPT", "MCP_ENDPOINT_AUDIT_SCRIPT")
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
mcp_audit = scripts["MCP_ENDPOINT_AUDIT_SCRIPT"]

assert "BUDDY_AUTH_HEADER" in auth and "BUDDY_TOKEN" in auth
assert "ProxyHandler({})" in auth and "NoRedirect" in auth
assert "print(auth)" not in auth
assert auth.index("if not process_identity(pid):") < auth.index('raw_env = open("/proc/%d/environ"')
assert auth.count("if not process_identity(pid):") >= 2
assert 'args[1] != "run"' in auth and 'args[1] != "run"' in launch and 'args[1] != "run"' in stop
assert "normalize(executable) == normalize(BINARY)" in auth
assert "timeout=3" in auth
assert "while page_count < 4:" in auth
assert "normalize(executable) == normalize(BINARY)" in launch
assert "normalize(executable) == normalize(BINARY)" in stop
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

# Endpoint discovery must expose useful local addresses while redacting auth values.
with tempfile.TemporaryDirectory(prefix="jiti-mcp-endpoint-audit-") as root:
    home = Path(root)
    bridge = home / ".config" / "tunnel-client-bridge-013" / "bridge-013.yaml"
    bridge.parent.mkdir(parents=True)
    bridge.write_text(
        "mcp:\n"
        "  server-url: http://127.0.0.1:8765/mcp?token=fixture-url-secret\n"
        "  command: mcp-server --token fixture-cli-secret\n"
        "  mcp.extra_headers: {Authorization: Bearer fixture-header-secret}\n"
        "  api-key: fixture-admin-secret\n",
        encoding="utf-8"
    )
    audited = execute(mcp_audit, home)
    assert audited.returncode == 0, (audited.returncode, audited.stdout, audited.stderr)
    assert "http://127.0.0.1:8765/mcp?token=[REDACTED]" in audited.stdout, audited.stdout
    assert "fixture-url-secret" not in audited.stdout
    assert "fixture-cli-secret" not in audited.stdout
    assert "fixture-header-secret" not in audited.stdout
    assert "fixture-admin-secret" not in audited.stdout
    assert "[droidmcp] config=missing" in audited.stdout
    assert "[rea-android] config=missing" in audited.stdout
    assert "ENDPOINT_FIELDS=" in audited.stdout

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

    # Symlinked environment files are rejected even if their target is mode 0600.
    external_env = home / "external-runtime.env"
    external_env.write_text(
        "CONTROL_PLANE_API_KEY=fixture-control-plane-key\nBUDDY_TOKEN=fixture-buddy-token\n",
        encoding="utf-8"
    )
    external_env.chmod(0o600)
    env_file.unlink()
    env_file.symlink_to(external_env)
    symlinked = report(execute(launch, home, "preflight"))
    assert symlinked["reason"] == "env_file_symlink_not_allowed", symlinked
    env_file.unlink()

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

    env_file.write_text(
        "CONTROL_PLANE_API_KEY=fixture-control-plane-key\nBUDDY_TOKEN=$BUDDY_TOKEN\n",
        encoding="utf-8"
    )
    env_file.chmod(0o600)
    variable_expansion = report(execute(launch, home, "preflight"))
    assert variable_expansion["reason"] == "unsupported_env_syntax", variable_expansion

    # An explicitly named Authorization header is normalized to its value.
    env_file.write_text(
        "CONTROL_PLANE_API_KEY=fixture-control-plane-key\n"
        "BUDDY_AUTH_HEADER=Authorization: Bearer fixture-buddy-token\n",
        encoding="utf-8"
    )
    env_file.chmod(0o600)
    header_preflight = report(execute(launch, home, "preflight"))
    assert header_preflight["result"] == "ready_to_start", header_preflight
    assert "fixture-buddy-token" not in json.dumps(header_preflight)

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

    # Exercise the positive identity path with an isolated copy of the
    # current Python interpreter placed at the exact expected executable path.
    # Its argv includes the exact Buddy config argument, so it is a safe fixture.
    helper_source = (
        "#include <unistd.h>" + chr(10)
        + "int main(int argc, char **argv) { (void)argc; (void)argv; "
        + "for (;;) sleep(1); return 0; }" + chr(10)
    )
    compiled = subprocess.run(
        ["cc", "-x", "c", "-o", str(binary), "-"],
        input=helper_source, text=True, capture_output=True, timeout=30
    )
    assert compiled.returncode == 0, compiled.stderr
    binary.chmod(0o700)
    child = subprocess.Popen(
        [str(binary), "run", "--config", str(config)],
        env={**os.environ, "HOME": str(home)},
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL
    )
    try:
        pid_file.write_text(str(child.pid), encoding="utf-8")
        status_file.write_text(sentinel, encoding="utf-8")
        ready_stop = report(execute(stop, home, "preflight"))
        assert ready_stop["result"] == "ready_to_stop", ready_stop
        assert ready_stop["pid"] == child.pid
        assert status_file.read_text(encoding="utf-8") == sentinel

        stopped = execute(stop, home, "stop")
        stopped_report = report(stopped)
        assert stopped.returncode == 0 and stopped_report["result"] == "stopped", stopped_report
        child.wait(timeout=5)
        persisted = json.loads(status_file.read_text(encoding="utf-8"))
        assert persisted["result"] == "stopped", persisted
    finally:
        if child.poll() is None:
            child.terminate()
            child.wait(timeout=5)

    # The MCP probe independently checks PID identity before reading environ.
    auth_probe = report(execute(auth, home, "1"))
    assert auth_probe["result"] == "pid_identity_mismatch", auth_probe
    assert "fixture-buddy-token" not in json.dumps(auth_probe)

print("Embedded Termux Python scripts compile; isolated launch/stop/MCP safety tests passed")
