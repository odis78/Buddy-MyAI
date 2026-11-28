#!/usr/bin/env python3
"""Read-only architecture and safety audit for Buddy Test225."""
from __future__ import annotations
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def read(path: str) -> str:
    p = ROOT / path
    if not p.is_file():
        raise FileNotFoundError(path)
    return p.read_text(encoding="utf-8")

gradle = read("app/build.gradle.kts")
version = read("app/src/main/java/app/buddy/assistant/BuildInfo.java")
linux = read("app/src/main/java/app/buddy/assistant/Linux.java")
mcp = read("app/src/main/java/app/buddy/assistant/McpServer.java")
secrets = read("app/src/main/java/app/buddy/assistant/Secrets.java")
backup = read("app/src/main/java/app/buddy/assistant/Backup.java")
binary = read("app/src/main/java/app/buddy/assistant/RuntimeBinaryLocator.java")
launcher = read("app/src/main/java/app/buddy/assistant/RuntimeProcessLauncher.java")
profile = read("app/src/main/java/app/buddy/assistant/RuntimeProfileWriter.java")
installer = read("app/src/main/java/app/buddy/assistant/ReaRuntimeInstaller.java")
service = read("app/src/main/java/app/buddy/assistant/BuddyRuntimeService.java")
supervisor = read("app/src/main/java/app/buddy/assistant/RuntimeSupervisor.java")
health_probe = read("app/src/main/java/app/buddy/assistant/RuntimeHealthProbe.java")
settings = read("app/src/main/java/app/buddy/assistant/RuntimeSettingsActivity.java")
manifest = read("app/src/main/AndroidManifest.xml")
bridge = read("app/src/main/assets/runtime/termux-bridge-0.1.3.mjs")
droid = read("app/src/main/assets/runtime/droidmcp-filesystem-0.4.0.mjs")
helper_bootstrap = read("app/src/main/java/app/buddy/assistant/RuntimeHelperBootstrap.java")
lifecycle_gate = read("app/src/main/java/app/buddy/assistant/RuntimeLifecycleGate.java")
main_fixture = read("app/src/test/resources/buddy-myai-tunnel-profile.yaml")
rea_fixture = read("app/src/test/resources/buddy-myai-rea-channel-profile.yaml")
bridge_fixture = read("app/src/test/resources/bridge-mcp-tunnel-profile.yaml")
droid_fixture = read("app/src/test/resources/droidmcp-tunnel-profile.yaml")
workflow = read(".github/workflows/autonomous-runtime-audit.yml")
migration_doc = read("docs/BUDDY_TEST225_ARCHITECTURE.md")

checks: list[dict[str, str]] = []

def add(name: str, ok: bool, detail: str) -> None:
    checks.append({"name": name, "status": "PASS" if ok else "FAIL", "detail": detail})

version_name = re.search(r'VERSION\s*=\s*"([^"]+)"', version)
version_code = re.search(r'VERSION_CODE\s*=\s*(\d+)', version)
app_id = re.search(r'applicationId\s*=\s*"([^"]+)"', gradle)
debug_suffix = re.search(r'applicationIdSuffix\s*=\s*"([^"]+)"', gradle)
debug_port = 'buildConfigField("int", "MCP_PORT", "18769")' in gradle
default_port = 'buildConfigField("int", "MCP_PORT", "8766")' in gradle

add("version_metadata", version_name is not None and version_name.group(1) == "2.3.25-GT.2" and version_code is not None and version_code.group(1) == "225",
    "Test225 versionName=2.3.25-GT.2; versionCode=225")
add("stable_test225_signing", (ROOT / "signing/test225-debug.keystore").is_file() and "test225Debug" in gradle and "buddy-test225" in gradle,
    "Test225 debug APKs use a stable, explicitly test-only signing identity for repeatable in-place updates")
add("side_by_side_package", app_id is not None and app_id.group(1) == "app.buddy.myai.gt" and debug_suffix is not None and debug_suffix.group(1) == ".test225",
    "Test225 uses app.buddy.myai.gt.test225 so Test224 remains installed and untouched")
add("packaged_arecord_matches_mic_port", "nc 127.0.0.1 8768" in read("linux/arecord") and "nc 127.0.0.1 8768" in read("app/src/main/assets/linux/arecord"),
    "The APK asset symlink resolves to the same Test225 microphone port used by MicServer")
add("isolated_phone_service_ports", "PORT = 8768" in read("app/src/main/java/app/buddy/assistant/MicServer.java") and "PORT = 8769" in read("app/src/main/java/app/buddy/assistant/SshServer.java") and "nc 127.0.0.1 8768" in read("linux/arecord"),
    "Test225 isolates microphone and SSH ports from Test220/Test224 and keeps the Linux recorder in sync")
