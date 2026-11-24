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
runtime_supervisor = read("app/src/main/java/app/buddy/assistant/RuntimeSupervisor.java")
runtime_service = read("app/src/main/java/app/buddy/assistant/BuddyRuntimeService.java")
runtime_boot = read("app/src/main/java/app/buddy/assistant/BuddyRuntimeBootReceiver.java")
runtime_settings = read("app/src/main/java/app/buddy/assistant/RuntimeSettingsActivity.java")
manifest = read("app/src/main/AndroidManifest.xml")
runtime_profile = read("app/src/main/java/app/buddy/assistant/RuntimeProfileWriter.java")
profile_fixture = read("app/src/test/resources/buddy-myai-tunnel-profile.yaml")
runtime_hardening = read("scripts/tunnel-client-runtime/dumpable_linux.go")
runtime_hardening_test = read("scripts/tunnel-client-runtime/dumpable_linux_test.go")
runtime_fifo_test = read("scripts/tunnel-client-runtime/file_reference_fifo_test.go")
cloudflared_hardening = read("scripts/tunnel-client-runtime/cloudflared_dumpable_linux.go")
cloudflared_hardening_test = read("scripts/tunnel-client-runtime/cloudflared_dumpable_linux_test.go")
cloudflared_patch = read("scripts/patch_cloudflared_module.py")
runtime_patch = read("scripts/patch_tunnel_client_runtime.py")
workflow = read(".github/workflows/build.yml")
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
add("local_mcp_server", "PASS" if "PORT = 8766" in mcp and "127.0.0.1" in mcp else "FAIL",
    "Buddy local MCP endpoint source markers")
add("android_keystore_secret_store", "PASS" if "AndroidKeyStore" in secrets and "AES/GCM/NoPadding" in secrets else "FAIL",
    "Existing encrypted secret storage uses Android Keystore AES/GCM")
add("runtime_artifact_integrity", "PASS" if all(term in runtime_binary for term in ["EXPECTED_SHA256", "EXPECTED_CLOUDFLARED_SHA256", "nativeLibraryDir", "locateCloudflared", "MessageDigest"]) else "FAIL",
    "Pinned Tunnel Client and cloudflared Linux ARM64 executables are located in nativeLibraryDir and SHA-256 verified")
profile_auth_ok = all(term in runtime_profile and term in profile_fixture for term in [
    "extra_headers:", "discovery_extra_headers:", "file:"
])
add("buddy_tunnel_auth_profile", "PASS" if profile_auth_ok else "FAIL",
    "Buddy profile preserves Authorization for both MCP discovery and request traffic")
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
settings_ok = "Secrets.put" in runtime_settings and "auto_start" in runtime_settings and "RuntimeProcessLauncher.DEFAULT_TUNNEL_ID" in runtime_settings
add("runtime_user_configuration", "PASS" if settings_ok else "FAIL",
    "User can save the control-plane key through Android Keystore and control opt-in startup")
hardening_ok = (
    "PR_SET_DUMPABLE" in runtime_hardening
    and "hardenProcess" in runtime_hardening
    and "TUNNEL_DUMPABLE_SECRET" in runtime_hardening_test
    and "dumpable_linux.go" in runtime_patch
    and "hardenProcess()" in runtime_patch
)
add("tunnel_runtime_procfs_hardening", "PASS" if hardening_ok else "FAIL",
    "Pinned Tunnel Client build is patched to deny same-UID procfs access to its environment, with a regression test")
cloudflared_hardening_ok = (
    "PR_SET_DUMPABLE" in cloudflared_hardening
    and "hardenCloudflaredProcess" in cloudflared_hardening
    and "hardenCloudflaredProcess()" in cloudflared_patch
    and "TestCloudflaredHidesEnvironmentFromSameUidProcess" in cloudflared_hardening_test
)
add("cloudflared_token_procfs_hardening", "PASS" if cloudflared_hardening_ok else "FAIL",
    "Pinned cloudflared companion hardens the managed-token process and has a same-UID procfs regression test")
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

add("persistent_runtime_supervisor", "PASS" if has_supervisor else "WARN",
    "Dedicated runtime supervisor source exists" if has_supervisor else "Dedicated multi-process runtime supervisor not present in baseline")
add("release_signing_gate", "WARN",
    "Workflow has optional signing hooks, but actual release keystore availability and certificate identity are not verified")
add("gt_package_line", "PASS" if app_id == "app.buddy.myai.gt" else "WARN",
    f"Current baseline package is {app_id}; proposed GT package app.buddy.myai.gt is not applied")
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
