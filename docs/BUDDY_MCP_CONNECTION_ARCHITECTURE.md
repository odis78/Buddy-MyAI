# Buddy-MyAI MCP connection architecture

Status: active development branch for the isolated test222 candidate. Test220 remains the protected baseline and is not modified by this branch.

## Connection topology

Buddy exposes three remote tunnel identities:

1. **Buddy-MyAI**: owned by the Android app. The local MCP server is exposed through the Buddy tunnel profile on `127.0.0.1:8766/mcp`.
2. **REA-Termux-Bridge 0.1.3**: remains a Termux-owned tunnel and is started/stopped through the Termux RUN_COMMAND API.
3. **DroidMCP-Filesystem 0.4.0**: remains a Termux-owned, read-only filesystem tunnel and is started/stopped through the same Termux manager.

The original **REA 6.5.0** runtime is installed inside Buddy's app-private Alpine/PRoot environment. It is exposed as the `rea` stdio MCP channel under Buddy's existing tunnel identity. It is not a fourth tunnel and does not receive a separate remote tunnel ID.

The app's `RuntimeConnectionManager` owns the Buddy `RuntimeSupervisor` and coordinates the two Termux-owned tunnel processes. `BuddyRuntimeService` supplies foreground-service lifecycle, user-visible status, and bounded retry scheduling.

## Termux ownership and safety

Buddy does not overwrite the user's existing Termux manager or profiles.

- On the explicit **Connect Termux** action, Buddy installs its own `~/bin/buddy-tunnels` script.
- The script prefers the known ARM64 Tunnel Client 0.0.19 binary at `~/tunnel-client-android-build-v0.0.19/bin/tunnel-client-android-arm64` and reuses the known Bridge and DroidMCP profile IDs. It refuses profiles with unexpected tunnel IDs or health ports.
- If the exact DroidMCP profile cannot be uniquely discovered, the script refuses to start it rather than guessing.
- The script verifies process command lines against the exact profile before signalling a process.
- It uses SIGTERM and bounded graceful waits only. It never uses SIGKILL.
- The known-good Bridge 0.1.3 profile is `~/.config/tunnel-client-bridge-013/bridge-013.yaml`. A live legacy profile on port 8080 (`~/.config/tunnel-client/termux-bridge.yaml`) is detected and not duplicated. The user must stop that legacy process with its existing Termux manager before Buddy starts the 0.1.3 profile on port 18080. The required Bridge 0.1.3 endpoint is port 18080. If only the legacy endpoint is present, Buddy reports it as legacy-only, does not count it as the required Bridge 0.1.3 connection, and waits for the old process to be stopped before starting 0.1.3.
- Bridge and DroidMCP are reconciled independently. Failure of one does not prevent an attempt to start the other.
- An owned process whose local health endpoint remains unavailable is gracefully stopped and retried. Remote control-plane failures such as HTTP 429 do not trigger process-kill loops.
- The app pins the manager script's SHA-256. A changed app-owned script is updated only when the installed file still matches the previously recorded hash; the old file is preserved as a timestamped backup. User-modified or unknown files are not overwritten.
- The Termux manager keeps its own PID and launcher logs under `$HOME/.local/state/tunnels`; it does not mutate the existing `rea-all` manager.

The app no longer requests Android's broad `MANAGE_EXTERNAL_STORAGE` permission for DroidMCP. Termux owns filesystem access for the read-only DroidMCP channel.

The historical `REA-Termux-Android` route is deliberately not started, stopped, or rewritten by this manager. It remains untouched until Buddy's internal `rea` channel is verified end-to-end on the physical device; only then can a separate, explicit handoff retire the old route.

## One-time Termux setup

The user must:

1. Install Termux.
2. Grant Buddy the Termux `RUN_COMMAND` permission when Android prompts.
3. Enable `allow-external-apps=true` in `~/.termux/termux.properties`, then restart Termux.
4. Run `termux-setup-storage` once if DroidMCP needs shared-storage access.
5. Ensure Node.js and `curl` are installed in Termux (`pkg install nodejs-lts curl` if needed).

Buddy does not silently change Termux properties or install Termux packages.

## Buddy main tunnel setup

The isolated test222 package intentionally starts with no usable main tunnel ID. The user must enter a separately provisioned tunnel ID and the control-plane API key in Buddy settings. The Test220 tunnel ID is explicitly reserved and rejected by the candidate.

Secrets remain in Buddy's encrypted app-private storage and are handed to the Tunnel Client through short-lived private file/FIFO references, not command-line arguments or environment variables.

## Recovery policy

- Buddy main Tunnel Client: owned by `RuntimeSupervisor`, with health-gated startup and bounded exponential retry.
- REA bootstrap: runs inside Buddy Alpine/PRoot; failed installs retry with bounded exponential backoff.
- Termux Bridge and DroidMCP: owned by the Termux manager script; Buddy requests reconciliation with a 90-second initial backoff increasing to a five-minute cap.
- A healthy local tunnel with a remote control-plane 429 is reported as transport-ready or MCP-discovered, not as remotely connected. It is not repeatedly killed or restarted.

## Automated verification

The development workflow validates:

- Java/JVM regression tests and APK compilation.
- The standard Tunnel Client profile.
- A combined Buddy profile with `main` and `rea` MCP channels under exactly one Buddy tunnel ID.
- The pinned Tunnel Client runtime tests and ARM64 smoke test.
- Original REA 6.5.0 doctor inventory: 207 tools and 6 prompts.
- Termux manager shell syntax and isolated lifecycle tests: start, status, idempotency, graceful stop, stale PID recovery, foreign PID protection, legacy Bridge avoidance, independent channel starts, and unhealthy-process recovery.
- APK package isolation, version code, embedded runtime payloads, and signature verification.

## Safe migration order

1. Install the isolated test222 APK alongside Test220; never update or uninstall Test220.
2. Enter the new Buddy tunnel ID and API key in test222.
3. Complete the one-time Termux permission, `allow-external-apps`, Node.js/curl, and shared-storage setup.
4. If the legacy Bridge on port 8080 is still active, stop it with the existing Termux manager. Buddy will then start the already-validated 0.1.3 profile on port 18080.
5. Verify Buddy main, internal REA channel, Bridge 0.1.3, and read-only DroidMCP end-to-end.
6. Only after the internal REA channel is proven on-device may the separate historical `REA-Termux-Android` route be retired. That retirement is a separate, explicit handoff and is not performed by the Buddy manager.

## Physical-device acceptance still required

CI and source tests do not prove live end-to-end operation on the Infinix device. Final acceptance requires the user to configure the new Buddy tunnel ID/API key and complete the one-time Termux permission/property setup. The acceptance run must verify all three remote tunnel identities, REA channel discovery under Buddy, a read-only DroidMCP file operation, Android control through Buddy, and recovery after process restart/reboot.

Test220 remains untouched throughout.
