package app.buddy.assistant;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/** Local-only Tunnel Client probes. Process, MCP discovery and remote delivery are separate signals. */
final class RuntimeHealthProbe {
    /** True when a loopback TCP port is already owned by another local process. */
    static boolean isLoopbackPortOpen(int port) {
        if (port < 1 || port > 65535) return false;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 250);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
    private static final int CONNECT_TIMEOUT_MS = 1200;
    private static final int READ_TIMEOUT_MS = 1200;
    private static final int MAX_HEALTH_BYTES = 1024 * 1024;
    private static final long DELIVERY_FRESH_MS = 10L * 60L * 1000L;

    private RuntimeHealthProbe() { }

    static boolean isBuddyMcpAvailable() { return isLoopbackPortOpen(McpServer.PORT); }

    static boolean isLoopbackPortOpen(int port) {
        if (port < 1 || port > 65535) return false;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 700);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Existing /readyz semantics: the process can accept work, not proof of MCP initialization. */
    static boolean isReady(File urlFile) {
        HttpURLConnection connection = null;
        try {
            connection = openLoopback(urlFile, "/readyz");
            return connection.getResponseCode() == 200;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    static boolean isReadyAtPort(int port) {
        HttpURLConnection connection = null;
        try {
            connection = openLoopback(port, "/readyz");
            return connection.getResponseCode() == 200;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** Confirms same-child MCP initialize/server-discover and complete tools/list evidence. */
    static boolean isMcpDiscovered(File urlFile, int expectedTools) {
        if (expectedTools < 0) return false;
        try {
            return isMcpComponent(getHealthJson(urlFile, "/health/mcp"), expectedTools);
        } catch (Exception ignored) {
            return false;
        }
    }

    static boolean isMcpDiscoveredAtPort(int port, int expectedTools) {
        if (expectedTools < 0) return false;
        try {
            return isMcpComponent(getHealthJson(port, "/health/mcp"), expectedTools);
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Confirms recent successful control-plane polling and an actually accepted response upload,
     * in addition to the complete MCP catalog. A live local process alone is not remote connectivity.
     */
    static boolean isRemoteConnectedAtPort(int port, int expectedTools) {
        if (expectedTools < 0) return false;
        try {
            return isRemoteSnapshotConnected(getHealthJson(port, "/health?details=true"), expectedTools);
        } catch (Exception ignored) {
            return false;
        }
    }

    static boolean isRemoteConnected(File urlFile, int expectedTools) {
        if (expectedTools < 0) return false;
        try {
            return isRemoteSnapshotConnected(getHealthJson(urlFile, "/health?details=true"), expectedTools);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isRemoteSnapshotConnected(JSONObject root, int expectedTools) {
            if (root.optInt("schema_version", -1) != 1
                    || !root.optBoolean("live", false)
                    || !root.optBoolean("ready", false)) return false;
            JSONObject components = root.optJSONObject("components");
            if (components == null) return false;
            JSONObject mcp = components.optJSONObject("mcp");
            JSONObject polling = components.optJSONObject("control-plane");
            JSONObject delivery = components.optJSONObject("response-delivery");
            if (mcp == null || polling == null || delivery == null
                    || !isMcpComponent(mcp, expectedTools)) return false;

            JSONObject pollDetails = polling.optJSONObject("details");
            JSONObject deliveryDetails = delivery.optJSONObject("details");
            if (pollDetails == null || deliveryDetails == null
                    || !"ok".equals(polling.optString("status", ""))
                    || !"ok".equals(delivery.optString("status", ""))
                    || pollDetails.optLong("consecutive_failures", -1) != 0
                    || deliveryDetails.optLong("accepted", 0) < 1) return false;

            long pollAgeLimitSeconds = (long) Math.max(180,
                    pollDetails.optDouble("configured_wait_seconds", 0)
                            + pollDetails.optDouble("deadline_seconds", 0) + 60);
            pollAgeLimitSeconds = Math.min(pollAgeLimitSeconds, 1800);
            if (!isRecent(pollDetails.optString("last_success", ""), pollAgeLimitSeconds * 1000L)) return false;
            return isRecent(deliveryDetails.optString("last_accepted", ""), DELIVERY_FRESH_MS);
    }

    private static boolean isMcpComponent(JSONObject root, int expectedTools) {
        JSONObject details = root.optJSONObject("details");
        if (details == null) return false;
        JSONObject initialize = details.optJSONObject("initialize");
        JSONObject serverDiscover = details.optJSONObject("server_discover");
        JSONObject tools = details.optJSONObject("tools_list");
        if (tools == null) return false;
        boolean legacyInitialized = initialize != null
                && initialize.optBoolean("ok", false)
                && initialize.optBoolean("identity_complete", false)
                && !initialize.optBoolean("limited", true);
        boolean modernDiscovered = serverDiscover != null
                && serverDiscover.optBoolean("ok", false)
                && !serverDiscover.optBoolean("limited", true);
        JSONArray names = tools.optJSONArray("tool_names");
        int retained = tools.optInt("retained_count", -1);

        return root.optInt("schema_version", 1) == 1
                && (!root.has("component") || "mcp".equals(root.optString("component", "")))
                && "ok".equals(root.optString("status", ""))
                && "discovered".equals(root.optString("state", ""))
                && !root.optBoolean("limited", true)
                && "stdio".equals(details.optString("transport", ""))
                && "running".equals(details.optString("child_state", ""))
                && "same_child".equals(details.optString("evidence", ""))
                && !details.optString("child_generation", "").isEmpty()
                && (legacyInitialized || modernDiscovered)
                && tools.optBoolean("ok", false)
                && tools.optBoolean("complete", false)
                && !tools.optBoolean("partial", true)
                && !tools.optBoolean("limited", true)
                && retained > 0
                && names != null
                && names.length() == retained
                && (expectedTools == 0 || retained == expectedTools);
    }

    private static JSONObject getHealthJson(File urlFile, String path) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = openLoopback(urlFile, path);
            if (connection.getResponseCode() != 200) throw new IllegalStateException("Health endpoint is not available");
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > MAX_HEALTH_BYTES) throw new IllegalStateException("Health response exceeded the size limit");
                    output.write(buffer, 0, count);
                }
                return new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static boolean isRecent(String timestamp, long maxAgeMs) {
        if (timestamp == null || timestamp.trim().isEmpty()) return false;
        try {
            long age = System.currentTimeMillis() - Instant.parse(timestamp).toEpochMilli();
            return age >= -30000 && age <= maxAgeMs;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static HttpURLConnection openLoopback(File urlFile, String path) throws Exception {
        if (urlFile == null || !urlFile.isFile()) throw new IllegalArgumentException("Health URL file is unavailable");
        String raw;
        try (BufferedReader reader = new BufferedReader(new FileReader(urlFile))) {
            raw = reader.readLine();
        }
        if (raw == null || raw.trim().isEmpty()) throw new IllegalArgumentException("Health URL is empty");
        URI base = URI.create(raw.trim());
        if (!"http".equalsIgnoreCase(base.getScheme())
                || !"127.0.0.1".equals(base.getHost())
                || base.getPort() <= 0
                || (base.getPath() != null && !base.getPath().isEmpty() && !"/".equals(base.getPath()))
                || base.getUserInfo() != null
                || base.getQuery() != null
                || base.getFragment() != null) {
            throw new IllegalArgumentException("Health URL must be a plain loopback HTTP address");
        }
        return openLoopback(base.getPort(), path);
    }

    private static JSONObject getHealthJson(int port, String path) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = openLoopback(port, path);
            if (connection.getResponseCode() != 200) throw new IllegalStateException("Health endpoint is not available");
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > MAX_HEALTH_BYTES) throw new IllegalStateException("Health response exceeded the size limit");
                    output.write(buffer, 0, count);
                }
                return new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static HttpURLConnection openLoopback(int port, String path) throws Exception {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid health port");
        URL endpoint = new URL("http://127.0.0.1:" + port + path);
        HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/json");
        return connection;
    }
}
