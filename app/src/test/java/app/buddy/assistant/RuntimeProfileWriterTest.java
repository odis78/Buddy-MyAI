package app.buddy.assistant;

import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class RuntimeProfileWriterTest {
    private static final String TUNNEL = "tunnel_6b02e7e420948191a1829df4a5373d20";

    @Test public void profilePreservesBothBearerHeaderMaps() {
        String yaml = RuntimeProfileWriter.renderBuddyProfile(TUNNEL, new File("/tmp/buddy-runtime"));
        assertTrue(yaml.contains("url: \"http://127.0.0.1:8766/mcp\""));
        assertTrue(yaml.contains("api_key: \"env:CONTROL_PLANE_API_KEY\""));
        assertTrue(yaml.contains("extra_headers:\n    Authorization: \"env:BUDDY_AUTH_HEADER\""));
        assertTrue(yaml.contains("discovery_extra_headers:\n    Authorization: \"env:BUDDY_AUTH_HEADER\""));
        assertTrue(yaml.contains("url_file: \"/tmp/buddy-runtime/health.url\""));
        assertFalse(yaml.contains("Bearer "));
        assertFalse(yaml.contains("api_key: \"sk-"));
    }


    @Test public void generatedProfileMatchesPinnedRuntimeFixture() throws Exception {
        java.io.InputStream input = getClass().getClassLoader()
                .getResourceAsStream("buddy-myai-tunnel-profile.yaml");
        assertNotNull("runtime profile fixture missing", input);
        byte[] bytes;
        try (java.io.InputStream in = input; java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            bytes = out.toByteArray();
        }
        assertEquals(new String(bytes, StandardCharsets.UTF_8), RuntimeProfileWriter.renderBuddyProfile(TUNNEL, new File("/tmp/buddy-runtime")));
    }

    @Test public void rejectsMalformedTunnelId() {
        try {
            RuntimeProfileWriter.renderBuddyProfile("../not-a-tunnel", new File("/tmp/buddy-runtime"));
            fail("invalid tunnel id accepted");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void writesProfileAtomicallyAndReplacesPreviousVersion() throws Exception {
        File dir = Files.createTempDirectory("buddy-profile-test").toFile();
        File target = new File(dir, "buddy-myai.yaml");
        try {
            Files.write(target.toPath(), "old".getBytes(StandardCharsets.UTF_8));
            RuntimeProfileWriter.writeBuddyProfile(target, TUNNEL, new File(dir, "runtime"));
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
