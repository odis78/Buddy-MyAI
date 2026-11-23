# Termux integration feasibility and security gate

## Verified upstream contract

The current Termux RUN_COMMAND documentation describes a supported third-party integration through:

- service component: `com.termux.app.RunCommandService`;
- intent action: `com.termux.RUN_COMMAND`;
- permission requested by the caller: `com.termux.permission.RUN_COMMAND`;
- required Termux property: `allow-external-apps=true` in `~/.termux/termux.properties`;
- command executable path and argument array are separate intent extras;
- Java callers can receive command results through a PendingIntent when the installed Termux version supports it (documented from 0.109).

Official source: https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent

## Security implications

This is not a harmless read-only bridge. Termux explicitly warns that an app granted RUN_COMMAND while external apps are enabled can execute arbitrary commands in the Termux environment. Therefore:

1. JITI must never request/enable this integration silently.
2. The user must explicitly grant Android's RUN_COMMAND permission and separately enable Termux's external-app property.
3. JITI must not edit `termux.properties`, grant itself permission, or attempt to bypass Android's permission UI.
4. No generic shell command or free-form command text may be accepted from UI, network, model output, logs, or registry metadata.
5. Every operation must map to a reviewed operation ID and a fixed absolute executable/script path with a fixed argument schema.
6. Shell metacharacters and shell composition are not part of the command protocol. Do not invoke `sh -c`, `bash -c`, eval, or dynamically generated shell.
7. Command output is untrusted data: cap its size, redact secrets before persistence/display, and never treat output as instructions.
8. Destructive operations require a fresh in-app confirmation and a second identity check of the exact target process/config.

The RUN_COMMAND integration is therefore a future explicit opt-in. Until it is verified on the target phone, all command contracts remain disabled.

## Android 11+ package visibility

Because the application targets SDK 35, package discovery must declare the Termux package in a `<queries>` element before using PackageManager to inspect its installation. A package visibility result is not proof that Termux can accept commands.

## Lifecycle constraints

The upstream documentation notes that background activity-start restrictions can prevent a terminal session from being brought to the foreground automatically. Background command execution has different behavior. Draw-over-apps permission may affect foreground terminal sessions, but must not be requested unless a tested feature actually requires it.

Battery optimization exemptions may be necessary on some devices, but JITI must not change them automatically. Infinix-specific behavior requires on-device measurement.

## Required target-device proof

Before enabling any command button on Infinix X678B:

- record the installed Termux package/version without exposing private data;
- verify RUN_COMMAND permission state;
- verify the external-app property as a boolean only;
- verify whether result PendingIntent is supported;
- run a harmless fixed read-only diagnostic command;
- verify bounded stdout/stderr and exit-code handling;
- close the Termux UI and repeat;
- test Termux process death separately;
- test JITI process death separately;
- test reboot separately;
- test force-stop separately and record expected non-recoverability.

No live device configuration was changed while documenting this contract.
