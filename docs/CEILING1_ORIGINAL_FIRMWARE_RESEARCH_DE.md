# ceiling1 (YLXD01YL/Luna): Original-Firmware nur lesend beschaffen und untersuchen

Stand 2026-11-01. **Keine OTA-/Flash-Freigabe, keine Geräteanfrage.** Ziel ist die separate Erforschung der wiederkehrenden WLAN-/Cloud-Ausfälle der älteren Deckenlampen ohne Verlust von Yeelight-/Google-Home-/BLE-Funktionen.

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

## Modellunterschied zum Color4

- **ceiling1 = yeelink.light.ceiling1 / YLXD01YL**, ältere Dual-WLAN/BLE-Deckenlampe (Hersteller: https://www.mi.com/yeelight-ceilinglamp/specs).
- Ein openHAB-Gerätebericht nennt für `ceiling1` mit `1.5.9_0184` bzw. `1.5.9_0189` **MediaTek MT7697** als Hardware, **nicht** ESP32: https://community.openhab.org/t/xiaomi-and-yeelight-item-firmware-summary/87509 . Das ist ein Nutzer-/Communitybefund, kein eigener Chip-Readback.
- Der aktuelle syssi-Xiaomi-Cloud-Firmwarekatalog enthält `ceiling1 1.5.9_0189`, **650224 Bytes**, **MD5 `0324c98a9bfde8faf531ab9014231d99`**, Chip nicht identifiziert: https://github.com/syssi/esphome-yeelight-ceiling-light/blob/9b2ce3e00c6e9759fff2f40f1f70a6cd0bd676dc/docs/stock-firmware-catalogue.md .
- Ein unabhängiger historischer Firmwareindex listet zusätzlich **`2.0.6_0193` Release 08.12.2020** als ungefähr 730,4 KiB: https://vacuum.mindsolo.net/en/firmwares/2727 . Für diese Variante liegt in der unabhängigen syssi-Tabelle kein vollständiger Referenz-MD5 vor; ein späteres Cloud-MD5 ist daher **keine unabhängige Prüfsumme**.
- Besonders relevant: Yeelight schrieb am 08.12.2020 über die geplante **`2.0.6_xxxx`-Serie für ältere Deckenlampen mit Offline-/Kommunikationsproblemen**, teils mit eingeschränkter Beta-Verteilung: https://forum.yeelight.com/t/topic/21632 . 2022 meldeten Besitzer für die angebotene `2.0.6_0193` wiederholt Download/Update bei 0%, verbunden mit Mi-Home-Backend-Problemen: https://forum.yeelight.com/t/topic/30609 . **Keines der Archive belegt, was auf dem individuellen Gerät aktuell installiert ist oder ob die historische Firmware alle Bugs behebt.**

## Windows-Abruf – separat von color4

Ein **separates, lokal bereitgestelltes** Paket `Yeelight-Ceiling1-Originalfirmware-ReadOnly-Windows.zip` nutzt die zuvor überprüfte und an genau einen Commit gepinnte QR-Login-Bibliothek. Es enthält **keine Firmware-Binärdatei** und keinen Authentifizierungstoken.

- Mit QR-Anmeldung **genau eine** geräteneutrale lesende Cloud-Metadatenanfrage `/home/latest_version` für `yeelink.light.ceiling1` beim Xiaomi-Server in Deutschland.
- **Keine** Xiaomi-Home-Geräteliste, `miIO.ota`, `/home/devupgrade`, lokale TCP-/UDP-Geräteanfrage, App-Firmwareupdate oder Routeränderung.
- Erlaubt ausschließlich explizite bekannte Versionsantworten `1.5.9_0189` und `2.0.6_0193`; bei einer anderen Version wird **ohne Download** abgebrochen (kein stiller Versionsersatz).
- Für `1.5.9_0189` wird der MD5 und die Größe zusätzlich gegen die feste **unabhängige** Katalogreferenz geprüft.
- Für `2.0.6_0193` existiert nur ein Cloud-MD5 und ein **plausibler** 700000–800000-Byte-Größenkorridor; die Ergebnisstufe heißt ausdrücklich `CLOUD_MD5_AND_PLAUSIBLE_SIZE_ONLY`. Keine OEM-Authentizitäts- oder Signaturbehauptung.
- Der CDN-Download ist auf HTTPS-Subdomains von `home.mi.com` beschränkt; keine Weiterleitungen, 32-MiB-Maximalgrenze, kein stilles Überschreiben, keine Ausgabe von signierten Download-URLs. QR-Bild wird nach dem Lauf gelöscht, Login-Sitzung wird nicht auf Platte gecacht.
- ZIP wurde lokal auf gültiges Archiv überprüft; 24 **synthetische** Offline-Regressionen des Windows-Pakets bestanden. Ein tatsächlicher Windows-Xiaomi-QR-Lauf wurde **nicht** im Entwicklungscontainer ausgeführt. Die vorhandene color4-Datei des Anwenders wird nicht angefasst.

## CI-geprüfter, rein offline OEM-Integritätsprüfer

`tools/ceiling1_firmware_research.py` ist bewusst ein **anderer** Parser als `tools/color4_fw_image_info.py`. Er unterstellt für MT7697 keine ESP-IDF/ESP32-Partitionsstruktur. Er nimmt ausschließlich lokale Versions-/Cloud-MD5-Daten und eine vorliegende Datei an, berechnet MD5/SHA-256 und bewertet optional die vier letzten Bytes als Xiaomi-CRC32 (zero-init, no final inversion), wie für zwei andere OEM-Images dokumentiert: https://github.com/syssi/esphome-yeelight-ceiling-light/blob/main/docs/flashing-over-the-network.md . Ein CRC-Match ist **keine** Hersteller-Signatur. Alle Rückgaben setzen `installable=false`, `manufacturer_signature_verified=false` und `oem_recovery_proven=false`.

Das Forschungsskript und zehn synthetische Python-Regressionen sind dem öffentlichen Android-CI-Workflow hinzugefügt. Keine Originalfirmware oder Nutzeridentifikatoren befinden sich im Repository.

## Noch offen: tatsächlicher Firmware-Fix

**Erst nach erfolgreichem Empfang und lokaler Offlineanalyse eines unveränderten Images** können wir bei `ceiling1` den vermutlich relevanten MT7697-Firmwarecontainer, die Timer-/WLAN-/Cloud-Routinen, eventuelle Signaturen sowie die Möglichkeiten einer vollständig sicheren Wiederherstellung überprüfen. Insbesondere ist aus einem `2.0.6_0193`-Archivtreffer **nicht** ableitbar, dass diese Firmware erfolgreich ausgerollt oder auf die betroffene Lampe installierbar ist. Ohne verifizierte Wiederherstellung und getrennte ausdrückliche Hardwarefreigabe **kein** OTA, Downgrade oder Patch.


## Klarstellung zur Priorität nach Nutzerkorrektur (01.11.2026)

Die Entpackung des OEM-Ceiling1-Images und die Zeichenkette `client quota exceeded` werden **nicht** als Nachweis einer Quota-Störung dieser Deckenlampe behandelt. Das konkrete Nutzerproblem ist das **teilweise dauerhaft verschwundene WLAN und ausbleibende Wiederverbinden**, zuweilen mit funktionierendem BLE. Der Quota-Zustand gehört bei dieser Entwicklung zu **Color4**. Die Ceiling1-Firmware wird deshalb primär hinsichtlich **Wi-Fi-Eventhandlern, Reconnect, DHCP, Watchdog und Cloud-Session** betrachtet. Aktuell keine Verbindung zur Fehlerursache belegt. Mehr dazu: [Ceiling1-WLAN-Forschungsakte](CEILING1_WLAN_ROOT_CAUSE_RESEARCH_DE.md).
