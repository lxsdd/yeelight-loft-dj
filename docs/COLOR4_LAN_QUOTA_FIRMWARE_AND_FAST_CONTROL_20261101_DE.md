# Color4 2.1.7_0039 · Original-LAN-Quota, stabile UI-Steuerung und DJ-Transport

**Priorität am 1.11.2026:** Color4-Quota-Failsafe und schnell reagierende Lichtsteuerung vor weiterer Ceiling1-WLAN-Forschung. **Ceiling1 bleibt ein eigenständiger Reconnect-/WLAN-Bug; gleiche Herstellerstrings bedeuten nicht denselben Defekt.**

## Direkt am privaten Originalbinary nachgewiesen

Das vom Nutzer bereitgestellte **Originalimage `yeelink.light.color4-original-2.1.7_0039.bin`** ist exakt **1.502.532 Bytes** groß, SHA-256 `67f075ca909ae9f5c08ff580a4847683d1899e8ba251a7cd75d275b9848f22aa` und MD5 `f13ec12c9d53573748054b36a709e75a`. Es ist das katalogisierte `2.1.7_0039`-Image mit **sieben ESP32-ESP-IDF-Segmenten**, internem SHA-256 und Xiaomi-CRC (alle strukturell gültig). **Kein Hersteller-Secure-Boot-/Rollback-Nachweis**.

**Neuer konkreter Code-Datenbefund:** Die OEM-Firmware enthält genau einen vollständig zusammenhängend nachgewiesenen **LAN-Fehlertabellenvektor** in Segment 1 bei File-Offset **`0x3E33C`**. Seine sieben 32-Bit-Pointer zeigen auf diese vollständigen NUL-terminierten Originalstrings:

| 0-basierter Fehlerindex | Originalfehlertext | Geladene Literaladresse |
|---|---|---|
| 0 | `ok` | `0x3F4099E7` |
| 1 | `invalid command` | `0x3F40FD2F` |
| 2 | `method not supported` | `0x3F40FD3F` |
| 3 | `invalid parameter` | `0x3F40FD54` |
| 4 | `out of memory` | `0x3F40FD66` |
| **5** | **`client quota exceeded`** | **`0x3F40FD74`** |
| 6 | `fail` | `0x3F40FD8A` |

**Wichtig zur Validität:** Im gleichen Binary kommen `fail` **14-mal** und `out of memory` **3-mal** als Null-terminierte Teilstrings vor. Daher ist ein „erster Stringtreffer“ keine zulässige Fehlernummernzuordnung. Der neue Parser `tools/color4_lan_quota_firmware_evidence.py` prüft stattdessen **jedes der sieben Zeigerwörter direkt zurück gegen die gemappten Segmentdaten** und verwirft mehrdeutige oder beschädigte Tabellen. Der Fehlerindex 5 ist **ein Feldindex der OEM-Tabelle**, **nicht** zwingend der numerische JSON-Rückgabecode; dafür ist erst ein Disassembly des aufrufenden Xtensa-Codes nötig. Der String beweist die herstellereigene Firmware-Ablehnung, **nicht** die konkrete Triggerbedingung.

Ebenfalls im tatsächlichen Firmwareimage vorhanden: `lan_ctrl_start`, `lan_ctrl_init`, `lan_clnt_recv`, `handle_lan_command`, `lan_cmd_ack`; `set_music` und `lan_open_mchannel`; `get_prop`, `set_rgb` und `set_bright`; ESP-/FreeRTOS-Timertexte. **Ein vorhandener `set_music`-String ersetzt keine von der Lampe selbst über Discovery als `SUPPORT` gemeldete Funktion.** Ohne Xtensa-Code-Xrefs, RAM-Zustandsdaten und Laufzeittrace ist **keine** exakte Firmware-Quota-Refill-/32-bit-Timerfunktion identifiziert. Das aus `ceiling1` bekannte `2^32 ms`-Refill-Problem bleibt für `color4` eine **plausible, aber unbewiesene Hypothese**.

