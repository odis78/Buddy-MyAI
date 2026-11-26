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

PROFILE_SOURCE = Path("control-center/app/src/main/java/app/jiti/controlcenter/termux/ProfileControlScript.java")
profile_source = PROFILE_SOURCE.read_text(encoding="utf-8")
profile_match = re.search(r'SCRIPT = """\n(.*?)\n""";', profile_source, re.S)
if not profile_match:
    raise SystemExit("PROFILE_CONTROL_SCRIPT text block not found")
profile_control = profile_match.group(1).replace("\\\\", "\\")
compile(profile_control, "PROFILE_CONTROL_SCRIPT.py", "exec")

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
assert 'TOKEN_FILE = HOME + "/.config/tunnel-client-buddy/buddy.token"' in launch
assert "buddy_token_file_symlink_not_allowed" in launch
assert "insecure_buddy_token_file_permissions" in launch
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

# Profile lifecycle script is allowlisted, profile-scoped, and fails closed.
assert "signal.SIGTERM" in profile_control
assert "signal.SIGKILL" not in profile_control
assert "existing_tunnel_manager_busy" in profile_control
assert "jiti-profile-control.lock" in profile_control
assert "buddy-myai.yaml" not in profile_control
assert "rea-android.yaml" in profile_control
assert "bridge-013.yaml" in profile_control
assert "droidmcp-filesystem.yaml" in profile_control

with tempfile.TemporaryDirectory(prefix="jiti-profile-control-") as root:
    home = Path(root)
    unknown = subprocess.run(
        [sys.executable, "-c", profile_control, "status", "buddy"],
        env={**os.environ, "HOME": str(home)}, text=True,
        capture_output=True, timeout=15
    )
    assert unknown.returncode == 2
    assert report(unknown)["reason"] == "unknown_profile"

    invalid_mode = subprocess.run(
        [sys.executable, "-c", profile_control, "shell", "bridge"],
        env={**os.environ, "HOME": str(home)}, text=True,
        capture_output=True, timeout=15
    )
    assert invalid_mode.returncode == 2
    assert report(invalid_mode)["reason"] == "invalid_mode"

    state = home / ".local" / "state" / "tunnels"
    state.mkdir(parents=True)
    binary = home / "tunnel-client-android-build-v0.0.19" / "bin" / "tunnel-client-android-arm64"
    binary.parent.mkdir(parents=True)
    binary.write_text("#!/bin/sh\\nexit 0\\n", encoding="utf-8")
    binary.chmod(0o700)
    profile = home / ".config" / "tunnel-client-bridge-013" / "bridge-013.yaml"
    profile.parent.mkdir(parents=True)
    profile.write_text("api_key: env:CONTROL_PLANE_API_KEY\\n", encoding="utf-8")
    profile.chmod(0o600)
    env_file = home / ".config" / "termux-bridge" / "runtime.env"
    env_file.parent.mkdir(parents=True)
    env_file.write_text("CONTROL_PLANE_API_KEY=fixture-profile-secret\\n", encoding="utf-8")
    env_file.chmod(0o600)

    preflight = subprocess.run(
        [sys.executable, "-c", profile_control, "preflight", "bridge"],
        env={**os.environ, "HOME": str(home)}, text=True,
        capture_output=True, timeout=15
    )
    assert preflight.returncode == 0
    assert report(preflight)["result"] == "ready_to_start"
    assert "fixture-profile-secret" not in preflight.stdout
    assert "fixture-profile-secret" not in preflight.stderr

    env_file.chmod(0o644)
    insecure = subprocess.run(
        [sys.executable, "-c", profile_control, "preflight", "bridge"],
        env={**os.environ, "HOME": str(home)}, text=True,
        capture_output=True, timeout=15
    )
    assert report(insecure)["reason"] == "insecure_env_file_permissions"

