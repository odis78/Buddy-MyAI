# JITI version checkpoints

Recorded: 2026-11-24

This file is a recovery index. The two checkpoint branches listed below are immutable starting points for future work. Do not develop directly on either checkpoint branch. Create a new development branch from the selected checkpoint.

## 2.3.19-JITI (VersionCode 216)

- Repository: `odis78/Buddy-MyAI`
- Checkpoint branch: `checkpoint/buddy-myai-2.3.19-JITI-2026-11-24`
- Exact commit: `d621393e51aede80691718b5f0093d4a2af72993`
- Candidate branch: `candidate/buddy-2.3.19-JITI-clean-20261124`
- Candidate PR: [#14](https://github.com/odis78/Buddy-MyAI/pull/14) (draft, not merged)
- Successful CI run: [45383945244](https://github.com/odis78/Buddy-MyAI/actions/runs/45383945244)
- Test APK: `app.buddy.myai.test219`, version name `2.3.19-JITI-test`, version code `216`
- Artifact ZIP SHA-256: `174c6b10b7720fcda57a41667e198f3e2ef58dd57707d10e04845f27cb08b05b`
- Test APK SHA-256: `8c74bc2ee999ce71c0350bbe415b5864b68877560e2ba8f37ba6f368804aa0d3`

### Changes in 2.3.19-JITI

Only these source files differ from the original 2.3.19 baseline:
1. `app/src/main/java/app/buddy/assistant/SetupActivity.java`
2. `app/src/main/java/app/buddy/assistant/McpServer.java`

SetupActivity hardening:
- Restricts token handling and permissions; validates token format; rejects symlink paths.
- Removes only Buddy-managed shell marker blocks and exact legacy Buddy token exports; preserves unrelated user shell configuration.
- Stops safely on malformed/incomplete managed blocks rather than rewriting the shell file.
- Fixes Claude Code JSON argument passing.
- Uses Codex bearer-token environment variable configuration.
- Configures OpenCode remote MCP with file-based token substitution and disables OAuth for this server.
- Configures Antigravity by safely merging JSON config with validation and atomic replacement.
- Keeps Grok token expansion literal in its config command.

McpServer hardening:
- Closes sockets reliably during bind/stop races.
- Closes local and Tailscale listeners independently.
- Closes accepted sockets if executor shutdown races with dispatch.
- Stops the Tailscale listener promptly during shutdown.

### 2.3.19 validation limits

- CI passed, including helper tests, native libraries, release/F-Droid build tasks, APK verification and reproducibility checks.
- Release signing secrets were unavailable. Release APK outputs are unsigned; the test APK is debug-signed.
- No real-device install/stress test or live MCP-client connection test was performed.
- Do not treat this test APK as a production update package.

## 2.3.17-JITI.1 (VersionCode 215)

- Repository: `odis78/Buddy-MyAI`
- Checkpoint branch: `checkpoint/buddy-myai-2.3.17-JITI.1-2026-11-21`
- Exact commit: `ef9fea50f86865f97cf82b45f6bc2c6e84f174e1`
- Version source: `BuildInfo.java` declares `2.3.17-JITI.1` and version code `215`.
- This checkpoint is retained as a separate historical recovery/build baseline. Do not overwrite it.

## Rules for future development and Android updates

1. Keep both checkpoint branches unchanged. For new work, branch from the 2.3.19 checkpoint, for example `development/buddy-myai-2.3.20-JITI`.
2. Preserve the package/application ID `app.buddy.myai` for the GitHub release flavor.
3. Increase `versionCode` for every new installable release. The next planned release after 216 is 2.3.20-JITI / version code 217, unless a different version is explicitly chosen.
4. Preserve the existing release signing key. Android only permits an in-place update when the new APK has the same application ID and is signed by the same compatible signing certificate as the installed app. Never uninstall first as an update workaround.
5. A debug test build has a different application ID suffix (`app.buddy.myai.test219`) and is not the production package. Its debug signature is not a substitute for the release signing key.
6. The 2.3.19 CI release outputs were unsigned because the repository's release-signing secrets were unavailable. Before testing an in-place update over an installed production build, configure/use the original release key and verify the signing certificate matches the installed app.
7. Do not generate a replacement signing key for an existing installation. Losing the original key can prevent normal updates unless a supported signing-key upgrade path already exists.
8. Before any device installation, ask for explicit approval. No installation, merge, or publication is authorized by this checkpoint record.

## Resume procedure

- To continue from 2.3.19, create a new branch from commit `d621393e51aede80691718b5f0093d4a2af72993`, not from a moving development branch.
- First inspect the working tree, version metadata, package ID, signing configuration, and CI workflow.
- Build and test incrementally. Keep the original 2.3.19 and 2.3.17 checkpoint branches untouched.
- For an Android update test, verify package ID, monotonically increasing version code, and signing-certificate continuity before attempting installation.