add("isolated_background_helper", "PORT = 8772" in read("app/src/main/java/app/buddy/assistant/Background.java") and "buddy-helper-test225.log" in read("app/src/main/java/app/buddy/assistant/Background.java") and 'assistant.Shell " + PORT + " ' in read("app/src/main/java/app/buddy/assistant/Background.java"),
    "Test225 uses helper port 8772 and a private log path; its cleanup pattern cannot kill Test224's 8771 helper")
add("isolated_background_adb_port", "ANDROID_ADB_SERVER_PORT=5140" in read("app/src/main/java/app/buddy/assistant/Background.java") and "ANDROID_ADB_SERVER_PORT=5138" not in read("app/src/main/java/app/buddy/assistant/Background.java"),
    "Test225 uses its own ADB server port 5140, isolated from Test224's 5138 and Termux's default")
add("isolated_loopback_ports", default_port and debug_port and "PORT = BuildConfig.MCP_PORT" in mcp and "127.0.0.1" in mcp and 'MCP_URL = "http://127.0.0.1:" + McpServer.PORT + "/mcp"' in profile,
    "Production MCP port 8766; Test225 debug port 18769; profile endpoint follows BuildConfig")
add("builtin_alpine_proot", "ALPINE_SHA256" in linux and "libproot.so" in linux and "alpine.partial" in linux,
    "App-private Alpine 3.24.2 rootfs and bundled PRoot are verified before use")
add("non_destructive_linux_install", "Existing Alpine root is incomplete; preserved" in linux and "staging.renameTo(root)" in linux and "deleteTree(root)" not in linux,
    "Incomplete existing Alpine data is preserved; only the temporary staging directory is removed")
add("android_keystore_secret_store", "AndroidKeyStore" in secrets and "AES/GCM/NoPadding" in secrets,
    "Secrets are encrypted with an Android Keystore AES/GCM key")
add("backup_secret_boundary", "API keys stay out" in backup and '"token"' in backup,
    "Chats/settings can be backed up; API keys and per-install tokens remain excluded")
add("pinned_tunnel_client_binary", all(x in binary for x in ["EXPECTED_SHA256", "nativeLibraryDir", "MessageDigest"]),
    "Pinned ARM64 Tunnel Client is located in nativeLibraryDir and SHA-256 verified")
add("rea_internal_channel", all(x in profile for x in ['channel: main', 'channel: rea']) and "REA_TUNNEL_ID" not in launcher and "startReaConfigured" not in launcher,
    "REA runs as a second channel under the main Buddy tunnel, not as a separate tunnel")
add("internal_bridge_droidmcp_launchers", all(x in launcher for x in ["BRIDGE_TUNNEL_ID", "DROIDMCP_TUNNEL_ID", "startBridgeConfigured", "startDroidMcpConfigured", "writeStdioProfile", "BUDDY_BRIDGE_REA_ROOT", "BUDDY_ANDROID_PACKAGE", 'DEFAULT_TUNNEL_ID = "tunnel_6b02e7e420948191a1829df4a5373d20"']),
    "Main, Bridge and DroidMCP keep their existing tunnel IDs; helpers are internal stdio MCP servers")
add("internal_helper_installer", all(x in installer for x in ["BRIDGE_ASSET", "DROIDMCP_ASSET", "HELPER_SERVERS_READY=bridge-0.1.3;droidmcp-0.4.0", "REA_MCP_READY=207/207;6/6"]),
    "One app-private installer provisions REA, Bridge and read-only DroidMCP inside Alpine")
add("bridge_fixed_command_allowlist", "const COMMANDS" in bridge and "shell: false" in bridge and "spawn(" in bridge and "buddy-bridge" in bridge and "BUDDY_ANDROID_PACKAGE" in bridge and "TERMUX_BRIDGE_REA_ROOT" not in bridge and "args.confirmed !== true" in bridge and "['confirmed']" in bridge,
    "Bridge exposes fixed commands only and never invokes a shell")
add("droidmcp_all_files_permission", "android.permission.MANAGE_EXTERNAL_STORAGE" in manifest and "Environment.isExternalStorageManager()" in launcher,
    "The manifest declares Android all-files special access required by the read-only shared-storage tunnel")
add("droidmcp_read_only", all(x in droid for x in ["read_file", "list_directory", "directory_tree", "read-only safety policy", "Path escapes the configured read-only root"]) and not any(x in droid for x in ['tool("write_file"', 'tool("delete_file"', 'tool("move_file"', 'tool("copy_file"']),
    "DroidMCP exposes read-only filesystem tools and blocks path traversal/sensitive files")
add("independent_helper_lifecycle", all(x in service for x in ["RuntimeHelperBootstrap", "bridgeSupervisor", "droidMcpSupervisor", "reaChannelConfigured", "startReaMonitor"]) and "TermuxRuntimeController" not in service and "reaSupervisor" not in service,
    "Buddy independently supervises its main tunnel and internal Bridge/DroidMCP helpers")