### Offizielles Originalimage disassemblieren – sichere technische Grundlage

Espressif stellt einen passenden **Xtensa-ESP32-Toolchain-Objdump** als Teil der offiziell dokumentierten `xtensa-esp-elf`-Werkzeuge bereit: https://docs.espressif.com/projects/esp-idf/en/latest/esp32/api-guides/tools/idf-tools.html . Der hier verfügbare allgemeine LLVM-17-Objdump kennt **Xtensa nicht**. Der nächste belastbare Schritt sind **private, rein lokale Xtensa-LX6-Code-/Daten-Xrefs** von der Fehlervektorreferenz in die LAN-Dispatch-/Quota- und Timer-Callback-Pfade. Die Originalfirmware und entpackten OEM-Codebytes dürfen niemals Bestandteil öffentlicher GitHub-CI/Artefakte/PR-Anhänge werden.

## Stabile, performante Kommunikation: zwei streng getrennte Kanäle

| Anwendungsfall | Gewünschter Weg | Begründung / Restriktion |
|---|---|---|
| Einschalten, Helligkeit, einzelne Farbänderung | Klassisches LAN `TCP/55443` mit **einer serialisierten Gerätesitzung** | Bewusst kleine Quota und begrenztes Tempo, keine stillen Retries/zusätzliche `get_prop`-Abfragen pro Änderung |
| Schnelles Ziehen eines RGB- oder Helligkeitsreglers | **UI sofort lokal aktualisieren**, bei Überlast nur den neuesten Wert desselben Reglertyps in das Transportfenster übernehmen | Die schnelle UI-Geste darf **nicht** zu 120 LAN-Befehlen oder einer langen veralteten Kommandowarteschlange führen; RGB und Helligkeit müssen getrennt coalescen |
| Musik / DJ-Lichtlauf | Nur bei über SSDP tatsächlich bestätigtem `set_music` **und** `set_rgb` einen zeitlich begrenzten Music-Reverse-TCP-Kanal verwenden | Echtzeitframes sind nicht auf den klassischen Quota-Kanal zurückzufallen. Music-Start/Stop bleiben getrennte quotierte Steuerkommandos; selbst Reverse-Stream-FPS sind noch an realer Hardware zu qualifizieren |
| Firmware antwortet `client quota exceeded` | **Gerätespezifische persistierte Quarantäne** | Kein automatisches Wiederholen, keine Scans, Readbacks oder Resetversuche gegen diese Lampe |
| Antwort nach Send fehlt / Verbindung bricht ab | **Kein Replay des möglicherweise schon ausgeführten Befehls** | Ambiguous-send-Sicherheit, Recovery und Zustand müssen getrennt behandelt werden |
| Yeelight Classic/Google Home antwortet trotz LAN-Quota | **Separater Cloud-/Home-Pfad, nicht als gesundes LAN werten** | Tatsächliche unabhängige PCAPdroid- und FRITZ!Box-HTTPS-Beobachtung im separaten Produkt-PR #31, keine freigegebene Cloud-API für Loft DJ |

Die **konservativen Policy-Werte** in Draft-PR #31 sind **24 klassische Kommandoversuche pro 60 Sekunden** und **mindestens 1500 ms** Abstand je Lampe. Das ist eine Safety-Policy und **keine** reverse-engineerte OEM-Maximalrate. Die Yeelight-Protokollbeschreibung nennt 60/min je TCP-Verbindung, 144/min über alle Verbindungen und höchstens vier LAN-Verbindungen; diese Aussagen hängen von Firmware/Modell ab. Andere Clients, alte Verbindungen und unbekannte Firmware-Buckets können selbst dann noch eine Sperre verursachen.

