# Yeelight Loft DJ

Android-first, local-first Yeelight controller (Kotlin, Jetpack Compose, Material 3).
The long-term goal is **everyday local lighting control and DJ/music-reactive spatial effects**.
Binding requirements: [PRODUCT_SPEC_DE.md](docs/PRODUCT_SPEC_DE.md).
Current takeover/acceptance status: [STATUS_2026-11-01_DE.md](docs/STATUS_2026-11-01_DE.md).

## Current verified state – 1 November 2026

- **v0.1.6 / build 7 on protected `main`**. Unit tests and Android build passed;
  its updateable stable-signed APK was produced in
  [trusted main CI](https://github.com/lxsdd/yeelight-loft-dj/actions/runs/41251999328).
  PR builds intentionally do not receive private signing secrets.
- **v0.1.7 / build 8 proposed in [PR #16](https://github.com/lxsdd/yeelight-loft-dj/pull/16)**:
  an easily accessible stop/recovery action for globally disabled
  `Identifizieren` controls, robust recovery after process death, regression
  tests. Not yet merged, installed or hardware-approved.
- Normal discovery combines Yeelight UDP/SSDP-like multicast and LAN /24 TCP
  fallback; saved devices, aliases, room assignment, physical drag-and-drop
  lamp order and direct device identification are implemented.
- Manual status/power/brightness/RGB/temperature controls, light scenes,
  configurable native multi-bulb chase, JSON scene import/export and optional
  original-state recovery are implemented.
- **Music MVP is implemented in v0.1.6**: explicit foreground microphone
  capture, measured six-band FFT and bass detection, conservative BPM and
  separate opt-in, rate-limited music-light mode. No automatic microphone
  capture or raw audio retention.
- **Real hardware evidence remains partial**: a color flow worked on Test (1)
  on 31 October 2026; after further tests some lights were reported staying
  at a static color. Discovery reported 5 multicast vs 15 direct TCP
  responders; the cause is not established.
- White/unknown-color lamp identification is capability-aware. Mi Desk Lamp
  1S has a separate, read-only Yeelight/MiIO connectivity probe, but its
  actual LAN control compatibility is **not established**.

## Not yet implemented or approved

The interactive 2D/3D floorplan editor (walls, doors, x/y/z coordinates,
spatial graph), internal Android playback capture (subject to source app
permission), own local audio player, Cast integrations beyond a microphone
fallback, complete multi-device lighting hardware acceptance, rich everyday
schedules and full Yeelight-app replacement are **not complete**.
The seven Dependabot PRs #2–#8 remain separately open for compatibility review.

## Device validation

1. Use the same WLAN and enable Yeelight LAN control; do not expose 55443
   on the internet. The Yeelight and Google Home apps are kept installed.
2. Search all lamps and compare direct TCP with multicast discovery.
3. Validate `Test (1)`, then multi-lamp chase, real movement, Stop and
   optional original-state restoration.
4. Identify one lamp at a time and drag it to the intended physical light path.
   If identification is disabled in v0.1.6, first use
   **Szenen → Alle laufenden Lichtshows stoppen**. PR #16 makes this
   stop/recovery action visible on all tabs with a clear explanation.
5. Test microphone levels before separately enabling actual music lighting.
6. Check the documented hardware scenarios in
   [INTEGRATION_016_DE.md](docs/INTEGRATION_016_DE.md) and
   [IDENTIFICATION_RECOVERY_017_DE.md](docs/IDENTIFICATION_RECOVERY_017_DE.md).

## Safety, contribution and signing

- Entirely local Yeelight LAN control; never open unencrypted LAN port 55443
  to the internet.
- No household IP lists, account data, private floor plans or signing keys
  in public source code, logs or exports.
- Stable signing keys are supplied only by trusted main GitHub Actions;
  see [SIGNING.md](docs/SIGNING.md).
- Develop in reviewable PRs; protected `main` and releases require explicit
  authorization. A green CI run alone is **not** a physical hardware acceptance.
