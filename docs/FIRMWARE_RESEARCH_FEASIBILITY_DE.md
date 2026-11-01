# Firmware Research – Machbarkeit, Geräteklassen, Funktionen und sichere Migrationspfade

**Stand:** 2026-11-01 · **Status:** dokumentierte externe Forschung und Architekturentscheidung, **keine** Hardware- oder Firmware-Abnahme.  
**Zugehörige Fehleranalyse:** [ceiling1 WLAN/Cloud/BLE #29](https://github.com/lxsdd/yeelight-loft-dj/issues/29). **App-LAN-Quota-Schutz:** PR #28, unabhängig von dieser Forschung.

## Auftrag und Abgrenzung

Parallel zu Yeelight Loft DJ als **Original-Firmware-kompatibler Android-Anwendung** wird eine eigenständige, modellbezogene Firmware-Forschungsstrecke betrieben. Ziel sind zusätzliche Funktionen und höhere Zuverlässigkeit, insbesondere musikreaktive hochfrequente Effekte, präzise Gruppen-/Zeitsteuerung, Netzwerk-Reconnect, zusätzliche Sensorik und lokale Steuerung. Firmware-Forschung darf **keine** Voraussetzung für bestehende App-Funktionen werden und muss vorhandene Yeelight-App-, Google-Home- und Bluetooth-Funktionalität als harte Abnahmegrenze berücksichtigen.

**Evidenzklassen:** A = ausdrücklich für genaues Hardwaremodell dokumentierter funktionierender Ansatz (nicht automatisch selbst getestet); B = derselbe Modelltyp mit offenen Hardware-/Firmwarevarianten; C = anderes Modell/analoges Konzept; U = unbestätigt. Veröffentlichte Fremdberichte ersetzen keinen eigenen Test.

## 1. Modell- und Hardwarematrix

| Vorhandene bzw. relevante Gerätegruppe | Identifikation | Firmware-Plattform / Nachweis | Migrationsstatus | Priorität |
| --- | --- | --- | --- | --- |
| Yeelight Luna Ceiling | `yeelink.light.ceiling1`, YLXD01YL | Für YLXD01YL identifiziert der ESPHome-Projektmaintainer einen **MediaTek-Mikrocontroller**; keine direkte ESPHome-Unterstützung. Hardware-Ersatzprojekt mit XIAO ESP32-C6 existiert. | Originalfirmware-RE **U**; invasiver Controller-Ersatz **A (Fremdprojekt)**, aber ohne unsere Tests/Kompatibilitätsfreigabe | **P0: Offline-Ursache ohne Umbau**, P2: Ersatz-Forschung |
| 1S Color-Lampen im Loft | `yeelink.light.color4`; in Modellkatalogen u. a. YLDP13YL und YLDP04YL | Modellkennung allein beweist weder Chip, Platinenrevision noch Bootloader. Hersteller YLDP13YL; die recherchierte ESPHome-Deckenlampen-Matrix enthält **kein color4**. | Direkte ESPHome-/Tasmota-Kompatibilität oder `miIO.ota` für **color4 NICHT belegt (U)**. Nicht von anderen ESP32-Leuchten übertragen. | **P1: genaue Hardware/Versionsmatrix**, vor Firmware-Experimenten |
| Vorhandene LED-Streifen (Modell noch zu bestätigen) | unbekannte SSDP-Modellkennung | Für **`yeelink.light.strip6` / YLDD05Y** liegt eine ESPHome-Konfiguration eines Drittprojekts vor. | A nur **wenn** tatsächlich strip6/YLDD05Y, sonst U. | P1: rein lesende Modellprüfung |
| Mi Desk Lamp 1S | konkrete Hardware-ID noch zu prüfen | Aktuelle App behandelt sie ausschließlich read-only bei Konnektivitätsdiagnose. | **U**. Keine Gleichsetzung mit anderen `lamp*`-Modellen. | P3 |
| Weitere ESP32-Yeelights als Referenz | u. a. `ceiling10`, `ceil26`, `ceiling15`, `lamp9`, `ceilb`, `ceilc` | ESPHome-Modelle, Pinouts und Konfigurationen öffentlich dokumentiert. | A auf den aufgeführten Fremdmodellen; **kein** Beweis für ceiling1/color4. | P2 Referenz-/Simulator-Design |

Quellen:
- ESPHome-Modellmatrix: https://github.com/syssi/esphome-yeelight-ceiling-light
- MediaTek-Hinweis und Controllerersatz-Idee für YLXD01YL: https://github.com/syssi/esphome-yeelight-ceiling-light/issues/10
- Implementierter YLXD01YL-Ersatz mit BLE-Remote, LD2410 und BH1750: https://github.com/bedson87/yeelight_YLXD01YL_xiao_esp32c6_LD2410_BH1750
- `color4`-SKU-Zuordnung: https://yeelight.readthedocs.io/en/stable/devices.html
- YLDP13YL Produktdaten: https://home.yeelight.de/en/products/essential-lighting/smart-led-bulb-1s-color/

**Nicht behaupten:** `color4` sei sicher ESP32, `ceiling1` könne direkt ESPHome starten, alle Streifen seien `strip6`, ein für `lamp9` bestätigter OTA-Weg gelte für andere Modelle.

## 2. Was ohne Firmwarewechsel bereits möglich ist – zuerst ausschöpfen

Die [offizielle Yeelight LAN-Spezifikation](https://home.yeelight.de/site/templates/downloads/yeelight_inter-operation-spec.pdf) beschreibt Discovery, TCP 55443, JSON-`\r\n`-Nachrichten, Antworten/Notifications und harte Grenzen: **vier TCP-Verbindungen je Lampe**, **60 Befehle/Minute je Verbindung** und **144 Befehle/Minute insgesamt**. Diese Grenzen sind für sicherheitskritische App-Regressionsprüfungen verbindliche Referenzwerte, keine Zusicherung für alle Firmwarestände.

- **Gerätebezogene Fähigkeitserkennung:** Die vom Gerät im Discovery-Header gelieferte `support`-Liste ist relevant; Methoden niemals anhand der Produktbezeichnung allein freischalten.
- **Native Farbabläufe:** `start_cf`, `stop_cf`, `set_scene` (nur wo unterstützt) – Zeittaktung weitgehend **in der Lampe** statt pro Frame über WLAN.
- **Music Mode:** `set_music` dreht den TCP-Kanal um und lässt kontinuierliche Änderungen ohne das gewöhnliche Nachrichtenquota zu; erfordert korrektes Öffnen, Rückkanalmanagement, STOP und Wiederherstellung. Nur für Lampen, deren `support` die Methode explizit meldet. Während dieses Modus können Statusmeldungen fehlen. **PR #28** arbeitet an einer defensiven Umsetzung. Nicht mit einem generellen Quota-Bypass für jede Lampe verwechseln.
- **Razer-Chroma-/Spezialprotokolle:** Für einzelne Modelle existieren zusätzliche Wege; jede UDP-/Streaming-Fähigkeit muss anhand Hardware und tatsächlicher Protokollunterstützung separat nachgewiesen werden. Nicht universell nutzbar.
- **Präzise Musik-/Raumsteuerung:** Auf dem Android-Host Frame/Beat/Gruppe berechnen und vom Gerät unterstützte native Effekte mit sparsamen Befehlen ausführen; keine millisekundengenaue Synchronität zwischen unabhängigen OEM-Firmwares behaupten.

**Architekturentscheidung R1:** Original-LAN bleibt Primärpfad. Ein späterer Firmwareadapter ist eine **optionale, anhand Device-ID aktivierte Transportalternative**, niemals eine heimliche Umleitung eines Originalgeräts.

## 3. OTA, Originalfirmware-Dumps und Rückkehrmöglichkeit

### Für nachgewiesene ESP32-Modelle – Fremdprojekt, nicht unsere Hardware

Das ESPHome-Yeelight-Projekt dokumentiert für mehrere **konkret benannte ESP32-Modelle** einen `miIO.ota`-Weg, der über eine Xiaomi-Cloud-RPC eine lokal bereitgestellte Firmware in eine **Anwendungs-Partition** schreibt:

- Selbst/bei Mitwirkenden bestätigt: `lamp9` Firmware `2.1.7_0031`, `ceiling10` `2.0.6_0049`, `ceilc` `2.0.6_0005`, `ceilb` `2.1.7_0011`; je nach Generation unterschiedliche Voraussetzungen.
- Für `lamp9` ist ein Rückwechsel zur Originalfirmware durch Wiederherstellung eines Stock-Images dokumentiert. Das bedeutet **nicht** allgemein `sicher rückrollbar` oder `ohne Hardwarezugriff rettbar`.
- Firmwarewechsel überschreibt den Hersteller-Anwendungsstack. Stock-LAN-API/Cloud/Google-/Hersteller-App-Funktionen fallen gewöhnlich bis zu einer erfolgreichen Rückkehr zur Originalfirmware aus.
- Störungen von Bootloader, Partitionstabelle, Boot-/WLAN-Konfiguration oder Stromversorgung können die reine OTA-Wiederherstellung unmöglich machen. Backup/Serienzugang/Fallback müssen **modell- und platinenversionsgenau** nachgewiesen sein.

Quellen: https://github.com/syssi/esphome-yeelight-ceiling-light/blob/main/docs/flashing-over-the-network.md und https://esphome.io/components/ota/

### Für `ceiling1` und `color4`

**Kein belastbarer Nachweis**, dass der ESP32-OTA-/Rückwechselweg oben auf diesen Modellen funktioniert. Ob eine Original-Firmware überhaupt signiert/geprüft wird, ob Dumps exportierbar sind und ob UART/JTAG/SPI sicher zugänglich wären, bleibt **offen**. Drittquellen mit Firmwaredateien sind kein Vertrauensnachweis (Integrität, Herkunft, Lizenz und Zielrevision prüfen).

**Architekturentscheidung R2:** Solange Hardware-ID/Boardrevision, Flashlayout, Integrität, Boot/Backup/Rollback und Herstellerfunktionen nicht nachweislich beherrscht werden, **keine** Flash-, OTA-, Dump- oder Schreibexperimente auf produktiven Lampen.

## 4. Eigene Firmware: zwei unterschiedliche Forschungswege

### Weg A – Originalcontroller erhalten

Vorteile: mögliche Erhaltung der Schaltung, geringer zusätzlicher Hardwareaufwand. Risiken: unbekannte Chipplattform, proprietäre Bootkette/Signaturen, nicht frei modifizierbares Image, Firmware-/Cloud-Abhängigkeiten, schwieriger Recovery-Pfad. Vor Machbarkeitsaussage zwingend: exakte PCB-/Chipkennung, verfügbare Entwicklerwerkzeuge/SDK, verifizierter rechtmäßiger und rückrollbarer Testpfad. Für `ceiling1` derzeit **nur Forschungsoption**, kein konkreter Flashkandidat.

### Weg B – Originalcontroller vollständig ersetzen

Für YLXD01YL existiert ein Fremdprojekt mit Seeed XIAO ESP32-C6, ESPHome, verschlüsselter BLE-Fernbedienung (Bindkey), LD2410-Präsenzsensor und BH1750-Helligkeitssensor. Dadurch werden lokale API, MQTT/Home Assistant, Sensorautomation, eigene PWM-Effekte und OTA prinzipiell erreichbar.

**Kosten/Verluste:** Eingriff in Netzspannungsgerät, Modifikation der Elektrik/EMV, Original-Google-/Cloud-/Yeelight-Funktion nicht mehr selbstverständlich. Die neue BLE-Fernbedienung benötigt passende Bindings/Schlüssel; ohne deren Verfügbarkeit kein 1:1-Ersatz. Google Home wäre über eine gesonderte Integration erneut zu evaluieren; **Matter wird nicht automatisch durch ESPHome bereitgestellt**.

**Architekturentscheidung R3:** Controller-Ersatz zunächst ausschließlich als **separater spannungsarmer Bench-Prototyp / Dokumenten- und Emulatorarbeit**, nicht auf einer produktiv installierten 230-V-Leuchte.

## 5. Zusätzliche Funktionen und Messkriterien

| Ziel | OEM-LAN ohne Flash | Alternative Firmware | Mess-/Abnahmekriterium |
| --- | --- | --- | --- |
| Lange Musik-/DJ-Lichtläufe | Native Flows, wo unterstützt; `set_music` mit Reverse TCP | Zeitgesteuerte lokale Render-Engine, API/MQTT | >30 min ohne Quota, STOP und Originalzustand sicher |
| Räumliche Lauflichter | hostseitig pro Gruppe/Capabilities, begrenzte Latenz | geplante absolute Zeitstempel und lokale Scheduler | Synchronversatz in Messreihen erfassen; kein unbelegtes SLA |
| Feinere Farbverläufe / Dimmung | Geräteabhängige OEM-Grenzen | LED-Treiber/PWM-Charakteristik angepasst | Flimmern, Untergrenze, Wärme, Strom & optische Rückmeldung messen |
| Netzwerk-Selbstheilung | passive Erkennung + vorsichtige, nur nachweisbare Diagnosen | Reconnect-State-Machine, Watchdog, WLAN-Fallback | Wiederverbindung nach AP-/DHCP-/WAN-Störung ohne gefährliche Reboots |
| Präsenz / Helligkeit | gesonderte Sensoren nötig | LD2410/BH1750 am Controller-Ersatz | reproduzierbarer Ereignis-/Sensor-Test |
| Lokaler Betrieb, Fernbedienung, Google | heutige Yeelight/Google/BLE-Funktionen erhalten | neue lokale API plus getrennte BLE/Google-Kompatibilität | Parität für Schalten/Dimmen, keine unbemerkten Funktionsverluste |
| Wartbare Updates | offizielle Hersteller-App | abgesichertes ESPHome OTA, Flash-Backup und Rollback | prüfbare Signatur/Hashes, Rescue- und Recovery-Prozedur |

**Nichtziele:** Remote-Angriff gegen fremde Lampen, unautorisierte Cloud-/Kontozugriffe, blindes Umgehen von Firmware-Signaturprüfungen, Flashen ungeprüfter Images, Veröffentlichen proprietärer Hersteller-Binaries, elektrischer Eingriff an installierten 230-V-Geräten.

## 6. ceiling1 Offline-Diagnose als separate Priorität

Ein funktionierender BLE-Controller bedeutet **nicht automatisch**, dass das WLAN-Modul selbst defekt ist: Die Bluetooth-Logik kann bei gestörtem WLAN, fehlendem Cloud-Dienst oder blockiertem LAN-Port weiterlaufen. Die Diagnose folgt #29 und klassifiziert: (1) WLAN-Assoziation weg, (2) assoziiert aber IP/TCP gestört, (3) lokaler Port erreichbar aber Cloud offline, (4) Quota-/Command-Lockout. Bei der FRITZ!Box **aktive WLAN-Assoziation, nicht bloß eine alte DHCP-Lease**, verifizieren.

**Keine** dauerhaften Wiederholungs-Kommandos, power-cycles, Factory Resets oder Router-Änderungen zur Fehlersuche ohne gezielte, dokumentierte Benutzeraktion. Exakte RSSI-/FW-/IP-Daten nur lokal, niemals in öffentlichem GitHub.

## 7. Entwicklungsphasen, unabhängig von der App-Entwicklung

1. **P0** Laufende Softwarestabilisierung #28 und `ceiling1`-Diagnose #29 respektieren. Keine neue Funk-/TCP-Last durch Firmware-Forschung.
2. **P1 – read-only Inventar:** `model`, `support`, Firmwareversion (nur aus bereits vorhandenen vertrauenswürdigen Daten), Hardware-SKU/Revision, lokale Capability-Liste; Gerätedaten bleiben privat. Liste mit **Confidence**-Attributen anreichern.
3. **P1 – Referenzarchitektur:** Firmwareadapter-Interface / definierte Capability-Matrix / klarer Modus `OEM_LAN`, `ALT_FIRMWARE_LOCAL`, `UNKNOWN`. Simulation, ohne echte Geräte umzuprogrammieren.
4. **P2 – Offline-Reproduktion:** lokaler simulierter DHCP/WLAN/TCP/Cloud-Ausfall; passive Proben; dokumentierte Router-A/B-Versuche beim echten Fehler. Mehrtägige Beobachtung ohne zusätzliche Befehlsflut.
5. **P2 – Firmware-Forschung:** MediaTek-Pin-/Werkzeug-/Bootloader- und ESP32-Alternativen fachlich verifizieren, Lizenzen, proprietäre Firmware-Grenzen, OTA-Authentizität und Backup/Recovery pro Modell dokumentieren.
6. **P3 – isolierter Bench-Demonstrator:** nur nach separate Sicherheits-/Hardwarefreigabe; keine produktive Lampe. Erst danach Entscheidung über eigene Firmware oder Controller-Tausch.

**Definition of Done für einen späteren Firmware-PR:** exakte Hardware-ID, nachvollziehbare Source-/Toolchain-Pins, reproduzierbarer Build, statische und simulierte Positiv-/Negativtests, Protokoll-/LED-Charakterisierung, dokumentierter Rollback, Parität von Yeelight/Google/BLE **oder ausdrücklich akzeptierte Änderungen**, sicherer Umgang mit Netzspannung und separate Hardwareabnahme.

## Forschungsstand (2026-11-01)

- **Erledigt:** extern verifizierte Quellen und SKU-/Migrationsmatrix; OTA-Fremdprojekt-Fund; Unterschiede Original-LAN/Music-Mode vs. echte Firmware; modellgenaue Sicherheitsgrenzen und Reihenfolge.
- **Nicht erledigt:** eigener Lampen-Firmwaredump, Bootloader-/PCB-/Chip-Abnahme unserer `color4`/Streifen/ceiling1, Hersteller-Firmwareanalyse, ausführbarer eigener Firmwarebuild, echter Hardware-/Netzwerktest, Google-Home-/BLE-Parität nach Flash, mehrtägiger Offline-Nachweis.
- Diese Dokumentation macht Firmware-Recherche nachvollziehbar und unterbindet riskante Modellverallgemeinerungen; sie ist **kein** experimenteller Erfolgsausweis.
