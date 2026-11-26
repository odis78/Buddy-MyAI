package app.jiti.controlcenter.termux;

import org.junit.Test;
import static org.junit.Assert.*;

public class TermuxCommandSpecTest {
    @Test public void onlyFixedAbsoluteReadOnlyOperationsAreAvailable() {
        for (TermuxOperation op : TermuxOperation.values()) {
            if (op == TermuxOperation.PROCESS_CMDLINE || op == TermuxOperation.PROCESS_EXECUTABLE
                    || op == TermuxOperation.BUDDY_MCP_AUTH_PROBE || op == TermuxOperation.BUDDY_LAUNCH
                    || op == TermuxOperation.BUDDY_STOP || op == TermuxOperation.PROFILE_CONTROL) continue;
            TermuxCommandSpec spec = TermuxCommandSpec.forOperation(op,
                    "/data/data/com.termux/files/usr",
                    "/data/data/com.termux/files/home");
            assertTrue(spec.isSafeFixedCommand());
            assertTrue(spec.background);
            assertTrue(spec.executable.startsWith("/"));
        }
    }
    @Test public void buddyLauncherStatusReadsOnlyFixedStatusFile() {
        TermuxCommandSpec status = TermuxCommandSpec.forOperation(TermuxOperation.BUDDY_LAUNCH_STATUS,
                "/data/data/com.termux/files/usr", "/data/data/com.termux/files/home");
        assertEquals("/data/data/com.termux/files/usr/bin/cat", status.executable);
        assertEquals(java.util.Collections.singletonList(
                "/data/data/com.termux/files/home/.config/tunnel-client-buddy/jiti-launcher.status"),
                status.arguments);
        assertTrue(status.isSafeFixedCommand());
    }

    @Test public void pythonVersionProbeUsesFixedBinaryAndArgument() {
        TermuxCommandSpec python = TermuxCommandSpec.forOperation(TermuxOperation.PYTHON_VERSION,
                "/data/data/com.termux/files/usr", "/data/data/com.termux/files/home");
        assertTrue(python.executable.endsWith("/bin/python"));
        assertEquals(java.util.Collections.singletonList("--version"), python.arguments);
        assertTrue(python.isSafeFixedCommand());
    }

    @Test public void tunnelsScriptAuditReadsOnlyTheExistingScript() {
        TermuxCommandSpec audit = TermuxCommandSpec.forOperation(TermuxOperation.TUNNELS_SCRIPT_AUDIT,
                "/data/data/com.termux/files/usr", "/data/data/com.termux/files/home");
        assertTrue(audit.executable.endsWith("/bin/sed"));
        assertTrue(audit.arguments.contains("1,360p"));
        assertTrue(audit.arguments.contains("/data/data/com.termux/files/home/bin/tunnels"));
        assertTrue(audit.isSafeFixedCommand());
    }

    @Test public void mcpEndpointAuditReadsOnlyTheThreeFixedProfileConfigs() {
        TermuxCommandSpec audit = TermuxCommandSpec.forOperation(TermuxOperation.MCP_ENDPOINT_AUDIT,
                "/data/data/com.termux/files/usr", "/data/data/com.termux/files/home");
        assertTrue(audit.executable.endsWith("/bin/python"));
        assertEquals("-c", audit.arguments.get(0));
        String script = audit.arguments.get(1);
        assertTrue(script.contains("/.config/tunnel-client-bridge-013/bridge-013.yaml"));
        assertTrue(script.contains("/droidmcp-stage-v0.4.0/profile-dedicated/droidmcp-filesystem.yaml"));
        assertTrue(script.contains("/.config/rea-tunnel/profile/rea-android.yaml"));
        assertTrue(script.contains("SENSITIVE_KEY"));
        assertTrue(script.contains("def redact(value):"));
        assertFalse(script.contains("runtime.env"));
        assertTrue(audit.isSafeFixedCommand());
    }

