# JITI Control Center device audit — 2026-11-26

## Scope and safety

Read-only inspection of the installed JITI 0.2.0-alpha app and Android/Termux process state. No live process, tunnel, configuration, key, or installed APK was changed. The existing Buddy tunnel was deliberately left running.

## Verified device state

- Installed package: `app.jiti.controlcenter`, version `0.2.0-alpha`, versionCode 2.
- Termux: `0.119.0-beta.3`.
- Tunnel Client: `0.0.19`.
- One Tunnel Client process was present: PID 25873, running the Buddy profile `.config/tunnel-client-buddy/buddy-myai.yaml`.
- JITI's read-only preflight returned Buddy tunnel `healthz=true`, `readyz=true`, `controlPlanePoll=true`, `processRunning=true`.
- JITI later displayed `Buddy MCP authenticated • 44 tools`. This confirms the Buddy path became healthy during this inspection; it does not establish health of the other profiles.
- The REA-Termux-Bridge, DroidMCP-Filesystem, and REA-Termux-Android Tunnel Client profiles each returned `processRunning=false`, with health/readiness/control-plane checks failing.
- The REA-Termux-Bridge MCP connection still returned HTTP 429 from the upstream SSE probe.

## Confirmed implementation gaps

- MainActivity renders the global `Проверить всё`, `Запустить всё`, `Перезапустить всё`, and `Остановить всё` controls with `setEnabled(false)` and no action listeners.
- Every component card renders `Live-проверка` and `Перезапустить` with `setEnabled(false)` and no action listeners.
- The command gate currently allows only `buddy.start` and `buddy.stop`.
- The app describes itself as audit mode. Multi-profile lifecycle controls and automatic state-changing recovery are explicitly not implemented.
- The existing tunnel-manager script hash on-device is `3759a07f7a01bb0dfc0ab21af2868b7ff148e9a8a1dfe41a600666a202f1cc8c`; the source's reviewed hash is `1efc8db1cc8b7e91a7f16546e5adad1206a0649b036dcc2afa048a4a87056b54`. The mismatch correctly prevents JITI from trusting that script for lifecycle operations. Do not bypass this guard without reviewing and pinning the exact script.
- A previous bridge audit reported a configured project root containing a literal `\${HOME}`. The current Bridge tunnel is stopped, so that local Bridge root must be rechecked after its tunnel is restored; it has not been changed.

## Interpretation

The Buddy Tunnel Client is not globally missing. The current live process is the Buddy profile and its health/authenticated MCP checks passed. The other three profiles are stopped. The initial "tunnel not ready" state was therefore not a sufficient diagnosis of all tunnels.

The disabled controls are not an Android permission glitch: they are hard-coded in the current app source and are consistent with the deliberately incomplete audit-only milestone. Enabling buttons without implementing and validating safe per-profile lifecycle operations would bypass the design's safety gate and could affect the working Buddy tunnel.

## Next implementation requirements

1. Keep the known-good Buddy tunnel untouched.
2. Review the exact on-device tunnel-manager script and reconcile its hash before any use.
3. Implement independently guarded start/stop/restart for each REA tunnel profile, with exact executable/config/PID/start-time identity checks, health confirmation, SIGTERM-only stop, and a shared operation lock.
4. Implement dependency-aware global controls without removing or hiding existing controls.
5. Add a shared operation coordinator so manual commands pre-empt queued automatic recovery and cannot race with it.
6. Add bounded automatic recovery with error classification, retry budget, backoff+jitter, circuit breaker, and a visible stop-automation control.
7. Add tests for manual/automatic contention, each profile lifecycle, partial failures, 403/429, auth/config errors, and restart-loop prevention.
8. Build and run CI/device acceptance before installing any replacement APK.

## Source baseline

Repository: `odis78/Buddy-MyAI`  
Project path: `control-center/`  
Base branch: `project/jiti-control-center`  
Base commit: `e4db9f24ceed4ac68c4bc93216b78383f305e568`  
Isolated work branch created: `fix/jiti-control-center-manual-recovery-20261126`
