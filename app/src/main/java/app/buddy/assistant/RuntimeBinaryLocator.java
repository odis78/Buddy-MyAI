package app.buddy.assistant;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;

/**
 * Locates the static Linux ARM64 runtime extracted by Android from lib/arm64-v8a.
 * Android restricts execution from ordinary app data files; nativeLibraryDir is the supported executable location.
 */
final class RuntimeBinaryLocator {
    static final String LIBRARY_NAME = "libtunnel-client.so";
    static final String EXPECTED_SHA256 = "cc013fae9b04523b078e6417fe5d379972e1d0d1b11b2f77b072d5f2442516eb";

    private RuntimeBinaryLocator() { }

    static File locate(Context context) throws IOException {
        File binary = new File(context.getApplicationInfo().nativeLibraryDir, LIBRARY_NAME);
        if (!binary.isFile() || !binary.canExecute()) {
            throw new IOException("Pinned Tunnel Client native runtime is missing or not executable");
        }
        verify(binary, EXPECTED_SHA256);
        return binary;
    }

    static File locateProot(Context context) throws IOException {
        File binary = new File(context.getApplicationInfo().nativeLibraryDir, "libproot.so");
        File loader = new File(context.getApplicationInfo().nativeLibraryDir, "libproot-loader.so");
        if (!binary.isFile() || !binary.canExecute() || !loader.isFile()) {
            throw new IOException("Bundled PRoot runtime or loader is missing");
        }
        return binary;
    }

    static String verify(File file, String expectedSha256) throws IOException {
        if (file == null || expectedSha256 == null || !expectedSha256.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException("File and SHA-256 are required");
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IOException("SHA-256 unavailable", e);
        }
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count > 0) digest.update(buffer, 0, count);
            }
        }
        StringBuilder actual = new StringBuilder(64);
        for (byte value : digest.digest()) {
            actual.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        }
        String result = actual.toString();
        if (!result.equalsIgnoreCase(expectedSha256)) {
            throw new IOException("Tunnel Client runtime SHA-256 mismatch");
        }
        return result;
    }
}
