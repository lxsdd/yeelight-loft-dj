# Classic stock launcher resolution — regression contract

## Protected-run evidence (11 November 2026)
Owner-dispatched offline stock-Classic workflow [#43034768219](https://github.com/lxsdd/yeelight-loft-dj/actions/runs/43034768219), on `main@e0004971ca14f27aa59a724da8620bf18681122e`, passed owner policy, SDK catalog, pinned private OEM-vault download and Android 17 emulator boot/isolation. The fixed-code result was `STOCK_ORIGINAL_ANDROID17_EMULATOR_BLOCKED_LAUNCHER_DISCOVERY_FAILED`. Cleanup succeeded, no artifacts. The original OEM APK was **not installed**; the actual launcher class and any failure after installation remain unverified.

The old `aapt dump badging` gate simultaneously required one exact `launchable-activity` and a prescribed class prefix. This rejected otherwise valid alias/no-badging cases but does **not** prove that this is the cause for the current OEM input. No vendor class names or private badging output may be written to GitHub.

## Functional correction / acceptance
- Continue to verify the pinned, unmodified original's ZIP identity and OEM signature before any private guest install.
- Verify the **exact original package** using private `aapt` badging, independent of optional launchable-activity lines; fail on mismatch.
- On the already isolated, disposable, offline Android 17 guest ONLY, install the pinned OEM once, then read only its MAIN/LAUNCHER candidates via `cmd package query-activities --components` and chosen candidate via `cmd package resolve-activity --components`.
- Require a single explicit resolved component in the original package and valid, unique, package-restricted query candidates; preserve valid activity-alias components. Reject no result, ambiguous/duplicate, malformed, foreign, system-resolver and inconsistent answers before any `am start`.
- Keep raw `aapt`/package-manager outputs inside ephemeral protected files; only fixed diagnostic codes may reach GitHub logs. The selected class is passed privately to the existing probe, never printed by the public workflow.
- Run synthetic positive and negative regression tests for aliases, absent badging launchable field, multiple candidates with explicit resolution, malformed/foreign/injection input, privacy and offline ordering; qualify exact head with `source-verify` and `verify-and-build`.
- No OEM content in public PR CI, no protected workflow dispatch from this branch, no signing, release, physical device/network, Color4 source, native Classic PR86 merge, or automatic private-test acceptance.

This contract documents an isolated owner-approved source-only successor. It is **not** evidence that the original Classic APK runs or the native Loft DJ integration works.
