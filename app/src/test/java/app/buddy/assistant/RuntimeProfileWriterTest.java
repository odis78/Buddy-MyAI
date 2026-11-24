package app.buddy.assistant;

import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class RuntimeProfileWriterTest {
    private static final String TUNNEL = "tunnel_6b02e7e420948191a1829df4a5373d20";

    @Test public void profilePreservesBothBearerHeaderMaps() {
        String yaml = RuntimeProfileWriter.renderBuddyProfile(TUNNEL);
        assertTrue(yaml.contains("url: \"http://127.0.0.1:8766/mcp\""));
        assertTrue(yaml.contains("api_key: \"env:CONTROL_PLANE_API_KEY\""));
        assertTrue(yaml.contains("extra_headers:\n    Authorization: \"env:BUDDY_AUTH_HEADER\""));
        assertTrue(yaml.contains("discovery_extra_headers:\n    Authorization: \"env:BUDDY_AUTH_HEADER\""));
        assertTrue(yaml.contains("health.url_file") == false);
        assertFalse(yaml.contains("Bearer "));
        assertFalse(yaml.contains("api_key: \"sk-"));
    }

    @Test public void rejectsMalformedTunnelId() {
        try {
            RuntimeProfileWriter.renderBuddyProfile("../not-a-tunnel");
            fail("invalid tunnel id accepted");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void writesProfileAtomicallyAndReplacesPreviousVersion() throws Exception {
        File dir = Files.createTempDirectory("buddy-profile-test").toFile();
        File target = new File(dir, "buddy-myai.yaml");
        try {
            Files.write(target.toPath(), "old".getBytes(StandardCharsets.UTF_8));
            RuntimeProfileWriter.writeBuddyProfile(target, TUNNEL);
            String yaml = new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8);
            assertTrue(yaml.contains(TUNNEL));
            assertTrue(yaml.contains("discovery_extra_headers"));
            assertFalse(new File(dir, "buddy-myai.yaml.installing").exists());
        } finally {
            target.delete();
            dir.delete();
        }
    }
}
