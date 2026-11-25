# GT release signing setup

This document describes how to configure the dedicated Buddy GT release-signing check.
The private keystore must never be committed to Git, uploaded as a workflow artifact, attached to an issue, or pasted into ChatGPT.

## 1. Create a long-lived release keystore

Generate the key on a trusted device with a current JDK. Keep the resulting file in a private, backed-up location. Do not use a temporary CI-generated key: every future update of this Android package must use the same signing identity.

Example (PKCS#12, RSA 4096, SHA-256, alias `buddy`):

```sh
keytool -genkeypair -v \
  -storetype PKCS12 \
  -keystore buddy-gt-release.p12 \
  -alias buddy \
  -keyalg RSA -keysize 4096 -sigalg SHA256withRSA \
  -validity 10000
```

Choose strong, unique passwords. If a separate key password is used, record it securely.

## 2. Record the public certificate fingerprint

Run:

```sh
keytool -list -v -keystore buddy-gt-release.p12 -alias buddy
```

Copy only the `SHA256` certificate fingerprint. It is public certificate metadata, not the private key.

## 3. Configure GitHub Actions secrets

In the `odis78/Buddy-MyAI` repository:

Settings → Secrets and variables → Actions → New repository secret.

Create these secrets:

| Secret | Value |
|---|---|
| `BUDDY_KEYSTORE_BASE64` | Base64 encoding of the complete PKCS#12 keystore file, with no line breaks |
| `BUDDY_KEYSTORE_PASSWORD` | Keystore password |
| `BUDDY_CERT_SHA256` | Public SHA-256 certificate fingerprint; colons are optional |
| `BUDDY_KEY_ALIAS` | `buddy` (optional; this is the default) |
| `BUDDY_KEY_PASSWORD` | Key password (optional only when identical to the keystore password) |

For example, produce a single-line Base64 value locally:

```sh
base64 buddy-gt-release.p12 | tr -d '\n'
```

Copy that output directly into the GitHub secret field. Never print it into a shared terminal log or paste it into chat.

## 4. Run the guarded GT release workflow

After all required secrets are configured:

1. Open GitHub Actions → **Build**.
2. Choose **Run workflow**.
3. Select branch `experiment/tunnel-client-v0.0.21-oauth-20261125`.
4. Start the workflow.

For this branch's manual run, the workflow fails closed unless the keystore, password and expected fingerprint are present. It verifies the certificate in the keystore before building, then verifies the signed APK and compares its certificate fingerprint with `BUDDY_CERT_SHA256`.

The workflow does not print or upload the keystore. The APK artifact is signed and can be inspected independently.

## 5. Preserve the signing identity

- Keep at least two secure backups of the keystore and passwords, separately protected.
- Never regenerate the key for an update to the same package ID.
- Do not install the resulting APK until the separate physical-test approval and checklist are complete.
- A successful CI signature check is not proof of correct runtime behavior or ChatGPT MCP connectivity.
