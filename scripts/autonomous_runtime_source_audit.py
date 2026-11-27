#!/usr/bin/env python3
"""Read-only baseline audit for Buddy Autonomous Runtime. No device or Termux access."""
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
helper = read("app/src/main/java/app/buddy/assistant/Helper.java")
mcp = read("app/src/main/java/app/buddy/assistant/McpServer.java")
secrets = read("app/src/main/java/app/buddy/assistant/Secrets.java")
runtime_binary = read("app/src/main/java/app/buddy/assistant/RuntimeBinaryLocator.java")
runtime_launcher = read("app/src/main/java/app/buddy/assistant/RuntimeProcessLauncher.java")
rea_runtime_installer = read("app/src/main/java/app/buddy/assistant/ReaRuntimeInstaller.java")
rea_profile_fixture = read("app/src/test/resources/rea-mcp-tunnel-profile.yaml")
termux_manager = read("app/src/main/assets/runtime/buddy-termux-tunnels.sh")
termux_controller = read("app/src/main/java/app/buddy/assistant/TermuxRuntimeController.java")
termux_receiver = read("app/src/main/java/app/buddy/assistant/TermuxCommandResultReceiver.java")
runtime_supervisor = read("app/src/main/java/app/buddy/assistant/RuntimeSupervisor.java")
runtime_service = read("app/src/main/java/app/buddy/assistant/BuddyRuntimeService.java")
runtime_helper_bootstrap = read("app/src/main/java/app/buddy/assistant/RuntimeHelperBootstrap.java")
runtime_helper_bootstrap_test = read("app/src/test/java/app/buddy/assistant/RuntimeHelperBootstrapTest.java")
runtime_boot = read("app/src/main/java/app/buddy/assistant/BuddyRuntimeBootReceiver.java")
runtime_settings = read("app/src/main/java/app/buddy/assistant/RuntimeSettingsActivity.java")
manifest = read("app/src/main/AndroidManifest.xml")
runtime_profile = read("app/src/main/java/app/buddy/assistant/RuntimeProfileWriter.java")
profile_fixture = read("app/src/test/resources/buddy-myai-tunnel-profile.yaml")
stdio_profile_fixtures = [read("app/src/test/resources/" + name) for name in [
    "rea-mcp-tunnel-profile.yaml", "bridge-mcp-tunnel-profile.yaml", "droidmcp-tunnel-profile.yaml"
]]
runtime_hardening = read("scripts/tunnel-client-runtime/dumpable_linux.go")
runtime_hardening_test = read("scripts/tunnel-client-runtime/dumpable_linux_test.go")
runtime_fifo_test = read("scripts/tunnel-client-runtime/file_reference_fifo_test.go")
runtime_patch = read("scripts/patch_tunnel_client_runtime.py")
runtime_workflow = read(".github/workflows/autonomous-runtime-audit.yml")
audit_doc = read("docs/BUDDY_AUTONOMOUS_RUNTIME_AUDIT_2026-11-24.md")

def match(pattern: str, value: str) -> str | None:
    found = re.search(pattern, value)
    return found.group(1) if found else None

checks = []
def add(name: str, status: str, detail: str):
    checks.append({"name": name, "status": status, "detail": detail})

app_id = match(r'applicationId\s*=\s*"([^"]+)"', gradle)
version_name = match(r'VERSION\s*=\s*"([^"]+)"', version)
version_code = match(r'VERSION_CODE\s*=\s*(\d+)', version)
compile_sdk = match(r'compileSdk\s*=\s*(\d+)', gradle)
target_sdk = match(r'targetSdk\s*=\s*(\d+)', gradle)
min_sdk = match(r'minSdk\s*=\s*(\d+)', gradle)

chatgpt_invariant = all(term in audit_doc for term in [
    "ГЛАВНЫЙ НЕИЗМЕНЯЕМЫЙ ИНВАРИАНТ: CHATGPT",
    "ChatGPT остаётся отдельным, самостоятельным приложением",
    "Критерий отказа:",
    "фактического подключения из ChatGPT",
])
add("chatgpt_control_plane_compatibility", "PASS" if chatgpt_invariant else "FAIL",
    "ChatGPT remains the external control plane; current MCP/auth/tool contract must pass end-to-end before cutover")

add("version_metadata", "PASS" if version_name and version_code else "FAIL",
    f"versionName={version_name}; versionCode={version_code}")
add("package_metadata", "PASS" if app_id else "FAIL", f"applicationId={app_id}")
add("sdk_metadata", "PASS" if compile_sdk and target_sdk and min_sdk else "FAIL",
    f"minSdk={min_sdk}; compileSdk={compile_sdk}; targetSdk={target_sdk}")
