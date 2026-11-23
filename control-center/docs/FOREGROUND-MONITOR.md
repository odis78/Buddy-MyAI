# Foreground monitor design and lifecycle limits

## Current implementation

- Android foreground service: `MonitorService`.
- Declared type: `specialUse`.
- Required permissions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, and runtime `POST_NOTIFICATIONS`.
- Manifest subtype: user-started monitoring and recovery coordination for local Termux tunnel and MCP services.
- Start: explicit user action in JITI Control Center after Termux RUN_COMMAND permission/version checks.
- Stop: in-app stop button or persistent notification action.
- Restart mode: `START_STICKY` as a best-effort recovery hint.
- Poll interval: 60 seconds.
- A bounded 10-minute `PARTIAL_WAKE_LOCK` is renewed during monitoring so Handler-based polling can run while the screen is off. It is released on stop/destroy and naturally expires if the monitor cannot renew it. This increases battery use; the notification states that the CPU wake lock is active.
- Current probes: Buddy tunnel health/readyz/Control Plane poll, exact Tunnel Client executable path via `/proc/PID/exe`, exact Buddy config argument via `/proc/PID/cmdline`, and unauthenticated local MCP challenge. A PID-exists result alone is labelled `PID_PRESENT`, never `RUNNING` or `READY`. When health fails, collect a bounded process-name snapshot.
- Event journal: SQLite, 2,000 most recent events maximum, messages redacted before persistence.

## Why specialUse

The monitor is a user-started, persistent local infrastructure supervisor. It is not a media playback, location, connected-device session, or finite data-transfer task. The Android `specialUse` type is the closest applicable category for a continuous use case not covered by the standard types. Android requires the matching permission and a declared subtype property. This declaration does not guarantee exemption from OEM battery management or process termination.

Official Android documentation:
- https://developer.android.com/develop/background-work/services/fgs/service-types#special-use
- https://developer.android.com/develop/background-work/services/fgs/launch

## Guarantees and non-guarantees

JITI can keep an ongoing foreground service while Android permits it and can request sticky recreation after ordinary process loss. It cannot guarantee execution after:
- the user force-stops JITI or Termux;
- the user revokes required permissions;
- Android or Infinix aggressively restricts background execution;
- the device reboots before the user reopens/starts monitoring;
- the OS terminates the service for resource or policy reasons.

There is no hidden restart loop and no attempt to bypass Android controls. The partial wake lock is a transparent battery tradeoff, not a guarantee against OEM process killing. No boot receiver is enabled. Battery optimization exemptions remain a separate, user-controlled setup step.

## Target-device acceptance matrix

On Infinix X678B / Android 14, test each case separately and record the observed outcome:

1. Start monitor from visible Activity; verify notification appears immediately.
2. Swipe JITI task away; verify monitor continues and notification remains.
3. Close Termux UI without force-stopping Termux; verify read-only health probes continue.
4. Verify Buddy reports READY only when healthz, readyz, Control Plane poll, executable path, and Buddy config argument all match; a stale/reused PID must remain PID_PRESENT or NOT_READY.
4. Kill Termux process; verify monitor reports unavailable and does not loop commands.
5. Kill JITI process; verify whether Android recreates the sticky service.
6. Revoke RUN_COMMAND permission; verify monitor reports BLOCKED and stops issuing commands.
7. Deny notification permission; verify JITI refuses to start the monitor.
8. Force-stop JITI; verify no claim of continued operation.
9. Reboot; verify no automatic restart claim until an explicit boot workflow is separately designed and tested.
10. Repeat with Infinix battery management settings before considering any user-approved exemption guidance.

The service currently monitors only Buddy. It does not launch, stop, or repair any tunnel. Recovery actions remain disabled until all four profile command contracts are verified.
