# color4 / YLDP13YL: Original-Firmware-Reparatur statt bloßer LAN-Quota-Umgehung

Recherche: **2026-11-01** · Forschungs-Draft-PR #30. **Kein Firmwaredump, kein Flashversuch, keine Geräte-/Routeränderung.**

## Executive Ergebnis

Die Möglichkeit einer eigenen **Korrektur der vorhandenen Originalfirmware** ist weder widerlegt noch bereits bewiesen. Drei neue, getrennt zu betrachtende Quellen machen den Ansatz technisch ernsthaft verfolgenswert:

1. **Yeelight bestätigt einen realen Defekt des Quota-Refill-Timers nach `2^32` Millisekunden** für die im Herstellerforum analysierte Plattform. Nach dem Überlauf wird der Refill-Timer nicht mehr aktiviert. Yeelight schrieb 2022, der Fix sei einfach, das Ausrollen scheitere an einer Xiaomi-IoT-Cloud-Service-Problematik. **Ausgangspunkt dort: `ceiling1`, nicht ein getesteter `color4`-Firmwaredump.** https://forum.yeelight.com/t/topic/15756/18
2. **Modellspezifisch `color4` / Yeelight 1S YLDP13YL:** Mehrere Nutzer berichteten im Februar 2025 über Ausfälle um **1.100–1.200 Betriebsstunden**, u. a. auf **`2.1.7_0039`**, bei denen App-/Home-Assistant-Neustart nicht half, aber Lampen-Stromneustart half. Das ist zeitlich sehr nahe an `2^32 ms = 49 d 17 h 2 min 47,296 s = 1193,05 h`; **starke Hypothese derselben Fehlerfamilie, kein binärer Codebeweis**. https://community.home-assistant.io/t/yeelight-1s-bulbs-not-responding-after-1200h-uptime/843294
3. **Originalfirmware existiert als katalogisierte Updates:** Ein inoffizieller Firmwareindex listet für `yeelink.light.color4` Firmwarestände `2.0.6_0022`, `2.0.6_0034`, `2.1.7_0035`–`0039` (und eventuell Zwischenstände), `0036` mit Beschreibung „Optimize network stability“, `0039` als Release vom **06.01.2022** mit „Optimize HomeKit experience“. **Katalog-Metadaten sind kein von uns heruntergeladenes, gehashtes oder rechtlich für Weiterverteilung geprüftes OEM-Image**; die Veröffentlichung des Index im Jahr 2026 ist **nicht** gleichbedeutend mit einer neuen Yeelight-Firmware von 2026. https://vacuum.mindsolo.net/en/firmwares/2835 und https://vacuum.mindsolo.net/en/models/yeelink.light.color4

### Was der reale Fehler von Loft DJ beweist — und nicht beweist

Der frühere `client quota exceeded`-Vorfall an „Wellness“ nach mehreren manuellen Änderungen mit Loft DJ ist ein **echter Gerätefehler**, keine bloße UI-Meldung. Dass der Yeelight-Client danach ebenfalls nicht mehr lokal steuern konnte und ein Stromneustart bei einer anderen Lampe half, ist mit einem Firmware-Zustandsdefekt vereinbar. **Unbekannt:** Zeitpunkt des letzten Power-Cycles (Geräte-Uptime), Firmwareversion jeder Lampe, Zahl der über alle TCP-Verbindungen tatsächlich ausgeführten Kommandos, übrige LAN-Clients und internes Refill-Verhalten. Ein Last-/Burst-Fehler bleibt ebenso plausibel. Die 2025-Beschreibung bestätigt **nicht**, dass jeder `color4`-Quota-Vorfall nach wenigen Befehlen durch den 49,7-Tage-Fehler verursacht wird.

## Hardware: ein prinzipieller ESP32-Reparaturpfad, aber kein bewiesener Zielchip

