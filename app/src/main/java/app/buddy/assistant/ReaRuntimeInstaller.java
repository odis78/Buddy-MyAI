package app.buddy.assistant;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.StatFs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;

/** First-run installer for the isolated REA 6.5.0 MCP runtime inside Buddy's Alpine rootfs. */
final class ReaRuntimeInstaller {
    static final String VERSION = "6.5.0";
    static final String NODE_VERSION = "v26.13.1";
    static final int EXPECTED_TOOLS = 207;
    static final int EXPECTED_PROMPTS = 6;
    static final String PACKAGE_ASSET = "runtime/rea-agents-6.5.0.tgz";
    static final String PACKAGE_SHA256 = "531717a06e92ca651aab52691190f916fac044272c01f875e22c5e293218e699";
    static final String BRIDGE_ASSET = "runtime/termux-bridge-0.1.3.mjs";
    static final String BRIDGE_SHA256 = "2b7add212d42e5b22502c4c56ded4423ae5de5de3d847c4970e2e845c7ae5da4";
    static final String DROIDMCP_ASSET = "runtime/droidmcp-filesystem-0.4.0.mjs";
    static final String DROIDMCP_SHA256 = "75156254216e1b524d9e5eaac03fe113b432529a9bd1009e8698558c8d03a265";
    static final String HELPER_PROBE_ASSET = "runtime/helper_mcp_catalog_probe.mjs";
    static final String HELPER_PROBE_SHA256 = "4b2cbe541523eea21f4d60371e8aa4e97fee248dbc94ac0deb881a799e3e7805";
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

        if (progress != null) progress.onProgress("Preparing Alpine Node.js 26 runtime");
        File packageFile = new File(root, "tmp/rea-agents-6.5.0.tgz");
        File bridgeFile = new File(root, "tmp/termux-bridge-0.1.3.mjs");
        File droidFile = new File(root, "tmp/droidmcp-filesystem-0.4.0.mjs");
        File probeFile = new File(root, "tmp/helper_mcp_catalog_probe.mjs");
        copyVerifiedAsset(app.getAssets(), PACKAGE_ASSET, packageFile, PACKAGE_SHA256);
        copyVerifiedAsset(app.getAssets(), BRIDGE_ASSET, bridgeFile, BRIDGE_SHA256);
        copyVerifiedAsset(app.getAssets(), DROIDMCP_ASSET, droidFile, DROIDMCP_SHA256);
        copyVerifiedAsset(app.getAssets(), HELPER_PROBE_ASSET, probeFile, HELPER_PROBE_SHA256);

        if (progress != null) progress.onProgress("Preparing Buddy-local REA and validating packaged Bridge/DroidMCP assets");
        String command = buildInstallCommand();
        String output = Linux.exec(app, command, 600);
        if (!output.trim().endsWith("[exit 0]") || !output.contains("REA_MCP_READY=207/207;6/6")
                || !output.contains("HELPER_MCP_READY=6/6;5/5")) {
            throw new IOException("REA bootstrap failed: " + tail(output));
        }