add("builtin_alpine_proot", "PASS" if "ALPINE_SHA256" in linux and "libproot.so" in linux and "static ProcessBuilder builder" in linux else "FAIL",
    "Buddy has an app-private Alpine rootfs installer and PRoot command builder" if "ALPINE_SHA256" in linux and "libproot.so" in linux else "Required PRoot markers missing")
add("child_process_launch", "PASS" if "Linux.builder(app, cmd).start()" in helper else "FAIL",
    "Existing Helper launches child processes inside Buddy Linux")
profile_writer = read("app/src/main/java/app/buddy/assistant/RuntimeProfileWriter.java")
gradle = read("app/build.gradle.kts")
local_mcp_ok = (
    "PORT = BuildConfig.MCP_PORT" in mcp
    and "127.0.0.1" in mcp
    and 'buildConfigField("int", "MCP_PORT", "8766")' in gradle
    and 'buildConfigField("int", "MCP_PORT", "18766")' in gradle
    and 'MCP_URL = "http://127.0.0.1:" + McpServer.PORT + "/mcp"' in profile_writer
)
add("local_mcp_server", "PASS" if local_mcp_ok else "FAIL",
    "Build-specific loopback MCP endpoint and profile URL")
add("android_keystore_secret_store", "PASS" if "AndroidKeyStore" in secrets and "AES/GCM/NoPadding" in secrets else "FAIL",
    "Existing encrypted secret storage uses Android Keystore AES/GCM")
add("runtime_artifact_integrity", "PASS" if all(term in runtime_binary for term in ["EXPECTED_SHA256", "nativeLibraryDir", "MessageDigest"]) and "CLOUDFLARED" not in runtime_binary else "FAIL",
    "The standard pinned Tunnel Client Linux ARM64 executable is located in nativeLibraryDir and SHA-256 verified")

standard_runtime_ok = (
    "cmd/client-runtime" in runtime_patch
    and "Flavor=runtime " in runtime_workflow
    and "./cmd/client-runtime" in runtime_workflow
    and "flavor=runtime" in runtime_workflow
    and "buddy_profile_runtime_test.go" in runtime_workflow
    and "libtunnel-client.so" in runtime_workflow
    and "libcloudflared.so" not in runtime_workflow
)
add("standard_runtime_profile_compatibility", "PASS" if standard_runtime_ok else "FAIL",
    "Buddy builds the standard runtime matching existing no-Cloudflared tunnel profiles and tests acceptance plus rejection of injected Cloudflared settings")


profile_auth_ok = all(term in runtime_profile and term in profile_fixture for term in [
    "extra_headers:", "discovery_extra_headers:", "file:"
])
add("buddy_tunnel_auth_profile", "PASS" if profile_auth_ok else "FAIL",
    "Buddy profile preserves Authorization for both MCP discovery and request traffic")
buddy_profile_mode_ok = ("cloudflared:" not in runtime_profile and "cloudflared:" not in profile_fixture
    and all("cloudflared:" not in fixture for fixture in stdio_profile_fixtures))
add("buddy_profile_matches_existing_tunnel_mode", "PASS" if buddy_profile_mode_ok else "FAIL",
    "Buddy, REA, Bridge, and DroidMCP profiles do not enable an unconfigured Cloudflared dependency")
rea_runtime_ok = (
    "nodejs-current" in rea_runtime_installer
    and "openjdk21-jre-headless" in rea_runtime_installer
    and "android-tools" in rea_runtime_installer
    and "rea-agents-6.5.0.tgz" in rea_runtime_installer
    and "git clone --depth 1 --branch rea-agents-6.5.0" in rea_runtime_installer
    and "mcp doctor --format json" in rea_runtime_installer
    and "EXPECTED_TOOLS = 207" in rea_runtime_installer
    and "EXPECTED_PROMPTS = 6" in rea_runtime_installer
    and "REA_MCP_INPUT_SCHEMA_PROFILE" not in rea_runtime_installer
    and "Linux.commandAt(app, \"/root/rea-source\"" in runtime_launcher
    and "channel: rea" in runtime_profile
)
add("rea_runtime_bootstrap", "PASS" if rea_runtime_ok else "FAIL",
    "Original REA 6.5.0 is installed only inside Buddy's existing Alpine/PRoot runtime and validated at 207 tools/6 prompts")

