package app.jiti.controlcenter.termux;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class TermuxCommandSpec {
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
        if (!executable.startsWith("/") || executable.contains("..")) return false;
        if (executable.endsWith("/sh") || executable.endsWith("/bash")) return false;
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
