package app.jiti.controlcenter.termux;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class TermuxCommandSpec {
    private static final String BUDDY_MCP_AUTH_PROBE_SCRIPT = """
import json
import sys
import urllib.request
import urllib.error

def emit(value):
    print(json.dumps(value, separators=(",", ":")))

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
    raw_env = open("/proc/%d/environ" % pid, "rb").read()
except Exception as exc:
    emit({"result": "process_env_unavailable", "reason": type(exc).__name__})
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

opener = urllib.request.build_opener(NoRedirect)

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
                        Arrays.asList("-n", "1,240p", home + "/bin/tunnels"));
            case BUDDY_TUNNEL_HEALTH:
                return new TermuxCommandSpec(operation,
                        home + "/tunnel-client-android-build-v0.0.19/bin/tunnel-client-android-arm64",
                        Arrays.asList("health", "--url-file", home + "/.config/tunnel-client-buddy/health.url",
                                "--pid-file", home + "/.config/tunnel-client-buddy/buddy-myai.pid",
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

    public boolean isSafeFixedCommand() {
        if (executable == null || !executable.startsWith("/") || executable.contains("..")) return false;
        if (executable.endsWith("/sh") || executable.endsWith("/bash")) return false;
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
