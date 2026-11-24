# JITI Control Center status

**Branch:** `project/jiti-control-center`  
**Protected base:** `ef9fea50f86865f97cf82b45f6bc2c6e84f174e1`  
**Current milestone:** M2 plus guarded Buddy lifecycle prototype; target-device acceptance remains pending.

## Completed
- Isolated project branch; protected Buddy checkpoint and main branch remain untouched.
- Historical inventory and dependency/recovery design documented from 22–23 November evidence.
- Android project and first debug APK build pipeline established.
- Added typed component registry with 42 evidence-labelled rows, JITI controller/monitor, remote tunnel objects, ChatGPT connector, MCP servers, separate tool catalogs, optional Buddy services/packages, authenticated MCP probe, and dependency metadata.
- Added dependency-aware startup/shutdown planner and scoped downstream restart planning.
- Added bounded retry/backoff and circuit-breaker decision policy.
- Added credential redaction utility.
- Added PID/process identity guard against stale or mismatched PID files.
- Reworked dashboard to render the registry, all 52 Buddy source tools, and historical evidence separately from live status.
- Implemented fixed Termux RUN_COMMAND bridge with version/permission gate, PendingIntent results, timeout, output redaction, and bounded output.
- Added guarded Buddy-only start/stop/restart prototype using fixed Python scripts, preflight, exact PID/executable/config identity checks, explicit confirmation, and SIGTERM-only shutdown.
- Stop preflight is read-only. The stop path records process start-time, rechecks identity, tolerates transient `/proc` teardown, detects PID reuse, and sends SIGTERM only.
- Authenticated MCP probe independently verifies the exact Buddy executable/config identity before reading `/proc/PID/environ`; secret values and raw HTTP bodies are never emitted.
- Environment preflight rejects symlinked env files, group/other permissions, and all shell expansion; Authorization-prefixed values are normalized safely.
- Added an isolated Python harness that exercises launch preflight, blocked/missing credentials, permission and symlink rejection, shell-expansion rejection, safe execve with a fake binary, read-only stop preflight, PID mismatch, positive PID identity/SIGTERM stop, and MCP PID guard.
- Hardened stop preflight to be genuinely read-only: it no longer writes launcher status for already-stopped, stale-PID, or identity-mismatch results; a JVM regression test protects this.
- Corrected UI success handling so nonzero process exit codes cannot be mistaken for successful identity or stop operations.
- Audited the installed Tunnel Client health command against upstream v0.0.19 source; `health --url-file --pid-file --require-control-plane-poll --json` matches the supported CLI flags.
- Added read-only Buddy tunnel health and local MCP authentication-challenge probes.
- Added user-started Android 14 specialUse foreground monitor with persistent notification and stop action.
- Added bounded SQLite diagnostic event journal (maximum 2,000 events) and persistent latest-state snapshots per component.
- Dashboard now renders historical evidence and live state as separate labels, with per-component freshness windows and cumulative error/retry counters.
- Added JVM unit tests for dependency planning/cycles and scoped restart plans, retry policy, circuit breaker, state transitions, secret redaction, process identity, tool catalog, Termux command allowlisting, version gates, and result redaction.
- CI now runs unit tests before APK assembly.

## Explicitly not complete
- Live Android/Termux process inspection has not yet been verified on the target device.
- Termux RUN_COMMAND adapter and Buddy lifecycle prototype are implemented, but target-device permission, external-app property, callback, and lifecycle proof are still pending.
- Buddy launch preflight currently fails closed unless `CONTROL_PLANE_API_KEY` and `BUDDY_TOKEN` or `BUDDY_AUTH_HEADER` are present in the fixed mode-600 `runtime.env`. Historical Stage 5 found only `CONTROL_PLANE_API_KEY` in that file; `BUDDY_TOKEN` was observed in the interactive shell. Its safe source remains unresolved. JITI does not source shell startup files or substitute the separate REA key.
- Authenticated HTTP MCP initialize and paginated tools/list probe is implemented, but has not yet been accepted on the target device.
- The three REA profiles are currently checked only during the user-started diagnostics sequence; continuous background polling still covers Buddy only.
- Exact profile stop contracts for all four tunnel-client instances.
- Foreground monitor and bounded SQLite event journal are implemented, but Android 14/Infinix lifecycle and notification behavior are not yet device-tested.
- Multi-profile state-changing controls and automatic recovery remain disabled. Buddy-only lifecycle code is implemented but has not passed target-device acceptance; it must not be treated as production-ready.

## Next engineering gates
1. JVM unit tests, isolated embedded-script safety harness, multi-profile health/identity tests, and debug APK build passed in GitHub Actions run 45298486523 (commit 0909757de9857dbfaeb75f82a470d0e0e5f2eff5).
2. Resolve the Buddy MCP credential source using names-only diagnostics; until then, Buddy launch must remain blocked.
3. Verify Termux RUN_COMMAND, Buddy PID/executable/config identity probes, guarded Buddy start/stop in an explicitly approved test window, Android 14 specialUse foreground start, notification stop action, and partial wake-lock behavior on Infinix X678B.
4. Recover and validate exact stop contracts for Buddy, Bridge, DroidMCP, and REA Android profiles without changing live configuration.
5. Extend the foreground monitor to poll all four tunnel profiles with per-profile identity checks and freshness tracking.
6. Add authenticated MCP probes and reconcile tool catalogs for Bridge, DroidMCP, and REA Android.
7. Only after these pass, add guarded per-profile start/stop/restart and bounded recovery.

## Safety and acceptance boundary
The target remains a fully working utility, not a visual shell. Completion requires real live status, verified dependency-aware controls, bounded safe recovery, foreground/lifecycle tests on Infinix X678B, and repeated test/fix cycles. CI success alone is not completion.

No existing Buddy app, protected checkpoint, REA working tree, tunnel YAML, runtime.env, credentials, or live phone service has been changed by this development stage.
