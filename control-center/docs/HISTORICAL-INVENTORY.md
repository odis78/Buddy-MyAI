# Historical runtime inventory: 22–23 November 2026

This is an evidence-led initial inventory. It deliberately distinguishes components that were proven working from components that were merely configured or installed.

## 1. Known-good checkpoint: 22 November

### 1.1 Buddy end-to-end path

Confirmed in the Russian Buddy Tunnel runbook on branch `docs/buddy-myai-tunnel-runbook-2026-11-22`:

`ChatGPT → custom MCP connection “Buddy-MyAI” → OpenAI Tunnel → Tunnel Client v0.0.19 in Termux → http://127.0.0.1:8766/mcp → Buddy MCP → Android`

Evidence:
- Buddy MCP initialize returned HTTP 200.
- Protocol version: `2025-11-25`.
- MCP server identity: `buddy-phone / 2.3.17-JITI.1`.
- Tunnel Client health reported process running, healthz 200, readyz 200, and control-plane poll OK.
- ChatGPT successfully invoked Buddy `read_screen` and received the real Android screen.
- `device_status`, `list_apps`, and `open_app` were also used in documented checks.
- Source reconciliation is exact: 31 unique core Tools + 13 PhoneTools = 44 base tools; 8 BrowserTools are added only when `Browser.available()` is true. `Browser.available()` reads `Prefs.browser`, whose default is false. The 44 connected Buddy functions exposed in this ChatGPT session match the 44 source base names exactly, with zero missing or extra names. None of the optional 8 BrowserTools is exposed in this connector snapshot. This explains the historical 44-vs-52 count.
- The protected Buddy checkpoint is `checkpoint/buddy-myai-2.3.17-JITI.1-2026-11-21`; checkpoint commit used by the existing PR history: `ef9fea50f86865f97cf82b45f6bc2c6e84f174e1`.

Buddy MCP:
- Local endpoint: `http://127.0.0.1:8766/mcp`.
- The MCP server is not a separate Termux process: `BuddyService` (the Android AccessibilityService) constructs and starts `McpServer` with the Buddy token. `McpServer` binds to `127.0.0.1:8766`; `BuddyService.onDestroy()` stops it. The server requires `Authorization: Bearer <token>`.
- This establishes the dependency edge `Buddy app → BuddyService/AccessibilityService → Buddy MCP listener → Buddy tunnel`.
- The token is supplied from environment/configuration and must never be printed or committed.
- Health URL observed in the runbook: `http://127.0.0.1:39073`. The Tunnel Client health URL is dynamic and must be read from its URL file, not hard-coded.

OpenAI Buddy tunnel:
- Name: `Buddy-MyAI`.
- Tunnel ID: `tunnel_6b02e7e420948191a1829df4a5373d20`.
- Tunnel Client: `v0.0.19`.
- Binary path: `$HOME/tunnel-client-android-build-v0.0.19/bin/tunnel-client-android-arm64`.
- Config: `$HOME/.config/tunnel-client-buddy/buddy-myai.yaml`.
- PID file: `$HOME/.config/tunnel-client-buddy/buddy-myai.pid`.
- Health URL file: `$HOME/.config/tunnel-client-buddy/health.url`.
- Log: `$HOME/.config/tunnel-client-buddy/tunnel-client.log`.
- Runtime environment referenced by the 22-Nov runbook: `$HOME/.config/termux-bridge/runtime.env`.
- Expected secret variable names documented at that checkpoint: `CONTROL_PLANE_API_KEY`, `BUDDY_TOKEN`; derived header `BUDDY_AUTH_HEADER`. Never store values in this repository.

Historical PID snapshot only (never reuse as a current PID):
- Buddy tunnel PID 32221.
- REA bridge PID 11189.
- DroidMCP PID 11309.
- REA Android PID 11530.

### 1.2 Additional tunnel profiles documented as running

The 22-Nov runbook explicitly says Buddy is a separate tunnel and does not replace REA tunnels. At the historical snapshot it lists:
- REA-Termux-Bridge 0.1.3.
- DroidMCP-Filesystem.
- REA-Termux-Android.

The 23-Nov script audit identifies the configuration paths:
- Bridge: `$HOME/.config/tunnel-client-bridge-013/bridge-013.yaml`.
- Bridge env: `$HOME/.config/termux-bridge/runtime.env`.
- DroidMCP: `$HOME/droidmcp-stage-v0.4.0/profile-dedicated/droidmcp-filesystem.yaml`.
- REA Android: `$HOME/.config/rea-tunnel/profile/rea-android.yaml`.
- REA env: `$HOME/.config/rea-tunnel/runtime.env`.

The exact historical OpenAI Tunnel IDs and health URLs for these three profiles still need to be recovered from their configuration files / historical logs. Do not guess them. Stage 7 confirms the existing launcher sequence: Bridge 0.1.3 → DroidMCP-Filesystem → REA-Termux-Android; its stop sequence is the reverse. This is a script-defined operational sequence, not proof that each profile is a hard dependency of the next.

