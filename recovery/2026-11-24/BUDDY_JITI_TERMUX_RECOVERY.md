# Buddy + JITI + Termux Recovery Checkpoint

**Checkpoint date:** 2026-11-24  
**Checkpoint type:** operational recovery snapshot (documentation only; no runtime configuration or secrets copied)  
**Status:** Buddy Tunnel is running; authenticated MCP and one live phone tool call have been confirmed.

## 1. Executive status

The working control path has been restored:

`JITI Control Center → Termux launcher → Tunnel Client v0.0.19 → Buddy MCP`

The existing REA stack remains healthy alongside Buddy:

`REA-Termux-Bridge 0.1.3`  
`DroidMCP-Filesystem`  
`REA-Termux-Android`  
`REA MCP 6.5.0`

### Confirmed live results (2026-11-24)

- JITI notification first reported `MCP listener up. tunnel not ready`, then advanced to `Buddy MCP authenticated`.
- JITI reported **44 tools** discovered.
- Buddy Tunnel process was observed in Termux as PID **3411** at the time of the check. PIDs are temporary and must be re-detected after a restart.
- Buddy local health endpoint: `http://127.0.0.1:39073/healthz` returned `live`.
- Buddy local readiness endpoint: `http://127.0.0.1:39073/readyz` returned `ready`.
- A real Buddy tool call `device_status` completed successfully. Returned phone status: battery 92%, Wi-Fi (VPN), 29.5 GB free storage. This is an end-to-end MCP action, not merely a UI status message.
- The phone reported Infinix X678B / Android 14 in the prior diagnostics; ADB target was `emulator-5554`, product `X678B-OP`.
- Three helper tunnel managers were still healthy: PIDs 22438, 22559, 22793 at the diagnostic snapshot. These PIDs are also temporary.
- REA MCP self-check: healthy; adapter `production-stdio`; REA 6.5.0; protocol `2025-11-25`; 208/208 tools; 6/6 prompts; catalog digest `195e27df5d43ddd434d535b2167c930449ba1ffae47257325ecf02bc295cf400`; initialize and binary-session request flow succeeded; stderr empty.
- MyAI Android package `com.dmitry.myai.infinix` was installed and visible to ADB.

## 2. JITI Control Center build checkpoint