- Yeelight-1S `YLDP13YL` wird durch Yeelight selbst mit der Firmware-Update-/Razer-Chroma-Funktion beworben: https://home.yeelight.de/en/products/essential-lighting/smart-led-bulb-1s-color/
- **Ein einzelner Besitzer berichtete 2020, innerhalb einer 1S einen `ESP32-WROOM` gesehen zu haben**, fragte aber nur nach Custom Firmware: https://forum.yeelight.com/t/topic/20446 . Kein veröffentlichter vollständiger `color4`-PCB-/GPIO-Plan, keine geprüfte Revision, kein eigenes Flash-Readback und keine bestätigte eFuse-Konfiguration. **Keinesfalls davon ausgehen, dass jede SKU/PCB derselbe ESP32 ist.**
- Die Modellbezeichnung `color4` entspricht laut Home Assistant u. a. YLDP13YL; es werden auch andere SKU-/Variantenangaben zu `color4` geführt. Modellstring und Gehäusetyp ersetzen keine PCB-Identifikation: https://www.home-assistant.io/integrations/yeelight/
- Einzelberichte belegen echte Veränderungen des YLDP13YL-Verhaltens über **Firmware `2.0.6_0028`→`2.0.6_0031`** (ohne WAN nach ~15 min entweder Restart oder dauerhafte Unerreichbarkeit): https://forum.yeelight.com/t/topic/22925 . Ein anderer Besitzer meldete nach `2.1.7_0037` Farb-/Helligkeitsregressionen; deswegen keinesfalls blind `0039` oder einen älteren Build als fehlerfreien Fix deklarieren: https://sympapa.hatenablog.com/entry/2021/10/24/135254

## Reparaturwege – nach Eignung für „Originalfunktionen behalten“

| Pfad | erhält Yeelight-App / Google Home / HomeKit? | Heute belegt? | Urteil |
|---|---|---|---|
| **A. Originalfirmware gezielt patchen**, Refill-Timer korrigieren | **potenziell am besten**, wenn Code/Signatur/OTA-/Rollbackpfad die Änderung erlauben | **Nein**: Originalcode, geprüfte Binärpakete, eFuse-Status und Rollback fehlen | **Primäres Reverse-Engineering-Ziel**, keinesfalls schon flashbar |
| **B. Korrigiertes Original-Image durch Yeelight/Xiaomi** | grundsätzlich beste Kompatibilitäts-Chance | Hersteller bestätigte Bug/Fixabsicht 2022, kein nachgewiesenes neues `color4`-Update nach Release `0039` 2022 | **Parallel anfragen**, ggf. bestehender interner Beta-/Whitelist-Fix |
| **C. Eigene Firmware mit Yeelight-LAN-Protokoll-Nachbildung** | lokale Loft-DJ-Steuerung realisierbar, **Cloud/Google/Apple-Originalintegration nicht automatisch** | Keine `color4`-GPIO-/PWM-/Farbkalibrierungs-/Bootloader-Validierung | **Nur Ersatzplan**, nicht still als OTA-Kompatibilität ausgeben |
| **D. ESPHome / Tasmota / WLED direkt** | Ersetzt im Normalfall OEM-Stack; Google-Home-Anbindung braucht neue Infrastruktur | Yeelight-ESPHome-Projekt hat modellgenaue andere Geräte, **keinen belegten `color4`-Port** | Erst nach komplettem PCB-Nachweis und bewusstem Funktionsverlust |
| **E. Music Mode / Chroma UDP mit Originalfirmware** | lässt Originalfirmware unverändert; kann Hochfrequenzbefehle vermeiden | TCP Music Mode laut Yeelight-Spec; Chroma-Connector und 1S-Marketing belegt, **nicht** die tatsächliche SUPPORT-/Quota-Fähigkeit unserer Geräte | Gut für DJ-Effekte, **repariert den defekten Refill-Timer selbst nicht** |
| **F. Konservative Befehlssteuerung in Loft DJ PR #31** | vollständig rückbaubar, schützt gegen Burst- und Mehrfach-Verbindungsfehler | Quell-CI erfolgreich, noch kein eigenständiger Hardware-Root-Cause-Beweis | **Notwendiger Schutz**, keine Firmware-Reparatur |

## Konkrete *eigentliche* Firmware-Korrektur, falls die Timerhypothese am Zielgerät bestätigt wird

Die Zielbedingung ist **nicht** „Rate Limit entfernen“. Das Limit schützt laut Hersteller die Lampe und ihren Cloud-Nachrichtenpfad. Ein eigener Wartungs-Patch soll **nur den Refill-/Rearm-Defekt korrigieren**, sodass ein temporärer Quota-Fehler wieder abklingen kann; die vorgesehenen 60 Befehle/min je TCP-Verbindung, 144/min insgesamt und die vier Socketplätze bleiben konservativ erhalten.

