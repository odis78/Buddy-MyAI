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
- Added read-only Buddy tunnel health and local MCP authentication-challenge probes.
- Added user-started Android 14 specialUse foreground monitor with persistent notification and stop action.
- Added bounded SQLite diagnostic event journal (maximum 2,000 events) and persistent latest-state snapshots per component.
- Dashboard now renders historical evidence and live state as separate labels, with per-component freshness windows and cumulative error/retry counters.
- Added JVM unit tests for dependency planning/cycles and scoped restart plans, retry policy, circuit breaker, state transitions, secret redaction, process identity, tool catalog, Termux command allowlisting, version gates, and result redaction.
- CI now runs unit tests before APK assembly.

## Explicitly not complete
- Live Android/Termux process inspection has not yet been verified on the target device.
- Termux RUN_COMMAND adapter is implemented for fixed read-only probes, but target-device permission, external-app property, and lifecycle proof are still pending.
- Authenticated HTTP MCP initialize/list-tools probes; current probe only verifies the local listener/auth challenge.
- Exact profile launch/stop contracts for all four tunnel-client instances.
- Foreground monitor and bounded SQLite event journal are implemented, but Android 14/Infinix lifecycle and notification behavior are not yet device-tested.
- Multi-profile state-changing controls and automatic recovery remain disabled. Buddy-only lifecycle code is implemented but has not passed target-device acceptance; it must not be treated as production-ready.

## Next engineering gates
1. JVM unit tests and debug APK build now pass in GitHub Actions run 45293208411 (commit f410352a053618c5ef6edca3e63c0d20de9ca025).
2. Verify Termux RUN_COMMAND, Buddy PID/executable/config identity probes, guarded Buddy start/stop in an explicitly approved test window, Android 14 specialUse foreground start, notification stop action, and partial wake-lock behavior on Infinix X678B.
3. Recover exact launch/stop and health-file contracts for Buddy, Bridge, DroidMCP, and REA Android profiles.
4. Identify the correct Buddy token/header source using the names-only environment audit, then add authenticated MCP initialize and tools/list probes without exposing values.
5. Add read-only health and MCP probes for the three REA profiles.
6. Only after these pass, add guarded per-profile start/stop/restart and bounded recovery.

## Safety and acceptance boundary
The target remains a fully working utility, not a visual shell. Completion requires real live status, verified dependency-aware controls, bounded safe recovery, foreground/lifecycle tests on Infinix X678B, and repeated test/fix cycles. CI success alone is not completion.

No existing Buddy app, protected checkpoint, REA working tree, tunnel YAML, runtime.env, credentials, or live phone service has been changed by this development stage.
