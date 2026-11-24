package app.jiti.controlcenter.termux;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class TermuxCommandSpec {
    private static final String BUDDY_LAUNCH_SCRIPT = """
import json
import os
import stat
import sys
import time

HOME = os.path.expanduser("~")
BINARY = HOME + "/tunnel-client-android-build-v0.0.19/bin/tunnel-client-android-arm64"
CONFIG = HOME + "/.config/tunnel-client-buddy/buddy-myai.yaml"
ENV_FILE = HOME + "/.config/termux-bridge/runtime.env"
PID_FILE = HOME + "/.config/tunnel-client-buddy/buddy-myai.pid"
HEALTH_URL_FILE = HOME + "/.config/tunnel-client-buddy/health.url"
LOG_FILE = HOME + "/.config/tunnel-client-buddy/tunnel-client.log"
STATUS_FILE = HOME + "/.config/tunnel-client-buddy/jiti-launcher.status"
BINARY_SUFFIX = "/tunnel-client-android-build-v0.0.19/bin/tunnel-client-android-arm64"
EXPECTED_TUNNEL_ID = "tunnel_6b02e7e420948191a1829df4a5373d20"

def emit(value):
    print(json.dumps(value, separators=(",", ":")))

def write_status(value):
    value["timestamp"] = int(time.time())
    os.umask(0o077)
    temp = STATUS_FILE + ".tmp"
    os.makedirs(os.path.dirname(STATUS_FILE), exist_ok=True)
    with open(temp, "w", encoding="utf-8") as handle:
        json.dump(value, handle, separators=(",", ":"))
    os.chmod(temp, 0o600)
    os.replace(temp, STATUS_FILE)
    os.chmod(STATUS_FILE, 0o600)

def normalize(path):
    return path.replace("/data/user/0/com.termux", "/data/data/com.termux")

def process_identity(pid):
    try:
        executable = os.readlink("/proc/%d/exe" % pid)
        raw = open("/proc/%d/cmdline" % pid, "rb").read()
    except Exception:
        return False, "process_unreadable"
    args = [item.decode("utf-8", "replace") for item in raw.split(b"\\0") if item]
    if len(args) < 3 or args[1] != "run":
        return False, "process_not_run_command"
    config_ok = False
    expected = normalize(CONFIG)
    for index, arg in enumerate(args):
        if arg == "--config" and index + 1 < len(args) and normalize(args[index + 1]) == expected:
            config_ok = True
        if arg.startswith("--config=") and normalize(arg.split("=", 1)[1]) == expected:
            config_ok = True
    executable_ok = executable.endswith(BINARY_SUFFIX)
    return executable_ok and config_ok, "identity_match" if executable_ok and config_ok else "identity_mismatch"

def load_environment():
    if os.path.islink(ENV_FILE):
        return None, "env_file_symlink_not_allowed", []
    if not os.path.isfile(ENV_FILE):
        return None, "missing_env_file", []
    mode = stat.S_IMODE(os.stat(ENV_FILE).st_mode)
    if mode & 0o077:
        return None, "insecure_env_file_permissions", []
    values = {}
    try:
        with open(ENV_FILE, "r", encoding="utf-8") as handle:
            for raw_line in handle:
                line = raw_line.strip()
                if not line or line.startswith("#"):
                    continue
                if line.startswith("export "):
                    line = line[7:].strip()
                if "=" not in line:
                    continue
                key, value = line.split("=", 1)
                key = key.strip()
                if key not in ("CONTROL_PLANE_API_KEY", "BUDDY_TOKEN", "BUDDY_AUTH_HEADER"):
                    continue
                value = value.strip()
                if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
                    value = value[1:-1]
                if "$" in value or chr(96) in value:
                    return None, "unsupported_env_syntax", []
                values[key] = value
    except Exception as exc:
        return None, "env_file_read_" + type(exc).__name__, []
    missing = []
    if not values.get("CONTROL_PLANE_API_KEY"):
        missing.append("CONTROL_PLANE_API_KEY")
    if not values.get("BUDDY_AUTH_HEADER") and not values.get("BUDDY_TOKEN"):
        missing.append("BUDDY_AUTH_HEADER_or_BUDDY_TOKEN")
    if missing:
        return None, "missing_required_env_keys", missing
    environment = os.environ.copy()
    environment["CONTROL_PLANE_API_KEY"] = values["CONTROL_PLANE_API_KEY"]
    if values.get("BUDDY_TOKEN"):
        environment["BUDDY_TOKEN"] = values["BUDDY_TOKEN"]
    auth = values.get("BUDDY_AUTH_HEADER") or values.get("BUDDY_TOKEN")
    if auth.lower().startswith("authorization:"):
        auth = auth.split(":", 1)[1].strip()
    if not auth.lower().startswith("bearer "):
        auth = "Bearer " + auth
    if "\\r" in auth or "\\n" in auth:
        return None, "invalid_auth_header_format", []
    environment["BUDDY_AUTH_HEADER"] = auth
    return environment, "ok", []

def validate():
    if not os.path.isfile(BINARY) or not os.access(BINARY, os.X_OK):
        return None, {"result": "blocked", "reason": "binary_missing_or_not_executable"}
    if not os.path.isfile(CONFIG) or not os.access(CONFIG, os.R_OK):
        return None, {"result": "blocked", "reason": "buddy_config_missing_or_unreadable"}
    try:
        config_text = open(CONFIG, "r", encoding="utf-8").read()
    except Exception as exc:
        return None, {"result": "blocked", "reason": "buddy_config_read_" + type(exc).__name__}
    if EXPECTED_TUNNEL_ID not in config_text:
        return None, {"result": "blocked", "reason": "buddy_tunnel_id_mismatch"}
    if "env:CONTROL_PLANE_API_KEY" not in config_text:
        return None, {"result": "blocked", "reason": "buddy_control_plane_key_reference_missing"}
    if config_text.count("BUDDY_AUTH_HEADER") < 2:
        return None, {"result": "blocked", "reason": "buddy_mcp_auth_header_references_incomplete"}
    for directory in (os.path.dirname(PID_FILE), os.path.dirname(HEALTH_URL_FILE), os.path.dirname(LOG_FILE)):
        if not os.path.isdir(directory) or not os.access(directory, os.W_OK):
            return None, {"result": "blocked", "reason": "buddy_runtime_directory_not_writable"}
    environment, env_state, missing = load_environment()
    if environment is None:
        return None, {"result": "blocked", "reason": env_state, "missing_keys": missing}
    stale_pid = False
    if os.path.exists(PID_FILE):
        try:
            with open(PID_FILE, "r", encoding="utf-8") as handle:
                pid = int(handle.read().strip())
            if pid <= 0:
                return None, {"result": "blocked", "reason": "invalid_pid_file"}
            if os.path.exists("/proc/%d" % pid):
                matches, reason = process_identity(pid)
                if matches:
                    return environment, {"result": "already_running", "pid": pid}
                return None, {"result": "blocked", "reason": "pid_conflict", "pid": pid, "identity": reason}
            stale_pid = True
        except Exception:
            return None, {"result": "blocked", "reason": "invalid_pid_file"}
    return environment, {"result": "ready_to_start", "stale_pid_file": stale_pid}

mode = sys.argv[1] if len(sys.argv) > 1 else ""
environment, outcome = validate()
if mode == "preflight":
    emit(outcome)
    sys.exit(0)
if mode != "start":
    emit({"result": "blocked", "reason": "invalid_operation"})
    sys.exit(2)
if outcome.get("result") == "already_running":
    write_status(outcome)
    sys.exit(0)
if outcome.get("result") != "ready_to_start" or environment is None:
    write_status(outcome)
    sys.exit(2)
if outcome.get("stale_pid_file"):
    try:
        os.unlink(PID_FILE)
    except Exception as exc:
        blocked = {"result": "blocked", "reason": "stale_pid_cleanup_" + type(exc).__name__}
        write_status(blocked)
        emit(blocked)
        sys.exit(2)
write_status({"result": "launch_requested", "timestamp": int(time.time())})
argv = [
    BINARY, "run", "--config", CONFIG,
    "--pid.file", PID_FILE,
    "--health.url-file", HEALTH_URL_FILE,
    "--log.file", LOG_FILE
]
try:
    os.execve(BINARY, argv, environment)
except Exception as exc:
    failed = {"result": "exec_failed", "reason": type(exc).__name__}
    write_status(failed)
    emit(failed)
    sys.exit(1)
""";

    private static final String BUDDY_STOP_SCRIPT = """
import json
import os
import signal
import sys
import time

HOME = os.path.expanduser("~")
PID_FILE = HOME + "/.config/tunnel-client-buddy/buddy-myai.pid"
CONFIG = HOME + "/.config/tunnel-client-buddy/buddy-myai.yaml"
BINARY_SUFFIX = "/tunnel-client-android-build-v0.0.19/bin/tunnel-client-android-arm64"
STATUS_FILE = HOME + "/.config/tunnel-client-buddy/jiti-launcher.status"

def emit(value):
    print(json.dumps(value, separators=(",", ":")))

def normalize(path):
    return path.replace("/data/user/0/com.termux", "/data/data/com.termux")

def identity(pid):
    try:
        executable = os.readlink("/proc/%d/exe" % pid)
        raw = open("/proc/%d/cmdline" % pid, "rb").read()
    except Exception:
        return False
    args = [item.decode("utf-8", "replace") for item in raw.split(b"\\0") if item]
    if len(args) < 3 or args[1] != "run":
        return False
    expected = normalize(CONFIG)
    config_ok = any(args[i] == "--config" and i + 1 < len(args)
                    and normalize(args[i + 1]) == expected for i in range(len(args)))
    config_ok = config_ok or any(arg.startswith("--config=")
                                 and normalize(arg.split("=", 1)[1]) == expected for arg in args)
    return executable.endswith(BINARY_SUFFIX) and config_ok

def process_start_time(pid):
    try:
        stat_text = open("/proc/%d/stat" % pid, "r", encoding="utf-8").read()
        fields = stat_text[stat_text.rfind(")") + 2:].split()
        return fields[19] if len(fields) > 19 else None
    except Exception:
        return None

def write_status(value):
    try:
        value["timestamp"] = int(time.time())
        os.umask(0o077)
        temp = STATUS_FILE + ".tmp"
        with open(temp, "w", encoding="utf-8") as handle:
            json.dump(value, handle, separators=(",", ":"))
        os.chmod(temp, 0o600)
        os.replace(temp, STATUS_FILE)
        os.chmod(STATUS_FILE, 0o600)
    except Exception:
        pass

mode = sys.argv[1] if len(sys.argv) > 1 else ""
if mode not in ("preflight", "stop"):
    emit({"result": "blocked", "reason": "invalid_operation"})
    sys.exit(2)

def finish(value, code=0):
    emit(value)
    if mode == "stop":
        write_status(value)
    sys.exit(code)

try:
    with open(PID_FILE, "r", encoding="utf-8") as handle:
        pid = int(handle.read().strip())
    if pid <= 0:
        raise ValueError()
except FileNotFoundError:
    finish({"result": "already_stopped"})
except Exception:
    finish({"result": "blocked", "reason": "invalid_pid_file"}, 2)

if not os.path.exists("/proc/%d" % pid):
    finish({"result": "stale_pid_file", "pid": pid})
if not identity(pid):
    finish({"result": "pid_identity_mismatch", "pid": pid}, 2)
initial_start_time = process_start_time(pid)
if not initial_start_time:
    finish({"result": "process_start_time_unavailable", "pid": pid}, 2)

if mode == "preflight":
    finish({"result": "ready_to_stop", "pid": pid})

try:
    os.kill(pid, signal.SIGTERM)
except ProcessLookupError:
    finish({"result": "stopped", "pid": pid})
except Exception as exc:
    finish({"result": "stop_failed", "reason": type(exc).__name__, "pid": pid}, 2)

deadline = time.time() + 10.0
identity_lost = False
while time.time() < deadline:
    if not os.path.exists("/proc/%d" % pid):
        finish({"result": "stopped", "pid": pid})
    try:
        stat_text = open("/proc/%d/stat" % pid, "r", encoding="utf-8").read()
        fields = stat_text[stat_text.rfind(")") + 2:].split()
        state = fields[0] if fields else ""
        if state in ("Z", "X", "x"):
            finish({"result": "stopped", "pid": pid})
    except Exception:
        finish({"result": "stopped", "pid": pid})
    current_start_time = process_start_time(pid)
    if current_start_time is None:
        finish({"result": "stopped", "pid": pid})
    if current_start_time != initial_start_time:
        finish({"result": "stopped", "pid": pid, "reason": "original_process_exited_pid_reused"})
    if not identity(pid):
        # During SIGTERM teardown, /proc can briefly stop exposing argv/exe
        # before the task becomes a zombie or disappears. Wait without ever
        # signalling a process whose stable start-time has changed.
        identity_lost = True
    time.sleep(0.2)

if identity_lost:
    finish({"result": "stop_identity_lost", "pid": pid}, 2)
finish({"result": "stop_timeout", "pid": pid, "signal": "SIGTERM"}, 2)
""";
    private static final String MCP_ENDPOINT_AUDIT_SCRIPT = """
import os
import re

HOME = os.path.expanduser("~")
PROFILES = [
    ("bridge", HOME + "/.config/tunnel-client-bridge-013/bridge-013.yaml"),
    ("droidmcp", HOME + "/droidmcp-stage-v0.4.0/profile-dedicated/droidmcp-filesystem.yaml"),
    ("rea-android", HOME + "/.config/rea-tunnel/profile/rea-android.yaml"),
]
KEY_RE = re.compile(r"(?i)(mcp|server[-_]?url|listen|port|endpoint|command|args)")
ENDPOINT_KEY = re.compile(r"(?i)(server[-_]?url|listen|port|endpoint|command|args)")
SENSITIVE_KEY = re.compile(r"(?i)(token|api[-_]?key|auth|secret|password|bearer|private|admin[-_]?key)")

def redact(value):
    value = re.sub(r"(?i)(authorization\\s*:\\s*)(?:bearer\\s+)?[^,}\\]]+", r"\\1[REDACTED]", value)
    value = re.sub(r"(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+", r"\\1[REDACTED]", value)
    value = re.sub(r"(?i)(--(?:api-key|token|auth|password|secret)(?:=|\\s+))\\S+", r"\\1[REDACTED]", value)
    value = re.sub(r"(?i)([?&](?:token|key|auth|secret|password)=)[^&\\s]+", r"\\1[REDACTED]", value)
    value = re.sub(r"(?i)(https?://[^:/\\s]+):[^@/\\s]+@", r"\\1:[REDACTED]@", value)
    return value

total = 0
endpoint_total = 0
for profile, path in PROFILES:
    if os.path.islink(path):
        print("[%s] config=symlink-blocked" % profile)
        continue
    if not os.path.isfile(path):
        print("[%s] config=missing" % profile)
        continue
    try:
        if os.path.getsize(path) > 262144:
            print("[%s] config=too-large" % profile)
            continue
        print("[%s] config=present" % profile)
        with open(path, "r", encoding="utf-8", errors="replace") as stream:
            for line_number, line in enumerate(stream, 1):
                if ":" not in line:
                    continue
                key, value = line.split(":", 1)
                key = key.strip().strip('"').strip("'")
                if not KEY_RE.search(key) or SENSITIVE_KEY.search(key):
                    continue
                value = redact(value.strip())
                print("  L%d %s: %s" % (line_number, key, value))
                total += 1
                if ENDPOINT_KEY.search(key):
                    endpoint_total += 1
    except Exception:
        print("[%s] config=unreadable" % profile)

print("MCP_CONFIG_FIELDS=%d" % total)
print("ENDPOINT_FIELDS=%d" % endpoint_total)
""";
    private static final String BUDDY_MCP_AUTH_PROBE_SCRIPT = """
import json
import os
import sys
import urllib.request
import urllib.error

HOME = os.path.expanduser("~")
BINARY_SUFFIX = "/tunnel-client-android-build-v0.0.19/bin/tunnel-client-android-arm64"
CONFIG = HOME + "/.config/tunnel-client-buddy/buddy-myai.yaml"

def emit(value):
    print(json.dumps(value, separators=(",", ":")))

def normalize(path):
    return path.replace("/data/user/0/com.termux", "/data/data/com.termux")

def process_identity(pid):
    try:
        executable = os.readlink("/proc/%d/exe" % pid)
        raw = open("/proc/%d/cmdline" % pid, "rb").read()
    except Exception:
        return False
    args = [item.decode("utf-8", "replace") for item in raw.split(b"\\0") if item]
    if len(args) < 3 or args[1] != "run":
        return False
    expected = normalize(CONFIG)
    config_ok = any(args[i] == "--config" and i + 1 < len(args)
                    and normalize(args[i + 1]) == expected for i in range(len(args)))
    config_ok = config_ok or any(arg.startswith("--config=")
                                 and normalize(arg.split("=", 1)[1]) == expected for arg in args)
    return executable.endswith(BINARY_SUFFIX) and config_ok

def decode_message(body):
    text = body.decode("utf-8", "replace").strip()
    if not text:
        return None
    if text.startswith("data:") or "\\ndata:" in text:
        for line in reversed(text.splitlines()):
            if line.startswith("data:"):
                try:
                    return json.loads(line[5:].strip())
                except Exception:
                    pass
    try:
        return json.loads(text)
    except Exception:
        return None

try:
    pid = int(sys.argv[1])
    if pid <= 0:
        raise ValueError()
except Exception:
    emit({"result": "invalid_pid"})
    sys.exit(0)

if not process_identity(pid):
    emit({"result": "pid_identity_mismatch"})
    sys.exit(0)

try:
    raw_env = open("/proc/%d/environ" % pid, "rb").read()
except Exception as exc:
    emit({"result": "process_env_unavailable", "reason": type(exc).__name__})
    sys.exit(0)

if not process_identity(pid):
    emit({"result": "pid_identity_changed"})
    sys.exit(0)

env = {}
for item in raw_env.split(b"\\0"):
    if b"=" in item:
        key, value = item.split(b"=", 1)
        if key in (b"BUDDY_AUTH_HEADER", b"BUDDY_TOKEN"):
            env[key.decode("ascii")] = value.decode("utf-8", "ignore").strip()

auth = env.get("BUDDY_AUTH_HEADER", "").strip()
auth_source = "BUDDY_AUTH_HEADER"
if not auth:
    token = env.get("BUDDY_TOKEN", "").strip()
    if token:
        auth = token
        auth_source = "BUDDY_TOKEN"

if not auth:
    emit({"result": "missing_auth_env", "keys_found": [key for key, value in env.items() if value]})
    sys.exit(0)

if auth.lower().startswith("authorization:"):
    auth = auth.split(":", 1)[1].strip()
if not auth.lower().startswith("bearer "):
    auth = "Bearer " + auth
if "\\r" in auth or "\\n" in auth:
    emit({"result": "invalid_auth_header", "auth_source": auth_source})
    sys.exit(0)

url = "http://127.0.0.1:8766/mcp"

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        return None

opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect)

def send(payload, session_id=None, protocol=None, method="POST"):
    headers = {
        "Authorization": auth,
        "Accept": "application/json, text/event-stream",
        "Content-Type": "application/json"
    }
    if session_id:
        headers["Mcp-Session-Id"] = session_id
    if protocol:
        headers["MCP-Protocol-Version"] = protocol
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with opener.open(request, timeout=6) as response:
            return response.status, response.headers, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.headers, error.read()
    except Exception:
        return 0, {}, b""

initialize = {
    "jsonrpc": "2.0",
    "id": 1,
    "method": "initialize",
    "params": {
        "protocolVersion": "2025-11-25",
        "capabilities": {},
        "clientInfo": {"name": "jiti-control-center", "version": "0.2.0"}
    }
}
status, headers, body = send(initialize)
if status in (401, 403):
    emit({"result": "auth_rejected", "http_status": status, "auth_source": auth_source})
    sys.exit(0)
if status != 200:
    emit({"result": "initialize_http_error", "http_status": status, "auth_source": auth_source})
    sys.exit(0)

message = decode_message(body)
if not isinstance(message, dict):
    emit({"result": "initialize_response_invalid", "http_status": status, "auth_source": auth_source})
    sys.exit(0)
if "error" in message:
    error = message.get("error") or {}
    emit({"result": "initialize_mcp_error", "error_code": error.get("code"), "auth_source": auth_source})
    sys.exit(0)
if "result" not in message:
    emit({"result": "initialize_response_invalid", "http_status": status, "auth_source": auth_source})
    sys.exit(0)

init_result = message.get("result") or {}
protocol = init_result.get("protocolVersion", "2025-11-25")
server_info = init_result.get("serverInfo") or {}
session_id = headers.get("Mcp-Session-Id")
send({"jsonrpc": "2.0", "method": "notifications/initialized"}, session_id, protocol)

names = []
cursor = None
page_count = 0
failure = None
while page_count < 10:
    params = {} if not cursor else {"cursor": cursor}
    tools_status, tools_headers, tools_body = send(
        {"jsonrpc": "2.0", "id": 2 + page_count, "method": "tools/list", "params": params},
        session_id, protocol
    )
    if tools_status in (401, 403):
        failure = {"result": "auth_rejected", "http_status": tools_status}
        break
    if tools_status != 200:
        failure = {"result": "tools_list_http_error", "http_status": tools_status}
        break
    tools_message = decode_message(tools_body)
    if not isinstance(tools_message, dict):
        failure = {"result": "tools_list_response_invalid"}
        break
    if "error" in tools_message:
        error = tools_message.get("error") or {}
        failure = {"result": "tools_list_mcp_error", "error_code": error.get("code")}
        break
    tools_result = tools_message.get("result") or {}
    tool_items = tools_result.get("tools", [])
    if not isinstance(tool_items, list):
        failure = {"result": "tools_list_response_invalid"}
        break
    names.extend([item.get("name") for item in tool_items
                  if isinstance(item, dict) and isinstance(item.get("name"), str)])
    cursor = tools_result.get("nextCursor")
    page_count += 1
    if not cursor:
        break

if session_id:
    send(None, session_id, protocol, "DELETE")

if failure:
    failure.update({"auth_source": auth_source, "tool_count_partial": len(names), "page_count": page_count})
    emit(failure)
elif cursor:
    emit({"result": "tools_list_page_limit", "auth_source": auth_source,
          "tool_count_partial": len(names), "page_count": page_count})
else:
    emit({
        "result": "authenticated_ready",
        "auth_source": auth_source,
        "protocol": protocol,
        "server": server_info.get("name", ""),
        "version": server_info.get("version", ""),
        "tool_count": len(names),
        "page_count": page_count,
        "tools": names
    })
""";
    public final TermuxOperation operation;
    public final String executable;
    public final List<String> arguments;
    public final boolean background;

    private TermuxCommandSpec(TermuxOperation operation, String executable, List<String> arguments) {
        this.operation = operation;
        this.executable = executable;
        this.arguments = Collections.unmodifiableList(arguments);
        this.background = true;
    }

    public static TermuxCommandSpec forOperation(TermuxOperation operation, String prefix, String home) {
        if (operation == null || prefix == null || home == null
                || !prefix.startsWith("/") || !home.startsWith("/"))
            throw new IllegalArgumentException("absolute Termux paths required");
        switch (operation) {
            case IDENTITY:
                return new TermuxCommandSpec(operation, prefix + "/bin/id", Collections.<String>emptyList());
            case TUNNEL_CLIENT_VERSION:
                return new TermuxCommandSpec(operation,
                        home + "/tunnel-client-android-build-v0.0.19/bin/tunnel-client-android-arm64",
                        Arrays.asList("--version"));
            case CURL_VERSION:
                return new TermuxCommandSpec(operation, prefix + "/bin/curl", Arrays.asList("--version"));
            case PYTHON_VERSION:
                return new TermuxCommandSpec(operation, prefix + "/bin/python", Arrays.asList("--version"));
            case MCP_ENDPOINT_AUDIT:
                return new TermuxCommandSpec(operation, prefix + "/bin/python",
                        Arrays.asList("-c", MCP_ENDPOINT_AUDIT_SCRIPT));
            case BUDDY_ENV_SOURCE_AUDIT:
                return new TermuxCommandSpec(operation, prefix + "/bin/grep",
                        Arrays.asList("-s", "-n", "-oE",
                                "(BUDDY_TOKEN|BUDDY_AUTH_HEADER|CONTROL_PLANE_API_KEY)",
                                home + "/.config/termux-bridge/runtime.env",
                                home + "/.config/rea-tunnel/runtime.env",
                                home + "/.config/tunnel-client-buddy/buddy-myai.yaml",
                                home + "/.bashrc", home + "/.profile", home + "/.bash_profile",
                                home + "/bin/tunnels"));
            case TUNNELS_SCRIPT_AUDIT:
                return new TermuxCommandSpec(operation, prefix + "/bin/sed",
                        Arrays.asList("-n", "1,360p", home + "/bin/tunnels"));
            case TUNNELS_SCRIPT_SHA256:
                return new TermuxCommandSpec(operation, prefix + "/bin/sha256sum",
                        Arrays.asList(home + "/bin/tunnels"));
            case BUDDY_LAUNCH_STATUS:
                return new TermuxCommandSpec(operation, prefix + "/bin/cat",
                        Arrays.asList(home + "/.config/tunnel-client-buddy/jiti-launcher.status"));
            case BUDDY_TUNNEL_HEALTH:
                return new TermuxCommandSpec(operation,
                        home + "/tunnel-client-android-build-v0.0.19/bin/tunnel-client-android-arm64",
                        Arrays.asList("health", "--url-file", home + "/.config/tunnel-client-buddy/health.url",
                                "--pid-file", home + "/.config/tunnel-client-buddy/buddy-myai.pid",
                                "--require-control-plane-poll", "--json"));
            case BRIDGE_TUNNEL_HEALTH:
                return new TermuxCommandSpec(operation,
                        home + "/tunnel-client-android-build-v0.0.19/bin/tunnel-client-android-arm64",
                        Arrays.asList("health", "--url-file", home + "/.local/state/tunnels/bridge-013.health.url",
                                "--pid-file", home + "/.local/state/tunnels/bridge-013.pid",
                                "--require-control-plane-poll", "--json"));
            case DROIDMCP_TUNNEL_HEALTH:
                return new TermuxCommandSpec(operation,
                        home + "/tunnel-client-android-build-v0.0.19/bin/tunnel-client-android-arm64",
                        Arrays.asList("health", "--url-file", home + "/.local/state/tunnels/droidmcp.health.url",
                                "--pid-file", home + "/.local/state/tunnels/droidmcp.pid",
                                "--require-control-plane-poll", "--json"));
            case REA_ANDROID_TUNNEL_HEALTH:
                return new TermuxCommandSpec(operation,
                        home + "/tunnel-client-android-build-v0.0.19/bin/tunnel-client-android-arm64",
                        Arrays.asList("health", "--url-file", home + "/.local/state/tunnels/rea-android.health.url",
                                "--pid-file", home + "/.local/state/tunnels/rea-android.pid",
                                "--require-control-plane-poll", "--json"));
            case BUDDY_MCP_LISTENER_CHALLENGE:
                return new TermuxCommandSpec(operation, prefix + "/bin/curl",
                        Arrays.asList("--silent", "--show-error", "--output", "/dev/null",
                                "--write-out", "%{http_code}", "--max-time", "3", "--request", "POST",
                                "--header", "Content-Type: application/json",
                                "--data-binary", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":\"jiti-control-center\",\"version\":\"0.2.0\"}}}",
                                "http://127.0.0.1:8766/mcp"));
            case PROCESS_LIST:
                return new TermuxCommandSpec(operation, "/system/bin/ps", Arrays.asList("-A"));
            default:
                throw new IllegalArgumentException("operation is not allowlisted");
        }
    }

    public static TermuxCommandSpec forBuddyLaunch(String prefix, String mode) {
        if (prefix == null || !prefix.startsWith("/") || prefix.contains("..")
                || !("preflight".equals(mode) || "start".equals(mode)))
            throw new IllegalArgumentException("valid absolute prefix and launch mode required");
        return new TermuxCommandSpec(TermuxOperation.BUDDY_LAUNCH, prefix + "/bin/python",
                Arrays.asList("-c", BUDDY_LAUNCH_SCRIPT, mode));
    }

    public static TermuxCommandSpec forBuddyStop(String prefix, String mode) {
        if (prefix == null || !prefix.startsWith("/") || prefix.contains("..")
                || !("preflight".equals(mode) || "stop".equals(mode)))
            throw new IllegalArgumentException("valid absolute prefix and stop mode required");
        return new TermuxCommandSpec(TermuxOperation.BUDDY_STOP, prefix + "/bin/python",
                Arrays.asList("-c", BUDDY_STOP_SCRIPT, mode));
    }

    public static TermuxCommandSpec forBuddyMcpAuthProbe(String prefix, int pid) {
        if (prefix == null || !prefix.startsWith("/") || prefix.contains("..") || pid <= 0)
            throw new IllegalArgumentException("valid absolute prefix and positive PID required");
        return new TermuxCommandSpec(TermuxOperation.BUDDY_MCP_AUTH_PROBE, prefix + "/bin/python",
                Arrays.asList("-c", BUDDY_MCP_AUTH_PROBE_SCRIPT, Integer.toString(pid)));
    }

    public static TermuxCommandSpec forProcessExecutable(int pid) {
        if (pid <= 0) throw new IllegalArgumentException("positive PID required");
        return new TermuxCommandSpec(TermuxOperation.PROCESS_EXECUTABLE, "/system/bin/readlink",
                Arrays.asList("/proc/" + pid + "/exe"));
    }

    public static TermuxCommandSpec forProcessCmdline(int pid) {
        if (pid <= 0) throw new IllegalArgumentException("positive PID required");
        return new TermuxCommandSpec(TermuxOperation.PROCESS_CMDLINE, "/system/bin/cat",
                Arrays.asList("/proc/" + pid + "/cmdline"));
    }

    public boolean isSafeFixedCommand() {
        if (executable == null || !executable.startsWith("/") || executable.contains("..")) return false;
        if (executable.endsWith("/sh") || executable.endsWith("/bash")) return false;
        if (operation == TermuxOperation.MCP_ENDPOINT_AUDIT) {
            return executable.endsWith("/bin/python") && arguments.size() == 2
                    && "-c".equals(arguments.get(0))
                    && MCP_ENDPOINT_AUDIT_SCRIPT.equals(arguments.get(1));
        }
        if (operation == TermuxOperation.BUDDY_MCP_AUTH_PROBE) {
            return executable.endsWith("/bin/python") && arguments.size() == 3
                    && "-c".equals(arguments.get(0))
                    && BUDDY_MCP_AUTH_PROBE_SCRIPT.equals(arguments.get(1))
                    && arguments.get(2).matches("[1-9][0-9]*");
        }
        if (operation == TermuxOperation.BUDDY_LAUNCH) {
            return executable.endsWith("/bin/python") && arguments.size() == 3
                    && "-c".equals(arguments.get(0))
                    && BUDDY_LAUNCH_SCRIPT.equals(arguments.get(1))
                    && ("preflight".equals(arguments.get(2)) || "start".equals(arguments.get(2)));
        }
        if (operation == TermuxOperation.BUDDY_STOP) {
            return executable.endsWith("/bin/python") && arguments.size() == 3
                    && "-c".equals(arguments.get(0))
                    && BUDDY_STOP_SCRIPT.equals(arguments.get(1))
                    && ("preflight".equals(arguments.get(2)) || "stop".equals(arguments.get(2)));
        }
        for (String arg : arguments) {
            if (arg == null || arg.contains("\n") || arg.contains("\r")) return false;
        }
        if (operation == TermuxOperation.PROCESS_CMDLINE) {
            return arguments.size() == 1 && arguments.get(0).matches("/proc/[1-9][0-9]*/cmdline");
        }
        if (operation == TermuxOperation.PROCESS_EXECUTABLE) {
            return arguments.size() == 1 && arguments.get(0).matches("/proc/[1-9][0-9]*/exe");
        }
        return true;
    }
}
