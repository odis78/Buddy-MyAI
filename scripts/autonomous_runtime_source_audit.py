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
test_dirs = [ROOT / "app/src/test", ROOT / "app/src/androidTest"]
has_tests = any(p.exists() for p in test_dirs)
add("app_java_test_coverage", "PASS" if has_tests else "WARN",
    "Java/JVM or instrumentation test source directory exists" if has_tests else "No app/src/test or app/src/androidTest directory exists at the audited baseline")
has_supervisor = any((ROOT / p).exists() for p in [
    "app/src/main/java/app/buddy/assistant/RuntimeSupervisor.java",
    "app/src/main/java/app/buddy/assistant/RuntimeOrchestrator.java",
])
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