Technische Anforderungen an eine C/ESP-IDF-Implementierung (***Konzept, kein aus OEM-Quellcode entnommener Patch***):
- Zeitstempel als monotone `uint64_t`-Ticks oder, falls gezielt ein `uint32_t`-Millisekundenzähler verwendet wird, ausschließlich **modulare Differenzen** `(uint32_t)(now - last_refill)` für zulässige kurze Zeitfenster statt `now >= last_refill + 60000` mit Überlauf. Keine absolute Deadline, die nach 49,7 Tagen nicht wieder bewaffnet wird.
- Refill unabhängig von Netzwerk-/Cloud-Erfolg beim Übergang über den Wrap; separate Grenzen für jede Verbindung und global; kein Overflow durch Multiplikation, unterlaufende Tokens oder negativ interpretiertes `int32_t`.
- Der Refill muss auch funktionieren, wenn gerade **kein** Kommando eingeht, oder bei neuer Anfrage korrekt nachgezogen werden; keine irreversible „QUOTA_EXCEEDED“-Flag-Sperre.
- Testvektoren: `2^32 - 120000`, `2^32 - 1`, `2^32`, `2^32 + 1`, `2^32 + 120000`, mehrere volle 60-s-Fenster und `2 × 2^32`, jeweils mit überlappenden aktiven TCP-Sessions, Fehlerantworten und längeren Idle-Phasen. Virtualisierte Uhr statt reale 50-Tage-Laufzeit.
- Nachweis an **genau der originalen `color4`-Binärfunktion** via erlaubter statischer Analyse; wenn verifizierte Signaturen und Plattformgrenzen nicht erfüllt sind: **nicht patchen**. Trennung der reproduzierbaren 49,7-Tage-Regressionsklasse von einem sofortigen Quota-Fehler bei kurzer Uptime.

**Präzision:** Der 2022 vom Hersteller bestätigte Fehler ist der *nicht erneut gestartete Refill-Timer*, nicht zwingend ein allgemein falsches `now - last` auf `color4`. Erst ein konkretes Image/Disassembly kann verraten, ob die exemplarische monotone Zeitrechnung an der richtigen Funktion ansetzt.

## Kann die Original-Firmware überhaupt als OTA ersetzt werden?

Ein öffentliches ESP32-Yeelight-Forschungsprojekt hat einen **modellabhängigen** `miIO.ota`-Pfad für **andere** ESP32-Yeelights belegt, darunter `lamp9`, `ceiling10`, `ceilb`, `ceilc`. Ein eigenes Firmwareimage muss dort bestimmte Herstellerformate/Integritätsmerkmale (z. B. vier zusätzliche CRC-Trailer-Bytes) erfüllen. Es existiert zudem ein projektspezifischer Boot-/Restore-Ablauf. https://github.com/syssi/esphome-yeelight-ceiling-light/blob/main/docs/flashing-over-the-network.md

**Dieser Nachweis darf nicht auf `color4` übertragen werden.** Es ist für `color4` weder ein akzeptierter `miIO.ota`-Aufruf noch ein manipulierbares Originalimage, eine getestete CRC-/Signaturkette, eine vollständige Partitionstabelle, ein Backupweg oder funktionierender Rollback belegt. OTA, das eine App-Partition schreibt, garantiert **keinen** Wiederherstellungspfad bei nicht bootender Firmware. Ein CRC ist keine kryptografische Signatur; die Möglichkeit eines CRC-Anpassungen beweist **nicht**, dass OEM-Signaturvalidierung deaktiviert ist.

ESP-IDF kann Secure Boot, signierte OTA-Updates und Flash-Verschlüsselung aktivieren, abhängig von Chiprevision und Geräte-eFuses. Falls ein Gerät die originale Herstellersignatur verlangt, dürfen wir **nicht behaupten**, ohne Schlüssel eine veränderte OEM-Firmware normal einspielen zu können. https://docs.espressif.com/projects/esp-idf/en/stable/esp32/security/secure-boot-v2.html und https://docs.espressif.com/projects/esp-idf/en/stable/esp32/api-reference/system/ota.html

## Entscheidungs-/Qualifikationsgates (strikt nach Reihenfolge)

