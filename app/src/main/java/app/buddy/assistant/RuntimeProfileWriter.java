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
    static final String MCP_URL = "http://127.0.0.1:" + McpServer.PORT + "/mcp";

    private RuntimeProfileWriter() { }

    static String renderBuddyProfile(String tunnelId, File runtimeDir) {
        return renderBuddyProfile(tunnelId, runtimeDir, null);
    }

    /** Adds the original REA MCP server as a second channel of Buddy's tunnel, not a fourth tunnel. */
    static String renderBuddyProfile(String tunnelId, File runtimeDir,
                                     java.util.List<String> reaCommand) {
        if (tunnelId == null || !tunnelId.matches("tunnel_[a-fA-F0-9]{32}")) {
            throw new IllegalArgumentException("Invalid Buddy tunnel id");
        }
        if (runtimeDir == null || !runtimeDir.isAbsolute()) {
            throw new IllegalArgumentException("Absolute runtime directory is required");
        }
        String dir = runtimeDir.getAbsolutePath();
        if (dir.indexOf('\n') >= 0 || dir.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Invalid runtime directory");
        }
        StringBuilder yaml = new StringBuilder()
                .append("config_version: 1\n")
                .append("control_plane:\n")
                .append("  base_url: \"https://api.openai.com\"\n")
                .append("  tunnel_id: \"").append(tunnelId).append("\"\n")
                .append("  api_key: \"file:").append(yamlQuote(dir + "/control-plane-api-key")).append("\"\n")
                .append("health:\n")
                .append("  listen_addr: \"127.0.0.1:0\"\n")
                .append("  url_file: \"").append(yamlQuote(dir + "/health.url")).append("\"\n")
                .append("process:\n")
                .append("  pid_file: \"").append(yamlQuote(dir + "/tunnel-client.pid")).append("\"\n")
                .append("log:\n")
                .append("  level: info\n")
                .append("  format: json\n")
                .append("  file: \"").append(yamlQuote(dir + "/tunnel-client.log")).append("\"\n")
                .append("mcp:\n")
                .append("  server_urls:\n")
                .append("    - channel: main\n")
                .append("      url: \"").append(MCP_URL).append("\"\n");
        if (reaCommand != null && !reaCommand.isEmpty()) {
            StringBuilder command = new StringBuilder();
            for (String part : reaCommand) {
                if (part == null || part.trim().isEmpty()
                        || part.indexOf('\n') >= 0 || part.indexOf('\r') >= 0) {
                    throw new IllegalArgumentException("Invalid REA MCP command argument");
                }
                if (command.length() > 0) command.append(' ');
                command.append(part);
            }
            yaml.append("  commands:\n")
                    .append("    - channel: rea\n")
                    .append("      command: \"").append(yamlQuote(command.toString())).append("\"\n");
        }
        yaml.append("  extra_headers:\n")
                .append("    Authorization: \"file:").append(yamlQuote(dir + "/buddy-auth-header")).append("\"\n")
                .append("  discovery_extra_headers:\n")
                .append("    Authorization: \"file:").append(yamlQuote(dir + "/buddy-discovery-auth-header")).append("\"\n");
        return yaml.toString();
    }

    static String renderStdioProfile(String tunnelId, File runtimeDir,
                                     java.util.List<String> mcpCommand) {
        if (tunnelId == null || !tunnelId.matches("tunnel_[a-fA-F0-9]{32}")) {
            throw new IllegalArgumentException("Invalid stdio tunnel id");
        }
        if (runtimeDir == null || !runtimeDir.isAbsolute()) {
            throw new IllegalArgumentException("Absolute runtime directory is required");
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

    static String renderReaProfile(String tunnelId, File runtimeDir,
                                   java.util.List<String> mcpCommand) {
        return renderStdioProfile(tunnelId, runtimeDir, mcpCommand);
    }

    static void writeStdioProfile(File target, String tunnelId, File runtimeDir,
                                  java.util.List<String> mcpCommand) throws IOException {
        if (target == null) throw new IllegalArgumentException("stdio profile target is required");
        byte[] bytes = renderStdioProfile(tunnelId, runtimeDir, mcpCommand)
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

    static void writeBuddyProfile(File target, String tunnelId, File runtimeDir) throws IOException {
        writeBuddyProfile(target, tunnelId, runtimeDir, null);
    }

    static void writeBuddyProfile(File target, String tunnelId, File runtimeDir,
                                  java.util.List<String> reaCommand) throws IOException {
        if (target == null) throw new IllegalArgumentException("Profile target is required");
        byte[] bytes = renderBuddyProfile(tunnelId, runtimeDir, reaCommand).getBytes(StandardCharsets.UTF_8);
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
