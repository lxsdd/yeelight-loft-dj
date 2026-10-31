# Yeelight Loft DJ

Android-first, local-first Yeelight controller (Kotlin, Jetpack Compose, Material 3).
The long-term goal is **everyday local lighting control and DJ/music-reactive spatial effects**. See [binding product requirements](docs/PRODUCT_SPEC_DE.md), including non-linear floor plans with walls, doors, light paths, and independent audio sources.

## Current verified state

- **0.1.1 on `main`**: trusted CI finished successfully, permanent Android signing certificate verified, signed APK stored as GitHub Actions artifact. [Successful signed build](https://github.com/lxsdd/yeelight-loft-dj/actions/runs/41240803680).
- Local SSDP multicast and Wi-Fi /24 TCP discovery are combined in normal search, with saved known devices, aliases, diagnostics, room labels, and four color flows.
- Hardware feedback (31 October 2026): color flow on **Test (1)** works. Follow-up diagnostic screenshot: **5 via multicast, 15 via direct TCP, 5 via both, 47 total UDP replies, 0 invalid**. The cause of missing multicast announcements remains **unverified**; direct TCP gives the working fallback.
- The manufacturer app and Google Home remain installed; no private IPs or floor plans in this repository.

## Proposed next milestone (draft PR)

**v0.1.2 / versionCode 3** adds optional manual controls to individual device cards: real status query (not a simulated live state), power, brightness, RGB and white temperature, safe deliberate bulb identification, and target-group on/off actions.

The PR must pass Android CI and be reviewed before merge. PR builds deliberately cannot access private signing secrets and do not supply updateable signed APKs. The next stable update is produced **only by trusted `main` CI after an approved merge**.

## Device validation

1. Ensure phone and bulbs are on the same local WLAN and Yeelight LAN control is enabled.
2. Use **Alle Lampen suchen**, compare multicast and direct totals; saved entries may also include offline bulbs.
3. Try **Test (1)** first. Verify colored scenes, stop, and no unwanted change to unrelated bulbs.
4. For proposed manual controls: refresh a device's actual power/brightness; toggle it; adjust brightness once; set RGB and temperature only if the device supports them.
5. On a known lamp, verify identification preserves its former static color/brightness and reverts an initially off bulb to off.
6. After individual acceptance, verify targeted room/loft actions and reported errors.

## Design and safety

- Strictly LAN-only; Yeelight TCP protocol is unencrypted, never open port 55443 to the internet.
- Local room/alias data only; no network IP lists, signing keys or private floor plans in Git.
- UI permissions and device actions remain explicit; no covert microphone capture.
- Private signing keystore lives in GitHub Actions secrets and an offline owner backup; see [SIGNING.md](docs/SIGNING.md).
- Feature development uses PRs; protected `main`, CI and stable signing should remain separate from experimental changes.
- Audio capture (microphone, supported Android playback capture, local player and Cast mic fallback), spatial editor and rich everyday scenes are **planned, not yet implemented**.

Current project requirements: [PRODUCT_SPEC_DE.md](docs/PRODUCT_SPEC_DE.md) ·
Network diagnostics: [NETWORK_DISCOVERY_DIAGNOSTICS_DE.md](docs/NETWORK_DISCOVERY_DIAGNOSTICS_DE.md)
