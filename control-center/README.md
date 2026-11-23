# JITI Control Center

A separate, compact Android control application for the user's existing Buddy-MyAI / Termux / Tunnel Client / REA environment.

## Project boundary

- This is a **separate Android application**, not a modification of the Buddy-MyAI app.
- It observes and controls only explicitly registered components.
- It must preserve the known-good Buddy checkpoint `checkpoint/buddy-myai-2.3.17-JITI.1-2026-11-21`.
- It must not modify `~/rea`, tunnel YAML, runtime.env, tokens, or existing services without an explicit user-approved operation.
- The first milestone is a truthful inventory dashboard and read-only diagnostics. Start/restart/stop actions are added only after the exact launch and stop contracts are verified.
- The app must never display or log secret values.

## Target device

- Infinix X678B / X678B-OP
- Android 14 / SDK 34
- ARM64
- 1080 × 2400 display
- Termux-based runtime environment

## What the app will show

Every registered item gets its own row and state:
- Running
- Stopped
- Starting
- Not ready
- Error
- Unknown / not checked
- Not installed / unavailable

Rows are grouped by type, not collapsed into a single "tunnel" list:
1. Tunnel Client profiles
2. MCP servers and transports
3. MCP tool catalogs and tool-call probes
4. Android apps and services
5. Termux/runtime dependencies
6. End-to-end paths

Each row should show a concise explanation, last checked time, PID when meaningful, health result, error/restart counters, and individual actions permitted for that component.

## Main controls

- Check all
- Start all (dependency order, only after readiness)
- Restart selected component
- Restart a component group
- Restart all (controlled, dependency-aware sequence)
- Stop all (reverse dependency order)

All state-changing operations require an explicit in-app confirmation. No blind repeated restarts. A retry budget, cooldown, circuit breaker, and error classification are mandatory.

## Android background requirement

The control app is intended to stay active as a foreground service with a persistent notification until the user stops it. Android 14 and Infinix power management can still terminate or restrict apps. The app cannot honestly guarantee survival after force-stop, reboot restrictions, OEM task killing, or Termux being force-stopped.

A critical feasibility spike must prove how the control app can safely invoke Termux commands using Termux's supported external-command interface, and what survives when the Termux UI is closed versus when the Termux process is killed. Do not claim independence from Termux until this is demonstrated on the target phone.

## Development stages

1. Historical inventory and evidence reconciliation (2026-11-22 known-good state and 2026-11-23 failure diagnostics).
2. Component registry and dependency/startup state machine.
3. Read-only Android dashboard.
4. Termux integration proof of concept.
5. Per-component start/stop/restart with safeguards.
6. Foreground monitoring, recovery policy, and end-to-end verification.
7. GitHub Actions build and installable test APK.

## Evidence labels

Every inventory entry must be labelled:
- **Confirmed working (Nov 22)**: backed by a successful check or end-to-end operation.
- **Present/configured**: files or installation exist, runtime state not proven.
- **Observed stopped/failing (Nov 23)**: backed by diagnostic output.
- **Unverified**: historical evidence incomplete.

Do not turn an inferred dependency into a confirmed running service.