### 1.3 MCP servers, tools, and runtime services

Confirmed as part of the 22-Nov working ecosystem:
- Buddy MCP server on port 8766.
- Buddy tool catalog: 44 base tools (31 core + 13 phone) match the connected ChatGPT Buddy tool names exactly; 8 optional background-browser tools are present in source but absent from this connector snapshot. The browser tools are included only when the Buddy browser setting is enabled; its source default is false.
- REA MCP server/tool inventory: latest saved direct inventory was 207/207 tools and 6/6 prompts. Do not report 208 unless a fresh inventory proves it.
- REA-Termux-Bridge 0.1.3 is a distinct MCP bridge process.
- DroidMCP-Filesystem is a distinct read-only filesystem MCP runtime.
- REA-Termux-Android is a distinct REA MCP runtime.
- Package identity discrepancy: the 22-Nov runbook records `app.buddy.myai.test`, while the protected checkpoint's `app/build.gradle.kts` declares applicationId `app.buddy.myai`. The live installed package must be read from Android before any package-targeted command; do not hardcode either value.
- Checkpoint `BuildInfo.java` records version `2.3.17-JITI.1`, versionCode 215.
- The checkpoint manifest declares `.BuddyService` as the AccessibilityService and `.NotificationWatcher` as a separate NotificationListenerService. Notification access is optional and user-granted.
- `BuddyService.onServiceConnected()` starts the local MCP listener immediately, then schedules `RemoteSession.ensure`, `SshServer.ensure`, and `CodexRemote.ensure` after 4 seconds. Each is gated by a separate preference and is off by default in source; RemoteSession/CodexRemote also require the corresponding agent sign-in. `SshServer` runs inside Buddy Linux on port 8767.
- `McpServer` also has a Tailscale listener on port 8766, but `Tailnet.address()` returns an address only when the Buddy Tailscale-tools preference is enabled and an active VPN interface has a Tailscale CGNAT address. It is not required for the local ChatGPT tunnel path.
- The background browser is a separate optional feature. Its eight tools are advertised only when `Prefs.browser` is true; source default is false. Chromium is installed/run inside Buddy Linux.
- Shizuku was installed and running in prior Android checks, but whether it is a hard dependency for the Buddy MCP path is not established.
- ADB / Android shell access is a capability path, not automatically a separate tunnel.

Do not duplicate conceptual components in the dashboard: a remote OpenAI tunnel object, local Tunnel Client profile, local MCP server/listener, tool catalog, ChatGPT connector association, and Android service are separate rows linked by dependency edges.

## 2. 23 November diagnostics

### 2.1 Buddy Tunnel failure

Stage 1:
- Buddy config directory exists: `$HOME/.config/tunnel-client-buddy`.
- PID file exists and contains historical PID `32221`.
- Health URL file exists and contains `http://127.0.0.1:39073`.
- PID 32221 was not running.
- `/healthz`, `/readyz`, and control-plane poll connection attempts were refused.
- Tunnel Client health result: fail, exit 2.

Stage 3:
- Tunnel Client doctor could parse the YAML and profile.
- Doctor output explicitly confirms the YAML field is `api_key: env:CONTROL_PLANE_API_KEY`.
- Doctor failed because `CONTROL_PLANE_API_KEY` was not exported in the current shell/process environment.
- The runbook identifies `$HOME/.config/termux-bridge/runtime.env` as the Buddy launch environment source for `CONTROL_PLANE_API_KEY`. Stage 5 confirms this file contains that variable and is mode 600. Stage 3 Doctor failed because the file had not been sourced into that command's environment. Do not infer that this file also contains Buddy's MCP token: Stage 5 found only `CONTROL_PLANE_API_KEY` in the two inspected runtime.env files, while Stage 4 found `BUDDY_TOKEN` present in the interactive shell. The origin/derivation of `BUDDY_TOKEN` and `BUDDY_AUTH_HEADER` remains unresolved. The separate `$HOME/.config/rea-tunnel/runtime.env` contains a different Control Plane key and must not be substituted into Buddy.

Stage 4:
- `CONTROL_PLANE_API_KEY`: not set in current shell.
- `OPENAI_API_KEY`: not set.
- `BUDDY_TOKEN`: present.
- `OPENAI_ADMIN_KEY`: not set.

Stage 5:
- `$HOME/.config/rea-tunnel/runtime.env` contains a non-empty `CONTROL_PLANE_API_KEY`.
- `$HOME/.config/termux-bridge/runtime.env` contains a non-empty `CONTROL_PLANE_API_KEY`.
- The two values differ.
- Neither is to be copied into Buddy config or substituted without evidence.
- Both files had mode 600.
- No secret values are included here.

