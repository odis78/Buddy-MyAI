# Android dashboard prototype

## Implemented in M0.1
- Standalone Android application namespace/application ID: `app.jiti.controlcenter`.
- Independent Gradle project under `control-center/`.
- Read-only dashboard skeleton with grouped component rows.
- All initial rows show **НЕ ПРОВЕРЕНО**; no stale PID or historical status is presented as live.
- Global and per-component controls are present visually but disabled until the Termux adapter and verified command contracts exist.
- No secrets, tokens, environment values, or private endpoint credentials are embedded.

## Deliberately not implemented yet
- No process launch/kill code.
- No Termux RUN_COMMAND integration.
- No foreground service.
- No automatic retries.
- No configuration editing.
- No network MCP probes.
- No automatic permissions changes.

This is intentional. A control panel that claims to restart processes before it knows the exact process identity and command contract would be unsafe.

## Next implementation step
Run the GitHub Actions debug build. Then add a read-only diagnostics layer and verify Termux's supported external-command interface on the target Infinix before enabling any action buttons.
