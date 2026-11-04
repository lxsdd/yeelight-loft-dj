# Yeelight Loft DJ: Google-Home-Szenenexport – Android-Integrationspaket v2

Stand: 2026-11-04. **Quellcode integriert in den isolierten Draft-PR #56** (Basis: Produkt-PR #50), Tracking: https://github.com/lxsdd/yeelight-loft-dj/issues/55 . Erste quellgenaue öffentliche Android-CI auf Commit `ccfa01450653e1a8491b2b244133006b68ace2bb`: `testDebugUnitTest` und `assembleDebug` **PASS** in https://github.com/lxsdd/yeelight-loft-dj/actions/runs/41892179897 . Weiterhin **keine** signierte APK, keine Freigabe für Live-Lampentests, kein Google-Home-Editor-Test und kein Merge/Release.

## Umgesetzt

- **Momentaufnahme:** Der Benutzer aktiviert zuerst seine gewünschte Yeelight-Lichtstimmung, wählt die bekannten Lampen und startet bewusst **eine serielle, reine `get_prop`-Abfrage pro Lampe (zusätzlich 1,5 s Pause zwischen zwei Lampen)**. Bei einem Gerätefehler wird keine Teilaufnahme als vollständig angeboten. Eine automatische Suche, Hintergrundaktualisierung oder ein Lampenschreibzugriff erfolgt nicht.
- **Google-Home-Zuordnung:** Pro Lampe den im Google-Home-Editor angezeigten Namen und Raum ausdrücklich eintragen. Die Zuordnungen bleiben in den Android-App-Einstellungen; im Skript stehen weder IP-Adressen noch Yeelight-IDs.
- **Google-Home-Skript:** Farbton (`ColorHex` als sechsstelliger Hex-String), Kelvin, Helligkeit, Ein/Aus und Sprachstart sind als eigene Google-Home-Aktionen im YAML enthalten, ohne `ActivateScene`-Befehl bzw. Yeelight-Szenen-Referenz. Für gleiche Helligkeiten wird eine gemeinsame Aktion ausgegeben.
- **Verlaufsvorschau:** Ein vorhandener **Loft-DJ**-Farbverlauf wird mit ausdrücklicher Zustimmung als endliche, verlangsamte Google-Home-Sequenz exportiert: höchstens 8 Keyframes, mindestens 10 Sekunden bis zum nächsten Wechsel; keine Live-Musik-, Raumphasen-, Lauflicht- oder Wiederholungsfunktion.
- **Android-UI, im Draft-PR kompiliert:** Eigenständige Material-3-Seite mit Auswahl, Zuordnungen, zwei Modi, Warnungen, YAML-Vorschau, Zwischenablage, Dateiexport und Link zum Google-Home-Script-Editor. Ein kleiner `MainActivity.kt`-Patch fügt den Einstieg in „Szenen“ hinzu.

## Grenzen und Sicherheit

- **Keine automatische Übertragung von in der Yeelight-Classic-App definierten Szenen.** Statisch können nur aktuelle Lampenzustände erfasst werden. Animierte Classic-Szenen erfordern separat zugängliche Original-Keyframes oder müssen nachgebaut werden.
- Google Home führt das Skript über seine bestehende Geräteintegration aus. Die Yeelight-App wird für die tägliche Bedienung eventuell entbehrlich, die Yeelight-Konto-/Cloud-Anbindung nicht automatisch. Ein Skript mit `spectrumRGB` kann für einzelne verknüpfte Lampen als nicht unterstützt abgelehnt werden.
- **Keine Color4-Hardwarefreigabe:** Selbst 2 Sekunden Abstand zwischen Farb- und Helligkeitsphase beweisen keinen Schutz vor `client quota exceeded`, da Googles Cloud-Verhalten und die OEM-Quota unverifiziert sind. Animierte Google-Home-Skripte sind nicht echtzeitfähig.
- Capture bei aktiven Shows oder offener Wiederherstellung gesperrt. Die zusätzliche 1,5-s-Pause zwischen verschiedenen Lampen ist vorsichtige Lastreduktion und **kein Beweis**, dass Google Home oder die Yeelight-Firmware eine bestimmte Rate vertragen. Es greift auf das bestehende `YeelightLan.readStatus` (gemeinsames Command-Gate) zu; für einen produktiven Merge sind zusätzlich die noch ausstehenden Color4-Quota-Schutzänderungen und deren Abnahme zu berücksichtigen.
- Gespeicherte Snapshots sind Momentaufnahmen. Spätere Änderungen durch Google Home oder Yeelight aktualisieren das exportierte YAML nicht. Vor der Übertragung erneut erfassen, falls nötig.
- **Kein Google-Konto-Schreibzugriff:** YAML muss auf https://home.google.com/automations manuell eingefügt, mit exakt passenden Gerätebezeichnungen validiert und gespeichert werden.

