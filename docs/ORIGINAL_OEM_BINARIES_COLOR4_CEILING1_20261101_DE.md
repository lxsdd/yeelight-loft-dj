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
| Originalformat | ESP32 ESP-IDF App, sieben Segmente | **`MMM`-FOTA mit drei erfolgreich entpackten LZMA-Abschnitten** |
| Eigenständige interne Integrität | ESP-IDF-XOR **PASS**, eingebetteter SHA-256 **PASS**, 4-Byte-Xiaomi-CRC **PASS** | **Alle drei SHA-1-Checks und LZMA-Entpackungen PASS** |
| Neue Original-Hardware-/Chipbehauptung möglich? | Chip-ID=0 im ESP-IDF-Header = ESP32 | Chip aus Container nicht gesichert; externe Community ordnet diese Produktversion MT7697 zu |
| Aus Originaldatei ableitbarer Quota-Befund | Echte Meldung `client quota exceeded` im LAN-Fehlertext-Katalog | **`client quota exceeded` im entpackten Hauptprogramm** |
| Aktiver Firmware-Patch, OTA oder Rücksicherung geeignet? | **NEIN, ungeprüft** | **NEIN, ungeprüft** |

### Color4 – neue tatsächliche Code-Artefakte

- Das unveränderte OEM-Image enthält einen ESP-IDF-App-Deskriptor `miio_app`, App/IDF-Verweis `bee4d0e`, Build-Zeit **06.01.2022 14:14:55**. Header-Magic `0xE9`, sieben valide Segmente; alle internen Integritätsstufen bestanden (XOR + SHA-256 + Hersteller-CRC).
- Die wörtliche Meldung **`client quota exceeded` liegt bei Datei-Offset `0xFD74`** und der geladenen Adresse **`0x3F40FD74`**. Die Firmware hält einen **direkten Vier-Byte-Pointer** auf diesen Text bei Datei-Offset **`0x3E350`** in einer LAN-Fehlertexttabelle zusammen mit `invalid command`, `method not supported`, `invalid parameter`, `out of memory`, `fail`.
- Im Klartext sind eigene Yeelight-LAN-Handlernamen `lan_ctrl_start`, `lan_ctrl_init`, `lan_clnt_recv`, `handle_lan_command` und `lan_open_mchannel` vorhanden. **Nachgewiesen ist die OEM-LAN-Quota-Fehlerklasse, noch nicht deren Timer-Rearm-Code**. Der Bericht über den 49,7-Tage-Bug an einer anderen Produktlinie darf **nicht** als automatischer Beweis derselben Codefunktion in `color4` ausgegeben werden.
- Obwohl das ESP-IDF-Image strukturell gültig und kataloggleich ist, sind die konkreten **Secure-Boot-eFuses, Originalsignatur-Prüfkette, OEM-OTA-Akzeptanz, NVS/Pairing-Sicherung und sichere Rücksicherung** der vorhandenen Nutzerlampen noch **NICHT** bestätigt. **Kein beliebiger Binär-Bytepatch, kein Abschalten des Quota-Limits.**

## Wichtige Korrektur: Ceiling1-Originalcode ist vollständig LZMA-entpackbar