- Repository: `odis78/Buddy-MyAI`
- App directory: `control-center/`
- Android package: `app.jiti.controlcenter`
- Working branch: `project/jiti-control-center`
- Source commit: `e4db9f24ceed4ac68c4bc93216b78383f305e568`
- Successful GitHub Actions run: [#45359799143](https://github.com/odis78/Buddy-MyAI/actions/runs/45359799143)
- Workflow run number: 386
- Workflow: `.github/workflows/jiti-control-center.yml`
- All steps succeeded: embedded Termux script validation, JVM unit tests, debug APK assembly, artifact upload.
- Artifact: `jiti-control-center-debug`
- Artifact ID: `14903578137`
- Artifact size: 88,988 bytes
- Artifact SHA-256: `c5c48b8233c29449cbaa77856a772f01099cb55bb488cfde250a262f3a6c2f2d`
- Artifact expiry: 2027-02-22 08:51:37 UTC
- [Build and artifact page](https://github.com/odis78/Buddy-MyAI/actions/runs/45359799143)

### Relevant JITI fix

The launcher previously required Buddy auth material in `~/.config/termux-bridge/runtime.env`, although the existing Buddy token was stored separately at `~/.config/tunnel-client-buddy/buddy.token`. The launcher now supports a protected token-file fallback without printing the token. It checks that the token file is not a symlink, has restrictive permissions, is bounded in size, and contains a valid nonempty token. `CONTROL_PLANE_API_KEY` remains required from the protected runtime environment. Unit and embedded-script tests passed.

The launcher also refuses to treat a stale PID/status record as proof of a fresh successful launch. The current successful run confirms the new build can launch and authenticate in this environment.

## 3. Runtime layout (paths only; no secret values)

### Buddy Tunnel

- Config directory: `~/.config/tunnel-client-buddy/`
- YAML: `~/.config/tunnel-client-buddy/buddy-myai.yaml`
- Token file: `~/.config/tunnel-client-buddy/buddy.token`
- PID file: `~/.config/tunnel-client-buddy/buddy-myai.pid`
- Health URL file: `~/.config/tunnel-client-buddy/health.url`
- Launcher log: `~/.config/tunnel-client-buddy/launcher.log`
- Tunnel Client log: `~/.config/tunnel-client-buddy/tunnel-client.log`
- Health URL observed: `http://127.0.0.1:39073`
- Tunnel Client version: `0.0.19`
- Source checkout previously identified at `~/tunnel-client-android-build-v0.0.19/source`.

### Termux / REA

- Full-stack helper: `~/bin/full-stack`
- Report inbox: `~/rea-report-uploader/inbox`
- Expected report uploader: `~/bin/rea-report-upload` (must verify exact installed script and usage before invoking; a prior bare `rea-report-upload` call failed because it was not on PATH).
- REA project root: `~/rea`
- Local bridge version: 0.1.3, configured project root `~/rea`.
- Bridge execution policy: fixed allowlist; shell disabled; one fixed Android app launch. Installing/enabling the ChatGPT plugin does not itself connect to the local process.

### Secret-handling rules

Never include the contents of `buddy.token`, `runtime.env`, YAML auth fields, bearer tokens, API keys, environment values, or full secret-bearing command lines in reports or GitHub. Only record:
- file exists / absent;
- file mode and size;
- whether required key names exist (never values);
- validation result / reason code;
- process and endpoint status.

Known metadata from the pre-launch audit:
- `runtime.env`: mode 600, 194 bytes; required control-plane key name present.
- `buddy.token`: mode 600, 48 bytes; nonempty, one line, no Bearer prefix.
- `buddy-myai.yaml`: mode 600, 651 bytes; expected tunnel ID and environment references present.
These are historical metadata observations, not a substitute for rechecking current metadata.

## 4. Safe recovery sequence

Do not start by restarting everything. Preserve healthy services and recover only the failed component.

### A. Baseline first

In Termux:

```bash
bash "$HOME/bin/full-stack" status
```

Expected helper state:
- REA-Termux-Bridge 0.1.3: Health OK
- DroidMCP-Filesystem: Health OK
- REA-Termux-Android: Health OK
- REA MCP: healthy; expected/observed tools 208; prompts 6; digest matches the value in section 1.

Do not restart these helpers if they are already healthy.

### B. Check Buddy without changing anything

```bash
D="$HOME/.config/tunnel-client-buddy"
U="$(cat "$D/health.url" 2>/dev/null)"
echo "=== TUNNEL CLIENT PROCESSES ==="
ps -A | grep '[t]unnel-client' || true
echo "=== BUDDY HEALTH ==="
curl -sS --max-time 5 "$U/healthz"
echo
echo "=== BUDDY READY ==="
curl -sS --max-time 5 "$U/readyz"
echo
```

Expected Buddy responses: `live` and `ready`. `ps -A` is used because the Termux ps implementation rejected a previous GNU-style `-o PID,ARGS` format.

A stale PID file is not proof that the process is alive. Verify process existence and endpoint responses. Do not delete a PID file manually.

### C. Start only Buddy through JITI

If Buddy is not running and JITI preflight passes:
1. Open JITI Control Center.
2. Choose **Start Buddy Tunnel** once.
3. Review the confirmation: it should identify the existing Tunnel Client v0.0.19 and current Buddy YAML, and state that YAML/runtime.env will not be modified.
4. Confirm **Start**.
5. Wait for the state to advance from `MCP listener up. tunnel not ready` to `Buddy MCP authenticated`.
6. Verify `healthz=live`, `readyz=ready`, and make one harmless read-only Buddy tool call.
7. Do not press Start repeatedly while the first launch is still progressing.

If preflight fails, stop and preserve the error/report. Do not copy secrets between files or edit YAML/runtime.env as a workaround.

### D. Verify end-to-end MCP

A green UI badge is not enough. Require all three:
1. JITI reports `Buddy MCP authenticated` and tool count.
2. Local health and readiness endpoints return `live` / `ready`.
3. A harmless read-only Buddy tool call returns a real result (for example `device_status`).

### E. Stop behavior

Use JITI's **Stop** control only when intentionally stopping Buddy. The helper REA tunnels are separate processes. Do not stop them as part of routine Buddy recovery. The JITI monitor may hold a CPU wake lock while active; this keeps monitoring responsive but increases battery use. Stop monitoring only when intentionally ending the monitoring session, not while performing a stability check.

## 5. Timeline / incident history

1. **2026-11-22:** Buddy-MyAI 2.3.17-JITI.1 checkpoint already existed on protected branch `checkpoint/buddy-myai-2.3.17-JITI.1-2026-11-21`, commit `ef9fea50f86865f97cf82b45fbc2c6e84f174e1`. Earlier recovery note documents the separate Buddy phone-shell / background-mode setup. Do not overwrite that protected branch.
2. **2026-11-22:** Older Buddy Tunnel log contained control-plane `403 unsupported_country_region_territory` and dispatcher `502` errors including transport EOF / connection refused. These log entries are historical and do not establish the current cause or current status.
3. **2026-11-23:** REA and helper tunnel diagnostics were being captured. A previous report upload attempt using bare `rea-report-upload` failed with command-not-found; verify the absolute helper path and script usage before the next upload.
4. **2026-11-24, before JITI repair:** helper tunnels and REA were healthy; Buddy PID file pointed to stale PID 32221; Buddy port 39073 refused connections; old health response files were stale and were not treated as proof of service.
5. **2026-11-24:** JITI launcher token-source handling was fixed to use the protected separate `buddy.token` file as a fallback. Python/script validation and JVM tests passed; debug APK assembled and uploaded successfully.
6. **2026-11-24:** user removed the previous JITI app because Android would not install the new APK over it, then installed the new build and granted its requested permission.
7. **2026-11-24:** user confirmed launch in JITI. UI first showed `MCP listener up. tunnel not ready`, then `Buddy MCP authenticated`, `44 tools`.
8. **2026-11-24:** Termux confirmed Buddy PID 3411, `healthz=live`, `readyz=ready`. A real `device_status` call through Buddy succeeded. REA and the three helper tunnels remained healthy.

## 6. Known issues and items not to confuse

- **Historical 403:** `unsupported_country_region_territory` appeared in older logs. Current authenticated MCP proves the Buddy path works now; do not treat old 403 entries as a present outage without a fresh timestamped log.
- **Historical 502 / connection refused:** these occurred while Buddy/local MCP was unavailable before the successful launch. Recheck only if a fresh failure occurs.
- **JADX:** previous status reported HTTP unavailable and PID-file validation failure. This is a separate analysis service and did not prevent REA or Buddy from working.
- **Termux ps syntax:** `ps -A -o PID,ARGS` produced an unsupported-format error. Use plain `ps -A` for quick inspection.
- **Report uploader:** bare `rea-report-upload` was not found on PATH. Do not claim a diagnostic report was uploaded until the absolute script path, its supported invocation, upload exit status, repository path, and SHA-256 are confirmed.
- **Phone-shell tool access:** one recent attempt was blocked because Linux tools were not enabled for that chat. This does not mean the Buddy Tunnel itself is down. Enable the chat's Linux tools only when a phone-shell diagnostic is actually needed; preserve per-command approval.

## 7. Boundaries / do not do during recovery

- Never expose or copy tokens, API keys, passwords, or secret-bearing environment values.
- Do not modify `~/rea`, Tunnel Client configuration, REA configuration, Termux runtime.env, Buddy YAML, token files, system settings, or other apps as part of routine recovery.
- Do not overwrite protected Buddy branch `checkpoint/buddy-myai-2.3.17-JITI.1-2026-11-21`.
- Do not change Control APK 102.
- Do not delete user data, reset, clean, or run broad cleanup.
- Do not restart healthy helper tunnels.
- Diagnose before changing anything; back up and define rollback before any future approved mutation.
- Keep user approval enabled for actions that change the phone or send external content.

## 8. Next work after this checkpoint

1. Capture a fresh timestamped Termux diagnostic report in `~/rea-report-uploader/inbox`, with secret-safe output, upload through the verified absolute `~/bin/rea-report-upload` helper, and record upload result plus SHA-256.
2. Store the matching report in `odis78/REA-Termux-Reports` and link it from the recovery notes.
3. Create a local archive containing this runbook, secret-free diagnostic output, and relevant non-secret logs only. Do not archive raw YAML, runtime.env, token files, or unrestricted raw logs.
4. Perform a short stability check without restarting anything: recheck Buddy health/readiness and one harmless MCP call after an observation interval.
5. Separately diagnose the JADX service. It is not a prerequisite for Buddy operation.
6. Continue JITI Control Center work only in `control-center/` and a feature branch; do not modify protected Buddy app source or the healthy runtime stack without explicit approval.

---

**Checkpoint integrity note:** this document is a reproducible operational runbook and source/build reference. It intentionally does not contain a raw Termux snapshot or a binary archive. Those local artifacts and the automatic report upload remain pending until phone-shell/Linux-tools access is available in the current chat and the uploader's exact interface has been inspected.


## 9. Component lineage and protected references

These are reference points from the preceding project checkpoints. They are not claims that a fresh local Git status was collected today.

### Buddy-MyAI Android app
- Repository: `odis78/Buddy-MyAI`.
- Previously protected Buddy app branch: `checkpoint/buddy-myai-2.3.17-JITI.1-2026-11-21`.
- Protected branch commit: `ef9fea50f86865f97cf82b45fbc2c6e84f174e1`.
- App version in that checkpoint: `2.3.17-JITI.1`, versionCode 215.
- Keep that protected branch unchanged. The JITI controller work lives under `control-center/` and is isolated in the dedicated checkpoint branch created for this recovery.

### Tunnel Client
- Upstream repository: `openai/tunnel-client`.
- Local checkout previously identified at `~/tunnel-client-android-build-v0.0.19/source`.
- Previously recorded tag: `v0.0.19`.
- Previously recorded source commit: `01d67bb4da0d8359b808b4761f8db171ab682cd7`.
- Runtime binary observed in the live process: `~/tunnel-client-android-build-v0.0.19/bin/tunnel-client-android-arm64`.
- The current runtime was launched from the existing installation; this checkpoint did not rebuild or replace Tunnel Client.

### REA
- Project root: `~/rea`.
- Previously recorded branch: `experiment/termux-jadx`.
- Previously recorded commit: `d72d856f5`.
- Previously recorded REA package version: `6.5.0`.
- The live status check in section 1 independently confirmed REA 6.5.0 and its full 208-tool / 6-prompt catalog. A fresh `git status` of `~/rea` has not been collected for this checkpoint.
- Do not update, reset, clean, or otherwise modify `~/rea` as part of this recovery.

### Device / app boundaries
- Device: Infinix X678B, Android 14; ADB serial shown in the diagnostic was `emulator-5554`.
- MyAI package: `com.dmitry.myai.infinix`.
- Do not modify Control APK 102 or unrelated Android apps while recovering Buddy.
- Do not infer the currently installed JITI versionCode from the build artifact; the package version was not independently queried after installation.


## 10. External diagnostic report archive

A redacted diagnostic report reconstructed from the actual output pasted in this conversation was also committed to the dedicated report repository:

- Repository: `odis78/REA-Termux-Reports`
- File: `recovery/2026-11-24/BUDDY_JITI_REA_DIAGNOSTIC_2026-11-24.md`
- Commit: `fd8493b4170f1dc5bf9817e32cde423ee1c6dcea`
- URL: https://github.com/odis78/REA-Termux-Reports/blob/main/recovery/2026-11-24/BUDDY_JITI_REA_DIAGNOSTIC_2026-11-24.md
- The report was fetched back from GitHub and checked for obvious bearer/API-key patterns; none were present.

**Important provenance boundary:** this is a GitHub-side report assembled from conversation-visible diagnostic output. It was not created by the Termux uploader, was not written to `~/rea-report-uploader/inbox`, and does not prove that `~/bin/rea-report-upload` executed. The phone-shell and REA bridge sessions terminated during the attempt to inspect the local uploader. The local inbox copy, local archive, and uploader invocation remain pending. Do not mark those steps complete until verified directly on the device.
