package app.jiti.controlcenter.termux;

import org.junit.Test;
import static org.junit.Assert.*;

public class TermuxCommandSpecTest {
    @Test public void onlyFixedAbsoluteReadOnlyOperationsAreAvailable() {
        for (TermuxOperation op : TermuxOperation.values()) {
            TermuxCommandSpec spec = TermuxCommandSpec.forOperation(op,
                    "/data/data/com.termux/files/usr",
                    "/data/data/com.termux/files/home");
            assertTrue(spec.isSafeFixedCommand());
            assertTrue(spec.background);
            assertTrue(spec.executable.startsWith("/"));
        }
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

    @Test(expected = IllegalArgumentException.class)
    public void relativePathsAreRejected() {
        TermuxCommandSpec.forOperation(TermuxOperation.IDENTITY, "usr", "home");
    }
}