1. **Nur bereits vorhandene Informationen** je Lampe: genaue SKU/Modellkennung und Hersteller-Firmwarestand (ohne weitere LAN-Kommandos), soweit in der Yeelight-App ablesbar. Außerdem – falls bekannt – den Zeitpunkt des letzten Stromneustarts notieren; beim nächsten Quota-Vorfall die Uptime grob relativ zum 49,7-Tage-Intervall prüfen. Persönliche IP/MAC/Token niemals im öffentlichen Repo.
2. **OEM-Images legitim beschaffen und offline archivieren**: Für exakt `color4` Versionen `2.0.6_0034`, `2.1.7_0036`, `2.1.7_0037`, `2.1.7_0038`, `2.1.7_0039`, inklusive Herkunfts-URL, SHA-256, Originaldateilänge, Dateiheader, ggf. dokumentierter Hersteller-Signatur und Versionsvergleich. Die oben gefundene Drittanbieter-Liste ist **noch kein erfolgreich beschafftes/verifiziertes Binärpaket**. Image-Dateien bleiben außerhalb des öffentlichen Repositories; keine fremden Firmenimages als GitHub-Releases.
3. **Offline-Imageanalyse** auf bekanntem Host (keine Lampenanfrage): Firmwareformat/ESP32-ESP-IDF-Header, Sektionen, Image-Ende/CRC/SHA, mögliche Signatur-/Verschlüsselungsmarker, Quota-Rearm-Routinen, Netzwerkroutinen, String-Referenzen, symbol-/versionsbasierter Binärvergleich. Hersteller-Code/Quellen und Patentrechte nicht voraussetzen; keine ungeprüften Byte-Patches.
4. **Quellnahen Mini-Regressionstest** implementieren für 49,7-Tage-Wrap, Mehrfachverbindungen, Quota-Erholung und Start/Stop-/Music-Mode-Koexistenz. Vollständige Original-Yeelight-/Google-/HomeKit-Parität ist Voraussetzung für ein OEM-beibehaltendes Produkt.
5. **Eine separate, ausdrücklich freigegebene Laborlampe** und dokumentierte, elektrisch sichere Read-only-Hardwarequalifikation erst wenn erforderlich (Geräte auf Netzspannung! Keine Öffnungsanweisungen an Laien). Vor jeglichem Schreibversuch echtes Image-/NVS-/eFuse-/Partition-Backup sowie nachweislich funktionsfähige Offline-Rücksicherung. Kein OTA nur mit „bekanntem Modellstring“.
6. **Korrigiertes Original-Image nur freigeben**, wenn statisch eindeutig lokalisiert, signaturseitig zulässig, testbar, reversibel und OEM-/Google-/HomeKit-Interoperabilität unabhängig nachgewiesen wurde. Sonst A als **derzeit nicht sicher umsetzbar** markieren und auf B/E/F bleiben.

## Endstand: klare Antwort zur eigenen Firmware

**Die 49,7-Tage-Quota-Hypothese ist für `color4` jetzt gut begründet, aber noch nicht verifiziert. Ein Patch der vorhandenen Yeelight-Originalfirmware ist derzeit NICHT herstellbar oder verantwortbar:** Kein analysierbarer, prüfsummenverifizierter OEM-`color4`-Imagebestand im Projekt, keine validierte Codefunktion und keine abgesicherte Signatur-/Recovery-Kette. Das ESP32-WROOM-Indiz erhöht die technische Machbarkeit, hebt diese Hindernisse aber nicht auf.

**Nächste forschungsseitig sinnvolle Arbeit ohne Hardware:** gezielte, rein offline Beschaffung/Analyse vertrauenswürdiger OEM-OTA-Binärpakete; unabhängig Herstelleranfrage nach dem am 07.06.2022 bestätigten Timerfix für **`yeelink.light.color4` / YLDP13YL / 2.1.7_0039**. Keine unautorisierten Schreibaktionen.

### Primärquellen und unabhängige Hinweise

- [Yeelight-Entwickler bestätigte Timer-Overflow-Korrektur, 07.06.2022](https://forum.yeelight.com/t/topic/15756/18)
- [YLDP13YL mit 2.1.7_0039: Ausfall um 1100–1200 h, 07.02.2025](https://community.home-assistant.io/t/yeelight-1s-bulbs-not-responding-after-1200h-uptime/843294)
- [Original-Firmware-Metadaten color4, inoffizieller Archivindex](https://vacuum.mindsolo.net/en/firmwares/2835)
- [ESP32-WROOM-Augenzeugenhinweis für Yeelight 1S, 2020](https://forum.yeelight.com/t/topic/20446)
- [YLDP13YL ohne WAN, Firmwareabhängige Reboots](https://forum.yeelight.com/t/topic/22925)
- [Yeelight-Music-Mode-Quota-Dokumentation](https://www.yeelight.com/en_US/developer)
- [Modellbegrenzter Yeelight-ESP32-OTA-Forschungsbericht](https://github.com/syssi/esphome-yeelight-ceiling-light/blob/main/docs/flashing-over-the-network.md)
- [Espressif Secure Boot v2](https://docs.espressif.com/projects/esp-idf/en/stable/esp32/security/secure-boot-v2.html)
