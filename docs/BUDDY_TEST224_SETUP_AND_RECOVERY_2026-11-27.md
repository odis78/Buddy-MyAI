# Buddy Test223 → Test224: setup, recovery and invariants (2026-11-27)

## Purpose

Test224 consolidates the verified Test223 Termux setup and fixes into one short in-app flow. It must preserve existing working services and never take ownership of historical Termux/REA processes.

## Protected baselines and identities

- Test220 remains untouched. Its reserved main tunnel ID is `tunnel_6b02e7e420948191a1829df4a5373d20`.
- Test223 remains installed as a separate APK/package: `app.buddy.myai.gt.test223`, version `2.3.23-GT.2-test223`, MCP port `18767`.
- Test224 is side-by-side: package `app.buddy.myai.gt.test224`, version `2.3.24-GT.2-test224`, versionCode `224`, MCP port `18768`.
- Test224's dedicated main tunnel ID, supplied by the user, is `tunnel_15bad0275a40c4f8eb80e7baec6e3ce2`. It is the default in the app and is copyable from the UI.
- Termux Bridge 0.1.3 keeps its own tunnel ID: `tunnel_6ae28b8f01788191b42901f090964502`.
- DroidMCP read-only keeps its own configured tunnel ID: `tunnel_6af1637e86788191aa429d5518d41938`.
- The original REA 6.5.0 remains inside Buddy's existing Alpine/PRoot as channel `rea` under the main Buddy tunnel. Do not create a separate REA tunnel in Termux.

## Test223 setup history and confirmed environment

Device: Infinix X678B, Android 14. Termux version observed: 0.119.0-beta.3.

One-time Termux prerequisites:
- Buddy has `com.termux.permission.RUN_COMMAND`.
- `allow-external-apps=true` is set in `~/.termux/termux.properties`.
- Node.js and curl are installed in Termux.
- `termux-setup-storage` is only needed when a workflow needs shared-storage links; it is not required for tunnel startup.
- Buddy installs only `~/bin/buddy-tunnels`. It does not replace the historical manager or modify REA.

Known Termux profiles:
- Bridge: `~/.config/tunnel-client-bridge-013/bridge-013.yaml`, health listener `127.0.0.1:18080`, API key reference `env:CONTROL_PLANE_API_KEY`, secret environment file `~/.config/termux-bridge/runtime.env`.
- DroidMCP: `~/droidmcp-stage-v0.4.0/profile-dedicated/droidmcp-filesystem.yaml`, `listen_addr: 127.0.0.1:0`, `health.url_file` publishes the actual ephemeral loopback listener.
- DroidMCP wrapper: `~/droidmcp-stage-v0.4.0/droidmcp-readonly`; root is `~/droidmcp-sandbox`; transport is stdio; write/delete/move/copy tools are disabled.
- Tunnel Client binary: `~/tunnel-client-android-build-v0.0.19/bin/tunnel-client-android-arm64`, version 0.0.19.

## Confirmed Test223 corrections

1. Bridge uses its fixed loopback `listen_addr` when `health.url_file` is absent; DroidMCP still requires its dynamic `health.url_file`.
2. The health URL parser accepts a valid non-empty file whose last line has no trailing newline. This fixed the observed `read`-at-EOF false failure.
3. Bridge startup loads `~/.config/termux-bridge/runtime.env` and exports `CONTROL_PLANE_API_KEY` only to the launched Bridge Tunnel Client process. The secret is never printed.
4. An owned process that misses the health deadline is reported as running and left in place for diagnosis. The manager does not kill it merely because a health probe timed out.
5. The manager verifies exact process/profile ownership, leaves unowned processes alone, and uses graceful TERM only. No SIGKILL.
6. Buddy receives `BUDDY_DROIDMCP_PORT=<port>` from the manager and uses the reported port for health/MCP probes. Do not hardcode 41507.
7. RUN_COMMAND callbacks are generation-checked so stale results cannot overwrite a newer dynamic port.
8. The manager is installed beside existing Termux tooling, with a backup on a recognized safe upgrade. Unknown manager hashes are conflicts and must not be overwritten.

The local Test223 manager hash at the last confirmed checkpoint was:
`862ba870fee625beaaaa8d35e8a2c7869ac51c606f4ce55c9f853de3106023fb`.
Test224 recognizes this exact prior manager hash for a backup-first migration. It does not accept arbitrary modified scripts.

## Last observed Termux checkpoint (2026-11-27)

- Bridge Tunnel Client: READY, PID 4363, health port 18080.
- DroidMCP Tunnel Client: READY, PID 11920, dynamic health port 41789.
- DroidMCP stdio child: PID 11941.
- A separate historical/unowned DroidMCP process PID 23936 was left untouched.
- The manager reported `BUDDY_DROIDMCP_PORT=41789`.
- These checks confirmed local transport health only. Full ChatGPT MCP `initialize` / `tools/list` and accepted remote delivery were still pending; do not describe the end-to-end chain as complete until those states are observed.

## Why Test224 is a separate package

The Test223 debug APK and another GitHub Actions debug APK were signed with different ephemeral Android Debug certificates. A new APK cannot safely update Test223 in place and preserve its Android Keystore identity. Test224 therefore uses a new package and port, leaving Test223 untouched.

The control-plane API key is stored by Buddy using AES-GCM with an Android Keystore key. Android Keystore credentials are app-UID scoped, so Test223's saved key cannot be silently imported into the separate Test224 package. Enter the key once in Test224; after saving, it remains encrypted there. The app provides explicit copy buttons for the saved key and tunnel ID. Never put the API key in source code, GitHub, reports, logs, or this document.

## Test224 one-tap flow

1. Open Autonomous Buddy.
2. Enter the control-plane API key once and save. The dedicated tunnel ID is prefilled.
3. Tap **Автонастройка и запуск**.
4. If Android asks, grant Buddy Termux RUN_COMMAND. Termux must already have `allow-external-apps=true`.
5. Buddy safely updates only its own manager, backs up the recognized previous version, waits for the install callback, then starts the main tunnel and reconciles Bridge/DroidMCP.
6. The app keeps the REA 6.5.0 channel inside Buddy Alpine/PRoot and reports the actual DroidMCP port.

Do not run Test223 and Test224 main tunnels simultaneously with the same main tunnel ID. Stop Test223's Buddy runtime before starting Test224; do not stop the already-running Termux Bridge or DroidMCP processes.

## Non-goals / protected infrastructure

- Do not change Test220, its APK, its tunnel ID, or its baseline.
- Do not modify the historical REA-Termux-Android route or the original Termux REA manager.
- Do not stop, adopt, reset, delete, clean, or replace unowned processes/profiles.
- Do not merge the Test223 PR or Test224 PR without explicit user approval.
