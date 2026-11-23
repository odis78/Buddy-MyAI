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
            case PROCESS_LIST:
                return new TermuxCommandSpec(operation, "/system/bin/ps", Arrays.asList("-A"));
            default:
                throw new IllegalArgumentException("operation is not allowlisted");
        }
    }

    public boolean isSafeFixedCommand() {
        if (!executable.startsWith("/") || executable.contains("..")) return false;
        if (executable.endsWith("/sh") || executable.endsWith("/bash")) return false;
        for (String arg : arguments) {
            if (arg == null || arg.contains("\n") || arg.contains("\r")) return false;
        }
        return true;
    }
}