**Aktuelle parallele Produktarbeit nicht duplizieren:** PR #31 verantwortet `YeelightLan`, `YeelightTcpSessions`, `DeviceCommandGate`, `YeelightMusicChannel`, #34 das Quarantäne-Journal und #35/#38 den Musik-Frameversand. Der Forschungszweig #30 verändert keinen dieser produktiven Dateien, um inkompatible Fixes und Fremd-Claims zu vermeiden.

## Neuer deterministischer Offline-Verkehrstest (kein Lampenkontakt)

`tools/color4_control_traffic_oracle.py --demo` simuliert **120 RGB-Änderungen in 2,975 s** (25-ms-Abstand). Erwartung:

- **Naiv**: 120 klassische Befehle – für die konservative 24/min-Policy und die Hersteller-Limits ungeeignet.
- **Nur klassischer, geglätteter UI-Kanal**: **3 geplante** Sendeversuche mit 1,5-s-Abstand (erste Änderung, neuester Zwischenstand, letzter Endzustand). Keine alte Warteschlange und keine zusätzlichen `get_prop`-Anfragen. **Kein tatsächlicher Lampen-Ack**; ein UI-Update ist nicht gleich physische Reaktion.
- **Bestätigter Musikrückkanal**: maximal **30 synthetische Frames** bei 100-ms-Mindestabstand (10 Hz) sowie rechnerisch zwei klassische Handshake-Kommandos. **10 Hz ist ein vorsichtiger Forschungsparameter, keine gemessene oder freigegebene Color4-Hardwareframerate**; der tatsächliche Kanal muss mit Hardware separat abgenommen werden.
- **Unbestätigte Musikfähigkeit**: **0 Frames und 0 klassischer Live-RGB-Fallback**.
- **Firmware-Quota-Antwort**: Ab dann 0 weitere automatische klassische Sendeversuche; Quarantäne bleibt bestehen. **Timeout ohne Antwort**: ebenfalls kein stilles Replay, aber klar als unbestätigter Transportzustand klassifiziert – nicht fälschlich als Firmware-Quota.

Die Simulation kann auch 5000 schnelle Eingaben und bis zu 20000 gültige Ereignisse prüfen. Alle Werte sind **rein synthetisch**, keine Lampe wird befragt. Sie liefert nur einen **Abnahme-Oracle/Integrationsvertrag** für die Besitzer der Produkt-PRs. Eine produktive Integration ist **nicht** durch die Tests dieses Forschungszweigs belegt.

## Abnahme-Gates

1. Statische Xtensa-Disassembly der **konkreten** Color4-LAN-Quota-/Timer-Aufrufstellen mit verifizierter Rückreferenz und genauem Firmware-Image-SHA.
2. Software-Hardwarevergleich **nur mit ausdrücklich erlaubtem, intaktem Testgerät**: wenige eindeutige, geordnete Befehle, Prüfung von Hersteller-App-Erreichbarkeit und Music-Mode-SUPPORT, sofortiger Abbruch bei Quota-Antwort.
3. Quarantäne, Command-Pacing, UI-Deduplizierung, begrenzte Sessions und Music-Reverse-Stream mit **SHA-exakten, negativen und positiven** App-Tests – ohne stumme Rückfallpfade. Dauerhaftes Cloud-Setup nur über offizielle, autorisierte Schnittstellen.
4. Eine modifizierte Originalfirmware ist **noch nicht flashbar oder signaturverifiziert**; Secure Boot, OTA-Akzeptanz, Recovery und Original-HomeKit/Google/Yeelight-Kompatibilität bleiben unbestätigt. **Kein Quota-Schutz wird deaktiviert.**

**Ergebnis:** OEM-Fehlervector und Transportbegriffe sind nun tatsächlich byte- und pointer-genau bestätigt, schnelle Bedienung kann synthetisch in eine sicher begrenzte Befehlsfolge überführt werden. Die echte Color4-Timer-Root-Cause und der Nachweis sicherer Dauerhardwaresteuerung stehen noch aus.
