package app.jiti.controlcenter.core;

public final class ProcessIdentity {
    public final int pid;
    public final String startTime;
    public final String executable;
    public final String commandLine;

    public ProcessIdentity(int pid, String commandLine) {
        this(pid, "", "", commandLine);
    }

    public ProcessIdentity(int pid, String startTime, String executable, String commandLine) {
        this.pid = pid;
        this.startTime = startTime == null ? "" : startTime;
        this.executable = executable == null ? "" : executable;
        this.commandLine = commandLine == null ? "" : commandLine;
    }

    public boolean matches(int expectedPid, String expectedExecutableFragment, String expectedConfigFragment) {
        if (pid <= 0 || pid != expectedPid) return false;
        if (expectedExecutableFragment != null && !commandLine.contains(expectedExecutableFragment)) return false;
        return expectedConfigFragment == null || commandLine.contains(expectedConfigFragment);
    }

    /** Requires kernel process start-time and executable identity, not PID alone. */
    public boolean matchesStable(int expectedPid, String expectedStartTime,
                                 String expectedExecutable, String expectedConfigFragment) {
        if (pid <= 0 || pid != expectedPid) return false;
        if (startTime.isEmpty() || executable.isEmpty()
                || expectedStartTime == null || expectedStartTime.isEmpty()
                || expectedExecutable == null || expectedExecutable.isEmpty()) return false;
        if (!startTime.equals(expectedStartTime) || !executable.equals(expectedExecutable)) return false;
        return expectedConfigFragment == null || commandLine.contains(expectedConfigFragment);
    }

    public static boolean isStalePid(int pid, boolean processExists, boolean identityMatches) {
        return pid <= 0 || !processExists || !identityMatches;
    }
}
