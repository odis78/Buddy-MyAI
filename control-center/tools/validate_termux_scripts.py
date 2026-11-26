#!/usr/bin/env python3
"""Compile and exercise embedded Termux scripts against isolated temporary fixtures."""
import fcntl
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
names = ("BUDDY_MCP_AUTH_PROBE_SCRIPT", "MCP_ENDPOINT_AUDIT_SCRIPT")
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
mcp_audit = scripts["MCP_ENDPOINT_AUDIT_SCRIPT"]

assert "BUDDY_AUTH_HEADER" in auth and "BUDDY_TOKEN" in auth
assert "ProxyHandler({})" in auth and "NoRedirect" in auth
assert "print(auth)" not in auth
assert auth.index("if not process_identity(pid):") < auth.index('raw_env = open("/proc/%d/environ"')
assert auth.count("if not process_identity(pid):") >= 2
assert "normalize(executable) == normalize(BINARY)" in auth
assert "timeout=3" in auth
assert "while page_count < 4:" in auth

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
assert 'getattr(os, "pidfd_open", None)' in profile_control
assert 'getattr(signal, "pidfd_send_signal", None)' in profile_control
assert "syscall(434" in profile_control and "syscall(424" in profile_control
assert "os.kill(pid, signal.SIGTERM)" not in profile_control
assert "existing_tunnel_manager_busy" in profile_control
assert "jiti-profile-control.lock" in profile_control
assert 'local_ready = health_ok(profile)' in profile_control
assert 'full = full_health_report(profile)' in profile_control
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

    log_file = state / "bridge-013-recovery.log"
    log_file.write_text(
        "Authorization: Bearer fixture-log-bearer-secret\\n"
        "CONTROL_PLANE_API_KEY=fixture-log-api-secret\\n"
        "GET /mcp?token=fixture-log-query-secret\\n",
        encoding="utf-8"
    )
    log_file.chmod(0o600)
    logs = subprocess.run(
        [sys.executable, "-c", profile_control, "logs", "bridge"],
        env={**os.environ, "HOME": str(home)}, text=True,
        capture_output=True, timeout=15
    )
    logs_report = report(logs)
    assert logs.returncode == 0 and logs_report["result"] == "logs"
    assert "[REDACTED]" in logs_report["text"]
    assert "fixture-log-bearer-secret" not in logs_report["text"]
    assert "fixture-log-api-secret" not in logs_report["text"]
    assert "fixture-log-query-secret" not in logs_report["text"]

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
        const char *forced_status = getenv("JITI_FIXTURE_HTTP_STATUS");
        if (forced_status) {
            printf("{\"result\":\"error\",\"http_status\":%s,\"retry_after_ms\":120000}\n", forced_status);
            return 1;
        }
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

    def profile_command(mode, extra_env=None):
        return subprocess.run(
            [sys.executable, "-c", profile_control, mode, "bridge"],
            env={**os.environ, "HOME": str(home), **(extra_env or {})}, text=True,
            capture_output=True, timeout=40
        )

    started = profile_command("start")
    started_report = report(started)
    assert started.returncode == 0 and started_report["result"] == "started_ready", started_report
    assert started_report["pid"] > 0
    assert "fixture-lifecycle-secret" not in started.stdout + started.stderr

    repeated_start = profile_command("start")
    repeated_start_report = report(repeated_start)
    assert repeated_start.returncode == 0
    assert repeated_start_report["result"] == "already_running"
    assert repeated_start_report["pid"] == started_report["pid"]

    running = profile_command("status")
    assert report(running)["result"] == "already_running"

    # Local readyz can be healthy while the control plane rejects or throttles
    # the process. Preflight must retain the full-health HTTP classification.
    for http_status in (401, 403, 429, 503):
        probe = profile_command("preflight", {"JITI_FIXTURE_HTTP_STATUS": str(http_status)})
        probe_report = report(probe)
        assert probe_report["result"] == "process_running_not_ready", probe_report
        assert probe_report["http_status"] == http_status, probe_report
        assert probe_report["retry_after_ms"] == 120000, probe_report

    # A second lifecycle command must fail closed while another caller owns
    # the per-profile manager lock; the running process must remain untouched.
    lock_path = state / "jiti-profile-control.lock"
    with lock_path.open("r+") as lock_handle:
        fcntl.flock(lock_handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        contended = profile_command("restart")
        assert contended.returncode == 3
        assert report(contended)["result"] == "operation_busy"
        fcntl.flock(lock_handle.fileno(), fcntl.LOCK_UN)
    after_contention = profile_command("status")
    assert report(after_contention)["pid"] == started_report["pid"]

    restarted = profile_command("restart")
    restarted_report = report(restarted)
    assert restarted.returncode == 0 and restarted_report["result"] == "started_ready", restarted_report
    assert restarted_report["pid"] > 0

    stopped = profile_command("stop")
    stopped_report = report(stopped)
    assert stopped.returncode == 0 and stopped_report["result"] == "stopped", stopped_report
    assert not (state / "bridge-013.pid").exists()
    assert not (state / "bridge-013.health.url").exists()

    repeated_stop = profile_command("stop")
    assert repeated_stop.returncode == 0
    assert report(repeated_stop)["result"] == "already_stopped"

    final_status = profile_command("status")
    assert report(final_status)["result"] == "stopped"
    assert (state / "jiti-profile-control.lock").exists()

    # The existing tunnels manager owns the shared runtime while active.
    manager = subprocess.Popen(
        [sys.executable, "-c", "import time; time.sleep(30)", str(home / "bin" / "tunnels")],
        env={**os.environ, "HOME": str(home)},
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL
    )
    try:
        manager_busy = profile_command("start")
        manager_report = report(manager_busy)
        assert manager_busy.returncode == 3
        assert manager_report["result"] == "blocked"
        assert manager_report["reason"] == "existing_tunnel_manager_busy"
        assert report(profile_command("status"))["result"] == "stopped"
    finally:
        manager.terminate()
        manager.wait(timeout=10)

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

with tempfile.TemporaryDirectory(prefix="jiti-buddy-mcp-auth-probe-") as root:
    home = Path(root)
    binary = home / "tunnel-client-android-build-v0.0.19" / "bin" / "tunnel-client-android-arm64"
    binary.parent.mkdir(parents=True)
    # PID 1 cannot match this isolated fixture's expected Tunnel Client binary/config.
    auth_probe = report(execute(auth, home, "1"))
    assert auth_probe["result"] == "pid_identity_mismatch", auth_probe
    assert "fixture-buddy-token" not in json.dumps(auth_probe)


print("Embedded Termux Python scripts compile; isolated REA lifecycle and Buddy read-only MCP tests passed")
