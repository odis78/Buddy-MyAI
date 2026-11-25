package app.buddy.assistant;

import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class RuntimeProcessLauncherTest {
    @Test public void credentialsStayOutOfArgvEnvironmentAndProfile() throws Exception {
        File dir = Files.createTempDirectory("buddy-launch-test").toFile();
        File binary = new File(dir, "libtunnel-client.so");
        File config = new File(dir, "buddy-myai.yaml");
        String apiKey = "test-control-plane-secret";
        String buddyToken = "test-buddy-token";
        try {
            Files.write(binary.toPath(), new byte[]{1});
            File cloudflared = new File(dir, RuntimeBinaryLocator.CLOUDFLARED_LIBRARY_NAME);
            Files.write(cloudflared.toPath(), new byte[]{2});
            Files.write(new File(dir, RuntimeProcessLauncher.API_KEY_FILE).toPath(),
                    apiKey.getBytes(StandardCharsets.UTF_8));
            Files.write(new File(dir, RuntimeProcessLauncher.AUTH_HEADER_FILE).toPath(),
                    ("Bearer " + buddyToken).getBytes(StandardCharsets.UTF_8));
            Files.write(new File(dir, RuntimeProcessLauncher.DISCOVERY_AUTH_HEADER_FILE).toPath(),
                    ("Bearer " + buddyToken).getBytes(StandardCharsets.UTF_8));
            RuntimeProfileWriter.writeBuddyProfile(config,
                    RuntimeProcessLauncher.DEFAULT_TUNNEL_ID, dir, cloudflared);
            ProcessBuilder builder = RuntimeProcessLauncher.createProcessBuilder(binary, config, dir);
            assertEquals(binary.getAbsolutePath(), builder.command().get(0));
            assertFalse(builder.command().toString().contains(apiKey));
            assertFalse(builder.command().toString().contains(buddyToken));
            assertFalse(builder.environment().containsKey("CONTROL_PLANE_API_KEY"));
            assertFalse(builder.environment().containsKey("BUDDY_AUTH_HEADER"));
            String yaml = new String(Files.readAllBytes(config.toPath()), StandardCharsets.UTF_8);
            assertFalse(yaml.contains(apiKey));
            assertFalse(yaml.contains(buddyToken));
            assertTrue(yaml.contains("file:" + new File(dir, RuntimeProcessLauncher.API_KEY_FILE).getAbsolutePath()));
            assertTrue(yaml.contains("file:" + new File(dir, RuntimeProcessLauncher.AUTH_HEADER_FILE).getAbsolutePath()));
            assertTrue(yaml.contains("file:" + new File(dir, RuntimeProcessLauncher.DISCOVERY_AUTH_HEADER_FILE).getAbsolutePath()));
            assertTrue(yaml.contains("path: \"" + cloudflared.getAbsolutePath() + "\""));
            assertEquals("/system/etc/security/cacerts", builder.environment().get("SSL_CERT_DIR"));
            assertEquals(new File(dir, "libproot-loader.so").getAbsolutePath(), builder.environment().get("PROOT_LOADER"));
            assertEquals(new File(dir, "proot-tmp").getAbsolutePath(), builder.environment().get("PROOT_TMP_DIR"));
            assertEquals(dir.getAbsolutePath(), builder.environment().get("LD_LIBRARY_PATH"));
            assertEquals("exit", builder.environment().get("MCP_STDIO_FAILURE_POLICY"));
            RuntimeProcessLauncher.cleanupSecretFiles(dir);
            assertFalse(new File(dir, RuntimeProcessLauncher.API_KEY_FILE).exists());
            assertFalse(new File(dir, RuntimeProcessLauncher.AUTH_HEADER_FILE).exists());
            assertFalse(new File(dir, RuntimeProcessLauncher.DISCOVERY_AUTH_HEADER_FILE).exists());
        } finally {
            config.delete();
            new File(dir, "launcher.log").delete();
            dir.delete();
        }
    }

    @Test public void rejectsIncompleteLaunchInputs() throws Exception {
        File dir = Files.createTempDirectory("buddy-launch-test").toFile();
        try {
            try {
                RuntimeProcessLauncher.createProcessBuilder(null, new File(dir, "config"), dir);
                fail("missing binary accepted");
            } catch (IllegalArgumentException expected) { }
        } finally {
            dir.delete();
        }
    }
}
