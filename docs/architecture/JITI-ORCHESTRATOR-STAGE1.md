# Jiti main assistant with selectable worker agents

Date: 2026-11-21  
Target app version: 2.3.17-JITI.1 (versionCode 215)  
Branch: `feature/jiti-orchestrator-codex-worker`  
Base: `test/mcp-sse-response-fix`

## Goal

Keep one visible assistant identity, Jiti, in Buddy regardless of which installed agent/model currently powers the chat. Jiti handles ordinary conversation and phone tasks directly. When a task genuinely benefits from a separate worker, Jiti can delegate it to a selected installed agent and receive its report in the same chat.

Buddy's existing Android accessibility service, MCP server, tool-level safety checks and approval cards remain the phone-control and safety layer.

## Free-plan and authentication facts

- Codex CLI signs in through the user's ChatGPT account. It is not an embedded ChatGPT web-chat session or a general API endpoint.
- ChatGPT Free includes limited Codex access. The allowance is account-controlled and cannot be expanded by spawning more Codex processes.
- A Codex helper uses the same ChatGPT account and plan allowance as a Codex parent. Delegation is task isolation, not a quota bypass.
- OpenCode can use its free models without a separate account. Those models may be busy, rate-limited or unavailable; no uptime or unlimited usage is promised.
- A practical no-API-cost arrangement is to run Jiti's main chat on an available OpenCode free model and request a Codex worker for specialist tasks when Codex is signed in and its allowance is available. The worker selection is explicit in the delegate task.
- If Codex's allowance is exhausted, Jiti must continue with its own active model/tools where possible or explain the limit. It must not disguise a plan refusal as a Buddy permission error or attempt to evade the provider's limits.
- API-key sessions are a separate billing path and are not required by this design.

## Request flow

1. The user sends a message in Buddy.
2. The primary chat is presented as Jiti, independent of the active backend.
3. Jiti answers ordinary questions directly.
4. For phone work, Jiti first uses Buddy's direct phone tools and verifies their results.
5. When an independent worker is genuinely useful, Jiti calls `delegate`. Each task can optionally set `agent` to `claude`, `codex`, `opencode`, `agy` or `grok`; if omitted, the worker uses the parent's backend.
6. A worker uses its own backend's authentication, model defaults and plan allowance. Its report returns to Jiti.
7. Jiti gives the user the verified result in the same visible chat.

## Safety and concurrency

- The primary chat can see the Buddy tool catalog, including direct phone tools and `delegate`.
- The server continues to enforce tool-level policy, app permissions, Stop/takeover, password protection, confirmation cards and helper restrictions. Hiding a tool from the model is not treated as the security boundary.
- Helpers remain read-only for phone actions: they may inspect and navigate but cannot send, post, buy, pay, delete, install, change settings or ask the user.
- If Background mode has a paired hidden display, independent phone helpers can use separate displays.
- If Background mode is not paired, only one helper may use the visible phone screen at a time. User takeover and PAUSED behavior remain in force.
- A helper cannot start another helper. Notification-rule runs retain their existing restrictions.

## Files changed in this stage

- `Tools.java`: full Buddy tool catalog for the primary chat; optional worker-agent selection in each delegate task.
- `Helpers.java`: validate the optional worker backend and use that backend's own default model/effort when it differs from the parent.
- `ChatHub.java` and `Sessions.java`: keep the primary identity as Jiti across backends.
- `buddy-run`: apply Jiti's role instructions across backends; make Free-plan limits explicit and explain that Codex helpers share the same allowance.
- `BuildInfo.java`: next test build version 2.3.17-JITI.1 / code 215.

## Acceptance tests on the Infinix X678B

1. Start a primary chat on Codex. Confirm the visible identity is Jiti and ordinary conversation works.
2. Ask Jiti to read the screen and open Settings. Confirm it uses direct Buddy tools and verifies the result.
3. Start a primary chat on OpenCode with a free model. Confirm the visible identity remains Jiti.
4. Ask Jiti on OpenCode to delegate a phone inspection with `agent=codex`. Confirm the child uses Codex if installed/signed in, and returns a report.
5. Confirm a helper cannot send, post, buy, pay, delete, install or change settings.
6. Confirm the same app-level approval cards still stop sensitive direct actions.
7. Simulate or observe a Codex quota refusal. Confirm Jiti reports the actual plan limit and continues only with its own available backend; no retry loop or claim of extra quota.
8. Confirm only one foreground phone helper runs when Background mode is not paired.
9. Confirm existing chats, settings and sign-ins survive an in-place update. Do not uninstall or clear app data.

## Known limits

- This stage does not bypass ChatGPT Free limits or guarantee that OpenCode's free providers are always available.
- It does not yet implement a transparent automatic migration of an already-running Codex turn to another backend after a quota refusal. Agent selection and cross-agent delegation are supported; automatic mid-turn recovery requires a separate state-machine change and dedicated tests.
- The helper remains read-only for phone actions. A parent-owned approval relay for delegated state-changing actions is not part of this stage.
- APK update compatibility still depends on signing with the same private key as the installed package. Do not install a differently signed APK over the existing app.