        File marker = new File(root, "root/.buddy-rea-runtime-version");
        try (FileOutputStream out = new FileOutputStream(marker, false)) {
            out.write((VERSION + "\n" + NODE_VERSION + "\n" + PACKAGE_SHA256 + "\n"
                    + BRIDGE_SHA256 + "\n" + DROIDMCP_SHA256 + "\n" + REA_SOURCE_COMMIT + "\n"
                    + HELPER_PROBE_SHA256 + "\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        packageFile.delete();
        bridgeFile.delete();
        droidFile.delete();
        probeFile.delete();
    }

    static boolean isReady(Context context) {
        File root = Linux.root(context);
        File marker = new File(root, "root/.buddy-rea-runtime-version");
        if (!marker.isFile()) return false;
        try {
            String value = new String(java.nio.file.Files.readAllBytes(marker.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            if (!value.contains(VERSION) || !value.contains(NODE_VERSION)
                    || !value.contains(PACKAGE_SHA256) || !value.contains(BRIDGE_SHA256)
                    || !value.contains(DROIDMCP_SHA256) || !value.contains(REA_SOURCE_COMMIT)
                    || !value.contains(HELPER_PROBE_SHA256)) return false;
            // Shared storage is mounted only for the DroidMCP child, not in generic Linux.exec checks.
            String output = Linux.exec(context,
                    "node --version && java -version >/dev/null 2>&1 && adb version >/dev/null 2>&1 && test -f /root/rea/scripts/rea.mjs && test -d /root/rea/node_modules && test -d /root/rea-source/.git && test \"$(git -C /root/rea-source rev-parse HEAD)\" = \"" + REA_SOURCE_COMMIT + "\" && test -f /root/bridge/server/index.mjs && test -f /root/droidmcp/server/index.mjs && test -f /root/rea/scripts/helper_mcp_catalog_probe.mjs && node -e 'const fs=require("fs"); const r=JSON.parse(fs.readFileSync("/root/rea/.buddy-helper-mcp-probe.json","utf8")); if(r.bridge?.version!=="0.1.3" || r.bridge?.tools!==6 || !r.bridge?.sampleOk || r.droidmcp?.version!=="0.4.0" || r.droidmcp?.tools!==5 || !r.droidmcp?.sampleOk) process.exit(2);'",
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
            throw new IOException("Bundled runtime asset failed SHA-256 verification: " + assetName);
        }
    }

    private static String buildInstallCommand() {
        return "set -eu; "
                + "stage=\"$(mktemp -d /root/.buddy-runtime-stage.XXXXXX)\"; "
                + "backup=\"/root/.buddy-runtime-backup-$(date +%s)-$$\"; "
                + "mkdir -p \"$stage/new/rea\" \"$stage/new/rea-source\" \"$stage/new/bridge/server\" \"$stage/new/droidmcp/server\"; "
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
                + "cp /tmp/termux-bridge-0.1.3.mjs \"$stage/new/bridge/server/index.mjs\"; "
                + "cp /tmp/droidmcp-filesystem-0.4.0.mjs \"$stage/new/droidmcp/server/index.mjs\"; "
                + "cp /tmp/helper_mcp_catalog_probe.mjs \"$stage/new/rea/scripts/helper_mcp_catalog_probe.mjs\"; "
                + "node --check \"$stage/new/bridge/server/index.mjs\"; "
                + "node --check \"$stage/new/droidmcp/server/index.mjs\"; "
                + "node \"$stage/new/rea/scripts/helper_mcp_catalog_probe.mjs\" \"$stage/new/bridge/server/index.mjs\" \"$stage/new/droidmcp/server/index.mjs\" > .buddy-helper-mcp-probe.json; "
                + "node -e 'const fs=require(\"fs\"); const r=JSON.parse(fs.readFileSync(\".buddy-helper-mcp-probe.json\",\"utf8\")); if(r.bridge?.version!==\"0.1.3\" || r.bridge?.tools!==6 || !r.bridge?.sampleOk || r.droidmcp?.version!==\"0.4.0\" || r.droidmcp?.tools!==5 || !r.droidmcp?.sampleOk) process.exit(3); console.log(\"HELPER_MCP_READY=6/6;5/5\");'; "
                + "node --version; npm --version; java -version; adb version; "
                + "mkdir -p \"$backup\"; "
                + "for name in rea rea-source bridge droidmcp; do "
                + "if [ -e \"/root/$name\" ]; then mv \"/root/$name\" \"$backup/$name\"; fi; done; "
                + "for name in rea rea-source bridge droidmcp; do "
                + "if ! mv \"$stage/new/$name\" \"/root/$name\"; then "
                + "for restore in rea rea-source bridge droidmcp; do "
                + "if [ -e \"$backup/$restore\" ]; then "
                + "if [ -e \"/root/$restore\" ]; then mv \"/root/$restore\" \"$stage/failed-$restore\"; fi; "
                + "mv \"$backup/$restore\" \"/root/$restore\"; fi; done; exit 1; fi; done; "
                + "printf '%s\\n' 'REA_RUNTIME_INSTALL_COMMITTED=1' "
                + "'PREVIOUS_RUNTIME_BACKUP='\"$backup\"; "
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
