package app.buddy.assistant;

import android.content.Context;
import android.system.Os;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;

/** Starts the authenticated Tunnel Client as a native Android child process, outside the agent PRoot. */
final class RuntimeProcessLauncher {
    static final String API_KEY_SECRET = "tunnel_control_plane_api_key";
    static final String DEFAULT_TUNNEL_ID = "tunnel_6b02e7e420948191a1829df4a5373d20";

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
        try {
            Os.chmod(runtimeDir.getAbsolutePath(), 0700);
        } catch (android.system.ErrnoException e) {
            throw new IOException("Buddy runtime directory permissions could not be secured", e);
        }
        File config = new File(runtimeDir, "buddy-myai.yaml");
        RuntimeProfileWriter.writeBuddyProfile(config, tunnelId, runtimeDir);
        try {
            Os.chmod(config.getAbsolutePath(), 0600);
        } catch (android.system.ErrnoException e) {
            throw new IOException("Buddy runtime profile permissions could not be secured", e);
        }
        File binary = RuntimeBinaryLocator.locate(app);
        return createProcessBuilder(binary, config, runtimeDir, apiKey, buddyToken).start();
    }

    static ProcessBuilder createProcessBuilder(File binary, File config, File runtimeDir,
                                               String apiKey, String buddyToken) {
        if (binary == null || config == null || runtimeDir == null
                || apiKey == null || apiKey.trim().isEmpty()
                || buddyToken == null || buddyToken.trim().isEmpty()) {
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
        // Secrets are environment-only. The patched runtime sets PR_SET_DUMPABLE=0 before loading config.
        env.put("CONTROL_PLANE_API_KEY", apiKey);
        env.put("BUDDY_AUTH_HEADER", "Bearer " + buddyToken);
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(new File(runtimeDir, "launcher.log")));
        return builder;
    }
}