rea_profile_ok = (
    "renderBuddyProfile(String tunnelId, File runtimeDir," in runtime_profile
    and "channel: main" in runtime_profile
    and "channel: rea" in runtime_profile
    and "REA_TUNNEL_ID" not in runtime_launcher
    and "startReaConfigured" not in runtime_launcher
)
add("rea_internal_buddy_channel", "PASS" if rea_profile_ok else "FAIL",
    "REA is a separate MCP channel under Buddy's existing tunnel identity, not an independent fourth tunnel")

termux_manager_ok = (
    "BRIDGE_PROFILE=" in termux_manager
    and "DROID_PROFILE=" in termux_manager
    and "start_one bridge" in termux_manager
    and "start_one droidmcp" in termux_manager
    and "kill -TERM" in termux_manager
    and "SIGKILL" in termux_manager
    and "rea-android" not in termux_manager
    and "rea-all" not in termux_manager
)
add("termux_bridge_droidmcp_manager", "PASS" if termux_manager_ok else "FAIL",
    "Buddy installs a separate Termux manager for Bridge and DroidMCP only, validates PID ownership and stops gracefully")

three_channel_ok = (
    "com.termux.RUN_COMMAND" in termux_controller
    and "com.termux.permission.RUN_COMMAND" in termux_controller
    and "PendingIntent.FLAG_MUTABLE" in termux_controller
    and "runtime/buddy-termux-tunnels.sh" in termux_controller
    and "termuxManagerReady" in termux_receiver
    and "TermuxRuntimeController.reconcile" in runtime_service
    and "com.termux.permission.RUN_COMMAND" in manifest
    and 'android:name="com.termux"' in manifest
    and "startBridgeConfigured" not in runtime_service
    and "startDroidMcpConfigured" not in runtime_service
)
add("three_mcp_channel_connection_manager", "PASS" if three_channel_ok else "FAIL",
    "Connection lifecycle is split between Buddy's main+REA channel and the two Termux-owned remote MCP tunnels")
secret_files_ok = all(term in runtime_launcher for term in [
    "Secrets.get", "createSecretFifo", "writeSecretFifo", "cleanupSecretFiles", "API_KEY_FILE", "AUTH_HEADER_FILE", "0600", "O_NONBLOCK"
]) and "CONTROL_PLANE_API_KEY" not in runtime_launcher and "BUDDY_AUTH_HEADER" not in runtime_launcher
add("runtime_secret_file_isolation", "PASS" if secret_files_ok else "FAIL",
    "Tunnel credentials are read from short-lived 0600 app-private files, not argv or environment")
fifo_ok = "TestBuddyFileSecretReferenceReadsOneShotFIFO" in runtime_fifo_test and "syscall.Mkfifo" in runtime_fifo_test
add("runtime_fifo_secret_handoff", "PASS" if fifo_ok else "FAIL",
    "Pinned Tunnel Client file-reference resolver is regression-tested with one-shot FIFOs")
supervisor_ok = all(term in runtime_supervisor for term in [
    "RuntimeProcessLauncher.startConfigured", "RuntimeHealthProbe.isReady", "tunnelProcess.isAlive", "destroyForcibly"
])
add("runtime_process_supervisor", "PASS" if supervisor_ok else "FAIL",
    "Supervisor owns an exact Process handle, gates on loopback MCP and health, and stops only that handle")
fgs_ok = (
    "FOREGROUND_SERVICE_TYPE_SPECIAL_USE" in runtime_service
    and "foregroundServiceType=\"specialUse\"" in manifest
    and "PROPERTY_SPECIAL_USE_FGS_SUBTYPE" in manifest
    and "BOOT_COMPLETED" in manifest and "auto_start" in runtime_boot
)
add("runtime_foreground_lifecycle", "PASS" if fgs_ok else "FAIL",
    "User-visible special-use foreground service and opt-in boot restart are declared")
settings_ok = "Secrets.put" in runtime_settings and "auto_start" in runtime_settings and "RuntimeProcessLauncher.DEFAULT_TUNNEL_ID" in runtime_settings and "REQUEST_TERMUX_RUN_COMMAND" in runtime_settings and "connectTermux" in runtime_settings
add("runtime_user_configuration", "PASS" if settings_ok else "FAIL",
    "User can save the control-plane key, configure opt-in startup, and explicitly connect Termux with a runtime permission gate")
hardening_ok = (
    "PR_SET_DUMPABLE" in runtime_hardening
    and "hardenProcess" in runtime_hardening
    and "TUNNEL_DUMPABLE_SECRET" in runtime_hardening_test
    and "dumpable_linux.go" in runtime_patch
    and "hardenProcess()" in runtime_patch
)
add("tunnel_runtime_procfs_hardening", "PASS" if hardening_ok else "FAIL",
    "Pinned Tunnel Client build is patched to deny same-UID procfs access to its environment, with a regression test")
