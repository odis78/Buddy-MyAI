# Buddy-MyAI working checkpoint
**Checkpoint date:** 2026-11-21  
**Checkpoint branch:** `checkpoint/working-2026-11-21`  
**Source branch copied:** `test/mcp-sse-response-fix`  
**Purpose:** immutable rollback point for the currently working Buddy-MyAI source tree and a record of the phone-control behavior observed on the user's Infinix device.

## 1. Preservation rules
- Treat this checkpoint branch as a recovery baseline. Do not develop new features on it.
- Do not modify `main` or upstream `ghorbelhamdi/buddy-android`.
- Keep PR #1 open and unmerged unless the user explicitly approves a merge: https://github.com/odis78/Buddy-MyAI/pull/1
- All future development must happen on a separate working branch created from this checkpoint.
- Before any source change: record current branch/commit, inspect the diff, back up affected files, make the smallest change, run tests, and preserve the checkpoint branch.
- Never overwrite, delete, force-push, or repoint this checkpoint branch.
- Do not change ports, URLs, tunnel settings, pairing, tokens, Android developer options, process limits, or app data merely as a diagnostic guess.
- Never commit API keys, Bearer tokens, Tailscale auth material, pairing secrets, private auth files, or other credentials to GitHub.

## 2. Repository state
- Repository: `odis78/Buddy-MyAI`
- Upstream: `ghorbelhamdi/buddy-android`
- Original Buddy version: `2.3.14`
- Android namespace: `app.buddy.assistant`
- Test applicationId: `app.buddy.myai`
- Source branch copied at checkpoint creation: `test/mcp-sse-response-fix`
- This branch was created directly from the source branch ref on GitHub. It preserves the source tree as it existed when GitHub resolved that ref during branch creation.
- Existing PR #1: `[TEST] Fix MCP SSE response parsing`, open and not merged at last check. https://github.com/odis78/Buddy-MyAI/pull/1
- The prior recorded branch HEAD was `bcd58ee7e2b9d06795c8b73bde3e9ff7632a25ba`; this is historical context, not asserted as the exact source SHA at checkpoint creation. Verify the branch history on GitHub if an exact SHA is required.

## 3. Device and observed working behavior
Device reported and checked in Buddy's phone UI:
- Commercial name: Infinix NOTE 30 Pro
- Model: `Infinix X678B`
- Android: 14
- XOS: `V14.0.0`
- Build: `X678B-H894OPQ-U-OP-250217V838`

Successful real-device checks reported by the user:
- Buddy app launches and chat responds.
- `read_screen` reads the current screen.
- `open_app` opens Android apps.
- `scroll` scrolls the screen.
- `tap` selects visible elements.
- A sequential run opened Settings, navigated to the device information page called «Мой телефон», and read Android 14, Infinix X678B, Infinix NOTE 30 Pro, XOS V14.0.0 and the build number above.
- The successful sequence used one agent and sequential tool calls. This is evidence that the basic phone-control path works, not proof that all long or concurrent runs are stable.

## 4. Buddy in-chat Steps history
The user supplied a screenshot of the in-chat tab titled «Шаги». The UI describes it as a record of what the agent did on the phone during recent runs, including screenshots after actions. The screenshot showed:
- Run at 11:16: duration 00:39, 0 steps, completed, context 58K; message said there were no phone actions.
- Run at 11:14: duration 00:56, 4 steps, completed, context 47K. Visible actions: opened Settings, tapped element 26, scrolled down, tapped element 37. Action cards included screen thumbnails and descriptions.
- Run at 11:09: duration 00:45, 2 steps, completed, context 41K. Visible actions included opening Settings and tapping element 26.
This in-chat Steps history is a useful diagnostic source. Compare its timestamps, action sequence, screenshots, duration, step count and context size with helper logs and failure messages. The screenshot alone does not establish the cause of a process crash.

