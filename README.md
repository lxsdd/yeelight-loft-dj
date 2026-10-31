# Yeelight Loft DJ

Android-first, local-first lighting controller for Yeelight 1S (`color4`) lights with room-aware scenes. **Development preview, not yet hardware-tested.**

## In this first PR

- Material 3 / Jetpack Compose dark UI, Home / Scenes / Devices.
- Automatic Yeelight SSDP-like discovery on local Wi-Fi; local IP fallback.
- Local TCP JSON control, protocol-based color flows with four presets: Aurora, Sunset, Neon, Fireplace.
- Assign lights to rooms; select the first light, the loft (living, dining, hobby), or all.
- Stop active color flows. No cloud credentials, analytics, or microphone permissions.

## Build and test

CI uses Android SDK 36, JDK 17, Gradle 8.13 and runs `gradle testDebugUnitTest assembleDebug`. Pull-request CI compiles and tests but deliberately does **not** publish ephemeral-signature APKs. Once two private GitHub Actions signing secrets are configured, trusted `main` or manually dispatched builds produce a consistently signed APK. Read [SIGNING.md](docs/SIGNING.md) before distributing an APK. The original 0.1.0 preview used temporary runner signatures and **must be uninstalled once** before adopting the stable identity.

## Hardware acceptance (open)

1. Pixel and color4 bulbs in the same non-guest Wi-Fi, LAN Control enabled.
2. Search devices, select **Test (1)** and run a slow **Aurora** flow, then stop.
3. Assign rooms to bulbs and check entire **Loft** (living, dining, hobby) selection.
4. Assess latency, missed devices, failed commands, persistence, and LED strip support separately.
5. Do not use rapid flashing near photosensitive people.

## Roadmap, not yet implemented

- Microphone music analysis, beat tracking, local audio capture (only with OS permission and source-app consent), opt-in mic fallback for Google Cast.
- Spatial ordering, scene composer, stable signing, optional Home integration.

Yeelight LAN messages are **unencrypted**; never expose device TCP ports to the internet. Never commit private IPs or device identifiers.

Docs: https://en.yeelight.com/developer/ and https://developer.android.com/media/platform/av-capture
