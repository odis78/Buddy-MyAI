package app.buddy.assistant;

import android.content.Context;
import android.system.Os;
import android.system.OsConstants;
import android.os.Build;
import android.os.Environment;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Starts the hardened Tunnel Client as a native Android child process, outside the agent PRoot. */
final class RuntimeProcessLauncher {
    static final String API_KEY_SECRET = "tunnel_control_plane_api_key";
    // Dedicated Test224 tunnel ID supplied for this installation; never reuse Test220.
    static final String DEFAULT_TUNNEL_ID = "tunnel_15bad0275a40c4f8eb80e7baec6e3ce2";
    static final String RESERVED_TEST220_TUNNEL_ID = "tunnel_6b02e7e420948191a1829df4a5373d20";
    static final String BRIDGE_TUNNEL_ID = "tunnel_6ae28b8f01788191b42901f090964502";
    static final String DROIDMCP_TUNNEL_ID = "tunnel_6af1637e86788191aa429d5518d41938";
    static final String API_KEY_FILE = "control-plane-api-key";
    static final String AUTH_HEADER_FILE = "buddy-auth-header";
    static final String DISCOVERY_AUTH_HEADER_FILE = "buddy-discovery-auth-header";

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
        File apiKeyFifo = new File(runtimeDir, API_KEY_FILE);
        File authHeaderFifo = new File(runtimeDir, AUTH_HEADER_FILE);
        File discoveryHeaderFifo = new File(runtimeDir, DISCOVERY_AUTH_HEADER_FILE);
        Process process = null;
        try {
            createSecretFifo(apiKeyFifo);
            createSecretFifo(authHeaderFifo);
            createSecretFifo(discoveryHeaderFifo);
            File binary = RuntimeBinaryLocator.locate(app);
            File config = new File(runtimeDir, "buddy-myai.yaml");
            java.util.List<String> reaCommand = null;
            if (app.getSharedPreferences("runtime", Context.MODE_PRIVATE).getBoolean("rea_enabled", true)
                    && ReaRuntimeInstaller.isReady(app)) {
                Map<String, String> guestEnvironment = new java.util.LinkedHashMap<>();
                guestEnvironment.put("REA_MCP_INPUT_SCHEMA_PROFILE", "compact");
                guestEnvironment.put("REA_MCP_OUTPUT_SCHEMA_PROFILE", "off");
                guestEnvironment.put("REA_MCP_MAX_RESPONSE_BYTES", "10485760");
                reaCommand = Linux.commandAt(app, "/root/rea-source", guestEnvironment,
                        "/usr/bin/node", "/root/rea/scripts/rea.mjs", "mcp");
            }
            RuntimeProfileWriter.writeBuddyProfile(config, tunnelId, runtimeDir, reaCommand);
            chmod(config, 0600, "Buddy runtime profile permissions could not be secured");
            ensureSecureFile(new File(runtimeDir, "launcher.log"));
            ensureSecureFile(new File(runtimeDir, "tunnel-client.log"));
            process = createProcessBuilder(binary, config, runtimeDir).start();
            handoffSecrets(process, apiKeyFifo, apiKey, authHeaderFifo,
                    "Bearer " + buddyToken, discoveryHeaderFifo, "Bearer " + buddyToken);
            cleanupSecretFiles(runtimeDir);
            return process;
        } catch (Exception e) {
            if (process != null) process.destroyForcibly();
            cleanupSecretFiles(runtimeDir);
            throw e;
        }
    }

    static Process startBridgeConfigured(Context context) throws Exception {
        Context app = context.getApplicationContext();
        Map<String, String> guestEnvironment = new java.util.LinkedHashMap<>();
        guestEnvironment.put("TERMUX_BRIDGE_REA_ROOT", "/root/rea-source");
        java.util.List<String> command = Linux.commandAtWithSystem(app, "/root/rea-source", guestEnvironment,
                "/usr/bin/node", "/root/bridge/server/index.mjs");
        return startStdioConfigured(app, "bridge-tunnel", BRIDGE_TUNNEL_ID, command);
    }

    static Process startDroidMcpConfigured(Context context) throws Exception {
        Context app = context.getApplicationContext();
        Map<String, String> guestEnvironment = new java.util.LinkedHashMap<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            throw new IllegalStateException("Grant all-files access to enable the read-only DroidMCP filesystem tunnel");
        }
        guestEnvironment.put("DROIDMCP_ROOT", "/storage/emulated/0");
        java.util.List<String> command = Linux.commandAtWithStorage(app, "/storage/emulated/0", guestEnvironment,
                "/usr/bin/node", "/root/droidmcp/server/index.mjs");
        return startStdioConfigured(app, "droidmcp-tunnel", DROIDMCP_TUNNEL_ID, command);
    }

    private static Process startStdioConfigured(Context app, String runtimeName, String tunnelId,
                                                java.util.List<String> command) throws Exception {
        String apiKey = Secrets.get(app, API_KEY_SECRET);
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException("Buddy Tunnel Client API key is not configured");
        }
        File runtimeDir = new File(app.getFilesDir(), "runtime/" + runtimeName);
        if (!runtimeDir.isDirectory() && !runtimeDir.mkdirs()) {
            throw new IOException("stdio MCP runtime directory could not be created");
        }
        chmod(runtimeDir, 0700, "stdio MCP runtime directory permissions could not be secured");
        File apiKeyFifo = new File(runtimeDir, API_KEY_FILE);
        Process process = null;
        try {
            createSecretFifo(apiKeyFifo);
            File binary = RuntimeBinaryLocator.locate(app);
            RuntimeBinaryLocator.locateProot(app);
            File config = new File(runtimeDir, "stdio-mcp.yaml");
            RuntimeProfileWriter.writeStdioProfile(config, tunnelId, runtimeDir, command);
            chmod(config, 0600, "stdio MCP runtime profile permissions could not be secured");
            ensureSecureFile(new File(runtimeDir, "launcher.log"));
            ensureSecureFile(new File(runtimeDir, "tunnel-client.log"));
            process = createProcessBuilder(binary, config, runtimeDir).start();
            handoffSecret(process, apiKeyFifo, apiKey);
            cleanupSecretFiles(runtimeDir);
            return process;
        } catch (Exception e) {
            if (process != null) process.destroyForcibly();
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
        File prootTmp = new File(runtimeDir, "proot-tmp");
        if (!prootTmp.isDirectory() && !prootTmp.mkdirs()) {
            throw new IllegalStateException("Buddy PRoot temporary directory could not be created");
        }
        env.put("PROOT_LOADER", new File(binary.getParentFile(), "libproot-loader.so").getAbsolutePath());
        env.put("PROOT_TMP_DIR", prootTmp.getAbsolutePath());
        env.put("LD_LIBRARY_PATH", binary.getParentFile().getAbsolutePath());
        env.put("MCP_STDIO_FAILURE_POLICY", "exit");
        // Credential values are handed over through one-shot FIFOs, never argv or environment.
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(new File(runtimeDir, "launcher.log")));
        return builder;
    }

    private static void createSecretFifo(File fifo) throws IOException {
        if (fifo.exists() && !fifo.delete()) throw new IOException("Could not replace stale credential FIFO");
        try {
            Os.mkfifo(fifo.getAbsolutePath(), 0600);
        } catch (android.system.ErrnoException e) {
            throw new IOException("Could not create one-shot credential FIFO", e);
        }
    }

    private static void handoffSecrets(Process process,
                                       File apiFifo, String apiKey,
                                       File authFifo, String authHeader,
                                       File discoveryFifo, String discoveryHeader) throws IOException {
        AtomicReference<IOException> failure = new AtomicReference<>();
        Thread apiWriter = writer(process, apiFifo, apiKey, failure);
        Thread authWriter = writer(process, authFifo, authHeader, failure);
        Thread discoveryWriter = writer(process, discoveryFifo, discoveryHeader, failure);
        apiWriter.start();
        authWriter.start();
        discoveryWriter.start();

        try {
            long deadline = System.currentTimeMillis() + 15000;
            while (apiWriter.isAlive() || authWriter.isAlive() || discoveryWriter.isAlive()) {
                if (failure.get() != null) throw failure.get();
                if (!process.isAlive()) throw new IOException("Tunnel Client exited during credential handoff");
                if (System.currentTimeMillis() >= deadline) throw new IOException("Tunnel Client credential handoff timed out");
                try {
                    Thread.sleep(40);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Tunnel Client credential handoff interrupted", e);
                }
            }
            if (failure.get() != null) throw failure.get();
        } finally {
            stopWriters(apiWriter, authWriter, discoveryWriter);
        }
    }

    /**
     * Ensure credential writers do not outlive the handoff attempt. FIFO opens are
     * non-blocking and retry sleeps are interruptible, so cancellation is bounded.
     */
    private static void stopWriters(Thread... writers) {
        for (Thread writer : writers) {
            if (writer != null && writer.isAlive()) writer.interrupt();
        }
        boolean interrupted = false;
        long deadline = System.currentTimeMillis() + 1000;
        for (Thread writer : writers) {
            if (writer == null) continue;
            while (writer.isAlive()) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                try {
                    writer.join(Math.min(remaining, 100));
                } catch (InterruptedException e) {
                    interrupted = true;
                    break;
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static Thread writer(Process process, File fifo, String value,
                                 AtomicReference<IOException> failure) {
        Thread thread = new Thread(() -> {
            try {
                writeSecretFifo(fifo, value, process);
            } catch (IOException e) {
                failure.compareAndSet(null, e);
            }
        }, "buddy-runtime-secret-" + fifo.getName());
        thread.setDaemon(true);
        return thread;
    }

    private static void writeSecretFifo(File fifo, String value, Process process) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        try {
            long deadline = System.currentTimeMillis() + 12000;
            while (System.currentTimeMillis() < deadline) {
                if (!process.isAlive()) throw new IOException("Tunnel Client exited before reading credential FIFO");
                FileDescriptor fd = null;
                try {
                    fd = Os.open(fifo.getAbsolutePath(), OsConstants.O_WRONLY | OsConstants.O_NONBLOCK, 0600);
                    int offset = 0;
                    while (offset < bytes.length) {
                        if (!process.isAlive()) {
                            throw new IOException("Tunnel Client exited during credential FIFO write");
                        }
                        if (System.currentTimeMillis() >= deadline) {
                            throw new IOException("Tunnel Client credential FIFO write timed out");
                        }
                        try {
                            int written = Os.write(fd, bytes, offset, bytes.length - offset);
                            if (written <= 0) throw new IOException("Credential FIFO write made no progress");
                            offset += written;
                        } catch (android.system.ErrnoException e) {
                            if (e.errno != OsConstants.EAGAIN) {
                                throw new IOException("Credential FIFO write failed", e);
                            }
                            try {
                                Thread.sleep(20);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new IOException("Credential FIFO handoff interrupted", interrupted);
                            }
                        }
                    }
                    return;
                } catch (android.system.ErrnoException e) {
                    if (e.errno != OsConstants.ENXIO) {
                        throw new IOException("Credential FIFO handoff failed", e);
                    }
                } finally {
                    if (fd != null) {
                        try { Os.close(fd); } catch (android.system.ErrnoException ignored) { }
                    }
                }
                try {
                    Thread.sleep(40);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Credential FIFO handoff interrupted", e);
                }
            }
            throw new IOException("Tunnel Client did not open its credential FIFO");
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    private static void ensureSecureFile(File file) throws IOException {
        if (!file.exists()) {
            try (FileOutputStream ignored = new FileOutputStream(file, true)) { }
        }
        chmod(file, 0600, "Runtime log permissions could not be secured");
    }

    static void cleanupSecretsIfLoaded(Context context) {
        File runtimeDir = new File(context.getFilesDir(), "runtime/tunnel-client");
        if (RuntimeHealthProbe.isReady(new File(runtimeDir, "health.url"))) cleanupSecretFiles(runtimeDir);
    }

    static void cleanupSecretFiles(File runtimeDir) {
        if (runtimeDir == null) return;
        new File(runtimeDir, API_KEY_FILE).delete();
        new File(runtimeDir, AUTH_HEADER_FILE).delete();
        new File(runtimeDir, DISCOVERY_AUTH_HEADER_FILE).delete();
    }

    private static void chmod(File file, int mode, String message) throws IOException {
        try {
            Os.chmod(file.getAbsolutePath(), mode);
        } catch (android.system.ErrnoException e) {
            throw new IOException(message, e);
        }
    }
}
