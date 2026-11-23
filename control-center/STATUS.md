# JITI Control Center status

**Branch:** `project/jiti-control-center`  
**Base:** protected Buddy checkpoint commit `ef9fea50f86865f97cf82b45f6bc2c6e84f174e1`  
**Current milestone:** M0, historical inventory and architecture baseline.

## Completed
- Created an isolated project branch from the checkpoint commit.
- Recorded the 22-Nov Buddy tunnel runbook and known-good end-to-end evidence.
- Recorded the 23-Nov staged diagnostics and observed stopped state.
- Defined component evidence labels and a separate dashboard inventory model.
- Defined dependency-aware startup/recovery rules and Android/Termux feasibility tests.

## Not yet implemented
- Android app module, UI, service, persistence, component registry, Termux adapter.
- Verified start/stop command contracts for each profile.
- Full per-tool catalog reconciliation.
- Device-side foreground/background survival tests.
- CI build and APK artifact.

## Immediate next work
1. Retrieve and inspect the exact 22-Nov and 23-Nov GitHub diagnostic reports.
2. Reconcile the Buddy 52-vs-44 tool count and REA 207-tool inventory.
3. Recover all four tunnel profiles' exact command lines, IDs, endpoints, health files, and startup order.
4. Inspect Termux external-command integration and test its lifecycle constraints.
5. Only then implement the first Android dashboard and read-only checks.

## Safety boundary
No existing Buddy app, protected checkpoint, REA working tree, tunnel configuration, runtime.env, token, or phone service was modified by this project initialization.
