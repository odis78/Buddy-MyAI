package app.jiti.controlcenter.termux;

import org.junit.Test;
import static org.junit.Assert.*;

public class TermuxInstallStateTest {
    @Test public void resultCallbacksRequireTermux0109OrNewer() {
        TermuxCommandClient.InstallState old = new TermuxCommandClient.InstallState(true, true, "0.108.0", "/data/user/0/com.termux", "");
        TermuxCommandClient.InstallState supported = new TermuxCommandClient.InstallState(true, true, "0.118.0", "/data/user/0/com.termux", "");
        assertFalse(old.canInvoke());
        assertTrue(supported.canInvoke());
    }
    @Test public void permissionIsMandatory() {
        TermuxCommandClient.InstallState state = new TermuxCommandClient.InstallState(true, false, "0.118.0", "/data/user/0/com.termux", "");
        assertFalse(state.canInvoke());
    }
}
