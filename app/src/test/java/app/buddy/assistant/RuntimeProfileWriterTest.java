package app.buddy.assistant;

import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class RuntimeProfileWriterTest {
    private static final String TUNNEL = "tunnel_6b02e7e420948191a1829df4a5373d20";
    private static final String REA_COMMAND = "/tmp/libproot.so --kill-on-exit --link2symlink -0 -r /tmp/alpine -b /dev -b /proc -b /sys -w /root/rea-source /usr/bin/env -i HOME=/root LANG=C.UTF-8 TERM=xterm-256color COLORTERM=truecolor PATH=/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin REA_MCP_INPUT_SCHEMA_PROFILE=compact REA_MCP_OUTPUT_SCHEMA_PROFILE=off REA_MCP_MAX_RESPONSE_BYTES=10485760 /usr/bin/node /root/rea/scripts/rea.mjs mcp";

    @Test public void profilePreservesBothBearerHeaderMaps() {
        String yaml = RuntimeProfileWriter.renderBuddyProfile(TUNNEL, new File("/tmp/buddy-runtime"));
        assertTrue(yaml.contains("url: \"" + RuntimeProfileWriter.MCP_URL + "\""));
        assertFalse(yaml.contains("cloudflared:"));
        assertTrue(yaml.contains("api_key: \"file:/tmp/buddy-runtime/control-plane-api-key\""));
        assertTrue(yaml.contains("extra_headers:\n    Authorization: \"file:/tmp/buddy-runtime/buddy-auth-header\""));
        assertTrue(yaml.contains("discovery_extra_headers:\n    Authorization: \"file:/tmp/buddy-runtime/buddy-discovery-auth-header\""));
        assertTrue(yaml.contains("url_file: \"/tmp/buddy-runtime/health.url\""));
        assertFalse(yaml.contains("commands:"));
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
        String expected = new String(bytes, StandardCharsets.UTF_8).replace("18766", Integer.toString(McpServer.PORT));
        assertEquals(expected, RuntimeProfileWriter.renderBuddyProfile(TUNNEL, new File("/tmp/buddy-runtime")));
    }

    @Test public void originalReaIsASecondChannelUnderTheBuddyTunnelIdentity() {
        String yaml = RuntimeProfileWriter.renderBuddyProfile(TUNNEL, new File("/tmp/buddy-runtime"),
                java.util.Collections.singletonList(REA_COMMAND));
        assertTrue(yaml.contains("tunnel_id: \"" + TUNNEL + "\""));
        assertTrue(yaml.contains("server_urls:\n    - channel: main"));
        assertTrue(yaml.contains("commands:\n    - channel: rea\n      command: \"" + REA_COMMAND + "\""));
        assertTrue(yaml.contains("REA_MCP_INPUT_SCHEMA_PROFILE=compact"));
        assertTrue(yaml.contains("REA_MCP_OUTPUT_SCHEMA_PROFILE=off"));
        assertEquals("The REA channel must not create another tunnel identity", 1,
                occurrences(yaml, "tunnel_id:"));
        assertFalse(yaml.contains("tunnel_6b0070cbe1508191b1b1463fa6ffeaa2"));
        assertFalse(yaml.contains("cloudflared:"));
        assertFalse(yaml.contains("Bearer "));
    }

    @Test public void rejectsNewlinesInReaCommandArguments() {
        try {
            RuntimeProfileWriter.renderBuddyProfile(TUNNEL, new File("/tmp/buddy-runtime"),
                    java.util.Collections.singletonList("node\nmalicious"));
            fail("newline in REA command accepted");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void generatedBridgeProfileMatchesPinnedFixture() throws Exception {
        assertProfileFixture("bridge-mcp-tunnel-profile.yaml", BRIDGE_TUNNEL,
                "/tmp/libproot.so --kill-on-exit --link2symlink -0 -r /tmp/alpine -b /dev -b /proc -b /sys -b /system -b /apex -b /vendor -b /product -b /system_ext -w /root/rea-source /usr/bin/env -i HOME=/root LANG=C.UTF-8 TERM=xterm-256color COLORTERM=truecolor PATH=/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin TERMUX_BRIDGE_REA_ROOT=/root/rea-source /usr/bin/node /root/bridge/server/index.mjs");
    }

    @Test public void generatedDroidMcpProfileMatchesPinnedFixture() throws Exception {
        assertProfileFixture("droidmcp-tunnel-profile.yaml", DROID_TUNNEL,
                "/tmp/libproot.so --kill-on-exit --link2symlink -0 -r /tmp/alpine -b /dev -b /proc -b /sys -w /storage/emulated/0 /usr/bin/env -i HOME=/root LANG=C.UTF-8 TERM=xterm-256color COLORTERM=truecolor PATH=/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin DROIDMCP_ROOT=/storage/emulated/0 /usr/bin/node /root/droidmcp/server/index.mjs");
    }

    private void assertProfileFixture(String name, String tunnelId, String command) throws Exception {
        java.io.InputStream input = getClass().getClassLoader().getResourceAsStream(name);
        assertNotNull(name + " fixture missing", input);
        byte[] bytes;
        try (java.io.InputStream in = input; java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            bytes = out.toByteArray();
        }
        assertEquals(new String(bytes, StandardCharsets.UTF_8),
                RuntimeProfileWriter.renderStdioProfile(tunnelId, new File("/tmp/buddy-helper"),
                        java.util.Collections.singletonList(command)));
    }

    @Test public void writeReaProfileCreatesCompleteAtomicProfile() throws Exception {
        File dir = Files.createTempDirectory("rea-profile").toFile();
        File target = new File(dir, "rea-mcp.yaml");
        RuntimeProfileWriter.writeReaProfile(target, REA_TUNNEL, dir,
                java.util.Collections.singletonList(REA_COMMAND));
        assertTrue(target.isFile());
        assertEquals(RuntimeProfileWriter.renderReaProfile(REA_TUNNEL, dir,
                        java.util.Collections.singletonList(REA_COMMAND)),
                new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
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
            RuntimeProfileWriter.writeBuddyProfile(target, TUNNEL, new File(dir, "runtime"),
                    java.util.Collections.singletonList(REA_COMMAND));
            String yaml = new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8);
            assertTrue(yaml.contains(TUNNEL));
            assertTrue(yaml.contains("channel: rea"));
            assertTrue(yaml.contains("discovery_extra_headers"));
            assertFalse(new File(dir, "buddy-myai.yaml.installing").exists());
        } finally {
            target.delete();
            dir.delete();
        }
    }

    private static int occurrences(String value, String token) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }
}
