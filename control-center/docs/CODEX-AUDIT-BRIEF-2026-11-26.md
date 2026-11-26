# Codex Audit Brief — JITI Control Center 0.2.1

Date: 2026-11-26
Repository: `odis78/Buddy-MyAI`
Active branch: `fix/jiti-control-center-manual-recovery-20261126`
Rollback/control-point branch: `project/jiti-control-center`

## User intent and release model

- The 0.2.0 implementation is a GitHub rollback checkpoint. Do not modify or merge changes into `project/jiti-control-center`.
- 0.2.1 is the only active development line.
- The user intends to uninstall the installed 0.2.0 app and then clean-install 0.2.1. Side-by-side installation is NOT a requirement.
- The new APK must retain the application ID `app.jiti.controlcenter`, use versionName `0.2.1-alpha` and versionCode 3. The CI debug APK is acceptable for clean install after 0.2.0 is intentionally uninstalled; it is not expected to update an APK signed with a different key.
- Do not uninstall anything, install anything, or operate the phone as part of this audit.
- Do not touch Buddy-MyAI, its tunnel, tokens, YAML, runtime.env, active services, or the existing REA runtime.
- Do not change `~/rea`, Termux runtime files, or any real tunnel configuration.
- Audit first. Do not edit source, commit, push, merge, tag, or create a release unless the user explicitly authorizes implementation after reviewing the audit.

## Product requirements

1. Preserve all current manual controls and their UI layout unless a proven defect requires a change.
2. Manual operations have priority over automation. An already-issued OS command may finish, but no competing lifecycle mutation may run in parallel; queued manual work must be dispatched next.
3. Provide one exclusive operation slot, reject duplicate commands, and prevent double start/stop/restart.
4. Automatic recovery is opt-in and can be disabled by the user. Disabling it must cancel pending/scheduled recovery work.
5. Automatic recovery allowlist is exactly `bridge`, `droidmcp`, and `rea-android`. Buddy must never be auto-managed.
6. No automatic process restart for network loss, HTTP 429, HTTP 401/403, or non-retryable 4xx. Respect bounded Retry-After; recheck transient failures without restarting a healthy local process.
7. Before every lifecycle mutation, revalidate network availability, process identity, executable identity, configuration identity, and existing manager/lock state.
8. Use bounded retry budgets, cooldown, circuit breaker, and safe failure behavior. Never loop indefinitely.
9. Keep diagnostics and user-visible status truthful. Distinguish local process readiness from remote control-plane/MCP availability.
10. Never print secrets, tokens, API keys, environment contents, or private YAML values.
11. The app must not claim that all tunnels are ready merely because the Buddy tunnel or MCP listener is ready.
12. No unknown process may be terminated. No SIGKILL.

## Required audit scope

### A. Architecture and concurrency
- Trace every manual and automatic start/stop/restart path from UI/service to Termux command execution.
- Verify `OperationCoordinator` is truly shared where required, every completion path releases only its own request, and stale callbacks cannot unlock another operation.
- Find race windows between preflight, confirmation, manual hold, automatic scheduling, and actual mutation.
- Verify the Termux-side file lock is atomic, non-blocking, safely permissioned, symlink-resistant, and held across the entire lifecycle mutation.
- Verify the existing `tunnels` / `rea-start` manager detection cannot falsely identify unrelated processes or miss the real manager.
- Check what happens when the Activity is paused/destroyed while a manual operation is queued or active.
- Check what happens when MonitorService stops, is killed, or restarts while callbacks or delayed tasks are outstanding.
- Confirm two simultaneous lifecycle calls cannot race through different entry points.

### B. Recovery policy correctness
- Trace classification for network unavailable, DNS/connect timeout/reset, TLS/certificate, 401, 403, 404, 429, 5xx, malformed health JSON, invalid PID, executable mismatch, configuration mismatch, and process missing.
- Verify 403 and auth/configuration errors require operator action and never restart local processes.
- Verify 429 uses Retry-After with sensible lower/upper bounds and never consumes process-restart budget.
- Verify transient transport/control-plane failures only schedule bounded rechecks.
- Verify process-missing/readiness recovery requires repeated observations and cannot act on stale observations.
- Inspect breaker half-open behavior, reset-on-success, persistence across service recreation, wall-clock changes, and app process death.
- Inspect whether persisted cooldown timestamps can become unsafe after clock changes or reboot.
- Verify manual stop hold persists and is not cleared by unrelated operations; manual start/restart releases only the selected profile's hold.

