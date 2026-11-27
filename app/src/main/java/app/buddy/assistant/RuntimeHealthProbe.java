package app.buddy.assistant;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Local-only Tunnel Client probes. Process readiness and MCP discovery are separate signals. */
final class RuntimeHealthProbe {
    private static final int CONNECT_TIMEOUT_MS = 1200;
    private static final int READ_TIMEOUT_MS = 1200;
    private static final int MAX_HEALTH_BYTES = 1024 * 1024;

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

    /**
     * Confirms same-child MCP initialize and complete tools/list evidence.
     * expectedTools <= 0 accepts any non-empty complete tool catalog.
     */
    static boolean isMcpDiscovered(File urlFile, int expectedTools) {
        if (expectedTools < 0) return false;
        HttpURLConnection connection = null;
        try {
            connection = openLoopback(urlFile, "/health/mcp");
            if (connection.getResponseCode() != 200) return false;
            String body;
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > MAX_HEALTH_BYTES) return false;
                    output.write(buffer, 0, count);
                }
                body = new String(output.toByteArray(), StandardCharsets.UTF_8);
            }

            JSONObject root = new JSONObject(body);
            JSONObject details = root.optJSONObject("details");
            if (details == null) return false;
            JSONObject initialize = details.optJSONObject("initialize");
            JSONObject tools = details.optJSONObject("tools_list");
            if (initialize == null || tools == null) return false;
            JSONArray names = tools.optJSONArray("tool_names");
            int retained = tools.optInt("retained_count", -1);

            return root.optInt("schema_version", -1) == 1
                    && "mcp".equals(root.optString("component", ""))
                    && "ok".equals(root.optString("status", ""))
                    && "discovered".equals(root.optString("state", ""))
                    && !root.optBoolean("limited", true)
                    && "stdio".equals(details.optString("transport", ""))
                    && "running".equals(details.optString("child_state", ""))
                    && "same_child".equals(details.optString("evidence", ""))
                    && !details.optString("child_generation", "").isEmpty()
                    && initialize.optBoolean("ok", false)
                    && initialize.optBoolean("identity_complete", false)
                    && !initialize.optBoolean("limited", true)
                    && tools.optBoolean("ok", false)
                    && tools.optBoolean("complete", false)
                    && !tools.optBoolean("partial", true)
                    && !tools.optBoolean("limited", true)
                    && retained > 0
                    && names != null
                    && names.length() == retained
                    && (expectedTools == 0 || retained == expectedTools);
        } catch (Exception ignored) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
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
        URL endpoint = new URL("http://127.0.0.1:" + base.getPort() + path);
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