**Dieser nachträglich verifizierte Befund ersetzt alle älteren Aussagen, der MMM-Container sei undekodierbar, eventuell verschlüsselt oder der Quota-Fehlertext nicht zugänglich.** Beim tatsächlichen Nutzer-Originalimage wurde das MediaTek-FOTA-Headerformat aus [dieser unabhängigen Formatdokumentation](https://gist.github.com/Intyre/d72fc40df079611ed78a4d8c18e45858) erkannt: Drei 32-Byte-Deskriptoren mit Dateioffset, Ladeadresse, komprimierter Länge, Partitionsgröße, 20-Byte-SHA-1-Feld und LZMA-Flag.

| FOTA-Abschnitt | Datei-Offset | Header-Ladeadresse | komprimiert | entpackt | SHA-1 des komprimierten Abschnitts |
|---|---|---|---:|---:|---|
| 0 – Hauptfirmware | `0x9C` | `0x71000` | 445573 Bytes | **782928 Bytes** | **PASS** |
| 1 – weiteres Image | `0x6CD35` | `0x8000` | 204349 Bytes | **288986 Bytes** | **PASS** |
| 2 – Zusatz | `0x9EB86` | `0x20A000` | 86 Bytes | **64 Bytes** | **PASS** |

Alle drei LZMA-Streams wurden **im Arbeitsspeicher vollständig dekomprimiert**, erreichten das Dateiende ohne überschüssige Bytes und blieben unter ihrer jeweiligen angegebenen Partitionsgröße. Die im FOTA-Header als „signature“ bezeichneten 20-Byte-Felder stimmen bytegenau mit **SHA-1 der komprimierten Sektionen** überein. **SHA-1 ist hier eine Integritätsprüfung, keine verifizierte Hersteller-Digitalsignatur.**

**Direkter neuer Codebeleg:** Im entpackten Hauptimage liegt die Herstellerfehlermeldung `client quota exceeded` bei Offset **`0x9D2B3`**, zugehöriger *Header-Adressen-Kandidat* **`0x10E2B3`**. Daneben stehen `invalid command`, `method not supported`, `invalid parameter`, `out of memory` sowie `lan_ctrl_task`, `start_lan_ctrl`, `cfg_lan_ctrl`, `get_prop` und `set_music`. Damit ist **auf beiden tatsächlich vorliegenden OEM-Images** der LAN-Quota-Fehlertext nachgewiesen. Dieselbe Quota-Timerimplementierung oder der genaue 49,7-Tage-Bug im Maschinencode sind **noch nicht** belegt.

Original-MTK-Binärinhalte werden **weder entpackt in GitHub noch als zusätzliches ZIP veröffentlicht**. Neues Skript `tools/mediatek_mmm_evidence.py` validiert rein offline LZMA/Segmentgrenzen/SHA-1 und berichtet ausschließlich Metadaten und ausgewählte bekannte Strings, keine Hersteller-Codebytes. 12 synthetische Regressionen in CI. **Kein Flashen, OTA, Secure-Boot-Bypass oder angeblich freigegebener Patch.**

## Neue reproduzierbare Offline-Analyse, ohne OEM-Code im Repo

`tools/oem_image_evidence.py` erkennt die **beiden konkret verifizierten Hash-/Größen-Kombinationen** und untersucht ESP32-Imageheader, Segmentgrenzen, XOR, SHA, CRC, App-Deskriptor, Quota-String und direkte Referenzen; für MediaTek-`MMM` außerdem `tools/mediatek_mmm_evidence.py` mit kontrollierter LZMA-Dekompression und SHA-1-Kontrolle. Alle Befunde werden über Standardausgabe als JSON ausgegeben, die Originaldateien werden nur gelesen. Keine Netzwerkbibliothek, keine Geräteadressen, keine OTA-/Flash-/Patch-Befehle. Bei jedem Resultat bleiben `installable=false`, `oem_signature_verified=false` und `quota_timer_root_cause_proven=false`.

Auf den beiden tatsächlichen Uploads wurde zusätzlich ein lokales, noch detaillierteres Prüfwerkzeug erfolgreich ausgeführt; **die Originalimages werden absichtlich NICHT Bestandteil der CI**. Die **15 ESP32-/Katalog- und 12 zusätzlichen MediaTek-FOTA-Regressionsfälle** laufen in `.github/workflows/android.yml` zusammen mit allen bisherigen Color4-/Ceiling1- und Android-Regressionen.

## Priorisierter echter Reparaturpfad und unvermeidliche Nachweise

1. **Color4 zuerst:** Die fehlerauslösende Firmwaremeldung ist im ESP32-Binary identifiziert; nun ist statisches **Xtensa-Disassembly und Code-/Daten-Xref** vom LAN-Dispatcher zum tatsächlichen Refill-Timer erforderlich. Quota-Schwellwerte und Cloud-/Herstellerfunktionen müssen erhalten bleiben.
2. **Ceiling1:** Die nun **vollständig dekodierten** MediaTek-Teilimages mit Cortex-M4-/ARM-Disassembly und Code-/Daten-Xrefs zum Quota-Timer untersuchen. SHA-1 im FOTA-Header beweist weder OEM-Signaturakzeptanz noch sicheres Recovery.
3. **Beide:** Signatur-/Secure-Boot-/Recovery-Gates und Tests der Original-Yeelight-/Google-/HomeKit-/BLE-Funktionen auf **separaten ausdrücklich freigegebenen Laborgeräten** abnehmen. Ohne verifizierten Rückweg bleibt **Flashen ausgeschlossen**.
4. **Loft DJ:** Produktive Quota-Quarantäne-/Rate-Limit-Korrektur bleibt im separaten PR #31. **Ein konservativer Client heilt keinen nicht mehr neu bewaffneten Firmware-Timer.**

**Fazit:** Die Originalimages sind authentisch im Sinn von **Kataloghash-/Größengleichheit**, nicht im strengeren Sinn einer bestätigten OEM-Signatur. Sie zeigen einen echten `color4`-LAN-Quota-Fehlerpfad und bei `ceiling1` **ebenfalls den echten LAN-Quota-Fehlertext nach erfolgreichem MediaTek-FOTA-LZMA-Entpacken**. **Eine modifizierte, sicher wiederherstellbare Originalfirmware kann derzeit noch nicht bereitgestellt werden.**

## 01.11.2026 – Produktfehler ausdrücklich von Color4 trennen

**Verbindliche Nutzeranforderung:** `ceiling1` (YLXD01YL) hat primär **sporadische WLAN-Ausfälle/fehlende Wiederanmeldung sowie zeitweise Yeelight-/Google-Home-/Sprachsteuerungs-Unerreichbarkeit**. `client quota exceeded` wurde an dieser Deckenlampe nicht als dominantes Nutzerproblem beobachtet. **Dagegen ist `color4` (1S RGB) das tatsächliche schnelle LAN-Quota-/Farbsteuerungsproblem.** Beide OEM-Binaries enthalten die generische Fehlerzeichenkette; das liefert **keinen** Anlass, dieselbe Root Cause oder dieselbe Reparatur anzunehmen.

Die ursprüngliche Priorisierung „Ceiling1-Quota-Timer zuerst“ war daher für das Produktziel falsch. Weitere Forschung bei ceiling1 richtet sich auf **WLAN-Station-Disconnect und Auto-Reconnect, DHCP-/IP-Stack, WLAN-N9-/Watchdog sowie nachgeordnete Cloud-/App-Sitzungen**. Besonders wichtig sind AP-aktuell-verbunden vs. historischer FRITZ!Box-Logeintrag vs. DHCP-Lease, Cloud-Steuerbarkeit und BLE; mehrere Zeitpunkte dürfen nicht scheinbar kausal vermischt werden.

**Neue echte Binärbefunde** werden im getrennten [WLAN-Problempfad-Bericht](CEILING1_WLAN_ROOT_CAUSE_RESEARCH_DE.md) dokumentiert. Für color4 bleibt [Issue #32](https://github.com/lxsdd/yeelight-loft-dj/issues/32), für ceiling1 [Issue #29](https://github.com/lxsdd/yeelight-loft-dj/issues/29) maßgeblich. Kein OTA oder WLAN-Recovery-Schreibversuch.
