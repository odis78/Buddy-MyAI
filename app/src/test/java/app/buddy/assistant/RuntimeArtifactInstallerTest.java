package app.buddy.assistant;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class RuntimeArtifactInstallerTest {
    private static String sha(String value) throws Exception {
        byte[] bytes = java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
        return out.toString();
    }

    @Test public void installsOnlyVerifiedBytesAndReplacesAtomically() throws Exception {
        File dir = Files.createTempDirectory("buddy-runtime-test").toFile();
        File target = new File(dir, "tunnel-client");
        try {
            Files.write(target.toPath(), "old".getBytes(StandardCharsets.UTF_8));
            String value = "verified-runtime-payload";
            String actual = RuntimeArtifactInstaller.copyVerified(
                    new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)), target, sha(value));
            assertEquals(sha(value), actual);
            assertEquals(value, new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
            assertFalse(new File(dir, "tunnel-client.installing").exists());
        } finally {
            target.delete();
            dir.delete();
        }
    }

    @Test public void hashMismatchPreservesPreviousRuntime() throws Exception {
        File dir = Files.createTempDirectory("buddy-runtime-test").toFile();
        File target = new File(dir, "tunnel-client");
        try {
            Files.write(target.toPath(), "known-good".getBytes(StandardCharsets.UTF_8));
            try {
                RuntimeArtifactInstaller.copyVerified(
                        new ByteArrayInputStream("tampered".getBytes(StandardCharsets.UTF_8)),
                        target, sha("expected"));
                fail("tampered runtime accepted");
            } catch (java.io.IOException expected) {
                assertTrue(expected.getMessage().contains("SHA-256 mismatch"));
            }
            assertEquals("known-good", new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
            assertFalse(new File(dir, "tunnel-client.installing").exists());
        } finally {
            target.delete();
            dir.delete();
        }
    }

    @Test public void rejectsMalformedExpectedHashBeforeWriting() throws Exception {
        File dir = Files.createTempDirectory("buddy-runtime-test").toFile();
        File target = new File(dir, "tunnel-client");
        try {
            try {
                RuntimeArtifactInstaller.copyVerified(new ByteArrayInputStream(new byte[]{1}), target, "bad");
                fail("invalid digest accepted");
            } catch (IllegalArgumentException expected) { }
            assertFalse(target.exists());
        } finally {
            target.delete();
            dir.delete();
        }
    }
}
