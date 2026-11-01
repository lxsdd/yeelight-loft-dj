# Signierter APK-Download und Abhängigkeitsprüfung (01.11.2026)

## Warum „Library file not found“ wiederholt auftritt

Die Android-CI erzeugt eine signierte APK, prüft deren SHA-256-Zertifikat
und speichert sie über `actions/upload-artifact@v4` mit explizit
**`retention-days: 14`**. Das ist kein dauerhaftes Downloadarchiv.
Ein von ChatGPT bereitgestellter `sandbox:/mnt/data/…`-Link kann
zusätzlich wegen der ChatGPT-/Library-Dateibereitstellung unzugänglich
sein, obwohl die Datei vorher tatsächlich verifiziert wurde. Dass
`zipfile.testzip()` besteht, beweist **nicht**, dass das jeweilige
ChatGPT-Frontend den Anhang herunterladen kann.

**Bis zum Release-Workflow:** GitHub Actions Run
[41298536086](https://github.com/lxsdd/yeelight-loft-dj/actions/runs/41298536086),
Artifact `yeelight-loft-dj-stable-signed`, ID `13080690863`;
GitHub-Login möglicherweise nötig. Keine neue APK-Binärdatei bauen,
solange dieser signierte v0.2.6-Build gültig ist.

**Dauerhafte Lösung in diesem PR:** Nach erfolgreichem
`verify-and-build`-Job **auf dem vertrauenswürdigen `main`**
lädt ein separater Job genau die eine zertifikatsgeprüfte APK aus
demselben Workflowlauf und veröffentlicht sie als GitHub Release
mit dem unveränderlichen Versions-Tag `vX.Y.Z`.

- Voraussichtlicher v0.2.6-Link nach erfolgreichem GitHub Release:
  `https://github.com/lxsdd/yeelight-loft-dj/releases/download/v0.2.6/Yeelight-Loft-DJ-v0.2.6-build17-signed.apk`
- Diesen Link **erst nach** erfolgreichem Live-Release verwenden;
  ein PR oder grüne Unit-Tests allein erstellen keinen Release.
- Für PRs wird der Release-Job nicht gestartet, und keine Signier-
  oder Publishing-Berechtigung wird an untrusted Builds weitergegeben.
- Nur der isolierte Publishing-Job erhält `contents: write`;
  Test-/Signierjob behält `contents: read`.
- Ein bestehendes Versions-Release wird weder gelöscht noch
  überschrieben, das Tag wird nie verschoben. Bei Änderungen muss
  die App-Version erhöht werden.
- Der Upload nach `verify-and-build` findet nur bei erfolgreichem
  Zertifikatscheck statt; das Release kopiert genau diese APK, ohne
  eigenmächtige Neusignierung oder private Schlüsselkopie.

## Unabhängige Dependabot-Prüfung

Sieben PRs sind offen; zunächst **keiner** zur Integration freigegeben.
Die Commit-CI-Ergebnisse stammen aus Oktober 2026; die dort
zugrundeliegenden Vergleichsbasen sind älter als der heutige
v0.2.6-Hauptzweig. Für jeden Merge erneute HEAD-/CI-Prüfung nötig.

| PR | Inhalt | Einzel-CI | Ursache / Entscheidung |
|---|---|---|---|
| #2 | `actions/checkout` v4 → v7 | PASS | Nur Action-Major; aktuelle `main`-CI erneut testen |
| #3 | `actions/setup-java` v4 → v6 | PASS | Nur Action-Major; erneut testen |
| #4 | `kotlinx-coroutines-android` 1.10.2 → 1.11.0 | PASS | Runtime-Abhängigkeit; erneut testen |
| #6 | `actions/upload-artifact` v4 → v7 | PASS | Prüfen, dass Signatur- und Release-Asset weiterhin erstellt werden |
| #5 | AGP 8.13.2 → 9.5.0 | FAIL | **Gradle 9.6 erforderlich**, CI verwendet 8.13 |
| #7 | Kotlin Android 2.2.20 → 2.4.21 | FAIL | `kotlinOptions { jvmTarget = "17" }` nicht mehr zulässig; migrieren zu `compilerOptions`, Compose-Compiler-Version ebenfalls koordinieren |
| #8 | Compose BOM 2026.05 → 2026.09 | FAIL | Compose 1.12.1 verlangt **compileSdk 37** und **AGP mindestens 9.1**; `main` nutzt SDK 36, AGP 8.13.2 |

Verifizierte Fehler aus den GitHub-Läufen:

- [#5 fehlgeschlagen](https://github.com/lxsdd/yeelight-loft-dj/actions/runs/41240111995): „Minimum supported Gradle version is 9.6.0. Current version is 8.13.“
- [#7 fehlgeschlagen](https://github.com/lxsdd/yeelight-loft-dj/actions/runs/41240212778): „Using 'jvmTarget: String' is an error. Please migrate to the compilerOptions DSL.“
- [#8 fehlgeschlagen](https://github.com/lxsdd/yeelight-loft-dj/actions/runs/41240216365): `checkDebugAarMetadata` meldet SDK 37 und AGP 9.1 als Minimum.
- #2/#3/#4/#6: erfolgreich unter ihren damaligen jeweiligen Commitständen.

## Umsetzungsempfehlung

1. Kein ungeprüfter Dependabot-Merge auf `main`.
2. #2/#3/#4/#6 isoliert auf heutige Basis bringen; CI und
   permanent signiertes Hauptpaket unverändert nach jedem Merge prüfen.
3. **AGP/Kotlin/Compose nur als koordinierte größere Migration**
   in eigener Branch-/PR-Kette: Gradle 9.6, AGP 9.5,
   compileSdk 37 (targetSdk zunächst unverändert),
   Kotlin CompilerOptions, passende Compose-Compiler-Version;
   danach vollständige Unit-/Android-Tests.
4. Die dauerhafte Release-Pipeline unabhängig von diesem
   großen Toolchain-Upgrade integrieren, um dem Nutzer vorher
   endlich einen robusten APK-Download zu geben.
