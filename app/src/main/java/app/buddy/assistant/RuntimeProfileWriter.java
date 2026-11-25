package app.buddy.assistant;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Writes Buddy's Tunnel Client profile directly instead of using the v0.0.19 connect-profile generator.
 * Both authenticated MCP header maps are explicit and credentials remain app-private file references.
 */
final class RuntimeProfileWriter {
    static final String MCP_URL = "http://127.0.0.1:8766/mcp";

    private RuntimeProfileWriter() { }

    static String renderBuddyProfile(String tunnelId, File runtimeDir, File cloudflaredBinary) {
        if (tunnelId == null || !tunnelId.matches("tunnel_[a-fA-F0-9]{32}")) {
            throw new IllegalArgumentException("Invalid Buddy tunnel id");
        }
        if (runtimeDir == null || !runtimeDir.isAbsolute()) {
            throw new IllegalArgumentException("Absolute runtime directory is required");
        }
        if (cloudflaredBinary == null || !cloudflaredBinary.isAbsolute()) {
            throw new IllegalArgumentException("Absolute cloudflared binary path is required");
        }
        String dir = runtimeDir.getAbsolutePath();
        if (dir.indexOf('\n') >= 0 || dir.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Invalid runtime directory");
        }
        return "config_version: 1\n"
                + "control_plane:\n"
                + "  base_url: \"https://api.openai.com\"\n"
                + "  tunnel_id: \"" + tunnelId + "\"\n"
                + "  api_key: \"" + "file:" + yamlQuote(dir + "/control-plane-api-key") + "\"\n"
                + "cloudflared:\n"
                + "  managed: true\n"
                + "  path: \"" + yamlQuote(cloudflaredBinary.getAbsolutePath()) + "\"\n"
                + "health:\n"
                + "  listen_addr: \"127.0.0.1:0\"\n"
                + "  url_file: \"" + yamlQuote(dir + "/health.url") + "\"\n"
                + "process:\n"
                + "  pid_file: \"" + yamlQuote(dir + "/tunnel-client.pid") + "\"\n"
                + "log:\n"
                + "  level: info\n"
                + "  format: json\n"
                + "  file: \"" + yamlQuote(dir + "/tunnel-client.log") + "\"\n"
                + "mcp:\n"
                + "  server_urls:\n"
                + "    - channel: main\n"
                + "      url: \"" + MCP_URL + "\"\n"
                + "  extra_headers:\n"
                + "    Authorization: \"" + "file:" + yamlQuote(dir + "/buddy-auth-header") + "\"\n"
                + "  discovery_extra_headers:\n"
                + "    Authorization: \"" + "file:" + yamlQuote(dir + "/buddy-discovery-auth-header") + "\"\n";
    }

    static String renderStdioProfile(String tunnelId, File runtimeDir, File cloudflaredBinary,
                                     java.util.List<String> mcpCommand) {
        if (tunnelId == null || !tunnelId.matches("tunnel_[a-fA-F0-9]{32}")) {
            throw new IllegalArgumentException("Invalid stdio tunnel id");
        }
        if (runtimeDir == null || !runtimeDir.isAbsolute()) {
            throw new IllegalArgumentException("Absolute runtime directory is required");
        }
        if (cloudflaredBinary == null || !cloudflaredBinary.isAbsolute()) {
            throw new IllegalArgumentException("Absolute cloudflared binary path is required");
        }
        if (mcpCommand == null || mcpCommand.isEmpty()) {
            throw new IllegalArgumentException("stdio MCP command is required");
        }
        String dir = runtimeDir.getAbsolutePath();
        if (dir.indexOf('\n') >= 0 || dir.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Invalid runtime directory");
        }
        StringBuilder command = new StringBuilder();
        for (String part : mcpCommand) {
            if (part == null || part.trim().isEmpty() || part.indexOf('\n') >= 0 || part.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("Invalid stdio MCP command argument");
            }
            if (command.length() > 0) command.append(' ');
            command.append(part);
        }
        return "config_version: 1\n"
                + "control_plane:\n"
                + "  base_url: \"https://api.openai.com\"\n"
                + "  tunnel_id: \"" + tunnelId + "\"\n"
                + "  api_key: \"file:" + yamlQuote(dir + "/control-plane-api-key") + "\"\n"
                + "cloudflared:\n"
                + "  managed: true\n"
                + "  path: \"" + yamlQuote(cloudflaredBinary.getAbsolutePath()) + "\"\n"
                + "health:\n"
                + "  listen_addr: \"127.0.0.1:0\"\n"
                + "  url_file: \"" + yamlQuote(dir + "/health.url") + "\"\n"
                + "process:\n"
                + "  pid_file: \"" + yamlQuote(dir + "/tunnel-client.pid") + "\"\n"
                + "log:\n"
                + "  level: info\n"
                + "  format: json\n"
                + "  file: \"" + yamlQuote(dir + "/tunnel-client.log") + "\"\n"
                + "mcp:\n"
                + "  commands:\n"
                + "    - channel: main\n"
                + "      command: \"" + yamlQuote(command.toString()) + "\"\n";
    }

    static String renderReaProfile(String tunnelId, File runtimeDir, File cloudflaredBinary,
                                   java.util.List<String> mcpCommand) {
        return renderStdioProfile(tunnelId, runtimeDir, cloudflaredBinary, mcpCommand);
    }

    static void writeStdioProfile(File target, String tunnelId, File runtimeDir,
                                  File cloudflaredBinary, java.util.List<String> mcpCommand) throws IOException {
        if (target == null) throw new IllegalArgumentException("stdio profile target is required");
        byte[] bytes = renderStdioProfile(tunnelId, runtimeDir, cloudflaredBinary, mcpCommand)
                .getBytes(StandardCharsets.UTF_8);
        File parent = target.getAbsoluteFile().getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IOException("stdio Tunnel Client profile directory is unavailable");
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
            throw new IOException("Could not atomically write stdio Tunnel Client profile", e);
        } finally {
            if (staging.exists()) staging.delete();
        }
    }

    static void writeReaProfile(File target, String tunnelId, File runtimeDir,
                                File cloudflaredBinary, java.util.List<String> mcpCommand) throws IOException {
        writeStdioProfile(target, tunnelId, runtimeDir, cloudflaredBinary, mcpCommand);
    }

    static void writeBuddyProfile(File target, String tunnelId, File runtimeDir, File cloudflaredBinary) throws IOException {
        if (target == null) throw new IllegalArgumentException("Profile target is required");
        byte[] bytes = renderBuddyProfile(tunnelId, runtimeDir, cloudflaredBinary).getBytes(StandardCharsets.UTF_8);
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

    private static String yamlQuote(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