### C. Android / Termux integration
- Verify Android manifest permissions, exported flags, foreground service type, notification permission handling, wake-lock acquisition/release, and service stop behavior for Android 14 / target SDK 35.
- Verify Termux RUN_COMMAND integration handles missing Termux, disabled external apps, missing permission, callback failure, timeout, process death, and malformed output without hanging the UI or operation coordinator.
- Check that every command is allowlisted and arguments cannot escape the intended profile scope.
- Confirm no path or process matching can select Buddy by mistake.
- Verify no destructive command is issued when preflight is unknown or ambiguous.

### D. UI and state truthfulness
- Check all displayed version strings and build metadata report 0.2.1 consistently.
- Verify button enabled/disabled states reflect actual operation state and recover after every error/cancel path.
- Verify manual stop/start/restart confirmations are clear and selected-profile-only.
- Verify automatic recovery switch state, persisted preference, service synchronization, and immediate cancellation behavior.
- Ensure status messages distinguish queued, preflight, confirmation-required, running, succeeded, failed, blocked, waiting for network, and operator action.
- Verify no stale UI text falsely implies READY.

### E. Tests and CI quality
- Review every JVM unit test and embedded Python fixture test for coverage gaps and false positives.
- Recommend deterministic tests for race conditions and operation ordering.
- Recommend Android Lint/static analysis and emulator/instrumentation tests where feasible.
- Identify missing tests for service lifecycle, process death, delayed callback, cancellation, manual priority, HTTP Retry-After, clock rollback, and cross-profile isolation.
- Inspect Gradle dependency, manifest, target SDK, and CI workflow configuration.
- Confirm CI builds the standard `app-debug.apk` with application ID `app.jiti.controlcenter`, versionName `0.2.1-alpha`, versionCode 3. Do not reintroduce the `.candidate` applicationId suffix.

## Known observations to independently verify

- The previous CI run #576 passed the embedded Python validation, JVM unit tests, and APK assembly.
- The prior code review found the UI still displayed `0.2.0-alpha` despite Gradle being set to 0.2.1. This label has since been corrected in the active branch.
- Previous CI only ran JVM tests and embedded Python checks; Android Lint and emulator tests were not yet part of the pipeline. Lint has now been added to the active workflow and must be checked in the latest run.
- The Termux profile-control script uses `jiti-profile-control.lock`, a non-blocking `flock`, and checks for an existing tunnel manager before lifecycle mutation. Validate the implementation rather than assuming these checks are sufficient.
- The 0.2.0 and 0.2.1 applications will not be operated simultaneously. The user plans to uninstall 0.2.0 before clean-installing 0.2.1. Therefore, do not treat separate per-install SharedPreferences or per-process in-memory coordinators as a release blocker for simultaneous app instances. Still audit concurrency among all entry points inside 0.2.1 and its Termux scripts.

## Required Codex deliverable

Return a structured audit report with:

1. Executive verdict: `PASS`, `PASS WITH BLOCKERS`, or `FAIL`.
2. Findings ranked `P0 / P1 / P2 / P3`.
3. For each finding: exact file and line range, reproducible scenario, impact, evidence, and a concrete fix.
4. Separate confirmed defects from risks, missing tests, and recommendations.
5. A matrix of each user requirement vs. code path and test evidence.
6. A list of tests Codex actually ran, exact commands, and results. Do not claim tests that were not run.
7. A minimal ordered remediation plan.
8. Explicit confirmation that the audit made no source changes and did not touch the phone, Buddy, Termux runtime, tunnel credentials, or REA.

If Codex cannot inspect the active branch directly, stop and ask for the exact GitHub branch or repository access. Do not silently audit the default branch.