## Implementierung, Branching und Abnahme

- Produktzweig: [Draft-PR #56](https://github.com/lxsdd/yeelight-loft-dj/pull/56) auf `feature/google-home-scene-export-20261104`, **gestapelt auf** `fix/color4-sequential-preflight-close-sockets-20261102` (PR #50). Keine Änderung an fremden Color4-/Chroma-/UX-Quellzweigen, keine Änderung an `main`.
- Die exklusive CHAT-Ownership wurde vor dem ersten Produkt-Source-Commit über die private GitHub-Registry mit atomarem CAS und unabhängigem Readback registriert und erworben. Quellstand und Claim nach dem ersten grünen Build generationssicher synchronisiert.
- Android-CI: 26 zusätzliche reine Kotlin-/JUnit-Tests in der tatsächlichen App-Quellstruktur; `testDebugUnitTest assembleDebug` im öffentlichen PR-Lauf **erfolgreich**. Dies ist keine Freigabe für reale Color4/Yeelight/Google-Home-Geräte.
- Status der PR nach weiteren Dokumentationsänderungen stets mit dem **neuesten** tatsächlichen PR-HEAD und passender CI prüfen; alte grüne Läufe sind keine Aussage über spätere SHA.
- Für echten Einsatz müssen im [Google-Home-Script-Editor](https://home.google.com/automations) Geräte-Einträge per Autovervollständigung auf die tatsächlich verfügbaren Lampen geprüft und das generierte Skript dort manuell validiert, gespeichert und probeweise ausgeführt werden. Es gibt keinen direkten Google-Account-Writer.
- Keine Merges, Releases, Signierung oder Geräte-/Router-/Firmwaretests ohne die hierfür erforderliche ausdrückliche Freigabe.

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

## Praxistest und Folgekorrekturen (04.11.2026)
- Nutzerbestätigter Google-Home-Editor-Test: **keine Validierungsfehler oder Warnungen**. Drei individuell zuordenbare Geräte haben beim einmaligen Cloud-Test tatsächlich auf Farbe/Helligkeit reagiert. Dies beweist **nicht**, dass Google-Home-Farbkreis und physische LED-Farbe visuell übereinstimmen.
- Benutzer wählt bei Voice-Startern eine **kurze, eindeutige Formulierung**, ohne `Hey Google`. Die App speichert Namen und Sprachstarter lokal für den nächsten Editorbesuch. Google Assistant kann Sprache unterschiedlich interpretieren; ein erfolgreicher Script-Editor-Check garantiert kein erkanntes Sprachereignis.
- Bei ausdrücklich angeforderter, frisch erfasster **RGB-Momentaufnahme** kann der Benutzer pro ausgewählter Lampe genau für diesen Szenenexport einen alternativen sechsstelligen RGB-Hexwert eingeben. Ohne Eingabe bleibt der Ursprung exakt erhalten. Keine pauschale Farblut/firmwareseitige Korrektur, keine lokale Lichtänderung, kein globales Profil. Vor erneuter Erfassung wird der Szenen-Override verworfen.
- Farbabweichungen müssen **optisch pro Gerät bei mehreren Zielwerten** geprüft werden. Ein Pixel im Google-Home-Farbkreis oder ein Yeelight-`get_prop`-Status ist kein physisches Farbmetrik-Messgerät. Automatische LEDs-Kalibrierung und Cloud-/Firmware-Ursachenzuweisung bleiben unbelegt.
- Die sechs mehrfach verwendeten Lampenbezeichnungen in der Google-Home-Wohnung werden **nicht** automatisch umbenannt oder willkürlich nummeriert. Mehrdeutige Zielgeräte müssen im Export weiterhin eindeutig aufgelöst sein; der vorhandene sichere Export verweigert doppelte Referenzen.
