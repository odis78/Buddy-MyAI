package app.jiti.controlcenter.termux;

import org.junit.Test;
import static org.junit.Assert.*;

public class TermuxCommandSpecTest {
    @Test public void onlyFixedAbsoluteReadOnlyOperationsAreAvailable() {
        for (TermuxOperation op : TermuxOperation.values()) {
            if (op == TermuxOperation.PROCESS_CMDLINE || op == TermuxOperation.PROCESS_EXECUTABLE) continue;
            TermuxCommandSpec spec = TermuxCommandSpec.forOperation(op,
                    "/data/data/com.termux/files/usr",
                    "/data/data/com.termux/files/home");
            assertTrue(spec.isSafeFixedCommand());
            assertTrue(spec.background);
            assertTrue(spec.executable.startsWith("/"));
        }
    }
    @Test public void tunnelsScriptAuditReadsOnlyTheExistingScript() {
        TermuxCommandSpec audit = TermuxCommandSpec.forOperation(TermuxOperation.TUNNELS_SCRIPT_AUDIT,
                "/data/data/com.termux/files/usr", "/data/data/com.termux/files/home");
        assertTrue(audit.executable.endsWith("/bin/sed"));
        assertTrue(audit.arguments.contains("1,240p"));
        assertTrue(audit.arguments.contains("/data/data/com.termux/files/home/bin/tunnels"));
        assertTrue(audit.isSafeFixedCommand());
    }

    @Test public void envAuditPrintsOnlyKeyNamesFromKnownFiles() {
        TermuxCommandSpec audit = TermuxCommandSpec.forOperation(TermuxOperation.BUDDY_ENV_SOURCE_AUDIT,
                "/data/data/com.termux/files/usr", "/data/data/com.termux/files/home");
        assertTrue(audit.executable.endsWith("/bin/grep"));
        assertTrue(audit.arguments.contains("(BUDDY_TOKEN|BUDDY_AUTH_HEADER|CONTROL_PLANE_API_KEY)"));
        assertFalse(audit.arguments.stream().anyMatch(a -> a.contains("secret") || a.contains("Bearer")));
        assertTrue(audit.isSafeFixedCommand());
    }

    @Test public void BuddyHealthProbeUsesOnlyKnownLocalFiles() {
        TermuxCommandSpec health = TermuxCommandSpec.forOperation(TermuxOperation.BUDDY_TUNNEL_HEALTH,
                "/data/data/com.termux/files/usr", "/data/data/com.termux/files/home");
        assertTrue(health.arguments.contains("--require-control-plane-poll"));
        assertTrue(health.arguments.contains("/data/data/com.termux/files/home/.config/tunnel-client-buddy/health.url"));
        assertTrue(health.arguments.contains("/data/data/com.termux/files/home/.config/tunnel-client-buddy/buddy-myai.pid"));
        assertTrue(health.isSafeFixedCommand());
    }

    @Test public void MCPChallengeIsPinnedToLoopbackAndUnauthenticated() {
        TermuxCommandSpec probe = TermuxCommandSpec.forOperation(TermuxOperation.BUDDY_MCP_LISTENER_CHALLENGE,
                "/data/data/com.termux/files/usr", "/data/data/com.termux/files/home");
        assertTrue(probe.arguments.contains("http://127.0.0.1:8766/mcp"));
        assertFalse(probe.arguments.stream().anyMatch(a -> a.contains("Authorization")));
        assertTrue(probe.isSafeFixedCommand());
    }

    @Test public void dynamicProcReadsRequirePositiveNumericPid() {
        TermuxCommandSpec cmdline = TermuxCommandSpec.forProcessCmdline(1234);
        TermuxCommandSpec executable = TermuxCommandSpec.forProcessExecutable(1234);
        assertTrue(cmdline.isSafeFixedCommand());
        assertTrue(executable.isSafeFixedCommand());
        assertEquals("/system/bin/cat", cmdline.executable);
        assertEquals("/system/bin/readlink", executable.executable);
        assertTrue(cmdline.arguments.get(0).equals("/proc/1234/cmdline"));
        assertTrue(executable.arguments.get(0).equals("/proc/1234/exe"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void nonPositivePidIsRejected() {
        TermuxCommandSpec.forProcessCmdline(0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void relativePathsAreRejected() {
        TermuxCommandSpec.forOperation(TermuxOperation.IDENTITY, "usr", "home");
    }
}
