package app.jiti.controlcenter.termux;

import org.junit.Test;
import static org.junit.Assert.*;

public class TunnelProcessIdentityTest {
    private static final String HOME = "/data/data/com.termux/files/home";
    private static final String BIN = HOME + "/tunnel-client-android-build-v0.0.19/bin/tunnel-client-android-arm64";
    private static final String CONFIG = HOME + "/.config/tunnel-client-buddy/buddy-myai.yaml";

    @Test public void exactExecutablePathIsRequired() {
        assertTrue(TunnelProcessIdentity.matchesExecutable(BIN));
        assertFalse(TunnelProcessIdentity.matchesExecutable("/system/bin/sh"));
        assertFalse(TunnelProcessIdentity.matchesExecutable(BIN + " (deleted)"));
    }

    @Test public void exactBuddyConfigArgumentIsRequired() {
        String cmdline = String.join(String.valueOf((char) 0), BIN, "run", "--config", CONFIG);
        assertTrue(TunnelProcessIdentity.matchesBuddy(BIN, cmdline, HOME));
        String wrongConfig = String.join(String.valueOf((char) 0), BIN, "run", "--config",
                HOME + "/.config/rea-tunnel/profile/rea-android.yaml");
        assertFalse(TunnelProcessIdentity.matchesBuddy(BIN, wrongConfig, HOME));
        String doctor = String.join(String.valueOf((char) 0), BIN, "doctor", "--config", CONFIG);
        assertFalse(TunnelProcessIdentity.matchesBuddy(BIN, doctor, HOME));
    }

    @Test public void AndroidTermuxDataPathAliasesAreNormalized() {
        String appInfoHome = "/data/user/0/com.termux/files/home";
        String cmdline = String.join(String.valueOf((char) 0), BIN, "run", "--config", CONFIG);
        assertTrue(TunnelProcessIdentity.matchesBuddyConfig(cmdline, appInfoHome));
    }

    @Test public void configEqualsFormIsSupported() {
        String cmdline = String.join(String.valueOf((char) 0), BIN, "run", "--config=" + CONFIG);
        assertTrue(TunnelProcessIdentity.matchesBuddyConfig(cmdline, HOME));
    }
}
