package app.buddy.assistant;

import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class RuntimeBinaryLocatorTest {
    private static String sha(String value) throws Exception {
        byte[] bytes = java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
        return out.toString();
    }

    @Test public void verifiesExactRuntimeBinaryBytes() throws Exception {
        File dir = Files.createTempDirectory("buddy-runtime-binary").toFile();
        File binary = new File(dir, "libtunnel-client.so");
        try {
            Files.write(binary.toPath(), "verified-runtime".getBytes(StandardCharsets.UTF_8));
            assertEquals(sha("verified-runtime"), RuntimeBinaryLocator.verify(binary, sha("verified-runtime")));
        } finally {
            binary.delete();
            dir.delete();
        }
    }

    @Test public void rejectsModifiedRuntimeBinary() throws Exception {
        File dir = Files.createTempDirectory("buddy-runtime-binary").toFile();
        File binary = new File(dir, "libtunnel-client.so");
        try {
            Files.write(binary.toPath(), "tampered-runtime".getBytes(StandardCharsets.UTF_8));
            try {
                RuntimeBinaryLocator.verify(binary, sha("expected-runtime"));
                fail("modified runtime accepted");
            } catch (java.io.IOException expected) {
                assertTrue(expected.getMessage().contains("SHA-256 mismatch"));
            }
        } finally {
            binary.delete();
            dir.delete();
        }
    }
}
