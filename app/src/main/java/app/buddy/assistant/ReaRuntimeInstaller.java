package app.buddy.assistant;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.StatFs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;

/** Installs only the original REA core inside Buddy's one existing Alpine/PRoot Linux runtime. */
final class ReaRuntimeInstaller {
    static final String VERSION = "6.5.0";
    static final String NODE_VERSION = "v26.13.1";
    static final int EXPECTED_TOOLS = 207;
    static final int EXPECTED_PROMPTS = 6;
    static final String PACKAGE_ASSET = "runtime/rea-agents-6.5.0.tgz";
    static final String PACKAGE_SHA256 = "531717a06e92ca651aab52691190f916fac044272c01f875e22c5e293218e699";
    static final String REA_SOURCE_COMMIT = "3b79af28e80785afce9f5998d8f03289402a1847";

    interface ProgressListener {
        void onProgress(String message);
    }

    private ReaRuntimeInstaller() { }

    static synchronized void ensureReady(Context context, ProgressListener progress) throws Exception {
        Context app = context.getApplicationContext();
        Linux.install(app);
        if (isReady(app)) return;

        File root = Linux.root(app);
        StatFs stat = new StatFs(root.getAbsolutePath());
        if (stat.getAvailableBytes() < 500L * 1024L * 1024L) {
            throw new IOException("At least 500 MB of free app storage is required for the REA runtime");
        }

        if (progress != null) progress.onProgress("Preparing original REA 6.5.0 in Buddy's Alpine runtime");
        File packageFile = new File(root, "tmp/rea-agents-6.5.0.tgz");
        copyVerifiedAsset(app.getAssets(), PACKAGE_ASSET, packageFile, PACKAGE_SHA256);

        if (progress != null) progress.onProgress("Installing REA and validating its MCP catalog (207 tools, 6 prompts)");
        String output = Linux.exec(app, buildInstallCommand(), 600);
        if (!output.trim().endsWith("[exit 0]") || !output.contains("REA_MCP_READY=207/207;6/6")) {
            throw new IOException("REA bootstrap failed: " + tail(output));
        }

        File marker = new File(root, "root/.buddy-rea-runtime-version");
        try (FileOutputStream out = new FileOutputStream(marker, false)) {
            out.write((VERSION + "\n" + NODE_VERSION + "\n" + PACKAGE_SHA256 + "\n"
                    + REA_SOURCE_COMMIT + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        packageFile.delete();
    }

    static boolean isReady(Context context) {
        File root = Linux.root(context);
        File marker = new File(root, "root/.buddy-rea-runtime-version");
        if (!marker.isFile()) return false;
        try {
            String value = new String(java.nio.file.Files.readAllBytes(marker.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            if (!value.contains(VERSION) || !value.contains(NODE_VERSION)
                    || !value.contains(PACKAGE_SHA256) || !value.contains(REA_SOURCE_COMMIT)) return false;
            String output = Linux.exec(context,
                    "node --version && test -f /root/rea/scripts/rea.mjs "
                            + "&& test -d /root/rea/node_modules "
                            + "&& test -d /root/rea-source/.git "
                            + "&& test \"$(git -C /root/rea-source rev-parse HEAD)\" = \"" + REA_SOURCE_COMMIT + "\"",
                    30);
            return output.contains(NODE_VERSION) && output.trim().endsWith("[exit 0]");
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void copyVerifiedAsset(AssetManager assets, String assetName, File target,
                                          String expectedSha256) throws Exception {
        File parent = target.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IOException("Runtime asset staging directory is unavailable");
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = assets.open(assetName);
             FileOutputStream out = new FileOutputStream(target, false)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = in.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
                out.write(buffer, 0, count);
            }
            out.getFD().sync();
        }
        String actual = hex(digest.digest());
        if (!expectedSha256.equals(actual)) {
            target.delete();
            throw new IOException("Bundled REA package failed SHA-256 verification");
        }
    }

    private static String buildInstallCommand() {
        return "set -eu; "
                + "stage=\"$(mktemp -d /root/.buddy-rea-stage.XXXXXX)\"; "
                + "backup=\"/root/.buddy-rea-backup-$(date +%s)-$$\"; "
                + "mkdir -p \"$stage/new/rea\" \"$stage/new/rea-source\"; "
                + "apk update >/tmp/buddy-apk-update.log 2>&1 || { tail -n 30 /tmp/buddy-apk-update.log; exit 1; }; "
                + "apk add --no-cache nodejs-current npm python3 make g++ linux-headers git openjdk21-jre-headless android-tools "
                + ">/tmp/buddy-apk-install.log 2>&1 || { tail -n 40 /tmp/buddy-apk-install.log; exit 1; }; "
                + "node --version | grep -F '" + NODE_VERSION + "' >/dev/null; "
                + "tar -xzf /tmp/rea-agents-6.5.0.tgz -C \"$stage/new/rea\" --strip-components=1; "
                + "cd \"$stage/new/rea\"; "
                + "npm install --omit=dev --no-audit --no-fund >/tmp/buddy-rea-npm.log 2>&1 "
                + "|| { tail -n 50 /tmp/buddy-rea-npm.log; exit 1; }; "
                + "REA_MCP_INPUT_SCHEMA_PROFILE=compact REA_MCP_OUTPUT_SCHEMA_PROFILE=off "
                + "REA_MCP_MAX_RESPONSE_BYTES=10485760 node scripts/rea.mjs mcp doctor --format json "
                + "> .buddy-mcp-doctor.json; "
                + "node -e 'const fs=require(\"fs\"); const r=JSON.parse(fs.readFileSync(\".buddy-mcp-doctor.json\",\"utf8\")); "
                + "if(!r.healthy || r.inventory.tools.expected!==" + EXPECTED_TOOLS
                + " || r.inventory.tools.observed!==" + EXPECTED_TOOLS
                + " || r.inventory.prompts.expected!==" + EXPECTED_PROMPTS
                + " || r.inventory.prompts.observed!==" + EXPECTED_PROMPTS
                + ") process.exit(2); console.log(\"REA_MCP_READY=207/207;6/6\");'; "
                + "git clone --depth 1 --branch rea-agents-6.5.0 https://github.com/morluto/rea.git \"$stage/new/rea-source\" "
                + ">/tmp/buddy-rea-source.log 2>&1 || { tail -n 40 /tmp/buddy-rea-source.log; exit 1; }; "
                + "test \"$(git -C \"$stage/new/rea-source\" rev-parse HEAD)\" = \"" + REA_SOURCE_COMMIT + "\"; "
                + "mkdir -p \"$backup\"; "
                + "for name in rea rea-source; do if [ -e \"/root/$name\" ]; then mv \"/root/$name\" \"$backup/$name\"; fi; done; "
                + "for name in rea rea-source; do "
                + "if ! mv \"$stage/new/$name\" \"/root/$name\"; then "
                + "for restore in rea rea-source; do "
                + "if [ -e \"$backup/$restore\" ]; then "
                + "if [ -e \"/root/$restore\" ]; then mv \"/root/$restore\" \"$stage/failed-$restore\"; fi; "
                + "mv \"$backup/$restore\" \"/root/$restore\"; fi; done; exit 1; fi; done; "
                + "printf '%s\\n' 'REA_RUNTIME_INSTALL_COMMITTED=1' "
                + "'PREVIOUS_RUNTIME_BACKUP=' \"$backup\"; "
                + "rm -rf \"$stage\"";
    }

    private static String tail(String value) {
        if (value == null) return "unknown error";
        String trimmed = value.trim();
        return trimmed.length() <= 1200 ? trimmed : trimmed.substring(trimmed.length() - 1200);
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format(java.util.Locale.US, "%02x", value & 0xff));
        return out.toString();
    }
}