Stage 7:
- Buddy YAML contains configured non-empty Control Plane base URL, tunnel ID, and api_key field.
- Stage 7's structural inspection initially recorded only that the field was non-empty. The subsequent Stage 3 Doctor output resolves the representation: it is `env:CONTROL_PLANE_API_KEY`.
- The runbook explicitly identifies `$HOME/.config/termux-bridge/runtime.env` as the Buddy environment source. Stage 5 confirms that this file contains `CONTROL_PLANE_API_KEY` and has mode 600. The Stage 3 Doctor failure is explained by running Doctor in a shell where the runtime.env had not been sourced. The differing value in `$HOME/.config/rea-tunnel/runtime.env` belongs to a separate REA environment and must not be substituted into Buddy.

### 2.2 All tunnel processes stopped after Termux reopen

Stage 8:
- Buddy process absent.
- Buddy health URL refused connections.

Stage 9:
- No `tunnel-client` processes appeared in the process audit.
- `~/bin/tunnels status` reported stale PID/no process for:
  - REA-Termux-Bridge 0.1.3.
  - DroidMCP-Filesystem.
  - REA-Termux-Android.
- Combined with Buddy Stage 8, all four tunnel processes were down at that audit time.

This proves the Tunnel Client processes were stopped. It does not prove the Android Buddy app, AccessibilityService, REA MCP server internals, or Shizuku were stopped.

### 2.3 Existing launcher script

`$HOME/bin/tunnels` manages three profiles only:
- REA-Termux-Bridge 0.1.3.
- DroidMCP-Filesystem.
- REA-Termux-Android.

It does not manage Buddy Tunnel.

Its old `start_one` implementation sources an env file and starts Tunnel Client via `nohup`. Tunnel Client v0.0.19 quickstart recommends managed `runtimes connect` for persistent runtimes where supported. This must be reconciled before reusing or replacing the old launcher. Do not blindly port `nohup` into the Android app.

### 2.4 Reports already uploaded

The user has a standing rule that every diagnostic produces a timestamped report, SHA-256, and automatic upload to `odis78/REA-Termux-Reports`. The 23-Nov report sequence includes:
- Stage 1: `f65d7bfb49e41388c8a2d33fbb329e8f59af329548a3377c2e871f9f0290d652`.
- Stage 2 help: `46e4d0ce5ead4f03725ff897f59d57bc86539d07c57c6be1aecdd09baa9bb860`.
- Stage 2 commands: `c1717eea9e1226a6586c1a0e12a0ddf636980757d8625e5bdfdc48e8db1da5b8`.
- Stage 3 doctor: `a911f092f9a234d309476ec87b86382dd3c01c9c49918b1db9210d913df11613`.
- Stage 4 key presence: `62ca1d374e198dc82e5b0b3fdf34604c4838477313382a1bc152bffbaee11602`.
- Stage 5 env names: `e7effe9634d9f8623b821f700a2cfee27a4341b9f0c935f3438fd123dbf203ab`.
- Stage 6 launcher script: `044eb21acbd8a8a5ab0c393a76dc2fb3e1ed4d2acf8724bfc6b14d300cc3133a`.
- Stage 7 Buddy config structure: `0a06d19302748cd2d90b131aeb63a1fe8c6dbc4f59370acc2875f751ad3616fe`.
- Stage 8 Termux reopen check: `7d187f37fe74c9c0f5ef10c04c78dc935d963f2d456ea5a58403e7e3c2d43c0c`.
- Stage 9 process audit: `17fc9caba1dfd920a9efdc55066af55b45c055c23fbf9112087a084ecd1606c5`.

Full report paths are in the conversation checkpoint; the local inbox is `~/rea-report-uploader/inbox`. Retrieve report content by exact GitHub path before making implementation decisions.

## 3. Important unresolved inventory questions

1. Recover exact 22-Nov process command lines and profile-to-tunnel mapping for all four Tunnel Client instances.
2. Recover Bridge/DroidMCP/REA Android tunnel IDs, health URL files, PID/log paths, and environment-variable names (values remain secret).
3. Determine which MCP endpoint belongs to each runtime, and distinguish server process from tunnel transport.
4. Reconcile Buddy tool catalog count (52 project-level tools vs 44 discovered by one client session).
5. Reconcile REA tool inventory and the six prompts from the exact 22-Nov snapshot.
6. Determine Android app package/version and AccessibilityService status at the time of successful end-to-end test.
7. Verify Shizuku, ADB, Termux:API, Termux:Boot, and other packages as present/optional/required; do not mark them required without evidence.
8. Prove which Termux processes survive UI closure, app process death, Android process reclaim, reboot, and force-stop.
9. Verify the supported Termux external command API and security settings before implementing any command execution.

## 4. Dashboard inventory states

Each registry item must carry evidence metadata:
- `evidence_status`: confirmed_working | present_configured | observed_stopped | unverified.
- `last_verified_at`: timestamp or null.
- `source_report`: exact report/runbook reference.
- `start_command_status`: verified | unknown | prohibited.
- `stop_command_status`: verified | unknown | prohibited.

Do not show a component as "running" based solely on a stale PID file or the existence of a config file.
