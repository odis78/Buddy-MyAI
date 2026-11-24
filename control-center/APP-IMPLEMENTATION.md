# Android application implementation

## Implemented through M2 (device validation pending)

- Standalone Android application namespace/application ID: `app.jiti.controlcenter`.
- Independent Gradle project under `control-center/`.
- Registry-backed dashboard with 42 distinct infrastructure, controller, remote-tunnel, MCP-server, tool-catalog, optional-package, optional-service, and capability rows.
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

- Guarded Buddy-only lifecycle operations exist as a prototype: start uses the fixed current Buddy YAML and validated environment; stop rechecks exact PID/executable/config identity and sends SIGTERM only. They remain unverified on the target Infinix device and require an in-app confirmation. No global or multi-profile lifecycle controls are enabled.
- Termux RUN_COMMAND adapter exists for a fixed allowlist: identity, Tunnel Client/curl/Python versions, secret-safe Buddy environment-source audit, existing tunnels-script audit, Buddy tunnel health, unauthenticated local MCP challenge, PID-scoped executable/config identity checks, and an authenticated MCP initialize plus paginated tools/list probe that returns no secret values. Guarded Buddy-only launch/stop scripts are separately allowlisted and require preflight plus explicit confirmation.
- The adapter uses Java PendingIntent result callbacks, output redaction/capping, a timeout, and requires Termux >= 0.109 plus the explicit Android permission.
- No changes to Termux external-app policy.
- User-started foreground monitor is implemented with Android 14 `specialUse` service type, persistent notification, notification stop action, sticky restart behavior, and a 60-second read-only Buddy health/MCP-listener poll.
- The monitor records state changes in a bounded SQLite event journal (2,000 rows maximum).
- No automatic retries executing state-changing commands; bounded retry/circuit-breaker policies currently govern diagnostic decisions only.
- No configuration editing.
- No app-side credential storage or display. The fixed Termux Buddy launch operation reads the existing approved env-file values in memory and passes them to the Tunnel Client process; values are never returned to Android or logged.
- The authenticated MCP probe is implemented but not yet proven on the target device; it independently verifies the exact Tunnel Client executable/config before reading its process environment, returns tool names/status only, and never displays or persists the token.
- Buddy launch preflight requires a mode-600 fixed env file with the Control Plane key and MCP auth token/header. Historical diagnostics show the MCP token is not in the documented runtime.env; until its source is safely resolved, launch intentionally returns BLOCKED. It does not source shell startup files or substitute the REA Control Plane key.
- Buddy stop preflight is read-only; status-file writes occur only during the confirmed stop operation. Stop revalidates PID, executable, exact Buddy YAML and process start-time before SIGTERM, handles PID reuse and `/proc` teardown, and never sends SIGKILL.
- The embedded-script CI harness exercises the fixed launch/stop paths against temporary fake files/processes; it does not touch the real phone or Termux configuration.
- Read-only diagnostics and the foreground monitor now check health URL/PID and exact executable/config identity for Buddy, Bridge, DroidMCP, and REA Android. The monitor polls the three REA profiles sequentially after the Buddy MCP cycle; their snapshots use a two-minute freshness window.
- UI success states require both a zero process exit code and a successful Termux result callback.
- No automatic permission changes.

This is intentional. The RUN_COMMAND interface is powerful enough to execute arbitrary commands in Termux if both Android permission and Termux policy are enabled. It must be an explicit opt-in and all command paths must be fixed and reviewed. See [TERMUX-INTEGRATION-GATE.md](docs/TERMUX-INTEGRATION-GATE.md).

## Next implementation gates

1. JVM tests, isolated Python lifecycle-script harness, four-profile monitor/identity compilation, and debug APK assembly passed in run 45299172715; target-device validation remains required.
2. Prove Termux package/version/permission/external-app policy, result callbacks, output redaction, and /proc identity reads on Infinix X678B.
3. Test Android 14 specialUse foreground start/stop, notification visibility, partial wake lock, task swipe, process death, force-stop, and OEM battery behavior.
4. Recover exact profile metadata and start/stop contracts for all four Tunnel Client profiles without writing current configuration.
5. Locate the Buddy token/header source by key names only; implement authenticated MCP initialize/tools/list only after the source is safely resolved.
6. Verify the four-profile background monitor on Infinix, including Termux service timeouts, Android foreground-service lifecycle, poll cadence, and OEM battery restrictions.
7. Add authenticated MCP probes and reconcile tool catalogs for Bridge, DroidMCP, and REA Android.
8. Implement guarded per-profile controls and bounded recovery only after those contracts pass.
9. Run repeated device acceptance tests and publish a signed-off APK only when functional criteria pass.

## Known design constraint

Tunnel Client v0.0.19 `runtimes connect` generates a minimal HTTP MCP profile without Buddy's required Authorization headers. It is not a drop-in migration for the existing Buddy profile. See [TUNNEL-CLIENT-0.0.19-ANALYSIS.md](docs/TUNNEL-CLIENT-0.0.19-ANALYSIS.md).
