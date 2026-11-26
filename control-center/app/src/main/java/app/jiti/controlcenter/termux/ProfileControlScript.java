package app.jiti.controlcenter.termux;

/**
 * Fixed, profile-scoped Tunnel Client lifecycle contract.
 *
 * This script is never invoked with a free-form path or shell command. It
 * supports only the three existing REA profiles and never touches Buddy.
 */
public final class ProfileControlScript {
    private ProfileControlScript() {}

    public static final String SCRIPT = """
import fcntl
import glob
import json
import os
import signal
import stat
import subprocess
import sys
import time
import urllib.error
import urllib.request
from urllib.parse import urlsplit

HOME = os.path.realpath(os.path.expanduser("~"))
BINARY = HOME + "/tunnel-client-android-build-v0.0.19/bin/tunnel-client-android-arm64"
STATE = HOME + "/.local/state/tunnels"
LOCK = STATE + "/jiti-profile-control.lock"
PROFILES = {
    "bridge": {
        "name": "REA-Termux-Bridge 0.1.3",
        "flag": "--config",
        "config": HOME + "/.config/tunnel-client-bridge-013/bridge-013.yaml",
        "env": HOME + "/.config/termux-bridge/runtime.env",
        "pid": STATE + "/bridge-013.pid",
        "url": STATE + "/bridge-013.health.url",
        "log": STATE + "/bridge-013-recovery.log",
    },
    "droidmcp": {
        "name": "DroidMCP-Filesystem",
        "flag": "--config",
        "config": HOME + "/droidmcp-stage-v0.4.0/profile-dedicated/droidmcp-filesystem.yaml",
        "env": "",
        "pid": STATE + "/droidmcp.pid",
        "url": STATE + "/droidmcp.health.url",
        "log": STATE + "/droidmcp.log",
    },
    "rea-android": {
        "name": "REA-Termux-Android",
        "flag": "--profile-file",
        "config": HOME + "/.config/rea-tunnel/profile/rea-android.yaml",
        "env": HOME + "/.config/rea-tunnel/runtime.env",
        "pid": STATE + "/rea-android.pid",
        "url": STATE + "/rea-android.health.url",
        "log": STATE + "/rea-android.log",
    },
}

def emit(result, **fields):
    print(json.dumps({"result": result, **fields}, separators=(",", ":")))

def norm(path):
    return path.replace("/data/user/0/com.termux", "/data/data/com.termux")

def regular_file(path, readable=True):
    if os.path.islink(path) or not os.path.isfile(path):
        return False
    return (not readable) or os.access(path, os.R_OK)

def load_environment(profile):
    env = os.environ.copy()
    path = profile["env"]
    if not path:
        return env, "ok"
    if os.path.islink(path) or not os.path.isfile(path):
        return None, "env_file_missing_or_symlink"
    mode = stat.S_IMODE(os.stat(path).st_mode)
    if mode & 0o077:
        return None, "insecure_env_file_permissions"
    values = {}
    try:
        with open(path, "r", encoding="utf-8") as handle:
            for raw in handle:
                line = raw.strip()
                if not line or line.startswith("#"):
                    continue
                if line.startswith("export "):
                    line = line[7:].strip()
                if "=" not in line:
                    return None, "invalid_env_line"
                key, value = line.split("=", 1)
                key = key.strip()
                if key != "CONTROL_PLANE_API_KEY":
                    continue
                value = value.strip()
                if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
                    value = value[1:-1]
                if not value or "$" in value or chr(96) in value or "\\n" in value or "\\r" in value:
                    return None, "unsupported_env_value"
                values[key] = value
    except Exception as exc:
        return None, "env_file_read_" + type(exc).__name__
    if not values.get("CONTROL_PLANE_API_KEY"):
        return None, "missing_control_plane_key"
    env["CONTROL_PLANE_API_KEY"] = values["CONTROL_PLANE_API_KEY"]
    return env, "ok"

def expected_argv(profile):
    return [
        BINARY, "run", profile["flag"], profile["config"],
        "--pid.file", profile["pid"],
        "--health.url-file", profile["url"],
        "--log.file", profile["log"],
    ]

def process_start_time(pid):
    try:
        raw = open("/proc/%d/stat" % pid, "r", encoding="utf-8").read()
        fields = raw[raw.rfind(")") + 2:].split()
        return fields[19] if len(fields) > 19 else None
    except Exception:
        return None

def process_state(pid):
    try:
        raw = open("/proc/%d/stat" % pid, "r", encoding="utf-8").read()
        fields = raw[raw.rfind(")") + 2:].split()
        return fields[0] if fields else None
    except Exception:
        return None

def process_identity(pid, profile):
    try:
        executable = os.readlink("/proc/%d/exe" % pid)
        raw = open("/proc/%d/cmdline" % pid, "rb").read()
    except Exception:
        return False
    args = [part.decode("utf-8", "replace") for part in raw.split(b"\\0") if part]
    expected = expected_argv(profile)
    if executable.endswith(" (deleted)") or norm(executable) != norm(BINARY):
        return False
    if len(args) != len(expected) or norm(args[0]) != norm(expected[0]):
        return False
    return args[1:] == expected[1:]

def find_matching(profile):
    found = []
    for cmdline in glob.glob("/proc/[0-9]*/cmdline"):
        try:
            pid = int(cmdline.split("/")[2])
        except Exception:
            continue
        if process_identity(pid, profile):
            found.append(pid)
    return found

def manager_is_active():
    candidates = (HOME + "/bin/tunnels", HOME + "/bin/rea-start")
    for cmdline in glob.glob("/proc/[0-9]*/cmdline"):
        try:
            raw = open(cmdline, "rb").read()
        except Exception:
            continue
        if any(candidate.encode() in raw for candidate in candidates):
            return True
    return False

def health_ok(profile):
    path = profile["url"]
    if os.path.islink(path) or not os.path.isfile(path):
        return False
    try:
        url = open(path, "r", encoding="utf-8").read().strip().rstrip("/")
        parsed = urlsplit(url)
        if parsed.scheme != "http" or parsed.hostname not in ("127.0.0.1", "localhost"):
            return False
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, req, fp, code, msg, headers, newurl):
                return None
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        with opener.open(url + "/readyz", timeout=3) as response:
            return response.status == 200
    except Exception:
        return False

def full_health_ok(profile):
    env, env_state = load_environment(profile)
    if env is None:
        return False
    argv = [
        BINARY, "health",
        "--url-file", profile["url"],
        "--pid-file", profile["pid"],
        "--require-control-plane-poll", "--json",
    ]
    try:
        result = subprocess.run(
            argv, cwd=HOME, env=env, stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            text=True, timeout=5, check=False
        )
        report = json.loads(result.stdout.strip().splitlines()[-1])
        return result.returncode == 0 and report.get("result") == "ok"
    except Exception:
        return False

def preflight(profile):
    if os.path.islink(BINARY) or not os.path.isfile(BINARY) or not os.access(BINARY, os.X_OK):
        return {"result": "blocked", "reason": "tunnel_binary_missing_or_symlink"}
    if not regular_file(profile["config"]):
        return {"result": "blocked", "reason": "profile_missing_or_symlink"}
    if os.path.islink(STATE) or not os.path.isdir(STATE) or not os.access(STATE, os.W_OK):
        return {"result": "blocked", "reason": "runtime_state_directory_unavailable_or_symlink"}
    try:
        config_mode = stat.S_IMODE(os.stat(profile["config"]).st_mode)
        if config_mode & 0o077:
            return {"result": "blocked", "reason": "insecure_profile_permissions"}
        config_text = open(profile["config"], "r", encoding="utf-8").read()
    except Exception:
        return {"result": "blocked", "reason": "profile_unreadable"}
    env, env_state = load_environment(profile)
    if env is None:
        return {"result": "blocked", "reason": env_state}
    if profile["env"] and "env:CONTROL_PLANE_API_KEY" not in config_text:
        return {"result": "blocked", "reason": "profile_key_reference_missing"}
    if os.path.islink(profile["pid"]) or os.path.islink(profile["url"]) or os.path.islink(profile["log"]):
        return {"result": "blocked", "reason": "runtime_file_symlink_not_allowed"}
    stale = False
    stale_pid = 0
    if os.path.exists(profile["pid"]):
        try:
            raw_pid = open(profile["pid"], "r", encoding="utf-8").read().strip()
            if not raw_pid.isdigit() or int(raw_pid) <= 0:
                return {"result": "blocked", "reason": "invalid_pid_file"}
            pid = int(raw_pid)
        except Exception:
            return {"result": "blocked", "reason": "pid_file_unreadable"}
        if os.path.exists("/proc/%d" % pid):
            if not process_identity(pid, profile):
                return {"result": "blocked", "reason": "pid_identity_mismatch", "pid": pid}
            if health_ok(profile) and full_health_ok(profile):
                return {"result": "already_running", "pid": pid}
            return {"result": "process_running_not_ready", "pid": pid}
        stale = True
        stale_pid = pid
    matches = find_matching(profile)
    if matches:
        return {"result": "blocked", "reason": "untracked_process", "pid": matches[0]}
    return {"result": "ready_to_start", "stale_pid_file": stale, "stale_pid": stale_pid}

def start_profile(profile):
    checked = preflight(profile)
    if checked["result"] == "already_running":
        return checked
    if checked["result"] != "ready_to_start":
        return checked
    if manager_is_active():
        return {"result": "blocked", "reason": "existing_tunnel_manager_busy"}
    if checked.get("stale_pid_file"):
        old_pid = checked.get("stale_pid", 0)
        if old_pid and os.path.exists("/proc/%d" % old_pid):
            return {"result": "blocked", "reason": "stale_pid_reused", "pid": old_pid}
        if find_matching(profile):
            return {"result": "blocked", "reason": "untracked_process"}
        if os.path.exists(profile["pid"]):
            os.unlink(profile["pid"])
    # A leftover health URL must never be allowed to make a newly launched
    # process appear ready by probing an unrelated service on a reused port.
    if os.path.exists(profile["url"]):
        os.unlink(profile["url"])
    if find_matching(profile):
        return {"result": "blocked", "reason": "untracked_process"}
    env, env_state = load_environment(profile)
    if env is None:
        return {"result": "blocked", "reason": env_state}
    flags = os.O_WRONLY | os.O_CREAT | os.O_APPEND
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    try:
        fd = os.open(profile["log"], flags, 0o600)
        os.fchmod(fd, 0o600)
        log_handle = os.fdopen(fd, "ab", buffering=0)
    except Exception as exc:
        return {"result": "blocked", "reason": "log_open_" + type(exc).__name__}
    try:
        child = subprocess.Popen(
            expected_argv(profile), cwd=HOME, env=env,
            stdin=subprocess.DEVNULL, stdout=log_handle, stderr=subprocess.STDOUT,
            close_fds=True, start_new_session=True
        )
    except Exception as exc:
        log_handle.close()
        return {"result": "start_failed", "reason": type(exc).__name__}
    log_handle.close()
    deadline = time.time() + 20
    while time.time() < deadline:
        if os.path.isfile(profile["pid"]) and not os.path.islink(profile["pid"]):
            try:
                pid = int(open(profile["pid"], "r", encoding="utf-8").read().strip())
            except Exception:
                return {"result": "pid_file_invalid_after_start", "spawned_pid": child.pid}
            if pid != child.pid or not process_identity(pid, profile):
                return {"result": "pid_identity_mismatch_after_start", "spawned_pid": child.pid}
            break
        if child.poll() is not None:
            return {"result": "process_exited_during_start", "exit_code": child.returncode}
        time.sleep(0.25)
    else:
        return {"result": "pid_file_not_created", "spawned_pid": child.pid}
    deadline = time.time() + 25
    while time.time() < deadline:
        if not process_identity(pid, profile):
            return {"result": "process_identity_lost_during_readiness", "pid": pid}
        if health_ok(profile):
            if full_health_ok(profile):
                return {"result": "started_ready", "pid": pid}
            return {"result": "started_not_ready", "reason": "control_plane_or_full_health_not_ready",
                    "pid": pid, "log": profile["log"]}
        time.sleep(1)
    return {"result": "started_not_ready", "reason": "local_readyz_timeout",
            "pid": pid, "log": profile["log"]}

def stop_profile(profile):
    pid_path = profile["pid"]
    if os.path.islink(pid_path):
        return {"result": "blocked", "reason": "pid_file_symlink"}
    if not os.path.isfile(pid_path):
        matches = find_matching(profile)
        if matches:
            return {"result": "blocked", "reason": "untracked_process", "pid": matches[0]}
        return {"result": "already_stopped"}
    try:
        raw_pid = open(pid_path, "r", encoding="utf-8").read().strip()
        if not raw_pid.isdigit() or int(raw_pid) <= 0:
            return {"result": "blocked", "reason": "invalid_pid_file"}
        pid = int(raw_pid)
    except Exception:
        return {"result": "blocked", "reason": "pid_file_unreadable"}
    if not os.path.exists("/proc/%d" % pid):
        return {"result": "stale_pid_file", "pid": pid}
    initial_start = process_start_time(pid)
    if not initial_start or not process_identity(pid, profile):
        return {"result": "blocked", "reason": "pid_identity_mismatch", "pid": pid}
    if manager_is_active():
        return {"result": "blocked", "reason": "existing_tunnel_manager_busy"}
    try:
        os.kill(pid, signal.SIGTERM)
    except ProcessLookupError:
        try:
            if not os.path.exists("/proc/%d" % pid) and open(pid_path, "r", encoding="utf-8").read().strip() == str(pid):
                os.unlink(pid_path)
                if os.path.isfile(profile["url"]) and not os.path.islink(profile["url"]):
                    os.unlink(profile["url"])
        except Exception:
            pass
        return {"result": "stopped", "pid": pid}
    except Exception as exc:
        return {"result": "stop_failed", "reason": type(exc).__name__, "pid": pid}
    deadline = time.time() + 15
    while time.time() < deadline:
        if not os.path.exists("/proc/%d" % pid) or process_state(pid) in ("Z", "X"):
            try:
                if open(pid_path, "r", encoding="utf-8").read().strip() == str(pid):
                    os.unlink(pid_path)
                    if os.path.isfile(profile["url"]) and not os.path.islink(profile["url"]):
                        os.unlink(profile["url"])
            except Exception:
                pass
            return {"result": "stopped", "pid": pid}
        current_start = process_start_time(pid)
        if current_start and (current_start != initial_start or not process_identity(pid, profile)):
            # Never signal a PID after its identity changes. If no matching
            # Tunnel Client remains, the old PID record is stale metadata.
            if find_matching(profile):
                return {"result": "blocked", "reason": "process_identity_changed", "pid": pid}
            try:
                if open(pid_path, "r", encoding="utf-8").read().strip() == str(pid):
                    os.unlink(pid_path)
                    if os.path.isfile(profile["url"]) and not os.path.islink(profile["url"]):
                        os.unlink(profile["url"])
            except Exception:
                pass
            return {"result": "stopped", "pid": pid, "identity_lost": True}
        time.sleep(1)
    return {"result": "stop_timeout_no_sigkill", "pid": pid}

def main():
    if len(sys.argv) != 3:
        emit("blocked", reason="invalid_arguments")
        return 2
    mode, profile_id = sys.argv[1], sys.argv[2]
    if mode not in ("preflight", "status", "start", "stop", "restart"):
        emit("blocked", reason="invalid_mode")
        return 2
    profile = PROFILES.get(profile_id)
    if profile is None:
        emit("blocked", reason="unknown_profile")
        return 2
    if mode in ("preflight", "status"):
        report = preflight(profile)
        if mode == "status" and report["result"] == "ready_to_start":
            report = {"result": "stopped", "stale_pid_file": report.get("stale_pid_file", False)}
        emit(report.pop("result"), **report)
        return 0
    if os.path.islink(LOCK):
        emit("blocked", reason="lock_file_symlink")
        return 3
    lock_flags = os.O_CREAT | os.O_RDWR
    if hasattr(os, "O_NOFOLLOW"):
        lock_flags |= os.O_NOFOLLOW
    lock_fd = os.open(LOCK, lock_flags, 0o600)
    os.fchmod(lock_fd, 0o600)
    try:
        fcntl.flock(lock_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        os.close(lock_fd)
        emit("operation_busy")
        return 3
    try:
        if manager_is_active():
            emit("blocked", reason="existing_tunnel_manager_busy")
            return 3
        if mode == "start":
            report = start_profile(profile)
        elif mode == "stop":
            report = stop_profile(profile)
        else:
            stopped = stop_profile(profile)
            if stopped["result"] not in ("stopped", "already_stopped"):
                emit("restart_aborted", stop_result=stopped["result"],
                     reason=stopped.get("reason", "stop_not_confirmed"))
                return 4
            report = start_profile(profile)
        result = report.pop("result")
        emit(result, **report)
        return 0 if result in ("already_running", "already_stopped", "started_ready", "stopped") else 1
    finally:
        os.close(lock_fd)

sys.exit(main())
""";
}
