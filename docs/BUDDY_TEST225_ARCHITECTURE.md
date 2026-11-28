# Buddy Test225 architecture and migration boundary

## Target

ChatGPT remains the external control plane. Buddy owns the on-device Linux runtime and manages the pinned Tunnel Client and MCP servers from its private app storage. Termux is not required for normal Buddy operation.

## Internal runtime

- Alpine 3.24.2 ARM64 runs inside Buddy's private files directory through bundled PRoot.
- Tunnel Client 0.0.19 is bundled as a SHA-256-verified ARM64 executable.
- The Buddy MCP endpoint is loopback-only. Release uses port 8766; Test225 debug uses port 18769.
- REA 6.5.0 is installed inside Alpine and exposed as a second `rea` channel under Buddy's existing main tunnel. It is not a separate fourth tunnel.
- Bridge 0.1.3 and read-only DroidMCP 0.4.0 are installed inside Alpine and supervised as independent stdio MCP tunnel processes.
- The Bridge has a fixed command allowlist and never invokes a shell. DroidMCP exposes only directory listing, bounded tree listing and file reading, with path traversal and sensitive-file protections.
- Credentials remain encrypted in Android Keystore and are handed to child processes through short-lived app-private FIFOs.

## Preserved identities and ports

- Test224 main tunnel identity remains the Buddy-local default while Test224 is preserved.
- Bridge keeps `tunnel_6ae28b8f01788191b42901f090964502`.
- DroidMCP keeps `tunnel_6af1637e86788191aa429d5518d41938`.
- Main MCP ports are build-specific. No helper is allowed to change the user's existing external Termux profile, ports, credentials or process state.

## Safety and lifecycle

- Buddy starts, monitors, recovers and stops only child processes that it created and owns.
- The Alpine installer stages a verified rootfs and never recursively deletes an existing incomplete root. An incomplete root is preserved for diagnosis.
- REA installation stages its components and retains prior runtime backups on failure.
- DroidMCP is read-only. No write, delete, move or copy tool is exposed.
- Existing Termux processes are not stopped or modified by Test225. Before the final cutover, any old external Bridge/DroidMCP processes using the same tunnel IDs must be stopped gracefully to avoid duplicate control-plane identities.
- Android may still require the user to grant Accessibility, Wireless debugging/ADB pairing, battery-exemption or all-files access. Buddy cannot bypass Android's system confirmations.
- Uninstall removes Buddy's app-private Linux rootfs and processes. Android system pairing/developer settings may remain.

## Test224 signing limitation

The installed Test224 package is `app.buddy.myai.gt.test224`. Its debug APK was signed by a GitHub-hosted runner's generated Android Debug certificate. GitHub-hosted runners generate different debug signing keys; the public certificate embedded in an APK does not contain the private key. Android refuses an in-place update signed by a different key.

Therefore Test225 uses `app.buddy.myai.gt.test225` and port 18769 side-by-side. Test224 is not overwritten or uninstalled. This is a deliberate safety deviation from the original in-place-update plan, not a package-ID preference.

Buddy's backup feature can move chats, settings and files, but intentionally excludes API keys and per-install tokens. To migrate data, restore a Test224 backup in Test225 and enter the control-plane API key once. When a stable repository signing key is configured, subsequent Test225 builds use it for repeatable updates.
