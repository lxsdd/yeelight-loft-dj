# Tatsächliche Originalimages: Color4 2.1.7_0039 und Ceiling1 1.5.9_0189 (2026-11-01)

**Quelle:** Zwei Originaldateien wurden durch den Nutzer direkt im vertraulichen Chat bereitgestellt. Diese wurden **rein offline** inspiziert; **kein OEM-Binary wurde in GitHub veröffentlicht, hochgeladen, geändert oder an Lampen versendet**. Alle folgenden Befunde beziehen sich auf die **konkreten unveränderten Daten**. Katalogquelle: https://github.com/syssi/esphome-yeelight-ceiling-light/blob/main/docs/stock-firmware-catalogue.md .

## Herkunfts- und Strukturprüfung

| | color4 | ceiling1 |
|---|---|---|
| Modell | `yeelink.light.color4` | `yeelink.light.ceiling1` |
| Version | `2.1.7_0039` | `1.5.9_0189` |
| Größe | **1502532 Bytes** | **650224 Bytes** |
| Lokale MD5 | `f13ec12c9d53573748054b36a709e75a` | `0324c98a9bfde8faf531ab9014231d99` |
| Unabhängige Kataloggröße/MD5 | **Exakt gleich** | **Exakt gleich** |
| Lokale SHA-256 | `67f075ca909ae9f5c08ff580a4847683d1899e8ba251a7cd75d275b9848f22aa` | `a19801c450c0bd9cd1a3539b6ef4eeabf399b9f99288f86b6b410453c709e3bf` |
| Originalformat | ESP32 ESP-IDF App, sieben Segmente | proprietärer **`MMM\x00`**-Container, Headerkandidat 156 Bytes |
| Eigenständige interne Integrität | ESP-IDF-XOR **PASS**, eingebetteter SHA-256 **PASS**, 4-Byte-Xiaomi-CRC **PASS** | Aus dem Container nicht allgemein bestimmbar; **die Color4-CRC-Variante über Gesamtdaten passt hier nicht** |
| Neue Original-Hardware-/Chipbehauptung möglich? | Chip-ID=0 im ESP-IDF-Header = ESP32 | Chip aus Container nicht gesichert; externe Community ordnet diese Produktversion MT7697 zu |
| Aus Originaldatei ableitbarer Quota-Befund | Echte Meldung `client quota exceeded` im LAN-Fehlertext-Katalog | Verdeckter/nicht lesbarer Payload; keine Quota-Textsuche beweiskräftig |
| Aktiver Firmware-Patch, OTA oder Rücksicherung geeignet? | **NEIN, ungeprüft** | **NEIN, ungeprüft** |

### Color4 – neue tatsächliche Code-Artefakte

- Das unveränderte OEM-Image enthält einen ESP-IDF-App-Deskriptor `miio_app`, App/IDF-Verweis `bee4d0e`, Build-Zeit **06.01.2022 14:14:55**. Header-Magic `0xE9`, sieben valide Segmente; alle internen Integritätsstufen bestanden (XOR + SHA-256 + Hersteller-CRC).
- Die wörtliche Meldung **`client quota exceeded` liegt bei Datei-Offset `0xFD74`** und der geladenen Adresse **`0x3F40FD74`**. Die Firmware hält einen **direkten Vier-Byte-Pointer** auf diesen Text bei Datei-Offset **`0x3E350`** in einer LAN-Fehlertexttabelle zusammen mit `invalid command`, `method not supported`, `invalid parameter`, `out of memory`, `fail`.
- Im Klartext sind eigene Yeelight-LAN-Handlernamen `lan_ctrl_start`, `lan_ctrl_init`, `lan_clnt_recv`, `handle_lan_command` und `lan_open_mchannel` vorhanden. **Nachgewiesen ist die OEM-LAN-Quota-Fehlerklasse, noch nicht deren Timer-Rearm-Code**. Der Bericht über den 49,7-Tage-Bug an einer anderen Produktlinie darf **nicht** als automatischer Beweis derselben Codefunktion in `color4` ausgegeben werden.
- Obwohl das ESP-IDF-Image strukturell gültig und kataloggleich ist, sind die konkreten **Secure-Boot-eFuses, Originalsignatur-Prüfkette, OEM-OTA-Akzeptanz, NVS/Pairing-Sicherung und sichere Rücksicherung** der vorhandenen Nutzerlampen noch **NICHT** bestätigt. **Kein beliebiger Binär-Bytepatch, kein Abschalten des Quota-Limits.**

### Ceiling1 – neuer Format-/Abnahmebefund

