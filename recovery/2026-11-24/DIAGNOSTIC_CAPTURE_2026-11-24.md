# Diagnostic capture: Buddy / JITI / REA
**Captured:** 2026-11-24 (device local time reported as 12:19 Europe/Moscow)  
**Source:** command output pasted by the user in this conversation, plus successful Buddy `device_status` tool response.  
**Classification:** secret-free operational evidence. No YAML, token, API key, or environment values included.

## 1. Full-stack status

Command:
```bash
bash "$HOME/bin/full-stack" status
```

Output:
```text
========== TUNNELS ==========

=== REA-Termux-Bridge 0.1.3 ===
PID: 22438 (manager)
Health: OK

=== DroidMCP-Filesystem ===
PID: 22559 (manager)
Health: OK

=== REA-Termux-Android ===
PID: 22793 (manager)
Health: OK

========== REA MCP ==========
healthy: true
adapter: production-stdio
server:
  name: rea
  version: 6.5.0
  protocol_version: 2025-11-25
inventory:
  tools:
    expected: 208
    observed: 208
    missing: []
    unexpected: []
    duplicates: []
  prompts:
    expected: 6
    observed: 6
    missing: []
    unexpected: []
    duplicates: []
identity:
  expected_package_version: 6.5.0
  observed_package_version: 6.5.0
  expected_catalog_digest: 195e27df5d43ddd434d535b2167c930449ba1ffae47257325ecf02bc295cf400
  observed_catalog_digest: 195e27df5d43ddd434d535b2167c930449ba1ffae47257325ecf02bc295cf400
request_flow:
  tool: binary_session
  ok: true
checks[5]{name,ok,detail}:
  initialize,true,rea@6.5.0; protocol=2025-11-25
  "inventory:tools",true,expected=208 observed=208 missing=none unexpected=none duplicates=none
  "inventory:prompts",true,expected=6 observed=6 missing=none unexpected=none duplicates=none
  server-identity,true,package=6.5.0; catalog=195e27df5d43ddd434d535b2167c930449ba1ffae47257325ecf02bc295cf400
  request-flow,true,binary_session target-free request
diagnostics:
  stderr: ""
  stderr_bytes: 0
  stderr_truncated: false

========== ADB ==========
List of devices attached
emulator-5554          device product:X678B-OP model:Infinix_X678B device:Infinix-X678B transport_id:1

========== MYAI ==========
package:/data/app/~~iLlb0ohouco-_jkGUVD7cw==/com.dmitry.myai.infinix-bCZ85KbY65JOJQh4JIaK0Q==/base.apk

========== JADX ==========
HTTP недоступен
PID-файл не прошёл проверку
```

## 2. Buddy before launch

The pre-launch check found:
- Buddy PID file contained stale PID `32221`; no process existed for that PID.
- No Buddy process matching the Buddy YAML was found.
- Local Buddy health endpoint on `127.0.0.1:39073` refused the connection for both `healthz` and `readyz`.
- The initial process-list attempt used `ps -A -o PID,ARGS`; Termux returned `unknown user-defined format specifier "PID"`. This was a command-format incompatibility, not a tunnel error.

## 3. Buddy after JITI launch

The user observed these JITI notifications in sequence:
1. `MCP listener up. tunnel not ready`
2. `Buddy MCP authenticated`
3. `44 tools`

Command:
```bash
D="$HOME/.config/tunnel-client-buddy"
U="$(cat "$D/health.url")"
echo "=== PROCESS ==="
ps -A | grep '[t]unnel-client'
echo "=== HEALTH ==="
curl -sS --max-time 5 "$U/healthz"
echo
echo "=== READY ==="
curl -sS --max-time 5 "$U/readyz"
echo
```

Output:
```text
=== PROCESS ===
 3411 ?        00:00:01 /data/data/com.termux/files/home/tunnel-client-android-build-v0.0.19/bin/tunnel-client-android-arm64
22438 pts/39   00:00:28 /data/data/com.termux/files/home/tunnel-client-android-build-v0.0.19/bin/tunnel-client-android-arm64
22559 pts/39   00:00:28 /data/data/com.termux/files/home/tunnel-client-android-build-v0.0.19/bin/tunnel-client-android-arm64
22793 pts/39   00:00:29 /data/data/com.termux/files/home/tunnel-client-android-build-v0.0.19/bin/tunnel-client-android-arm64
=== HEALTH ===
live
=== READY ===
ready
```

## 4. End-to-end Buddy tool result

A read-only Buddy MCP call `device_status` completed successfully:
- Battery: 92%, not charging.
- Network: Wi-Fi (VPN).
- Device-reported local time: 2026-11-24 12:19 (Europe/Moscow).
- Storage: 29.5 GB free of 241.8 GB.
- Ringer: silent.

## 5. Interpretation

At capture time, REA and its three helper tunnels were healthy, Buddy Tunnel had authenticated, the local health/readiness endpoints were live/ready, and a real Buddy MCP tool call had succeeded. The PIDs are observations only and must not be reused as launch instructions.

JADX HTTP/PID failure is recorded as a separate issue; it did not prevent the confirmed REA or Buddy MCP checks.

This is a conversation-derived diagnostic capture committed alongside the recovery runbook. It is not yet the automatic Termux inbox report and does not claim that `rea-report-upload` ran.


## 6. Follow-up stability check

A second read-only Buddy MCP call `device_status` succeeded at 2026-11-24 12:24 (Europe/Moscow), several minutes after the first successful call:
- Battery: 91%, not charging.
- Network: Wi-Fi (VPN).
- Storage: 29.5 GB free of 241.8 GB.
- Ringer: silent.

This confirms that authenticated Buddy MCP remained callable across the observation interval. Local `healthz/readyz` were directly checked at the earlier timestamp; this follow-up was an end-to-end MCP call.