## 5. Source-level architecture and endpoints previously inspected
The following details were read from the source branch in the preceding audit. They describe code configuration, not a fresh live inspection of all app settings.

### Local MCP server
File: `app/src/main/java/app/buddy/assistant/McpServer.java`
- Local endpoint used by the Android app and Linux helper: `http://127.0.0.1:8766`
- MCP path: `/mcp`
- Event callback path used by helper: `/event`
- Server is bound to loopback for local access.
- MCP calls require Bearer authorization.
- Buddy issues a per-run token associated with a chat. It is distinct from a Tailscale paired-computer token.
- The current implementation uses `Executors.newCachedThreadPool()` for incoming MCP requests. This is an audit target for resource/concurrency analysis, not an instruction to change it without tests.

### Linux helper and Codex route
Files: `app/src/main/java/app/buddy/assistant/Helper.java`, `linux/buddy-run`
- Helper launches `/root/bin/buddy-run` inside Buddy's embedded Linux environment.
- The helper issues a run token and streams process output/events to the Android app.
- The Codex route points phone MCP at `http://127.0.0.1:8766/mcp?chat=$SID`.
- It uses `BUDDY_TOKEN` for Bearer authorization.
- MCP tool timeout is configured as 600 seconds.
- Standard Codex run is configured with read-only sandbox and shell/unified_exec disabled; developer mode uses a broader sandbox. Do not weaken these safety settings as a speculative crash fix.
- `buddy-run` includes process lifecycle and cleanup logic. Inspect before changing process-tree handling, traps or cleanup.

### Tailscale phone tools
File: `app/src/main/java/app/buddy/assistant/Tailnet.java` and MCP handling in `McpServer.java`
- Source supports an optional remote phone-tools listener on a Tailscale IPv4 address in `100.64.0.0/10`.
- The remote listener uses port 8766 and the MCP path.
- Remote pairing is separate from per-run chat tokens. A paired computer receives its own random token after user approval; revocation is supported.
- The feature is off by default in source.
- Actual current toggle state, Tailscale address, paired computer names and live tunnel status were not verified during this checkpoint operation.

### Codex Remote Control
File: `app/src/main/java/app/buddy/assistant/CodexRemote.java`, script `linux/codex-remote`
- Separate Codex app-server process inside Buddy's embedded Linux.
- Its source configuration points to local MCP port 8766 and uses the helper-provided `BUDDY_TOKEN`.
- MCP tool timeout is 600 seconds.
- This is separate from Tailscale phone-tools pairing and separate from the user's ChatGPT app session.

### Claude Remote Control
File: `app/src/main/java/app/buddy/assistant/RemoteSession.java`
- Separate Claude Code remote-control session inside Buddy's embedded Linux.
- Source uses working directory `/root/remote` and log `/root/.buddy/remote.log`.
- Actual runtime toggle/session state was not verified here.

### SSH
- Buddy has a separate SSH server component, scheduled/managed by `BuddyService`.
- Actual runtime state, port, credentials and reachability were not verified here. Do not infer that SSH is active just because the source schedules an `ensure` call.

## 6. Port discrepancy to preserve and investigate carefully
- Android MCP server and Linux helper in this source branch use port **8766**.
- `mcp-bridge/bridge.mjs` was previously read and its default `BUDDY_URL` was `http://127.0.0.1:8765`; comments also referenced 8765.
- This may be a legacy/default bridge setting or may be overridden in the actual runtime environment. It has not been established which bridge instance is currently used.
- Do not change either port or bridge URL until the actual app/bridge path and environment override are verified. The discrepancy is intentionally recorded, not silently normalized.

