# Stable Android signing: Yeelight Loft DJ

This public repo intentionally contains **no private keys or passwords**.

## Why this is needed

The first 0.1.0 APKs were signed by separate temporary GitHub Actions debug keys. Android refuses an update signed by a different key. The old debug key cannot be reconstructed from the APK.

A **one-time uninstall** is necessary to migrate from an ephemeral v0.1.0 build to the permanent signing identity. After that, preserve the exact signing key and use strictly increasing `versionCode` values for updates. Never use different keys for successive installations of this app.

## One-time owner setup

1. Obtain the private signing backup created for this project and **store it safely offline**. Do not commit it or paste its contents into issues, PRs, logs or public discussions.
2. Open https://github.com/lxsdd/yeelight-loft-dj/settings/secrets/actions and create **two repository secrets**:
   - `YEELIGHT_SIGNING_STORE_B64`: complete single-line base64 text of the PKCS12 signing keystore.
   - `YEELIGHT_SIGNING_PASSWORD`: password for the same keystore and its `loft-dj-app` alias.
3. Do not share the secret values in a chat. Keep an offline backup for future devices or GitHub migrations.
4. Once the current draft PR has passed CI and been explicitly approved/merged to `main`, the trusted `main` workflow builds with that private identity and uploads `yeelight-loft-dj-stable-signed` as an Actions artifact.
5. Install that APK after removing earlier ephemeral-signature previews. Future updates from the stable identity can be installed without uninstalling, provided `applicationId` remains the same and `versionCode` increases.

## Security

- PR workflows **never receive signing secrets** and never publish debug artifacts for installation.
- The workflow fails closed if signing secrets are missing for a trusted build. It never silently produces an update-incompatible temporary APK.
- Secret-bearing steps run only on trusted push-to-main and manual workflow dispatch, not pull requests.
- Do not distribute the signing keystore publicly or use this credential for other apps.
- The preview key is a project-maintained signing identity; carefully review app permissions and code before installing APKs.

## Verification

On a new stable artifact, confirm the signing certificate fingerprint matches the private backup documentation. Android's package installer also verifies update signatures, but it does not prove that an APK is trustworthy.