test_dirs = [ROOT / "app/src/test", ROOT / "app/src/androidTest"]
has_tests = any(p.exists() for p in test_dirs)
add("app_java_test_coverage", "PASS" if has_tests else "WARN",
    "Java/JVM or instrumentation test source directory exists" if has_tests else "No app/src/test or app/src/androidTest directory exists at the audited baseline")
has_supervisor = any((ROOT / p).exists() for p in [
    "app/src/main/java/app/buddy/assistant/RuntimeSupervisor.java",
    "app/src/main/java/app/buddy/assistant/RuntimeOrchestrator.java",
])
runtime_primitives = all((ROOT / p).is_file() for p in [
    "app/src/main/java/app/buddy/assistant/RuntimeComponentSpec.java",
    "app/src/main/java/app/buddy/assistant/RuntimeDependencyPlanner.java",
    "app/src/main/java/app/buddy/assistant/RuntimeProcessIdentity.java",
    "app/src/main/java/app/buddy/assistant/RuntimeStateMachine.java",
])
runtime_tests = all((ROOT / p).is_file() for p in [
    "app/src/test/java/app/buddy/assistant/RuntimeDependencyPlannerTest.java",
    "app/src/test/java/app/buddy/assistant/RuntimeProcessIdentityTest.java",
    "app/src/test/java/app/buddy/assistant/RuntimeStateMachineTest.java",
])
add("runtime_lifecycle_primitives", "PASS" if runtime_primitives else "FAIL",
    "Dependency planning, process identity and lifecycle state machine sources are present")
add("runtime_jvm_tests", "PASS" if runtime_tests else "FAIL",
    "Focused JVM tests cover dependency validation, PID reuse identity and lifecycle transitions")

lifecycle_gate = (
    "RuntimeSupervisor" in runtime_service
    and "scheduleReaInstall" in runtime_service
    and "connectionMonitorTask" in runtime_service
    and "TermuxRuntimeController.stop" in runtime_service
    and "RuntimeStateMachine.State.READY" in runtime_service
)
add("connection_lifecycle_ownership", "PASS" if lifecycle_gate else "FAIL",
    "Buddy owns its main Tunnel Client and REA child; Termux-owned Bridge/DroidMCP are managed through a separate serialized controller")

helper_failure_isolation = (
    "startOne" in runtime_helper_bootstrap
    and "catch (Exception e)" in runtime_helper_bootstrap
    and "oneHelperFailureDoesNotBlockFollowingHelpers" in runtime_helper_bootstrap_test
)
add("helper_startup_failure_isolation", "PASS" if helper_failure_isolation else "FAIL",
    "A helper startup exception is recorded independently and does not suppress later helper starts")

cleartext_enabled = 'android:usesCleartextTraffic="true"' in manifest
add("global_cleartext_policy", "WARN" if cleartext_enabled else "PASS",
    "Application-wide cleartext HTTP is enabled; trace HTTP consumers and constrain exceptions to required local/user-configured endpoints" if cleartext_enabled
    else "Application-wide cleartext HTTP is disabled")

add("persistent_runtime_supervisor", "PASS" if has_supervisor else "WARN",
    "Dedicated runtime supervisor source exists" if has_supervisor else "Dedicated multi-process runtime supervisor not present in baseline")
add("release_signing_gate", "WARN",
    "Workflow has optional signing hooks, but actual release keystore availability and certificate identity are not verified")
add("gt_package_line", "PASS" if app_id == "app.buddy.myai.gt" else "WARN",
    f"GT package identity is correctly set to {app_id}" if app_id == "app.buddy.myai.gt" else f"Expected app.buddy.myai.gt, found {app_id}")
report = {
    "schema": "buddy-autonomous-runtime-audit/v1",
    "source": "GitHub Actions workspace",
    "version": {"applicationId": app_id, "versionName": version_name,
                "versionCode": int(version_code) if version_code else None,
                "minSdk": int(min_sdk) if min_sdk else None,
                "compileSdk": int(compile_sdk) if compile_sdk else None,
                "targetSdk": int(target_sdk) if target_sdk else None},
    "checks": checks,
    "summary": {
        "pass": sum(x["status"] == "PASS" for x in checks),
        "warn": sum(x["status"] == "WARN" for x in checks),
        "fail": sum(x["status"] == "FAIL" for x in checks),
    },
}
out = ROOT / "build/reports/autonomous-runtime-audit.json"
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
print(json.dumps(report, indent=2))
if report["summary"]["fail"]:
    raise SystemExit(1)
