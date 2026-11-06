# Yeelight Loft DJ RC8 – isolierte Sicherheits-/Google-Home-Integration

Stand: 05.11.2026. Eigentümerfreigabe für die **Vorbereitung und bei erfolgreicher
CI die ausdrücklich manuell gestartete Signierung** von `0.2.8-rc8`,
`versionCode 26`, unveränderte App-ID. Integrations-Draft PR #58, Basis:
Sicherheits-Draft PR #57 (`687c6eceea7fbcc357e64f0a716f18691d7794d9`).
Google-Home-Quelle: Draft PR #56
(`75c922867d8576cfc1190efe00a5198a89ae4637`).
**Kein Merge nach main und kein unkontrolliertes Mehrlampentesten.**

## Integrierte Funktionsgrenzen

- PR #57 behält die **beiden ausgeschalteten** Color4-Musik-Live-Akzeptanzgates
  `Color4MusicAcceptancePolicy.LIVE_TEST_APPROVED=false` und
  `Color4DualAcceptancePolicy.LIVE_TEST_APPROVED=false`. Erhalten bleiben
  geräteweise Quota-Quarantäne, reine historische Quota-Belege und der Fix
  für fehlschlagende TCP-Initialisierung ohne Socket-Leck.
- PR #56: Google-Home-Export als Android-Seite, serialisierte explizite
  *read-only* Statusmomentaufnahme über bestehende LAN-Quota-Gates,
  lokale Geräte-/Raumzuordnung, unabhängige YAML-Szenen, endliche langsame
  Farbfolgen und optionaler Einzellampen-A/B-60s-Vergleich.
- Beide gemeinsam veränderten Dateien `LoftController.kt` und
  `MainActivity.kt` wurden mit allen Sicherheits- und Google-Home-Änderungen
  vereinigt; keine Sicherheitsgate-Rücksetzung aus dem älteren Google-Home-Zweig.
- Vier zusätzliche `Rc8IntegrationSafetyTest`-Regressionen prüfen gesperrte
  Music-Live-Gates, statische Google-Home-Szenen, den Ein-Starter-Farbwechsel
  und Ablehnung uneindeutiger Google-Home-Ziele. Die JVM-/Android-Pflicht-CI
  enthält ebenfalls alle ursprünglichen PR-Regressionssuiten.

## Release-Grenze: kein PR-Signing

- Jeder gewöhnliche `pull_request`-Lauf erhält **keinen Signier-Schlüssel**
  und erzeugt weder ein installierbares Zertifikat-Artefakt noch einen Release.
- Das GitHub-Workflowfile `.github/workflows/android.yml` existiert bereits
  auf dem Default-Branch und erlaubt `workflow_dispatch`. Nur ein **manueller**
  Lauf des Repo-Eigentümers `lxsdd` auf dem **exakten Integrationszweig**
  `integration/rc8-safety-google-home-20261105` darf die vorhandene
  Zertifikats-Identität verwenden.
- Vor jeglicher geheimnisabhängiger Ausführung prüft der manuelle Lauf:
  Eigentümer-Akteur, den genauen Ref und Checkout-SHA, festes Build 26/RC8,
  dass PR #58 offen/Draft ist und der HEAD exakt mit dem laufenden Commit
  übereinstimmt, sowie eine **erfolgreiche Android-PR-CI für denselben SHA**.
  Bei Abweichung: fail closed, **keine Schlüsselkonfiguration**.
- Danach: vollständige JVM-Tests + Android-Debugbuild mit unverändertem
  PKCS12-Zertifikat, `apksigner verify`, Prüfung des fest vorgegebenen
  SHA-256-Zertifikatsfingerabdrucks, signiertes ZIP-Artefakt aus demselben
  Workflowlauf, Ausgabe der APK-SHA-256.
- Nur dann: **neuer unveränderlicher** Prerelease-Tag `v0.2.8-rc8`
  mit `Yeelight-Loft-DJ-v0.2.8-rc8-build26-signed.apk` und `apk.sha256`.
  Existiert der Tag, wird nichts überschrieben oder verschoben.
  Kein automatischer Stable-Release oder Fremd-Branch-Signing.

## Explizite nächste Nutzeraktion, erst NACH grüner End-PR-CI

1. Unter **GitHub → lxsdd/yeelight-loft-dj → Actions → Android CI**
   `Run workflow` öffnen.
2. Im **Branch-Auswahlmenü** genau
   `integration/rc8-safety-google-home-20261105` wählen.
3. **Run workflow** einmal auslösen. Der Workflow prüft intern zusätzlich
   den genauen aktuellen PR-HEAD und die zugehörige erfolgreiche PR-CI.
4. Nach erfolgreichem manuellen Lauf über den GitHub-Release `v0.2.8-rc8`
   die veröffentlichte APK herunterladen. Bei fehlgeschlagenen Checks keine
   unsignierte oder anders signierte Ersatz-APK installieren.

Die vorbereitete Workflow-Änderung ist noch **keine ausgeführte signierte CI**.
Eine gültige PR-CI ist kein Test realer Color4-Quota-Heilung und kein
Google-Home-Konto-Abnahmetest der integrierten App. Es wurden keine
realen Lampenbefehle, Netzwerk-Scans oder Firmwareänderungen ausgeführt.