## 7. Previous source fixes and CI evidence
- `mcp-bridge/sse-response.mjs` provides parsing for JSON and SSE responses, including multi-line data, CRLF/CR, ignoring notifications/unmatched IDs and validating JSON-RPC response envelopes.
- `mcp-bridge/bridge.mjs` calls `parseRpcResponse(text, 1)`.
- Node regression tests were added; the prior audit reported 17 tests.
- Previously observed CI run #14: https://github.com/odis78/Buddy-MyAI/actions/runs/44878596425
- Reported successful jobs: `helper-tests`, `apk`.
- Artifact name `buddy-apk`, artifact id `14697142663`.
- Previously reported artifact ZIP SHA-256: `dd738646bd92d670d2556f4ce0b5a00216d22895717854d9774fcfc36b05cd58`.
- A previously extracted APK was reported at `/mnt/data/Buddy-MyAI-port-fix-test.apk`, SHA-256 `2e56513b0b981ef9fc42c0f6fade33b33ad23c39dcda76a7f6d5777240d151df`. That was a session-scoped path and must be re-verified before reuse.
- Do not assume this is the exact APK currently installed unless its version/hash is checked on device.

## 8. Failure evidence and what is not yet proven
Previous longer run ended with:
- `exit 137`
- `failed to spawn thread: ... WouldBlock`
- `Broken pipe`
- Buddy's UI message said Android terminated agent processes.

Interpretation:
- 137 commonly represents termination by SIGKILL, but does not identify who sent it or why.
- `WouldBlock` while creating a thread indicates a resource/limit problem but does not identify the specific limit.
- `Broken pipe` may be secondary after a consumer or process exits.
- No verified OOM record, Android LMK evidence, process/thread count, memory snapshot or kernel resource-limit measurement was available in the supplied output.
- The later short sequential run succeeded. This does not prove the underlying cause has been fixed.
- Do not disable Android child-process restrictions, change developer options, or change concurrency/thread-pool settings based on the available evidence alone.

## 9. What this GitHub checkpoint does and does not preserve
This branch preserves the repository source tree at the moment it was created from `test/mcp-sse-response-fix`. It is a rollback point for source code.

It does **not** back up private runtime data stored inside the installed app or embedded Linux, including:
- Tailscale auth state and paired-device secrets;
- MCP run/pairing tokens;
- SSH keys/passwords;
- Codex/Claude authentication state;
- private environment files;
- chat/session databases and local workspaces;
- Android app-private data and permissions;
- current on-device tunnel toggle states or actual network reachability.

These must be backed up separately through a secure, user-approved export from Buddy/app storage. Do not copy secrets into this repository or into a public report. A source-code branch alone cannot restore private app data or Android permissions.

## 10. Safe rollback and continuation procedure
1. To recover source code, create a new working branch from `checkpoint/working-2026-11-21`; do not force-move or edit the checkpoint branch.
2. Compare the new working branch against the checkpoint before building.
3. Restore only the intended app/source version; do not blindly restore old app-private data over newer data.
4. For a future change, use a separate branch and a narrowly scoped diff.
5. Run unit/helper tests and CI before producing an APK.
6. Verify APK applicationId, versionCode/versionName, signature and SHA-256.
7. Install only after review and explicit user approval. Do not uninstall the original Buddy package `app.buddy.assistant`.
8. Re-check the app's actual runtime tunnel settings after installation; source defaults do not prove current runtime state.

## 11. Next read-only audit
Before any fix:
- Verify this checkpoint branch and the source branch history on GitHub.
- Inspect the full process startup/cleanup flow in `linux/buddy-run`.
- Inspect `Helper.java`, `ChatHub.java`, `McpServer.java`, `BuddyService.java`, and process/thread concurrency.
- Read the exact runtime state of Buddy's tunnel settings from the app UI without changing them.
- Compare Buddy's in-chat «Шаги» history with `last-error.log` and any available Android process/memory diagnostics.
- Resolve the 8765/8766 bridge discrepancy from observed configuration before touching it.
- Propose a minimal patch only after the cause is supported by evidence.

**Checkpoint principle:** preserve the working phone-control path first. No refactor, tunnel redesign, port cleanup, process-limit changes, or broad architecture changes as part of a diagnostic guess.