- **`MMM\x00`** als Originalcontainer statt ESP32; nachfolgende 32-Bit-Headerfelder `3` und `156` (0x9C). Der Header enthält weitere Zeiger/Offsets, die sich nicht ohne weitere Formatbelege semantisch belegen lassen.
- In den ersten 8192 Payloadbytes ab Offset 156 liegt die Entropie bei **7,9803 Bit/Byte**; keine lesbaren ASCII-Folgen über 15 Zeichen. **Kompression und Verschlüsselung sind damit beide möglich, aber keinesfalls unterschieden oder bewiesen.** Kein Klartextcode für sichere Patchentwicklung aus dieser Datei extrahierbar.
- Der 4-Byte-Trailer stimmt **nicht** mit dem an ESP32 beobachteten Xiaomi-CRC32 über das Gesamtimage überein. Bei einem anderen Container bedeutet das **keine beschädigte Datei**. Die volle Datei stimmt gleichzeitig vollständig mit den **independently katalogisierten 650224 Bytes und MD5** überein.
- Bemerkenswert: Im [Yeelight-Herstellerthread](https://forum.yeelight.com/t/topic/15756) wurden bei `ceiling1` **exakt `1.5.9_0189` und ca. 1199 h Uptime** genannt. Yeelight bestätigte am 07.06.2022 ausdrücklich einen **nicht erneut bewaffneten Refill-Timer nach `2^32` Millisekunden**. Damit liegt nun ein OEM-Image zur **gleichen Firmwareversion** wie in diesem Fehlerbericht vor. Es beweist aber noch nicht die genaue Routine oder ihre Reparierbarkeit.
- Communityhardwarebeleg MT7697: https://community.openhab.org/t/xiaomi-and-yeelight-item-firmware-summary/87509 . MCU-Recovery, Container-/Schlüsselformat, Bootloader-Signaturen bleiben offen.

## Neue reproduzierbare Offline-Analyse, ohne OEM-Code im Repo

`tools/oem_image_evidence.py` erkennt die **beiden konkret verifizierten Hash-/Größen-Kombinationen** und untersucht ESP32-Imageheader, Segmentgrenzen, XOR, SHA, CRC, App-Deskriptor, Quota-String und direkte Referenzen; für `MMM` nur Formatindikatoren und Entropie. Alle Befunde werden über Standardausgabe als JSON ausgegeben, die Originaldateien werden nur gelesen. Keine Netzwerkbibliothek, keine Geräteadressen, keine OTA-/Flash-/Patch-Befehle. Bei jedem Resultat bleiben `installable=false`, `oem_signature_verified=false` und `quota_timer_root_cause_proven=false`.

Auf den beiden tatsächlichen Uploads wurde zusätzlich ein lokales, noch detaillierteres Prüfwerkzeug erfolgreich ausgeführt; **die Originalimages werden absichtlich NICHT Bestandteil der CI**. Die **15 synthetischen** positiven/negativen Tests des öffentlichen Skripts laufen in `.github/workflows/android.yml` zusammen mit allen bisherigen Color4-/Ceiling1- und Android-Regressionen.

## Priorisierter echter Reparaturpfad und unvermeidliche Nachweise

1. **Color4 zuerst:** Die fehlerauslösende Firmwaremeldung ist im ESP32-Binary identifiziert; nun ist statisches **Xtensa-Disassembly und Code-/Daten-Xref** vom LAN-Dispatcher zum tatsächlichen Refill-Timer erforderlich. Quota-Schwellwerte und Cloud-/Herstellerfunktionen müssen erhalten bleiben.
2. **Ceiling1:** `MMM`-Container und möglicherweise komprimierte/verschlüsselte Firmwarepayloads anhand verifizierter MediaTek-OTA-/Bootloaderreferenzen interpretieren. Ohne legitime Dekodierung kein scheinbarer Ersatz-ESP32-Patch.
3. **Beide:** Signatur-/Secure-Boot-/Recovery-Gates und Tests der Original-Yeelight-/Google-/HomeKit-/BLE-Funktionen auf **separaten ausdrücklich freigegebenen Laborgeräten** abnehmen. Ohne verifizierten Rückweg bleibt **Flashen ausgeschlossen**.
4. **Loft DJ:** Produktive Quota-Quarantäne-/Rate-Limit-Korrektur bleibt im separaten PR #31. **Ein konservativer Client heilt keinen nicht mehr neu bewaffneten Firmware-Timer.**

**Fazit:** Die Originalimages sind authentisch im Sinn von **Kataloghash-/Größengleichheit**, nicht im strengeren Sinn einer bestätigten OEM-Signatur. Sie zeigen einen echten `color4`-LAN-Quota-Fehlerpfad und bei `ceiling1` einen nicht offen lesbaren Mediencontainer. **Eine modifizierte, sicher wiederherstellbare Originalfirmware kann derzeit noch nicht bereitgestellt werden.**
