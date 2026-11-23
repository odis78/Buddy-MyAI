# Android application implementation

## Implemented in M0.1–M1

- Standalone Android application namespace/application ID: `app.jiti.controlcenter`.
- Independent Gradle project under `control-center/`.
- Registry-backed dashboard with 40 distinct infrastructure, controller, remote-tunnel, MCP-server, tool-catalog, optional-package, optional-service, and capability rows.
- Historical evidence is displayed separately from live runtime status.
- Dependency metadata is stored independently from UI grouping.
- Pure-Java dependency planner generates forward startup and reverse shutdown order.
- Component status state machine rejects invalid transitions.
- Bounded exponential retry policy and per-component circuit breaker.
- Process identity model supports PID + process start-time + executable + config-path matching.
- Secret redaction runs when runtime snapshots and diagnostic events are constructed.
- Command gate denies all operations unless the exact operation is enabled, its contract is verified, user confirmation is present, and target identity is verified.
- JVM unit tests cover dependency ordering/cycles, retry/backoff, circuit breaker, state transitions, process identity, command gating, and secret redaction.
- GitHub Actions runs JVM unit tests before assembling the debug APK.

## Deliberately not enabled yet

- No process launch/kill code.
- Termux RUN_COMMAND adapter exists for a fixed allowlist of read-only checks: identity, Tunnel Client version, curl version, Buddy tunnel health, unauthenticated local MCP challenge, and process-name inventory.
- The adapter uses Java PendingIntent result callbacks, output redaction/capping, a timeout, and requires Termux >= 0.109 plus the explicit Android permission.
- No changes to Termux external-app policy.
- User-started foreground monitor is implemented with Android 14 `specialUse` service type, persistent notification, notification stop action, sticky restart behavior, and a 60-second read-only Buddy health/MCP-listener poll.
- The monitor records state changes in a bounded SQLite event journal (2,000 rows maximum).
- No automatic retries executing state-changing commands.
- No configuration editing.
- No credential retrieval or storage.
- No live MCP calls from the app.
- No automatic permission changes.

This is intentional. The RUN_COMMAND interface is powerful enough to execute arbitrary commands in Termux if both Android permission and Termux policy are enabled. It must be an explicit opt-in and all command paths must be fixed and reviewed. See [TERMUX-INTEGRATION-GATE.md](docs/TERMUX-INTEGRATION-GATE.md).

## Next implementation gates

1. Confirm CI unit tests and APK build pass.
2. Add a read-only Termux adapter behind a deny-by-default interface.
3. Prove package visibility, permission state, external-app policy, result callbacks, and output redaction on Infinix X678B.
4. Read and validate existing profile metadata without writing any current configuration.
5. Add read-only health and MCP probes, preserving Buddy Authorization header references.
6. Implement one-profile-at-a-time control only after exact launch/stop contracts and process identity have been validated.
7. Add persistent event history and a foreground monitoring service after Android 14 requirements and Infinix lifecycle behavior are tested.
8. Run repeated device acceptance tests and publish a signed-off APK only when functional criteria pass.

## Known design constraint

Tunnel Client v0.0.19 `runtimes connect` generates a minimal HTTP MCP profile without Buddy's required Authorization headers. It is not a drop-in migration for the existing Buddy profile. See [TUNNEL-CLIENT-0.0.19-ANALYSIS.md](docs/TUNNEL-CLIENT-0.0.19-ANALYSIS.md).
