package app.buddy.assistant;

import android.content.Context;
import android.os.Build;
import android.system.Os;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;

/**
 * Installs the pinned, statically linked Linux ARM64 Tunnel Client into Buddy's private Alpine rootfs.
 * The asset is supplied by the pinned-source CI build; it is never loaded from Termux or shared storage.
 */
final class RuntimeArtifactInstaller {
    static final String ASSET_PATH = "runtime/tunnel-client-runtime";
    static final String EXPECTED_SHA256 = "f0fd2c503ba66a7e9e92971dec36c20cd793ed1b766b560e6f22e31ff7dd2b44";

    private RuntimeArtifactInstaller() { }

    static File installTunnelClient(Context context) throws IOException {
        boolean arm64 = false;
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) arm64 = true;
        }
        if (!arm64) throw new IOException("Tunnel Client runtime requires arm64-v8a");
        File target = new File(Linux.root(context), "root/bin/tunnel-client");
        try (InputStream input = context.getAssets().open(ASSET_PATH)) {
            copyVerified(input, target, EXPECTED_SHA256);
        }
        Os.chmod(target.getAbsolutePath(), 0700);
        return target;
    }

    /** Stream to a sibling staging file, verify before atomically replacing the installed binary. */
    static String copyVerified(InputStream input, File target, String expectedSha256) throws IOException {
        if (input == null || target == null || expectedSha256 == null
                || !expectedSha256.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Input, target and SHA-256 are required");
        }
        File parent = target.getAbsoluteFile().getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IOException("Runtime target directory is unavailable");
        }
        File staging = new File(parent, target.getName() + ".installing");
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IOException("SHA-256 unavailable", e);
        }
        try {
            try (FileOutputStream output = new FileOutputStream(staging, false)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (count == 0) continue;
                    output.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                }
                output.getFD().sync();
            }
            String actual = hex(digest.digest());
            if (!actual.equalsIgnoreCase(expectedSha256)) {
                throw new IOException("Tunnel Client runtime SHA-256 mismatch");
            }
            try {
                Files.move(staging.toPath(), target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                throw new IOException("Atomic runtime installation failed", e);
            }
            return actual;
        } finally {
            if (staging.exists()) staging.delete();
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }
}
