package app.buddy.assistant;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Writes Buddy's Tunnel Client profile directly instead of using the upstream connect-profile generator.
 * Both authenticated MCP header maps are explicit: the generator in v0.0.19 omits these fields.
 * Only secret references are written; credential values never enter the YAML file.
 */
final class RuntimeProfileWriter {
    static final String MCP_URL = "http://127.0.0.1:8766/mcp";
    static final String API_KEY_REFERENCE = "env:CONTROL_PLANE_API_KEY";
    static final String AUTH_HEADER_REFERENCE = "env:BUDDY_AUTH_HEADER";

    private RuntimeProfileWriter() { }

    static String renderBuddyProfile(String tunnelId) {
        if (tunnelId == null || !tunnelId.matches("tunnel_[a-fA-F0-9]{32}")) {
            throw new IllegalArgumentException("Invalid Buddy tunnel id");
        }
        return "config_version: 1\n"
                + "control_plane:\n"
                + "  base_url: \"https://api.openai.com\"\n"
                + "  tunnel_id: \"" + tunnelId + "\"\n"
                + "  api_key: \"" + API_KEY_REFERENCE + "\"\n"
                + "health:\n"
                + "  listen_addr: \"127.0.0.1:0\"\n"
                + "  url_file: \"/root/.config/tunnel-client/health.url\"\n"
                + "process:\n"
                + "  pid_file: \"/root/.config/tunnel-client/tunnel-client.pid\"\n"
                + "log:\n"
                + "  level: info\n"
                + "  format: json\n"
                + "  file: \"/root/.config/tunnel-client/tunnel-client.log\"\n"
                + "mcp:\n"
                + "  server_urls:\n"
                + "    - channel: main\n"
                + "      url: \"" + MCP_URL + "\"\n"
                + "  extra_headers:\n"
                + "    Authorization: \"" + AUTH_HEADER_REFERENCE + "\"\n"
                + "  discovery_extra_headers:\n"
                + "    Authorization: \"" + AUTH_HEADER_REFERENCE + "\"\n";
    }

    static void writeBuddyProfile(File target, String tunnelId) throws IOException {
        if (target == null) throw new IllegalArgumentException("Profile target is required");
        byte[] bytes = renderBuddyProfile(tunnelId).getBytes(StandardCharsets.UTF_8);
        File parent = target.getAbsoluteFile().getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IOException("Tunnel Client profile directory is unavailable");
        }
        File staging = new File(parent, target.getName() + ".installing");
        try {
            try (FileOutputStream out = new FileOutputStream(staging, false)) {
                out.write(bytes);
                out.getFD().sync();
            }
            Files.move(staging.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            throw new IOException("Could not atomically write Buddy Tunnel Client profile", e);
        } finally {
            if (staging.exists()) staging.delete();
        }
    }
}
