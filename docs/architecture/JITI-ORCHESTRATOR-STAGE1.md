# Jiti main chat + Codex phone executor (Stage 1)

Date: 2026-11-21  
Target app version: 2.3.15 (versionCode 213)  
Branch: `feature/jiti-orchestrator-codex-worker`  
Base: `test/mcp-sse-response-fix`

## Goal

Keep one visible assistant identity, Jiti, in Buddy. The main chat handles normal conversation and decides when a phone task is needed. A delegated Codex helper performs the phone inspection and returns a report to the main chat.

The existing Android accessibility service and Buddy MCP server remain the phone-control layer. No API key or separate OpenAI API billing is introduced.

## Important authentication/model distinction

The zero-API implementation uses Codex CLI authenticated with the user's ChatGPT account. It does **not** embed the ordinary ChatGPT Free web-chat model as a public in-app API. OpenAI documents ChatGPT sign-in for local Codex CLI/app-server use; API-key use is separately billed. The app labels the parent conversation `Jiti · Codex` to make the implementation honest.

This is the supported no-API route available to this project today. A true standard-ChatGPT model inside a third-party Android app would require a supported API entitlement or another officially supported product interface; the ChatGPT Free web session is not a general-purpose API endpoint.

## Request flow

1. User sends a normal message in Buddy.
2. A Codex parent chat runs as Jiti's conversation/orchestration layer.
3. For a phone task, Jiti calls Buddy's existing `delegate` tool with one phone task and waits.
4. Buddy creates a child helper chat using the same ChatGPT-authenticated Codex CLI.
5. The helper receives the full phone MCP tool set. The parent does not.
6. The helper opens apps, reads the screen, navigates and/or takes a screenshot, then returns a report.
7. Jiti explains the verified result in the same visible conversation.

## Safety and concurrency

- The main Codex parent chat's MCP catalog exposes only orchestration-safe tools (`delegate`, `subagents`, `ask`, `notify`, scheduling tools, and optional background-browser tools).
- The server-side call guard also rejects direct phone-tool calls from a parent Codex chat. Hiding tools from the model is not the only enforcement layer.
- Child helper chats retain the full MCP catalog.
- Existing helper restrictions remain: helpers may inspect and navigate, but cannot send, post, buy, pay, delete, install, change settings or ask the user. This first stage is intentionally read-only.
- If Background mode has a paired hidden display, helpers use it as before and may run multiple independent phone tasks.
- If Background mode is not paired, one helper may use the visible phone screen. Only one phone task is allowed at a time in that mode. Buddy's pause/takeover behavior remains in force.
- Existing non-Codex backends are not changed by the Jiti tool filter.

## Files changed

- `BuildInfo.java`: version 2.3.15 / code 213.
- `ChatHub.java`: marks parent Codex runs as Jiti orchestration runs.
- `buddy-run`: appends the Jiti role instructions to those Codex runs; helper runs do not receive them.
- `BuddyService.java` and `Tools.java`: role-specific MCP tool catalog plus server-side enforcement.
- `Helpers.java` and `Sessions.java`: permit one read-only helper to use the visible screen when no hidden display is paired; preserve the existing helper safety rules.
- Helper script version increased to 35 so the app syncs the updated script.

## Acceptance test on the Infinix X678B

1. Install the test APK over the current Buddy-MyAI test build only after CI succeeds. Do not uninstall or clear app data.
2. Confirm Codex remains signed in through the ChatGPT account.
3. Send a normal conversational question. Jiti should answer without opening an app.
4. Ask: "Open Settings, check the screen, and tell me the Android version."
5. Verify the parent chat starts a helper, the helper opens Settings and reads the screen, and Jiti reports the helper's result.
6. Verify the parent cannot directly call `read_screen` or `open_app`; the helper can.
7. Verify a second simultaneous phone task is refused when Background mode is not paired.
8. Verify a task that would change settings or send/delete anything is not performed by the helper.

## Explicitly not included in Stage 1

- A standard ChatGPT web-chat model embedded inside Buddy.
- API-key billing or API credential storage.
- Delegated state-changing actions with approval cards relayed from helper to parent.
- Multiple simultaneous foreground phone workers when no hidden display is paired.
- Changes to the immutable checkpoint branch, PR #1, or `main`.

## Next engineering stage

After the acceptance test, design a parent-owned approval relay so a Codex worker can propose a state-changing action, the user approves it in the main Jiti chat, and only then the worker executes it. Do not weaken the existing helper restrictions as a shortcut.
