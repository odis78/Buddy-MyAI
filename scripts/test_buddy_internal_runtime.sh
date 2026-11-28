#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

test ! -e app/src/main/assets/runtime/buddy-termux-tunnels.sh
test ! -e app/src/main/java/app/buddy/assistant/TermuxRuntimeController.java
test ! -e app/src/main/java/app/buddy/assistant/TermuxCommandResultReceiver.java
test ! -e app/src/main/java/app/buddy/assistant/RuntimeConnectionManager.java
! grep -q 'com.termux.permission.RUN_COMMAND' app/src/main/AndroidManifest.xml
! grep -q 'TermuxRuntimeController' app/src/main/java/app/buddy/assistant/BuddyRuntimeService.java
! grep -q 'tunnelId' app/src/main/java/app/buddy/assistant/RuntimeSettingsActivity.java
! grep -q 'deleteTree(root)' app/src/main/java/app/buddy/assistant/Linux.java

grep -Fq 'tunnel_6b02e7e420948191a1829df4a5373d20' app/src/main/java/app/buddy/assistant/RuntimeProcessLauncher.java
grep -Fq 'tunnel_6ae28b8f01788191b42901f090964502' app/src/main/java/app/buddy/assistant/RuntimeProcessLauncher.java
grep -Fq 'tunnel_6af1637e86788191aa429d5518d41938' app/src/main/java/app/buddy/assistant/RuntimeProcessLauncher.java
grep -Fq 'channel: rea' app/src/main/java/app/buddy/assistant/RuntimeProfileWriter.java
grep -Fq '"bridge", command' app/src/main/java/app/buddy/assistant/RuntimeProcessLauncher.java
grep -Fq '"droidmcp", command' app/src/main/java/app/buddy/assistant/RuntimeProcessLauncher.java
grep -Fq 'startBridgeConfigured' app/src/main/java/app/buddy/assistant/RuntimeProcessLauncher.java
grep -Fq 'startDroidMcpConfigured' app/src/main/java/app/buddy/assistant/RuntimeProcessLauncher.java
grep -Fq 'RuntimeHelperBootstrap' app/src/main/java/app/buddy/assistant/BuddyRuntimeService.java
test "$(grep -o 'isLoopbackPortOpen(39073)' app/src/main/java/app/buddy/assistant/BuddyRuntimeService.java | wc -l)" -ge 2
grep -Fq 'RuntimeHealthProbe.isLoopbackPortOpen(18768)' app/src/main/java/app/buddy/assistant/BuddyRuntimeService.java
grep -Fq 'RuntimeHealthProbe.isLoopbackPortOpen(18080)' app/src/main/java/app/buddy/assistant/BuddyRuntimeService.java
grep -Fq 'RuntimeHealthProbe.isLoopbackPortOpen(44611)' app/src/main/java/app/buddy/assistant/BuddyRuntimeService.java
grep -Fq 'supervisor.isMcpDiscovered(0)' app/src/main/java/app/buddy/assistant/BuddyRuntimeService.java
grep -Fq 'bridgeSupervisor.isMcpDiscovered(6)' app/src/main/java/app/buddy/assistant/BuddyRuntimeService.java
grep -Fq 'droidMcpSupervisor.isMcpDiscovered(5)' app/src/main/java/app/buddy/assistant/BuddyRuntimeService.java
grep -Fq 'requiresAtLeastOneToolWhenNoExactCountIsRequested' app/src/test/java/app/buddy/assistant/RuntimeHealthProbeTest.java

node --check app/src/main/assets/runtime/termux-bridge-0.1.3.mjs
node --check app/src/main/assets/runtime/droidmcp-filesystem-0.4.0.mjs
node --check scripts/mcp_stdio_smoke.mjs

! grep -Eq 'name:[[:space:]]*"(write_file|delete_file|move_file|copy_file)"' app/src/main/assets/runtime/droidmcp-filesystem-0.4.0.mjs
grep -Fq 'Path escapes the configured read-only root' app/src/main/assets/runtime/droidmcp-filesystem-0.4.0.mjs
grep -Fq 'android.permission.MANAGE_EXTERNAL_STORAGE' app/src/main/AndroidManifest.xml
grep -Fq 'shell: false' app/src/main/assets/runtime/termux-bridge-0.1.3.mjs
grep -Fq 'buddy-bridge' app/src/main/assets/runtime/termux-bridge-0.1.3.mjs
grep -Fq 'BUDDY_BRIDGE_REA_ROOT' app/src/main/java/app/buddy/assistant/RuntimeProcessLauncher.java
grep -Fq 'BUDDY_ANDROID_PACKAGE' app/src/main/java/app/buddy/assistant/RuntimeProcessLauncher.java
grep -Fq 'args.confirmed !== true' app/src/main/assets/runtime/termux-bridge-0.1.3.mjs
grep -Fq "['confirmed']" app/src/main/assets/runtime/termux-bridge-0.1.3.mjs
! grep -Fq 'TERMUX_BRIDGE_REA_ROOT' app/src/main/assets/runtime/termux-bridge-0.1.3.mjs

echo "Buddy Test225 internal runtime safety checks passed."
