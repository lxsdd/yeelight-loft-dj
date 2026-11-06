# Yeelight Loft DJ & Classic – kontrollierte Aktualisierung der Buildwerkzeuge

Stand: **06.11.2026**, nur private Quelltests. **Keine GitHub-Commits, kein neuer CI-Run, kein signierter oder installierbarer Classic-Fork.**

## 1. Belegter Ausgangsstand

Die aktuelle öffentliche RC17-Quelle `lxsdd/yeelight-loft-dj` (HEAD `93a58be2c530f9faa7b74688692a4796b2a6dd71`, Draft #69) hat **AGP 8.13.2, Kotlin 2.2.20 und Gradle 8.13**. Der vorherige CI-Erfolg bleibt gültig **ausschließlich für diesen alten Quellstand**. Die GitHub-Buildkonfiguration ist nicht durch dieses Archiv geändert worden.

## 2. Stufenweises Update ohne Freigabeumgehung

| Werkzeug | Bisher | Aktualisierter Kandidat | Anmerkung |
| --- | --- | --- | --- |
| Gradle | 8.13 | **9.8.2** | Offizielles aktuelles stabiles Release; Sicherheitskorrekturen in 9.8.x |
| Android Gradle Plugin | 8.13.2 | **9.5.1** | AGP 9: **Built-in Kotlin** statt separat angewandtem `org.jetbrains.kotlin.android` |
| Kotlin/Compose | 2.2.20 | **2.4.21** | Ein gemeinsamer, fester Compiler-Stand |
| JDK | 17 | **17** | Bewusst unverändert – unterstützt von AGP 9.5 |
| Android SDK/Build Tools | 36 / 36.0.0 | **36 / 36.0.0** | Bewusst keine Erhöhung von App `targetSdk`, weil Verhaltensänderungen möglich sind |
| checkout | v4 | **v7** | Neue Node-Runtime; für GitHub-hosted Runner vorgesehen |
| setup-java | v4 | **v6** | Tempurin 17 bleibt |
| setup-gradle | v6 | **v6** | Aktuelles Major beibehalten |
| setup-android | v4 | **v4** | Aktuelles Major beibehalten |
| upload-artifact | v4 | **v7** | **Releasepfad muss gesondert als Trusted-Canary qualifiziert werden** |
| download-artifact | v4 | **v8** | Striktere Integritätsprüfung; Trusted-Canary Pflicht |
| Apktool | 2.11.1 | **3.0.3** | Major-Update, SHA-256-gepinnt; Original-APK-Roundtrip **noch nicht** mit 3.0.3 verifiziert |

Prüfsummen für späteren Tool-Download: Gradle 9.8.2 `-bin.zip` SHA-256 `21bd5da2de49b642904077f38c0efdc7778e92b3ed0889392dca61b97e389607`; Apktool-3.0.3-JAR SHA-256 `dbf930b076c6b9be08d57c449cacefc3bdd6b71ebd59b3066fc0e1f5b14f9423`. Die JAR-Bytes sind **nicht** Bestandteil des ZIP. Keine Downloads ohne überprüften Hash ausführen.

## 3. Eigene Änderungen dieses Pakets

`private_gradle_bridge` verwendet Kotlin **2.4.21**. Die private, ausschließlich unsignierte Build-Strecke fordert Gradle **9.8.2** und Apktool **3.0.3** mit Pin, ohne OEM-Dateien in ein öffentliches CI zu exportieren. `private_ci/source_ci_example.yml` verwendet die neuen Checkout-/Java-Actions sowie Gradle 9.8.2, führt source-only Python-Regressionen aus, ohne Original-APK-Zugriff.

`toolchain_upgrade/prepare_loft_upgrade.py` erstellt eine **SHA-gebundene, reine Quellcode-Diff-Vorlage** für **genau die drei geprüften RC17-Dateiblobs** (`build.gradle.kts`, `app/build.gradle.kts` und `.github/workflows/android.yml`). Es entfernt den unter AGP 9 inkompatiblen separaten Android-Kotlin-Plugin-Eintrag, ersetzt die veralteten `kotlinOptions` und lässt Signing-/Release-Gates identisch. Änderungen entstehen **nur in einem separaten neuen Ausgabeordner**. Stimmt ein Git-Blob-SHA nicht mehr, verweigert das Tool jede Ausgabe. Es schreibt **nicht** ins Repository.

Beispiel auf einer **lokalen Quellkopie des verifizierten RC17-Commits**, **ohne** Hersteller-APK:

```sh
python3 toolchain_upgrade/prepare_loft_upgrade.py \
  --repo /pfad/zur/oeffentlichen/loft-dj-quellkopie \
  --output /privater/pfad/loft_toolchain_upgrade_vorschlag
```

Der erzeugte Patch muss in **eigenem legitimen Branch/PR** mit dem privaten Controller-CAS-Ownership-Verfahren angewendet werden. Vor jeder Änderung: GitHub-HEAD, PR/Claim und Source-Blob erneut prüfen. Keine fremden Branches übernehmen oder den PR #69 ohne passende Route überschreiben.

## 4. Test- und Migrationsgates

1. **PR-Quell-CI für neue Toolchain:** GitHub-hosted Ubuntu; `gradle --version` muss exakt 9.8.2 zeigen. `testDebugUnitTest` und `assembleDebug` auf **dem neuen Commit** ausführen, mit denselben bestehenden Testfällen und ohne Zugang zu Signierschlüsseln; ggf. Build-Fehler direkt korrigieren und wiederholen.
2. **Compile-/Runtime-Kompatibilität:** Gradle 9/AGP 9 Migration einschließlich Compose-Compiler, Kotlin 2.4 und D8/R8 validieren. Keine Behauptung über erfolgreichen Build nur durch die 47 Python-/7 JVM-Tests.
3. **Release-Gate:** Weil Artifact-Actions aktualisiert sind, einen **separat genehmigten nichtöffentlichen Testlauf** der Artefakt-Namens-/Pfad-/SHA-Kette durchführen. PR-CI überspringt diesen Pfad und kann ihn nicht qualifizieren. Keine Signierung, Releases, Tags oder Main-Merges ohne ausdrücklich zutreffende Freigabe.
4. **Classic-APK-Build:** `CLASSIC_ORIGINAL_APK` nur auf einem privaten Host mit kontrolliertem Dateizugriff. Apktool 3.0.3 JAR erst nach Hashprüfung verwenden; Original-APK SHA-Pin beibehalten; DEX-/Provider-/Ressourcen-/Android-Smoke-Prüfungen; Login/BLE/Cloud/Music nicht als gegeben ansehen.
5. **Rollback:** Vorheriges Paket `Yeelight_Classic_Loft_DJ_Reused_Android_CI_20261106.zip` unverändert als geprüfte Quellbasis behalten. Public-Repo `main` und bestehende Draft-PRs bleiben unberührt. Bei einem neuen CI- oder Gerätefehler **kein** stilles Upgrade in die Produktlinie übernehmen.

## 5. Yeelight Classic: Altbestand nicht blind ersetzen

Das originale `com.yeelight.cherry_3.5.4.apk` ist zur Quelleninventur schreibgeschützt und SHA-256-identisch geblieben: `abdcffbcf118e5d5c48088a478c8ea49f4c3fa911db1f1bc5013fed1d61fb7f2`.

Die Inventur weist **18.880 DEX-Klassen**, darunter AndroidX, historische Android-Support-Klassen, Kotlin, Gson, OkHttp, Retrofit, Firebase und Google-Play-Dienste, sowie **19 native Bibliotheksdateien in fünf ABIs** nach. Das sind Klassennamen-/Dateizählungen, **keine belastbaren Bibliotheksversionsnummern** und **kein Schwachstellen-Scan**. Details ohne OEM-Bytecode stehen in `classic_oem_component_inventory.json`.

Solange kein vollständiger Bytecode-/ABI-/Android-Verhaltensnachweis vorhanden ist, gilt: **Hersteller-DEX, Cloud-/MiIO-/BLE-Laufzeiten und die 19 nativen `.so`-Dateien bleiben im Original erhalten.** Eigene Kotlin-/DJ-Komponenten können separat modernisiert werden – allerdings nur mit Kollisionsprüfung und echten Android-Smoke-Tests; ein Quell-/JVM-PASS reicht nicht für die OEM-Kompatibilität.

## 6. Tatsächlich geprüfter Umfang

- **47/47** Python-Integrations-/Toolchain-Tests (inkl. SHA-CAS-Mismatch und Negativpfade)
- **37/37** Python-Regressionen aus dem vorherigen Classic-Checkpoint
- **7/7** Kotlin/JVM-Haupttests einschließlich BPM, AudioCapture, Stop/Recovery und fail-closed Adapter
- Kotlin- und Java-Code im älteren lokal verfügbaren Compiler 1.9.0 synthetisch geprüft; **kein echter Compiler-2.4.21-Durchlauf**
- Original-APK statisch gelesen; SHA vor und nach der Inventur identisch
- `bash -n` Build-Entry-Points erfolgreich

**NICHT durchgeführt:** Gradle 9.8.2 / AGP 9.5.1 / Kotlin 2.4.21 als echter Android-CI-Build; Apktool-3.0.3-Dekodier-/Rebuild-Test; OEM-Laufzeit-/Login-/Bluetooth-/Lampenabnahme; GitHub-Schreibaktionen.

Quellen zu Versions- und Kompatibilitätsangaben: https://gradle.org/release-checksums/ · https://docs.gradle.org/current/release-notes.html · https://developer.android.com/build/releases/about-agp · https://developer.android.com/build/migrate-to-built-in-kotlin · https://kotlinlang.org/docs/releases.html · https://apktool.org/blog/ · https://github.com/actions/checkout/releases · https://github.com/actions/setup-java · https://github.com/actions/upload-artifact/releases · https://github.com/actions/download-artifact/releases
