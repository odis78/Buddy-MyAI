# Startup, dependency, and recovery design

## 1. Core rule

A component is ready only when its own process state, health/readiness probe, and required downstream probe all pass. A PID file or open TCP port alone is insufficient.

## 2. Proposed dependency graph

This is the initial graph to validate against the 22-Nov runtime evidence. Dashed/unknown relationships must not be treated as proven dependencies.

```text
Android OS
 ├─ JITI Control Center foreground service
 ├─ Termux app/runtime
 │   ├─ Tunnel Client v0.0.19
 │   │   ├─ Buddy-MyAI tunnel profile
 │   │   │   └─ Buddy MCP transport → 127.0.0.1:8766/mcp
 │   │   ├─ REA-Termux-Bridge 0.1.3 profile
 │   │   ├─ DroidMCP-Filesystem profile
 │   │   └─ REA-Termux-Android profile
 │   └─ REA / Bridge runtime processes (exact process ownership to verify)
 ├─ Buddy-MyAI Android app
 │   ├─ AccessibilityService
 │   └─ Buddy MCP listener on 127.0.0.1:8766
 └─ Optional/conditional dependencies: Shizuku, ADB, Termux:API, Termux:Boot
```

## 3. Startup phases

1. **Preflight**: Android API, package presence, app permissions, Termux package/activity state, executable/config existence, file permissions, available storage, and secret-variable presence (boolean only).
2. **Process reconciliation**: read PID files, inspect process command lines, identify duplicate instances, verify ownership/config path. Never trust stale PID files.
3. **Local services**: verify Buddy app and MCP listener; verify REA/Bridge/DroidMCP services only if enabled in the selected profile set.
4. **Tunnel Client profiles**: start one profile at a time in dependency order; await process identity and health readiness before moving on.
5. **MCP discovery**: initialize each endpoint, list tools/prompts, compare against expected catalog fingerprints/counts.
6. **End-to-end tests**: perform read-only calls such as Buddy read_screen and REA inventory/health probes; no phone mutations.
7. **Final state**: report per-component status, skipped optional dependencies, errors, retries, and the first failing edge.

The exact order of REA Bridge, DroidMCP, and REA Android startup must be recovered from the 22-Nov run history. The app must not invent an order.

## 4. Restart policy

- Restart only the failing component and, if required, its downstream dependants.
- Never restart an upstream dependency while a dependent service is in active use without showing the impact.
- Retry budget: default 2 attempts per component per recovery cycle (configurable later).
- Exponential backoff with a maximum delay and a cooldown.
- Detect repeated identical errors; open a circuit breaker and stop retrying.
- Do not restart on authentication/configuration errors until a human-approved correction is available.
- Do not mutate YAML, env files, credentials, package installation, Android permissions, or application data automatically.
- Keep a bounded event log and append-only diagnostic report; redact secrets before persistence/upload.

## 5. Stop and restart ordering

Stop in reverse dependency order. Start in forward dependency order. The sequence must be generated from a validated dependency graph, not from a hard-coded flat list.

Before any stop/restart:
- confirm the target process belongs to the selected component;
- compare PID and command line/config path;
- reject stale or ambiguous process matches;
- preserve unrelated profiles;
- display the components that will be interrupted;
- require user confirmation for group-wide or full-system actions.

## 6. Termux and Android background feasibility

The UI closing, Termux activity closing, Termux process death, Android low-memory kill, reboot, and user force-stop are distinct events. They need separate tests.

A foreground service in JITI Control Center can keep the controller alive under normal Android service rules, but it cannot guarantee that another app's Termux process or child runtime remains alive after Termux is force-stopped or reclaimed by the OS. Termux command execution must use its supported external app API, with explicit user configuration/permission and strict command allowlisting.

Before implementation, prove:
- external command invocation from the control app;
- foreground notification and Android 14 service-type requirements;
- behavior when Termux UI is closed;
- behavior when Termux process is killed;
- behavior after device reboot;
- behavior after Termux is force-stopped;
- Infinix battery optimization and background launch restrictions.

Never promise "always on" as an absolute guarantee. UI must explain the actual last-known state and any Android restriction.

## 7. Acceptance tests

- A stale PID is shown as stopped, not running.
- Duplicate process is detected and not launched again.
- Each service waits for readiness before the next dependent service starts.
- A failed health check increments the right counter and identifies the failing edge.
- Authentication/config errors trip the circuit breaker instead of endless restarts.
- Individual restart leaves unrelated profiles untouched.
- Full restart follows reverse-stop / forward-start order.
- Secret values never appear in logs, screenshots, reports, or Git history.
- Closing the Termux UI is tested separately from force-stopping Termux.
- Buddy read_screen and REA health/tool discovery prove end-to-end readiness without modifying the phone.