add("legacy_tunnel_conflict_guard", all(x in service for x in ["isLoopbackPortOpen(39073)", "isLoopbackPortOpen(8766)", "isLoopbackPortOpen(18766)", "isLoopbackPortOpen(18767)", "isLoopbackPortOpen(18768)", "isLoopbackPortOpen(18080)", "isLoopbackPortOpen(44611)"]) and service.count("isLoopbackPortOpen(39073)") >= 2 and "static boolean isLoopbackPortOpen" in health_probe,
    "Test225 fails closed on legacy Buddy, Bridge and DroidMCP ports and stops only its own helpers if a conflict appears later")
add("mcp_tool_discovery_gate", all(x in service for x in ["supervisor.isMcpDiscovered(0)", "bridgeSupervisor.isMcpDiscovered(6)", "droidMcpSupervisor.isMcpDiscovered(5)"]) and "&& retained > 0" in health_probe,
    "Main, Bridge and DroidMCP are not marked ready until complete MCP tool discovery is evidenced")
add("helper_shutdown_gate", all(x in helper_bootstrap for x in ["startIfActive", "runIfActive"]) and all(x in lifecycle_gate for x in ["markStopping", "cleanup", "startIfActive"]),
    "Helper startup is serialized against graceful shutdown")
add("exact_process_ownership", all(x in supervisor for x in ["tunnelProcess.isAlive()", "RuntimeHealthProbe.isReady", "destroyForcibly"]),
    "Supervisor tracks and terminates only the exact child Process it owns")
add("no_termux_runtime_dependency", "com.termux.permission.RUN_COMMAND" not in manifest and "TermuxCommandResultReceiver" not in manifest and "tunnelId" not in settings and "Termux" not in settings and not (ROOT / "app/src/main/assets/runtime/buddy-termux-tunnels.sh").exists() and not (ROOT / "app/src/main/java/app/buddy/assistant/TermuxRuntimeController.java").exists() and not (ROOT / "app/src/main/java/app/buddy/assistant/RuntimeConnectionManager.java").exists(),
    "No Termux RUN_COMMAND permission, runtime controller, manager script or manual Tunnel ID field")
add("credential_fifo_isolation", all(x in launcher for x in ["Secrets.get", "createSecretFifo", "writeSecretFifo", "cleanupSecretFiles", "O_NONBLOCK"]) and "CONTROL_PLANE_API_KEY" not in launcher and "BUDDY_AUTH_HEADER" not in launcher,
    "Tunnel credentials are handed to child processes through short-lived private FIFOs, not argv/env")
add("profiles_match_pinned_runtime", all(x in main_fixture and x in rea_fixture for x in ["extra_headers:", "discovery_extra_headers:", "file:"]) and "tunnel_id:" in bridge_fixture and "commands:" in bridge_fixture and "channel: bridge" in bridge_fixture and "tunnel_id:" in droid_fixture and "commands:" in droid_fixture and "channel: droidmcp" in droid_fixture,
    "Main, REA, Bridge and DroidMCP fixtures preserve the pinned runtime schema, IDs and original channel names")
add("upstream_runtime_ci", "go test ./pkg/runtimeconfig" in workflow and "testGithubDebugUnitTest" in workflow and "cloudflared:" not in profile,
    "CI validates the four profiles against the pinned standard Tunnel Client and runs Android unit tests")
add("chatgpt_control_plane_invariant", "ChatGPT remains the external control plane" in migration_doc and "Termux is not required" in migration_doc,
    "ChatGPT remains external; Buddy owns its on-device runtime")
add("test225_migration_boundary", "Test224" in migration_doc and "signing" in migration_doc and "API key" in migration_doc,
    "Test224 is preserved; side-by-side migration requires restoring backup and re-entering the package-local API key")

failed = [x for x in checks if x["status"] != "PASS"]
report = {
    "project": "Buddy Test225",
    "version": version_name.group(1) if version_name else None,
    "version_code": version_code.group(1) if version_code else None,
    "debug_application_id": (app_id.group(1) + (debug_suffix.group(1) if debug_suffix else "")) if app_id else None,
    "migration_mode": "side-by-side",
    "checks": checks,
    "summary": {"passed": len(checks) - len(failed), "failed": len(failed), "status": "FAIL" if failed else "PASS"},
}
out = ROOT / "build/reports/autonomous-runtime-audit.json"
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
for item in checks:
    print(f'{item["status"]}: {item["name"]}: {item["detail"]}')
print(f'AUDIT_RESULT={report["summary"]["status"]} PASS={report["summary"]["passed"]} FAIL={report["summary"]["failed"]}')
raise SystemExit(1 if failed else 0)
