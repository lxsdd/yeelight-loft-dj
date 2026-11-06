# Loft DJ → private Classic toolchain reuse

The existing public product repo `lxsdd/yeelight-loft-dj` at source
`integration/rc17-unified-loft-20261106` provides validated Android CI:
Temurin Java 17, `android-actions/setup-android@v4`, `gradle/actions/setup-gradle@v6`
with Gradle 8.13, Android Gradle Plugin 8.13.2, Kotlin 2.2.20 and SDK 36 (verified on **Loft DJ only**). A SHA-fenced, **not yet CI-qualified upgrade** to Gradle 9.8.2 / Kotlin 2.4.21 is staged here; AGP 9.5.1 is staged separately as a patch for the public Android source after owner-routed CI.
This Classic code is upgraded against the **same existing hosted build environment**, with pinned new versions and no additional runner infrastructure. The upgrade remains a **candidate**, not an Android CI PASS.

- `../private_gradle_bridge` uses Kotlin Gradle plugin 2.4.21 to build DJ Java/Kotlin
  classes against `android-36/android.jar` and stages compiled JAR + Kotlin runtime.
- `../bridge/build_bridge.py` uses existing Gradle if available, then D8 from SDK
  build-tools 36.0.0 (or an available compatible version) to produce the sidecar
  DEX. It compares class descriptors against the *real* original Classic DEX;
  Kotlin runtime classes already owned by Classic are removed from a private
  copy of the runtime jars. Other collisions are rejected.
- `../build_private_unsigned.py` can use a locally supplied, SHA-256-checked
  **Apktool 3.0.3** JAR via `CLASSIC_APKTOOL_JAR`. Pinned digest:
  `dbf930b076c6b9be08d57c449cacefc3bdd6b71ebd59b3066fc0e1f5b14f9423`
  (upstream release). This is the only extra prerequisite beyond Java/SDK/Gradle.
- `build_with_loft_toolchain.sh` is the **private full-build entrypoint** on a
  trusted runner where owner-supplied original APK bytes are available **locally**.
  It rejects public GitHub workflows and does not create signed APKs. An
  unsigned APK is a structural diagnostic, *not installable or field approved*.
- `source_ci_example.yml` is **not active**. If a separate private source repo
  is later authorized, it runs source-only compilation on the same hosted CI
  toolchain with zero private OEM bytes and zero uploaded artifacts. GitHub's
  public Loft-DJ runner cannot access user-private chat uploads and must **never**
  receive or publish those OEM inputs.

This package remains private, uncommitted, unmerged. No CI run of the new Classic
code is claimed here. Source regression tests and exact APK static inspection are
not substitutes for an actual Android-36 Gradle/D8/Apktool build and GUI tests.

Apktool 3.x is a major upgrade: its CLI and resource roundtrip must be verified against the owner APK before treating the private fork as build-qualified. The old pinned 2.11.1 path remains available in the earlier unchanged archive for comparison/rollback. No runtime component embedded in the manufacturer APK was upgraded.
