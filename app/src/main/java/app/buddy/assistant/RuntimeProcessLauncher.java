package app.buddy.assistant;

import android.content.Context;
import android.system.Os;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Map;

/** Starts the hardened Tunnel Client as a native Android child process, outside the agent PRoot. */
final class RuntimeProcessLauncher {
    static final String API_KEY_SECRET = "tunnel_control_plane_api_key";
    static final String DEFAULT_TUNNEL_ID = "tunnel_6b02e7e420948191a1829df4a5373d20";
    static final String API_KEY_FILE = "control-plane-api-key";
    static final String AUTH_HEADER_FILE = "buddy-auth-header";

    private RuntimeProcessLauncher() { }

    static Process startConfigured(Context context) throws Exception {
        Context app = context.getApplicationContext();
        String apiKey = Secrets.get(app, API_KEY_SECRET);
        String buddyToken = Prefs.token(app);
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException("Buddy Tunnel Client API key is not configured");
        }
        if (buddyToken == null || buddyToken.trim().isEmpty()) {
            throw new IllegalStateException("Buddy MCP authentication token is unavailable");
        }
        String tunnelId = app.getSharedPreferences("runtime", Context.MODE_PRIVATE)
                .getString("tunnel_id", DEFAULT_TUNNEL_ID);
        File runtimeDir = new File(app.getFilesDir(), "runtime/tunnel-client");
        if (!runtimeDir.isDirectory() && !runtimeDir.mkdirs()) {
            throw new IOException("Buddy runtime directory could not be created");
        }
        chmod(runtimeDir, 0700, "Buddy runtime directory permissions could not be secured");
        File apiKeyFile = new File(runtimeDir, API_KEY_FILE);
        File authHeaderFile = new File(runtimeDir, AUTH_HEADER_FILE);
        try {
            writeSecretFile(apiKeyFile, apiKey);
            writeSecretFile(authHeaderFile, "Bearer " + buddyToken);
            File config = new File(runtimeDir, "buddy-myai.yaml");
            RuntimeProfileWriter.writeBuddyProfile(config, tunnelId, runtimeDir);
            chmod(config, 0600, "Buddy runtime profile permissions could not be secured");
            File binary = RuntimeBinaryLocator.locate(app);
            return createProcessBuilder(binary, config, runtimeDir).start();
        } catch (Exception e) {
            cleanupSecretFiles(runtimeDir);
            throw e;
        }
    }

    static ProcessBuilder createProcessBuilder(File binary, File config, File runtimeDir) {
        if (binary == null || config == null || runtimeDir == null) {
            throw new IllegalArgumentException("Runtime launch inputs are incomplete");
        }
        ProcessBuilder builder = new ProcessBuilder(Arrays.asList(
                binary.getAbsolutePath(), "run", "--config", config.getAbsolutePath()));
        builder.directory(runtimeDir);
        Map<String, String> env = builder.environment();
        env.clear();
        env.put("HOME", runtimeDir.getAbsolutePath());
        env.put("TMPDIR", runtimeDir.getAbsolutePath());
        env.put("LANG", "C.UTF-8");
        env.put("PATH", binary.getParentFile().getAbsolutePath());
        // Go's Linux cert pool reads Android's system trust directory through this explicit override.
        env.put("SSL_CERT_DIR", "/system/etc/security/cacerts");
        // Credential values are read from 0600 app-private files, never argv or environment.
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(new File(runtimeDir, "launcher.log")));
        return builder;
    }

    static void writeSecretFile(File target, String value) throws IOException {
        if (target == null || value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Secret file input is incomplete");
        }
        writeSecretFileAtomically(target, value);
        chmod(target, 0600, "Could not secure runtime credential file");
    }

    static void writeSecretFileAtomically(File target, String value) throws IOException {
        if (target == null || value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Secret file input is incomplete");
        }
        File parent = target.getAbsoluteFile().getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IOException("Secret directory is unavailable");
        }
        File staging = new File(parent, target.getName() + ".installing");
        try {
            try (FileOutputStream out = new FileOutputStream(staging, false)) {
                out.write(value.getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            Files.move(staging.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            throw new IOException("Could not securely write runtime credential file", e);
        } finally {
            if (staging.exists()) staging.delete();
        }
    }

    static void cleanupSecretFiles(File runtimeDir) {
        if (runtimeDir == null) return;
        new File(runtimeDir, API_KEY_FILE).delete();
        new File(runtimeDir, AUTH_HEADER_FILE).delete();
        new File(runtimeDir, API_KEY_FILE + ".installing").delete();
        new File(runtimeDir, AUTH_HEADER_FILE + ".installing").delete();
    }

    private static void chmod(File file, int mode, String message) throws IOException {
        try {
            Os.chmod(file.getAbsolutePath(), mode);
        } catch (android.system.ErrnoException e) {
            throw new IOException(message, e);
        }
    }
}