# Exercise the complete start/status/restart/stop path against a disposable local C process.
with tempfile.TemporaryDirectory(prefix="jiti-profile-control-lifecycle-") as root:
    home = Path(root)
    state = home / ".local" / "state" / "tunnels"
    state.mkdir(parents=True)
    binary = home / "tunnel-client-android-build-v0.0.19" / "bin" / "tunnel-client-android-arm64"
    binary.parent.mkdir(parents=True)
    profile = home / ".config" / "tunnel-client-bridge-013" / "bridge-013.yaml"
    profile.parent.mkdir(parents=True)
    profile.write_text("api_key: env:CONTROL_PLANE_API_KEY\n", encoding="utf-8")
    profile.chmod(0o600)
    env_file = home / ".config" / "termux-bridge" / "runtime.env"
    env_file.parent.mkdir(parents=True)
    env_file.write_text("CONTROL_PLANE_API_KEY=fixture-lifecycle-secret\n", encoding="utf-8")
    env_file.chmod(0o600)

    c_source = r"""
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
int main(int argc, char **argv) {
    if (argc > 1 && strcmp(argv[1], "health") == 0) {
        const char *health_pid = NULL, *health_url = NULL;
        for (int i = 2; i + 1 < argc; i++) {
            if (strcmp(argv[i], "--pid-file") == 0) health_pid = argv[i + 1];
            if (strcmp(argv[i], "--url-file") == 0) health_url = argv[i + 1];
        }
        if (!health_pid || !health_url || access(health_pid, R_OK) != 0 || access(health_url, R_OK) != 0) return 20;
        printf("{\"result\":\"ok\"}\n");
        return 0;
    }
    const char *pidfile = NULL, *urlfile = NULL;
    for (int i = 1; i + 1 < argc; i++) {
        if (strcmp(argv[i], "--pid.file") == 0) pidfile = argv[i + 1];
        if (strcmp(argv[i], "--health.url-file") == 0) urlfile = argv[i + 1];
    }
    if (!pidfile || !urlfile) return 10;
    FILE *f = fopen(pidfile, "w");
    if (!f) return 11;
    fprintf(f, "%d\n", getpid());
    fclose(f);

    int server = socket(AF_INET, SOCK_STREAM, 0);
    if (server < 0) return 12;
    int reuse = 1;
    setsockopt(server, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));
    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    addr.sin_port = 0;
    if (bind(server, (struct sockaddr *)&addr, sizeof(addr)) != 0) return 13;
    if (listen(server, 8) != 0) return 14;
    socklen_t len = sizeof(addr);
    if (getsockname(server, (struct sockaddr *)&addr, &len) != 0) return 15;

    f = fopen(urlfile, "w");
    if (!f) return 16;
    fprintf(f, "http://127.0.0.1:%u\n", ntohs(addr.sin_port));
    fclose(f);

    for (;;) {
        int client = accept(server, NULL, NULL);
        if (client < 0) continue;
        char request[1024] = {0};
        read(client, request, sizeof(request) - 1);
        const char *ok = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok";
        const char *missing = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
        const char *response = strstr(request, "/readyz") ? ok : missing;
        write(client, response, strlen(response));
        close(client);
    }
}
"""
    compiled = subprocess.run(
        ["cc", "-x", "c", "-o", str(binary), "-"],
        input=c_source, text=True, capture_output=True, timeout=30
    )
    assert compiled.returncode == 0, compiled.stderr
    binary.chmod(0o700)

    def profile_command(mode):
        return subprocess.run(
            [sys.executable, "-c", profile_control, mode, "bridge"],
            env={**os.environ, "HOME": str(home)}, text=True,
            capture_output=True, timeout=40
        )

    started = profile_command("start")
    started_report = report(started)
    assert started.returncode == 0 and started_report["result"] == "started_ready", started_report
    assert started_report["pid"] > 0
    assert "fixture-lifecycle-secret" not in started.stdout + started.stderr

    running = profile_command("status")
    assert report(running)["result"] == "already_running"

    restarted = profile_command("restart")
    restarted_report = report(restarted)
    assert restarted.returncode == 0 and restarted_report["result"] == "started_ready", restarted_report
    assert restarted_report["pid"] > 0

    stopped = profile_command("stop")
    stopped_report = report(stopped)
    assert stopped.returncode == 0 and stopped_report["result"] == "stopped", stopped_report
    assert not (state / "bridge-013.pid").exists()
    assert not (state / "bridge-013.health.url").exists()

    final_status = profile_command("status")
    assert report(final_status)["result"] == "stopped"
    assert (state / "jiti-profile-control.lock").exists()

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
    token_file = profile_dir / "buddy.token"
    pid_file = profile_dir / "buddy-myai.pid"

    profile_dir.mkdir(parents=True)
    env_dir.mkdir(parents=True)
    binary.parent.mkdir(parents=True)
    binary.write_text("#!/bin/sh\nif [ \"$BUDDY_AUTH_HEADER\" = \"Bearer fixture-buddy-token-file\" ]; then touch \"$HOME/auth-env-ok\"; fi\nexit 0\n", encoding="utf-8")
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
    assert missing["result"] == "blocked" and missing["reason"] == "missing_buddy_auth_source", missing

    # A dedicated token file is accepted only as a regular, private file.
    token_file.write_text("fixture-buddy-token-file\n", encoding="utf-8")
    token_file.chmod(0o600)
    token_fallback = report(execute(launch, home, "preflight"))
    assert token_fallback["result"] == "ready_to_start", token_fallback
    assert "fixture-buddy-token-file" not in json.dumps(token_fallback)
    token_file.chmod(0o644)
    insecure_token = report(execute(launch, home, "preflight"))
    assert insecure_token["reason"] == "insecure_buddy_token_file_permissions", insecure_token
    token_file.chmod(0o600)
    external_token = home / "external-buddy.token"
    external_token.write_text("fixture-buddy-token-file\n", encoding="utf-8")
    external_token.chmod(0o600)
    token_file.unlink()
    token_file.symlink_to(external_token)
    symlink_token = report(execute(launch, home, "preflight"))
    assert symlink_token["reason"] == "buddy_token_file_symlink_not_allowed", symlink_token
    token_file.unlink()

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
        "CONTROL_PLANE_API_KEY=fixture-control-plane-key\n",
        encoding="utf-8"
    )
    env_file.chmod(0o600)
    token_file.write_text("fixture-buddy-token-file\n", encoding="utf-8")
    token_file.chmod(0o600)
    started = execute(launch, home, "start")
    assert started.returncode == 0, (started.returncode, started.stdout, started.stderr)
    start_status = json.loads(status_file.read_text(encoding="utf-8"))
    assert start_status["result"] == "launch_requested", start_status
    assert (home / "auth-env-ok").exists(), "token file auth was not passed to Tunnel Client"

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
