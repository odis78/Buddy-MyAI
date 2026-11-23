# JITI Control Center status

**Branch:** `project/jiti-control-center`  
**Protected base:** `ef9fea50f86865f97cf82b45f6bc2c6e84f174e1`  
**Current milestone:** M1, core registry and safety-policy foundation.

## Completed
- Isolated project branch; protected Buddy checkpoint and main branch remain untouched.
- Historical inventory and dependency/recovery design documented from 22–23 November evidence.
- Android project and first debug APK build pipeline established.
- Added typed component registry with evidence labels and dependency metadata.
- Added dependency-aware startup/shutdown planner.
- Added bounded retry/backoff and circuit-breaker decision policy.
- Added credential redaction utility.
- Added PID/process identity guard against stale or mismatched PID files.
- Reworked dashboard to render the registry and historical evidence separately from live status.
- Added JVM unit tests for dependency planning, retry policy, secret redaction, and process identity.
- CI now runs unit tests before APK assembly.

## Explicitly not complete
- Live Android/Termux process inspection.
- Termux external-command integration and permission/lifecycle proof.
- HTTP MCP initialize/list-tools probes.
- Exact profile launch/stop contracts for all four tunnel-client instances.
- Foreground monitoring service and persistence.
- Runtime actions, recovery executor, and on-device acceptance tests.

## Next engineering gates
1. Pass the new unit-test and APK build workflow.
2. Add graph-cycle validation tests and runtime state/event model.
3. Recover exact source evidence for each profile's launch contract and endpoints.
4. Prove supported Termux app integration before adding any process-control implementation.
5. Add read-only live probes, then device tests.
6. Only after those pass, add guarded start/stop/restart controls.

## Safety and acceptance boundary
The target remains a fully working utility, not a visual shell. Completion requires real live status, verified dependency-aware controls, bounded safe recovery, foreground/lifecycle tests on Infinix X678B, and repeated test/fix cycles. CI success alone is not completion.

No existing Buddy app, protected checkpoint, REA working tree, tunnel YAML, runtime.env, credentials, or live phone service has been changed by this development stage.
