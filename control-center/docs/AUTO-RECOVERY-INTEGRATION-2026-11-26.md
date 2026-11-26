# JITI Control Center: bounded automatic recovery

## Scope and guard rails

Automatic recovery is opt-in and runs only while the user-started foreground monitor is active. The allowlist is exactly `bridge`, `droidmcp`, and `rea-android`. Buddy is excluded at three layers: the recovery supervisor rejects unknown profiles, the operation coordinator rejects automatic targets outside the three-profile allowlist, and the lifecycle script has no Buddy profile.

Every automatic mutation is preceded by a fresh profile preflight. If the profile became READY while waiting, the stale action is cancelled. A missing profile is started; an already-running but persistently unready profile may be restarted only after repeated observations and another identity/configuration preflight. The profile script also uses a non-blocking file lock and checks the existing `tunnels` / `rea-start` manager before mutating a profile.

## Error policy

| Observation | Automatic behavior |
|---|---|
| Validated network unavailable | Wait. Do not start, stop, or restart a process. |
| Process missing | Require repeated observation, then bounded start attempt. |
| Local readiness failure with no non-retryable HTTP status | Require three observations, then bounded restart attempt. |
| HTTP 429 | Recheck only. Honor reported Retry-After, bounded to 15 minutes. Never restart because of rate limiting. |
| HTTP 401 / 403 | Stop automatic recovery and require operator action. |
| HTTP 400 / 404 / 409 / 422, TLS/configuration/identity/permission errors | Stop automatic recovery and require operator action. |
| Transient network/control-plane failure (408/425/5xx, DNS/connect/read timeout, reset) | Bounded exponential recheck only. Do not restart the local process. |
| Unknown failure | Fail closed and require operator action. |

Recovery state (attempt budget, cooldown, consecutive observations) is persisted. The retry budget is bounded; the circuit breaker opens on repeated identical failures. A successful READY observation resets the budget.

## Manual priority

Manual profile and stack actions share the same operation coordinator as automatic recovery. A manual request made during an already-issued automatic command is queued for the next exclusive slot; no second lifecycle command runs concurrently. The automatic runner then broadcasts the queued request back to the foreground activity.

A confirmed manual stop sets a persistent per-profile hold so automatic recovery does not undo the user's stop. A manual start or restart releases that hold. The global auto-recovery switch cancels queued future automatic work; an OS command already issued is allowed to report completion safely, after which a queued manual action can run.

## Verification matrix

- JVM unit tests cover opt-in defaults, manual-over-automatic arbitration, Buddy exclusion, network-loss behavior, 401/403 non-retry, 429 Retry-After recheck, transient recheck without restart, cooldown, retry budget persistence, and circuit breaking.
- The embedded-script validator exercises isolated start/status/restart/stop lifecycle operations against a disposable local fixture, plus redaction and fixed-path checks.
- The validator also tests non-blocking lifecycle lock contention and rejection while the existing manager is active.
- GitHub Actions runs the script validator, JVM unit tests, and debug APK assembly.

## Device acceptance still required

CI and disposable fixtures do not prove the actual Infinix / Termux / VPN environment is healthy. Before installing over the known-good application, unlock the phone and verify read-only process identity, config paths, local readiness endpoints, and tunnel status. Then test network loss/restore, HTTP 403 and 429 handling, manual stop holds, manual priority during an automatic step, and repeated start/restart attempts on the real device. Buddy must remain untouched throughout. No API key, token, YAML, runtime.env, or existing service is modified by this integration.
