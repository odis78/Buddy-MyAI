package app.buddy.assistant;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;

/** Local-only Tunnel Client readiness probe. Never follows a URL outside loopback. */
final class RuntimeHealthProbe {
    private RuntimeHealthProbe() { }

    static boolean isReady(File urlFile) {
        if (urlFile == null || !urlFile.isFile()) return false;
        HttpURLConnection connection = null;
        try (BufferedReader reader = new BufferedReader(new FileReader(urlFile))) {
            String raw = reader.readLine();
            if (raw == null || raw.trim().isEmpty()) return false;
            URI base = URI.create(raw.trim());
            if (!"http".equalsIgnoreCase(base.getScheme())
                    || !"127.0.0.1".equals(base.getHost())
                    || base.getPort() <= 0
                    || (base.getPath() != null && !base.getPath().isEmpty() && !"/".equals(base.getPath()))
                    || base.getUserInfo() != null) return false;
            URL readyUrl = new URL("http://127.0.0.1:" + base.getPort() + "/readyz");
            connection = (HttpURLConnection) readyUrl.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(1200);
            connection.setReadTimeout(1200);
            connection.setUseCaches(false);
            return connection.getResponseCode() == 200;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
