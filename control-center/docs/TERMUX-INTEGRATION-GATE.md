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

The RUN_COMMAND integration is therefore a future explicit opt-in. The supported Java path uses `startService()` with action `com.termux.RUN_COMMAND`, absolute executable path, argv array, working directory, background flag, and a one-shot mutable PendingIntent for results. Separate stdout, stderr, and exit code are returned for background commands; Termux internal errors are returned separately. Result callbacks require Termux `>= 0.109`; without a PendingIntent, the caller cannot receive command results. Termux truncates combined returned stdout/stderr to about 100 KB, so JITI must impose a much smaller per-operation cap (for example 16 KB) before display or persistence.

JITI must use Java/PendingIntent rather than `am startservice`, because the latter cannot return results. Draw-over-apps permission is not required for background commands; it is only relevant to foreground terminal sessions. Battery exemptions may be needed on some OEM devices but must remain a separate, user-approved step.

Until these requirements are verified on the target phone, all command contracts remain disabled.

## Android 11+ package visibility

Because the application targets SDK 35, package discovery must declare the Termux package in a `<queries>` element before using PackageManager to inspect its installation. A package visibility result is not proof that Termux can accept commands.

## Lifecycle constraints

The upstream documentation notes that background activity-start restrictions can prevent a terminal session from being brought to the foreground automatically. Background command execution has different behavior. Draw-over-apps permission may affect foreground terminal sessions, but must not be requested unless a tested feature actually requires it.

Battery optimization exemptions may be necessary on some devices, but JITI must not change them automatically. Infinix-specific behavior requires on-device measurement.

## RUN_COMMAND protocol constraints

- Request `com.termux.permission.RUN_COMMAND` in the manifest, but never treat declaration as permission granted.
- Check the Termux package/version before invoking the service. Result callbacks are unavailable on Termux versions older than 0.109.
- Use the official service component `com.termux.app.RunCommandService` and action `com.termux.RUN_COMMAND` only after verifying the installed package matches.
- Pass an absolute executable path and a separate argument array. Do not assemble a shell command string from user/model input.
- Use background execution for non-interactive probes; use a PendingIntent to receive result bundle, exit code, and internal Termux errors.
- Bound output well below Termux's 100 KB transport limit and redact before any persistence.
- The Termux docs explicitly warn that the combination of external-apps enabled and RUN_COMMAND permission grants command execution in Termux context; do not expose a generic command console.

Official contract: https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent

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
