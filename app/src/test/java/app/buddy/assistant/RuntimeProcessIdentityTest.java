package app.buddy.assistant;

import org.junit.Test;
import static org.junit.Assert.*;

public class RuntimeProcessIdentityTest {
    private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test public void requiresFullIdentityMatch() {
        RuntimeProcessIdentity a = new RuntimeProcessIdentity(123, 456, "/usr/bin/tunnel", HASH);
        assertTrue(a.matches(new RuntimeProcessIdentity(123, 456, "/usr/bin/tunnel", HASH)));
        assertFalse(a.matches(new RuntimeProcessIdentity(123, 457, "/usr/bin/tunnel", HASH)));
        assertFalse(a.matches(new RuntimeProcessIdentity(123, 456, "/usr/bin/other", HASH)));
        assertFalse(a.matches(new RuntimeProcessIdentity(123, 456, "/usr/bin/tunnel", HASH.replace('a', 'b'))));
    }

    @Test public void rejectsInvalidPidAndHash() {
        try { new RuntimeProcessIdentity(0, 1, "/bin/x", HASH); fail("pid accepted"); }
        catch (IllegalArgumentException expected) { }
        try { new RuntimeProcessIdentity(1, 1, "/bin/x", "not-a-hash"); fail("hash accepted"); }
        catch (IllegalArgumentException expected) { }
    }
}