    @Test public void tunnelsScriptHashProbeUsesOnlyTheFixedManagerPath() {
        TermuxCommandSpec hash = TermuxCommandSpec.forOperation(TermuxOperation.TUNNELS_SCRIPT_SHA256,
                "/data/data/com.termux/files/usr", "/data/data/com.termux/files/home");
        assertTrue(hash.executable.endsWith("/bin/sha256sum"));
        assertEquals(java.util.Collections.singletonList(
                "/data/data/com.termux/files/home/bin/tunnels"), hash.arguments);
        assertTrue(hash.isSafeFixedCommand());
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

    @Test public void allReaTunnelProfilesUseFixedHealthUrlAndPidFiles() {
        String home = "/data/data/com.termux/files/home";
        TermuxCommandSpec bridge = TermuxCommandSpec.forOperation(TermuxOperation.BRIDGE_TUNNEL_HEALTH,
                "/data/data/com.termux/files/usr", home);
        TermuxCommandSpec droid = TermuxCommandSpec.forOperation(TermuxOperation.DROIDMCP_TUNNEL_HEALTH,
                "/data/data/com.termux/files/usr", home);
        TermuxCommandSpec rea = TermuxCommandSpec.forOperation(TermuxOperation.REA_ANDROID_TUNNEL_HEALTH,
                "/data/data/com.termux/files/usr", home);

        assertTrue(bridge.isSafeFixedCommand());
        assertTrue(droid.isSafeFixedCommand());
        assertTrue(rea.isSafeFixedCommand());
        assertTrue(bridge.arguments.contains(home + "/.local/state/tunnels/bridge-013.health.url"));
        assertTrue(bridge.arguments.contains(home + "/.local/state/tunnels/bridge-013.pid"));
        assertTrue(droid.arguments.contains(home + "/.local/state/tunnels/droidmcp.health.url"));
        assertTrue(droid.arguments.contains(home + "/.local/state/tunnels/droidmcp.pid"));
        assertTrue(rea.arguments.contains(home + "/.local/state/tunnels/rea-android.health.url"));
        assertTrue(rea.arguments.contains(home + "/.local/state/tunnels/rea-android.pid"));
        for (TermuxCommandSpec probe : java.util.Arrays.asList(bridge, droid, rea)) {
            assertEquals("health", probe.arguments.get(0));
            assertTrue(probe.arguments.contains("--require-control-plane-poll"));
            assertTrue(probe.arguments.contains("--json"));
        }
    }

    @Test public void MCPChallengeIsPinnedToLoopbackAndUnauthenticated() {
        TermuxCommandSpec probe = TermuxCommandSpec.forOperation(TermuxOperation.BUDDY_MCP_LISTENER_CHALLENGE,
                "/data/data/com.termux/files/usr", "/data/data/com.termux/files/home");
        assertTrue(probe.arguments.contains("http://127.0.0.1:8766/mcp"));
        assertFalse(probe.arguments.stream().anyMatch(a -> a.contains("Authorization")));
        assertTrue(probe.isSafeFixedCommand());
    }

    @Test public void buddyLaunchAndStopScriptsAreFixedAndIdentityGuarded() {
        TermuxCommandSpec preflight = TermuxCommandSpec.forBuddyLaunch(
                "/data/data/com.termux/files/usr", "preflight");
        TermuxCommandSpec start = TermuxCommandSpec.forBuddyLaunch(
                "/data/data/com.termux/files/usr", "start");
        TermuxCommandSpec stopPreflight = TermuxCommandSpec.forBuddyStop(
                "/data/data/com.termux/files/usr", "preflight");
        TermuxCommandSpec stop = TermuxCommandSpec.forBuddyStop(
                "/data/data/com.termux/files/usr", "stop");
        assertTrue(preflight.isSafeFixedCommand());
        assertTrue(start.isSafeFixedCommand());
        assertTrue(stopPreflight.isSafeFixedCommand());
        assertTrue(stop.isSafeFixedCommand());
        assertEquals("preflight", stopPreflight.arguments.get(2));
        assertTrue(preflight.arguments.get(1).contains(".config/termux-bridge/runtime.env"));
        assertTrue(preflight.arguments.get(1).contains(".config/tunnel-client-buddy/buddy.token"));
        assertTrue(preflight.arguments.get(1).contains("CONTROL_PLANE_API_KEY"));
        assertTrue(preflight.arguments.get(1).contains("BUDDY_TOKEN"));
        assertTrue(preflight.arguments.get(1).contains("buddy_token_file_symlink_not_allowed"));
        assertTrue(preflight.arguments.get(1).contains("insecure_buddy_token_file_permissions"));
        assertTrue(preflight.arguments.get(1).contains("auth_from_token_file"));
        assertTrue(stop.arguments.get(1).contains("signal.SIGTERM"));
        assertFalse(stop.arguments.get(1).contains("signal.SIGKILL"));
        assertTrue(stop.arguments.get(1).contains("pid_identity_mismatch"));
        assertTrue(stop.arguments.get(1).contains("def finish(value, code=0):"));
        assertTrue(stop.arguments.get(1).contains("if mode == \"stop\":\n        write_status(value)"));
        assertFalse(stop.arguments.get(1).contains("emit(result)\n    write_status(result)"));
        assertTrue(stopPreflight.arguments.get(1).contains("if mode == \"preflight\":\n    finish({\"result\": \"ready_to_stop\""));
    }

    @Test public void buddyLaunchRejectsUnsupportedMode() {
        try {
            TermuxCommandSpec.forBuddyLaunch("/data/data/com.termux/files/usr", "shell");
            fail("unsupported launch mode must be rejected");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void authenticatedMcpProbeIsFixedAndNeverReturnsSecretMaterial() {
        TermuxCommandSpec probe = TermuxCommandSpec.forBuddyMcpAuthProbe(
                "/data/data/com.termux/files/usr", 1234);
        assertTrue(probe.executable.endsWith("/bin/python"));
        assertTrue(probe.arguments.get(1).contains("/proc/%d/environ"));
        assertTrue(probe.arguments.get(1).contains("BINARY = HOME +"));
        assertTrue(probe.arguments.get(1).contains("normalize(executable) == normalize(BINARY)"));
        assertTrue(probe.arguments.get(1).contains("timeout=3"));
        assertTrue(probe.arguments.get(1).contains("while page_count < 4:"));
        assertTrue(probe.arguments.get(1).contains("def process_identity(pid):"));
        assertTrue(probe.arguments.get(1).contains("pid_identity_mismatch"));
        assertTrue(probe.arguments.get(1).contains("BUDDY_AUTH_HEADER"));
        assertTrue(probe.arguments.get(1).contains("BUDDY_TOKEN"));
        assertFalse(probe.arguments.get(1).contains("Bearer very-secret"));
        assertTrue(probe.arguments.get(1).contains("ProxyHandler({})"));
        assertTrue(probe.arguments.get(1).contains("NoRedirect"));
        assertFalse(probe.arguments.get(1).contains("print(auth)"));
        assertTrue(probe.isSafeFixedCommand());
    }

    @Test(expected = IllegalArgumentException.class)
    public void authenticatedMcpProbeRejectsNonPositivePid() {
        TermuxCommandSpec.forBuddyMcpAuthProbe("/data/data/com.termux/files/usr", -1);
    }

    @Test public void profileControlUsesOnlyThreeFixedProfilesAndFiveFixedModes() {
        String prefix = "/data/data/com.termux/files/usr";
        for (String profile : java.util.Arrays.asList("bridge", "droidmcp", "rea-android")) {
            for (String mode : java.util.Arrays.asList("preflight", "status", "start", "stop", "restart")) {
                TermuxCommandSpec spec = TermuxCommandSpec.forProfileControl(prefix, profile, mode);
                assertEquals(TermuxOperation.PROFILE_CONTROL, spec.operation);
                assertEquals(prefix + "/bin/python", spec.executable);
                assertEquals("-c", spec.arguments.get(0));
                assertEquals(ProfileControlScript.SCRIPT, spec.arguments.get(1));
                assertEquals(mode, spec.arguments.get(2));
                assertEquals(profile, spec.arguments.get(3));
                assertTrue(spec.isSafeFixedCommand());
            }
        }
        assertTrue(ProfileControlScript.SCRIPT.contains("signal.SIGTERM"));
        assertFalse(ProfileControlScript.SCRIPT.contains("signal.SIGKILL"));
        assertTrue(ProfileControlScript.SCRIPT.contains("jiti-profile-control.lock"));
        assertTrue(ProfileControlScript.SCRIPT.contains("existing_tunnel_manager_busy"));
        assertFalse(ProfileControlScript.SCRIPT.contains("buddy-myai.yaml"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void profileControlRejectsUnknownProfile() {
        TermuxCommandSpec.forProfileControl("/data/data/com.termux/files/usr", "buddy", "start");
    }

    @Test(expected = IllegalArgumentException.class)
    public void profileControlRejectsUnknownMode() {
        TermuxCommandSpec.forProfileControl("/data/data/com.termux/files/usr", "bridge", "shell");
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
