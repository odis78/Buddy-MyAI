package app.buddy.assistant;

import org.junit.Test;
import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class RuntimeProcessLauncherTest {
    @Test public void keepsCredentialsOutOfArgvAndProfile() throws Exception {
        File dir = Files.createTempDirectory("buddy-launch-test").toFile();
        File binary = new File(dir, "libtunnel-client.so");
        File config = new File(dir, "buddy-myai.yaml");
        try {
            Files.write(binary.toPath(), new byte[]{1});
            RuntimeProfileWriter.writeBuddyProfile(config,
                    RuntimeProcessLauncher.DEFAULT_TUNNEL_ID, dir);
            String apiKey = "test-control-plane-secret";
            String buddyToken = "test-buddy-token";
            ProcessBuilder builder = RuntimeProcessLauncher.createProcessBuilder(
                    binary, config, dir, apiKey, buddyToken);
            assertEquals(binary.getAbsolutePath(), builder.command().get(0));
            assertFalse(builder.command().toString().contains(apiKey));
            assertFalse(builder.command().toString().contains(buddyToken));
            String yaml = new String(Files.readAllBytes(config.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            assertFalse(yaml.contains(apiKey));
            assertFalse(yaml.contains(buddyToken));
            assertEquals(apiKey, builder.environment().get("CONTROL_PLANE_API_KEY"));
            assertEquals("Bearer " + buddyToken, builder.environment().get("BUDDY_AUTH_HEADER"));
            assertEquals("/system/etc/security/cacerts", builder.environment().get("SSL_CERT_DIR"));
        } finally {
            config.delete();
            new File(dir, "launcher.log").delete();
            dir.delete();
        }
    }

    @Test public void rejectsMissingCredentialInputs() throws Exception {
        File dir = Files.createTempDirectory("buddy-launch-test").toFile();
        try {
            try {
                RuntimeProcessLauncher.createProcessBuilder(
                        new File(dir, "binary"), new File(dir, "config"), dir, "", "token");
                fail("missing API key accepted");
            } catch (IllegalArgumentException expected) { }
        } finally {
            dir.delete();
        }
    }
}
