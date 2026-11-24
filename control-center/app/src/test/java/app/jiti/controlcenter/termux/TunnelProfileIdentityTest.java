package app.jiti.controlcenter.termux;

import org.junit.Test;
import static org.junit.Assert.*;

public class TunnelProfileIdentityTest {
    private static final String HOME = "/data/data/com.termux/files/home";
    private static final String EXE = HOME
            + "/tunnel-client-android-build-v0.0.19/bin/tunnel-client-android-arm64";

    private String cmd(String... args) {
        return String.join(String.valueOf((char) 0), args) + String.valueOf((char) 0);
    }

    @Test public void bridgeAndDroidProfilesRequireExactConfigArgument() {
        String bridge = cmd(EXE, "run", "--config",
                HOME + "/.config/tunnel-client-bridge-013/bridge-013.yaml");
        assertTrue(TunnelProfileIdentity.matchesProfile(EXE, bridge, HOME,
                "/.config/tunnel-client-bridge-013/bridge-013.yaml", "--config"));

        String droid = cmd(EXE, "run", "--config",
                HOME + "/droidmcp-stage-v0.0.4/profile-dedicated/droidmcp-filesystem.yaml");
        assertTrue(TunnelProfileIdentity.matchesProfile(EXE, droid, HOME,
                "/droidmcp-stage-v0.0.4/profile-dedicated/droidmcp-filesystem.yaml", "--config"));
    }

    @Test public void reaAndroidUsesProfileFileFlag() {
        String rea = cmd(EXE, "run", "--profile-file",
                HOME + "/.config/rea-tunnel/profile/rea-android.yaml");
        assertTrue(TunnelProfileIdentity.matchesProfile(EXE, rea, HOME,
                "/.config/rea-tunnel/profile/rea-android.yaml", "--profile-file"));
    }

    @Test public void equalsFormAndAndroidPathAliasAreSupported() {
        String cmdline = cmd(EXE, "run", "--config=/data/user/0/com.termux/files/home/.config/x/profile.yaml");
        assertTrue(TunnelProfileIdentity.matchesProfile(EXE, cmdline, HOME,
                "/.config/x/profile.yaml", "--config"));
    }

    @Test public void mismatchedExecutableOrConfigIsRejected() {
        String cmdline = cmd(EXE, "run", "--config", HOME + "/.config/other/profile.yaml");
        assertFalse(TunnelProfileIdentity.matchesProfile(EXE, cmdline, HOME,
                "/.config/bridge/profile.yaml", "--config"));
        assertFalse(TunnelProfileIdentity.matchesProfile("/system/bin/sh", cmdline, HOME,
                "/.config/other/profile.yaml", "--config"));
        assertFalse(TunnelProfileIdentity.matchesExecutable(EXE + " (deleted)"));
    }

    @Test public void unsafeConfigPathsAndFlagsAreRejected() {
        String cmdline = cmd(EXE, "run", "--config", HOME + "/../other.yaml");
        assertFalse(TunnelProfileIdentity.matchesProfile(EXE, cmdline, HOME,
                "/../other.yaml", "--config"));
        assertFalse(TunnelProfileIdentity.matchesProfile(EXE, cmdline, HOME,
                "/.config/other.yaml", "--arbitrary"));
    }
}
