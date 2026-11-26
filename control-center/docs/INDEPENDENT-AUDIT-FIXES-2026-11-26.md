# JITI Control Center 0.2.1: independent audit remediation

Baseline reviewed: `0977b4b6fb148780e438e9aa0354792322bdf8bb`  
Working branch: `fix/jiti-control-center-manual-recovery-20261126`  
Protected baseline: `project/jiti-control-center` (unchanged)

## Implemented in the working branch

### JITI-01: monitor session isolation
- Added `SessionGeneration`; every monitor start receives a new token and stop/destroy invalidates it.
- Replaced the shared poll runnable with a session-bound runnable.
- All asynchronous Termux result callbacks are dispatched through session-bound wrappers.
- Delayed automatic-recovery tasks capture and validate the session token before changing queues or starting work.
- A late automatic lifecycle result releases only its matching `OperationCoordinator` request; it cannot publish stale snapshots or notification state into a newer session.
- Added JVM tests for invalidation across stop/start and stale tokens.

### JITI-02: durable manual stop intent
- Manual stop writes a persistent pending-hold before dispatching the Termux command.
- Recovery treats either a confirmed manual hold or pending manual stop as held.
- The result callback converts pending intent into a confirmed hold only for a confirmed stop, and clears pending intent on a failed stop.
- The callback persists the final state using the application context before checking whether the Activity still exists.
- Activity teardown completes the matching coordinator request without touching destroyed UI and broadcasts any queued manual request.
- Group stop steps use the same pending/confirmed protocol.

A pending hold intentionally remains after process death or a lost callback. This is fail-closed: automatic recovery will not undo a stop request whose final result was lost. A successful manual start clears the hold.

### JITI-03: PID-to-signal safety
- Replaced `os.kill(pid, SIGTERM)` with a pidfd-bound SIGTERM.
- Revalidates process start time and exact executable/config identity after opening the pidfd.
- Rechecks external manager activity immediately before signalling.
- Uses Python pidfd wrappers when present and Linux pidfd syscalls 434/424 as an Android arm64 fallback.
- Fails closed if pidfd acquisition or signalling fails. SIGKILL remains prohibited.

**Residual limitation:** `~/bin/tunnels` and `~/bin/rea-start` do not participate in JITI's per-profile flock. The second manager check narrows the race but cannot make the external-manager check atomic with pidfd signalling. Full cross-manager exclusion requires a shared lock protocol or lifecycle requests routed through the managers. Do not mark JITI-03 fully closed until that contract is inspected and implemented.

### JITI-04: retry scheduling
- Exposed the supervisor's `isProbeAllowed(profile, now)` gate.
- Auxiliary upstream health probes are skipped until the persisted `nextAllowedAt` expires.
- Local Buddy monitoring continues on its own poll cycle; one profile's backoff does not stop checks for other profiles.
- Added tests for Retry-After and transient backoff boundaries.

### JITI-05: preflight HTTP classification
- Profile preflight now runs the structured full-health probe even when local readyz is not ready.
- HTTP status and Retry-After are retained in the preflight result.
- Automatic restart is refused when a running-but-not-ready process has no trustworthy HTTP status.
- Added isolated validator fixtures for HTTP 401, 403, 429 and 503.

### JITI-06: Buddy read-only
- Removed Buddy start/stop/restart controls from the UI.
- Removed Buddy lifecycle command APIs, operation enum values, command factories and embedded lifecycle scripts from the app.
- Retained read-only Buddy tunnel health, MCP authentication probe, listener challenge and launcher-status file read.
- Added tests asserting Buddy lifecycle operation types are absent and CommandGate denies Buddy lifecycle commands.

## Verification

The CI workflow must pass on the final branch head:
- embedded Termux script validator
- JVM unit tests
- Android Lint
- clean-install 0.2.1 APK assembly

CI success is not a substitute for runtime validation. No APK has been installed and no phone, Buddy service, Termux tunnel, key or live process has been modified as part of this remediation.

## Remaining acceptance work

1. Confirm the final CI run for the current branch head.
2. Have Codex independently audit the remediation commits, especially session callback coverage, manual pending-hold lifecycle, pidfd fallback and preflight status handling.
3. Inspect the external manager scripts read-only and design a shared lock contract before declaring JITI-03 closed.
4. Run controlled device tests on Infinix X678B only after code audit and CI pass. Test stop/start races, Activity destruction, manager contention, pidfd availability, retry timing and HTTP error fixtures.
5. Keep the 0.2.0 checkpoint branch unchanged. Do not install 0.2.1 until the above gates are satisfied.
