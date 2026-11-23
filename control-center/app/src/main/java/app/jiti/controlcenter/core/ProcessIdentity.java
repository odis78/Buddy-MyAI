package app.jiti.controlcenter.core;

public final class ProcessIdentity {
    public final int pid;
    public final String commandLine;
    public ProcessIdentity(int pid, String commandLine) {
        this.pid = pid; this.commandLine = commandLine == null ? "" : commandLine;
    }
    public boolean matches(int expectedPid, String expectedExecutableFragment, String expectedConfigFragment) {
        if (pid <= 0 || pid != expectedPid) return false;
        if (expectedExecutableFragment != null && !commandLine.contains(expectedExecutableFragment)) return false;
        return expectedConfigFragment == null || commandLine.contains(expectedConfigFragment);
    }
    public static boolean isStalePid(int pid, boolean processExists, boolean identityMatches) {
        return pid <= 0 || !processExists || !identityMatches;
    }
}
