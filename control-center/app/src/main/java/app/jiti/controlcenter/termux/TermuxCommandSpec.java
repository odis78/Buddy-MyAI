package app.jiti.controlcenter.termux;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class TermuxCommandSpec {
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
BINARY = HOME + "/tunnel-client-android-build-v0.0.19/bin/tunnel-client-android-arm64"
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
    return normalize(executable) == normalize(BINARY) and config_ok

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
        with opener.open(request, timeout=3) as response:
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
while page_count < 4:
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

    public static TermuxCommandSpec forProfileControl(String prefix, String profileId, String mode) {
        if (prefix == null || !prefix.startsWith("/") || prefix.contains("..")
                || !("bridge".equals(profileId) || "droidmcp".equals(profileId)
                || "rea-android".equals(profileId))
                || !("preflight".equals(mode) || "status".equals(mode) || "start".equals(mode)
                || "stop".equals(mode) || "restart".equals(mode) || "logs".equals(mode))) {
            throw new IllegalArgumentException("fixed profile id, mode, and absolute Termux prefix required");
        }
        return new TermuxCommandSpec(TermuxOperation.PROFILE_CONTROL, prefix + "/bin/python",
                Arrays.asList("-c", ProfileControlScript.SCRIPT, mode, profileId));
    }

    public boolean isSafeFixedCommand() {
        if (executable == null || !executable.startsWith("/") || executable.contains("..")) return false;
        if (executable.endsWith("/sh") || executable.endsWith("/bash")) return false;
        if (operation == TermuxOperation.MCP_ENDPOINT_AUDIT) {
            return executable.endsWith("/bin/python") && arguments.size() == 2
                    && "-c".equals(arguments.get(0))
                    && MCP_ENDPOINT_AUDIT_SCRIPT.equals(arguments.get(1));
        }
        if (operation == TermuxOperation.PROFILE_CONTROL) {
            return executable.endsWith("/bin/python") && arguments.size() == 4
                    && "-c".equals(arguments.get(0))
                    && ProfileControlScript.SCRIPT.equals(arguments.get(1))
                    && ("preflight".equals(arguments.get(2)) || "status".equals(arguments.get(2))
                    || "start".equals(arguments.get(2)) || "stop".equals(arguments.get(2))
                    || "restart".equals(arguments.get(2)) || "logs".equals(arguments.get(2)))
                    && ("bridge".equals(arguments.get(3)) || "droidmcp".equals(arguments.get(3))
                    || "rea-android".equals(arguments.get(3)));
        }
        if (operation == TermuxOperation.BUDDY_MCP_AUTH_PROBE) {
            return executable.endsWith("/bin/python") && arguments.size() == 3
                    && "-c".equals(arguments.get(0))
                    && BUDDY_MCP_AUTH_PROBE_SCRIPT.equals(arguments.get(1))
                    && arguments.get(2).matches("[1-9][0-9]*");
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
