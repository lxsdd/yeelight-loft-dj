# Yeelight Loft DJ: Google-Home-Szenenexport – Android-Integrationspaket v2

Stand: 2026-11-04. Dieses Bundle ist eine **lokal getestete, aber nicht in GitHub integrierte Entwicklungsfassung**. Kein signiertes APK, kein Android-Gradle-Build und kein Google-Home- oder Lampentest. Aktueller GitHub-Trackingpunkt: https://github.com/lxsdd/yeelight-loft-dj/issues/55

## Umgesetzt

- **Momentaufnahme:** Der Benutzer aktiviert zuerst seine gewünschte Yeelight-Lichtstimmung, wählt die bekannten Lampen und startet bewusst **eine serielle, reine `get_prop`-Abfrage pro Lampe (zusätzlich 1,5 s Pause zwischen zwei Lampen)**. Bei einem Gerätefehler wird keine Teilaufnahme als vollständig angeboten. Eine automatische Suche, Hintergrundaktualisierung oder ein Lampenschreibzugriff erfolgt nicht.
- **Google-Home-Zuordnung:** Pro Lampe den im Google-Home-Editor angezeigten Namen und Raum ausdrücklich eintragen. Die Zuordnungen bleiben in den Android-App-Einstellungen; im Skript stehen weder IP-Adressen noch Yeelight-IDs.
- **Google-Home-Skript:** Farbton (`ColorHex` als sechsstelliger Hex-String), Kelvin, Helligkeit, Ein/Aus und Sprachstart sind als eigene Google-Home-Aktionen im YAML enthalten, ohne `ActivateScene`-Befehl bzw. Yeelight-Szenen-Referenz. Für gleiche Helligkeiten wird eine gemeinsame Aktion ausgegeben.
- **Verlaufsvorschau:** Ein vorhandener **Loft-DJ**-Farbverlauf wird mit ausdrücklicher Zustimmung als endliche, verlangsamte Google-Home-Sequenz exportiert: höchstens 8 Keyframes, mindestens 10 Sekunden bis zum nächsten Wechsel; keine Live-Musik-, Raumphasen-, Lauflicht- oder Wiederholungsfunktion.
- **Android-UI-Entwurf als Quellcode:** Eigenständige Material-3-Seite mit Auswahl, Zuordnungen, zwei Modi, Warnungen, YAML-Vorschau, Zwischenablage, Dateiexport und Link zum Google-Home-Script-Editor. Ein kleiner `MainActivity.kt`-Patch fügt den Einstieg in „Szenen“ hinzu.

## Grenzen und Sicherheit

- **Keine automatische Übertragung von in der Yeelight-Classic-App definierten Szenen.** Statisch können nur aktuelle Lampenzustände erfasst werden. Animierte Classic-Szenen erfordern separat zugängliche Original-Keyframes oder müssen nachgebaut werden.
- Google Home führt das Skript über seine bestehende Geräteintegration aus. Die Yeelight-App wird für die tägliche Bedienung eventuell entbehrlich, die Yeelight-Konto-/Cloud-Anbindung nicht automatisch. Ein Skript mit `spectrumRGB` kann für einzelne verknüpfte Lampen als nicht unterstützt abgelehnt werden.
- **Keine Color4-Hardwarefreigabe:** Selbst 2 Sekunden Abstand zwischen Farb- und Helligkeitsphase beweisen keinen Schutz vor `client quota exceeded`, da Googles Cloud-Verhalten und die OEM-Quota unverifiziert sind. Animierte Google-Home-Skripte sind nicht echtzeitfähig.
- Capture bei aktiven Shows oder offener Wiederherstellung gesperrt. Die zusätzliche 1,5-s-Pause zwischen verschiedenen Lampen ist vorsichtige Lastreduktion und **kein Beweis**, dass Google Home oder die Yeelight-Firmware eine bestimmte Rate vertragen. Es greift auf das bestehende `YeelightLan.readStatus` (gemeinsames Command-Gate) zu; für einen produktiven Merge sind zusätzlich die noch ausstehenden Color4-Quota-Schutzänderungen und deren Abnahme zu berücksichtigen.
- Gespeicherte Snapshots sind Momentaufnahmen. Spätere Änderungen durch Google Home oder Yeelight aktualisieren das exportierte YAML nicht. Vor der Übertragung erneut erfassen, falls nötig.
- **Kein Google-Konto-Schreibzugriff:** YAML muss auf https://home.google.com/automations manuell eingefügt, mit exakt passenden Gerätebezeichnungen validiert und gespeichert werden.

