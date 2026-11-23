# Tunnel Client v0.0.19 source audit

## Source identity

- Repository: https://github.com/openai/tunnel-client
- User's installed source tag: `v0.0.19`
- Tag commit recorded in project history: `01d67bb4da0d8359b808b4761f8db171ab682cd7`
- Relevant pinned source:
  - `cmd/client/runtimes_command.go`
  - `pkg/codexplugin/manager.go`
  - `pkg/codexplugin/session/session.go`

The audit below is against the recorded v0.0.19 commit, not the repository's moving default branch.

## Runtime command surface confirmed in source

The v0.0.19 CLI includes the native `runtimes` command family:
- `runtimes create`
- `runtimes connect`
- `runtimes list`
- `runtimes status <alias>`
- `runtimes stop <alias>`
- `runtimes rm <alias>`
- `runtimes cleanup`

The `connect` command accepts an alias, optional existing tunnel ID, profile name/directory, MCP server URL or stdio command, runtime-key reference, and optional admin profile details. It writes a generated native profile and local alias/process state, then starts or reuses a managed runtime. This is a state-changing operation: it may resolve/create remote tunnel metadata, write a local YAML profile, persist alias and process records, clear/write health URL files, and launch a process. The implementation records process identity (PID start time and executable), health URL, profile/config paths, and log path. It performs health/readiness observation rather than treating a successful process spawn as sufficient.

The default managed process path starts the current tunnel-client executable with a fixed argument shape:
`run --profile-dir <profile-dir> --profile <profile-name> --log.file ""`.
The parent keeps the log handle, captures the child process, records stable identity, and checks health/readiness. Process termination is guarded by the recorded process identity; a bare PID is not considered sufficient. By contrast, the standalone `health --pid-file` probe only reads the PID and calls the process-exists check. It does not validate executable path, process start time, or config path. JITI labels this evidence `PID_PRESENT`, not verified `RUNNING`, until it performs its own /proc identity check. `runtimes stop` only stops an alias already known to this native state store, checks recorded PID start-time/executable or owned tmux identity, waits for termination, and clears the managed health URL/state. It is not a generic stop command for arbitrary `run --config` processes.

## Important incompatibility with the existing Buddy profile

The v0.0.19 `runtimes connect` profile generator's `mcpConfig` for an HTTP target writes only a `server_urls` entry with `channel=main` and `url=<target>`. It does not write `extra_headers` or `discovery_extra_headers`.

The existing Buddy MCP endpoint requires a Bearer Authorization header. Therefore, using `runtimes connect` naively for Buddy would generate an incomplete profile and break authenticated MCP forwarding. Do not migrate Buddy to this command unless a reviewed, tested method preserves the existing header references without exposing or copying the token.

This is a concrete reason not to replace the existing Buddy YAML or the three REA profiles with generated profiles as a blanket strategy.

## Existing profiles versus managed aliases

The existing Buddy and REA launcher scripts run explicit YAML configurations using `tunnel-client run --config <path>`. These are not automatically equivalent to aliases created and tracked by `runtimes connect`.

Consequences:
- `runtimes status <alias>` and `runtimes stop <alias>` must only be used for runtimes created/registered through that native managed-runtime subsystem.
- The v0.0.19 manager's `Status` path loads local alias/process records and may perform a remote tunnel lookup when a key reference is available. It also calls admin-profile resolution, which can write/update the local admin-profile state if the expected profile is missing or differs. Treat it as a managed-runtime status operation, not a guaranteed filesystem-read-only probe.
- `runtimes rm` removes managed local profile/config/health/log files and alias/process records; never use it against an unverified alias.
- `runtimes cleanup --apply` removes local stale alias/process metadata. It is not a generic repair or restart command.
- Do not assume the old profiles have corresponding aliases or process records.
- Do not run `runtimes cleanup --apply` as a general repair action.
- Do not let a generated alias overwrite, replace, or silently shadow an existing profile.
- The old `~/bin/tunnels` script's `nohup` launch strategy is not the preferred new design, but replacing it requires a tested migration plan and explicit runtime evidence.

## Implications for JITI Control Center

1. Inventory each existing profile independently, including its exact config, env source, MCP target, header references, health URL file, PID file, and log.
2. Use a read-only health/identity audit before any launch or stop action.
3. Keep the existing Buddy auth header references intact; never materialize token values into app storage.
4. Evaluate native `runtimes connect` only for a profile whose complete config can be represented without losing authentication or transport settings.
5. For profiles that cannot be represented by native aliases, use a separate fixed, reviewed launch contract through Termux RUN_COMMAND only after its opt-in security and lifecycle checks pass.
6. Keep stop/restart scoped to one profile and require stable process identity plus config-path match.
7. Never claim ready unless the process identity, healthz, readyz, Control Plane poll, and relevant MCP probe all pass.

## Current conclusion

The native managed-runtime subsystem is valuable source material for lifecycle and process-identity safeguards, but it is **not a drop-in replacement** for the four existing profiles. Buddy's required HTTP Authorization header alone proves that blindly converting it to the v0.0.19 generated profile format is unsafe.