## Dateien und Integration (erst nach gültigem exklusivem Claim)

1. Zielquellstand bei Prüfung: `main` Commit `cfe94070c3c060ebdf4f1995a29b9f6ce786cba3`; `LoftController.kt` Blob `395cdacf36b5bd5a05e9ee9b3175a87f3ecc023f`; `MainActivity.kt` Blob `6b91cf06530d6dd2584c303a6792860272e9ea71`.
2. **Nicht einfach in `main` oder laufende PRs schreiben.** Vor Integration private Ownership-Registry, Claims, parallele PRs, Branch-SHA und zulässige Schreibberechtigung prüfen, einen isolierten Feature-Branch rechtmäßig registrieren und Claim/Readback sichern.
3. Neue Dateien im Paket von `app/src/main/java/io/github/lxsdd/yeelightloftdj/` und zugehörige Unit-Tests übernehmen.
4. Auf dem rechtmäßig autorisierten und SHA-passenden Quellstand nach `git apply --check LoftController.GoogleHome.diff MainActivity.GoogleHome.diff` die beiden Patches anwenden. **Nicht blind** auf andere Branches anwenden.
5. Mit `./gradlew testDebugUnitTest assembleDebug` und der erforderlichen Android-CI testen; insbesondere Compose/SAF-Verkabelung und sicherheitsrelevante Controller-Interaktionen verifizieren. Der gelieferte Code wurde noch nicht im Android-Projekt kompiliert.
6. Danach im Google-Home-Script-Editor ein Testskript mit **Geräteliste aus dem tatsächlichen Google Home** syntaktisch validieren. Live-Hardware-Kommandos erst mit ausdrücklicher Freigabe und dokumentiertem Color4-Gate.

## Offline-Qualifikation

- `offline_validation/run.sh` kompiliert den reinen Kotlin-Kern mit lokalem JUnit-Minimalharness (kein Android-SDK erforderlich).
- **26/26 unabhängige Offline-JVM-Tests bestanden**: statisch/animiert, RGB/Kelvin/HSV, 14 Lampen, Quarantäne-Preflight, doppelte Endpunkte, fehlende Statuswerte, YAML-Injection, Actions-Bounds, kein stiller Schnell-Animations-Export.
- Zwei **direkt vom Kotlin-Exporter erzeugte** Beispielskripte durch lokalen YAML-Parser strukturell validiert: statisch 6 Aktionen; langsame Animation 7 Aktionen.
- Google beschreibt `spectrumRGB` widersprüchlich (Feldbeschreibung „decimal integer“, referenzierter Script-Datentyp `ColorHex` sechs Hex-Zeichen). Wir verwenden die `ColorHex`-Notation; der reale Editor muss diese bei deinem Lampentyp bestätigen.

## Offizielle Quellen

- https://developers.home.google.com/automations/schema/basics (`ColorHex`, Kelvin und Duration)
- https://developers.home.google.com/automations/schema/reference/entity/sht_device/color_absolute_command
- https://developers.home.google.com/automations/schema/reference/entity/sht_device/brightness_absolute_command
- https://developers.home.google.com/automations/schema/reference/entity/sht_device/on_off_command
- https://developers.home.google.com/automations/example-scripts