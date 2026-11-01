# ceiling1/YLXD01YL · WLAN-Abbruch und fehlende Wiederverbindung: separate Root-Cause-Forschung

**Datum:** 01.11.2026 · Repository `lxsdd/yeelight-loft-dj` · Forschung auf Draft PR #30 / Issue #29.

## Verbindliche Problemzuordnung

| Modell | Produktproblem / beobachtete Symptome | Forschungspriorität | Nicht behaupten |
|---|---|---|---|
| **`ceiling1` YLXD01YL/Luna** `1.5.9_0189` | spontanes Abmelden aus dem WLAN oder später keine Wi-Fi-/Cloud-/Google-Home-/Sprachsteuerung mehr; BLE kann unabhängig funktionieren; nach Strom-Neustart mitunter wieder verbunden | **WLAN STA Event-/Reconnect**, DHCP, MediaTek-N9-Driver/Watchdog, Cloud-/MiNet-Sitzung | Aus Text `client quota exceeded` einen real beobachteten ceiling1-Quota-Defekt machen |
| **`color4` RGB 1S** `2.1.7_0039` | `client quota exceeded` nach mehreren lokalen Farb-/Helligkeitsänderungen, danach auch Hersteller-App betroffen | **LAN-Quota-Zustand, Timer-Rearm und App-Burst/Session-Beherrschung** | Den ceiling1-Disconnect durch Color4-Quota-Befunde erklären |

Die Tatsache, dass **beide Original-Binaries denselben generischen Fehlertext enthalten**, ist ein Beleg für gemeinsame Yeelight-LAN-Protokollfunktionen, **kein** Beleg für das tatsächliche Fehlerbild derselben Endgeräte.

## Unabhängige Beobachtungen – ausdrücklich zur gleichen Modell-/Firmwarefamilie

1. **25.09.2020**: Yeelight-Forum, **5 von 10** dauerhaft versorgten Deckenlampen verlieren innerhalb von 1–3 Monaten die WLAN-Assoziation und versuchen keine Neuverbindung; AP-Neustart hilft nicht, Lampen-Strom-Neustart schon. Sowohl **`1.5.9_0184` und `1.5.9_0189`** waren bei funktionierenden und fehlerhaften Lampen vertreten. Das ist ein beinahe direkter Analogfall – aber **kein** Nachweis der Ursache auf dem hiesigen Gerät. https://forum.yeelight.com/t/topic/20179
2. **17.12.2019**: YLXD01YL **`1.5.9_0189`** meldet sich am WLAN an und beantwortet einen Ping, aber Yeelight-App und LAN-Steuerung versagen an bestimmten APs (UniFi AP-AC-LR). **AP-Assoziation und echte Nutzbarkeit müssen getrennt werden.** https://forum.yeelight.com/t/topic/15197
3. **05–06/2020**: Deckenlampen fallen teils etwa stündlich aus und verbinden sich meist wieder; Ausfälle können auftreten, wenn die Wiederverbindung fehlschlägt. Andere Modellvarianten, daher ein Musterhinweis, **keine bestätigte ceiling1-spezifische feste Timerperiode**. https://forum.yeelight.com/t/topic/18057
4. **19.07.2018**: Yeelight-Entwickler berichten ausdrücklich über neue `1.5.9`-Firmware gegen WLAN-Probleme bei überlasteten Netzen oder bestimmten Routern und spätere LAN-Modus-Fixes; daraus folgt nicht, dass `1.5.9_0189` gegen sämtliche WLAN-Probleme immun ist. https://forum.yeelight.com/t/topic/6106
5. **2019**: Andere Besitzer melden mit exakt **`1.5.9_0189`** intermittierende Offline-Zustände; einzelne wurden auf eine Firmware-Whitelist gesetzt, ohne öffentliche Endabnahme des Rückwegs. https://forum.yeelight.com/t/topic/11638

## Tatsächlich untersuchte Nutzer-Originalfirmware

**Modell:** `yeelink.light.ceiling1`, **Version:** `1.5.9_0189`, **MD5:** `0324c98a9bfde8faf531ab9014231d99`, **SHA-256:** `a19801c450c0bd9cd1a3539b6ef4eeabf399b9f99288f86b6b410453c709e3bf`.

Das Original liegt **nur im geschützten Arbeitscontainer** vor; es ist mit dem öffentlich katalogisierten Original identisch. Der MediaTek-MMM-FOTA-Container wurde vollständig in **drei LZMA-Sektionen** entpackt, SHA-1 und Partitionsgrenzen kontrolliert. **Keine Originalcodebytes, Firmware-Images oder privaten Netzwerkinformationen im GitHub-Repository.**

### Nachweisbare Diagnosefunktionen im *entpackten Hauptprogramm*

| Pfad | Echte im Image vorhandene Zeichenketten | Offset im entpackten Abschnitt |
|---|---|---|
| **WLAN-Link-Events** | `wifi_station_disconnected_event_handler` | `0x96DF4` |
| **WLAN-Link-Events** | `wifi_station_port_secure_event_handler` | `0x96DCD` |
| **Stations-Auto-Reconnect** | `Auto connect enabled: try to reconnect` | `0xAD426` |
| **Stations-Auto-Reconnect** | `Try to reconnect to the same BSS` | `0xAD4FB` |
| **WLAN-Treiber/Assoziation** | `Association request to the driver failed` | `0xAC05C` |
| **DHCP** | `DHCP got Failed` / `DHCP got IP:` | `0x96E44` / `0x96E35` |
| **DHCP-Start** | `start DHCP client failed` | `0x898E1` |
| **N9-WLAN-Prozessor** | `N9 may crashed, reboot...` | `0x9D970` |
| **Watchdog** | `Init watchdog failed.` | `0x9B049` |
| **Cloud-/Router-Kommunikation** | `[minet] communicate with router timeout` | `0x9C5D6` |
| **Cloud-/Router-Kommunikation** | `[minet] communicate with router failed` | `0x9C981` |
| **Socket** | `minet socket connect failed` | `0x9C0C4` |

Quellwerkzeug `tools/ceiling1_wlan_firmware_markers.py` meldet ausschließlich **vordefinierte, ungefährliche Strings und deren Offsets**. Vor Analyse prüft es den festen SHA-256 der echten OEM-Version und alle FOTA-Section-SHA1s/LZMA-Daten. Es kontaktiert weder WLAN noch Cloud noch BLE, hat keinen OTA-/Flash-Code und gibt keine proprietären Binaries aus.

**Interpretationsgrenze:** Eine Diagnosezeichenkette kann im Image vorhanden sein, ohne dass dieser Pfad jemals ausgeführt wurde. Für die Ursache wäre eine statische ARM-Thumb-Code-/Daten-Xref vom Trennungsereignis über Zustandsvariablen, Timer/Retry bis zum tatsächlichen Reconnect und ggf. Watchdog-Eskalationspfad erforderlich. **Keiner dieser Codepfade ist bereits als kausaler Defekt nachgewiesen.**

## Passive, ausfallsichere Gegenprobe in der Android-Oberfläche

Neuer `Ceiling1NetworkPathDiagnostic` mit passivem `Ceiling1NetworkPathPanel` **ersetzt im vorhandenen Netzwerkforschungsabschnitt die alte kleinere Statusabfrage**, nutzt nur flüchtige manuelle Eingaben, keine neuen Lampenkommandos.

- **Aktuelle FRITZ!Box-AP-Assoziation** nachweislich vorhanden/fehlend/ungeprüft. Alte Routerereignisse, im Router gespeicherte „bekannte Geräte“ oder DHCP-Leases sind **nicht** dasselbe.
- **Aktuelle Yeelight-App-Steuerung** funktioniert/nicht/ungeprüft; eine Hersteller-App-Reaktion kann über Cloud laufen, sie beweist daher allein keine lokale TCP-55443-Steuerbarkeit.
- **Bereits unabhängig belegter LAN-Dienstzustand** getrennt vom AP-Status; **kein neues aktives Testen** oder Flooding.
- **Aktuelle BLE-Fernbedienung** funktioniert/nicht/ungeprüft. Wenn BLE funktioniert, kann der WLAN-Teil trotzdem blockiert sein.
- Die Beobachtungen müssen ausdrücklich **im selben Zeitraum** gemacht sein; fehlende / zeitversetzte Belege liefern nur `UNKNOWN` oder einen Widerspruch, **nie** „Firmwaredefekt bewiesen“.

### Ergebnismatrix

| Aktuell am AP? | LAN-Dienst? | Yeelight-App? | BLE? | Einstufung |
|---|---|---|---|---|
| **Nein** | nicht bestätigt | offline | funktioniert | WLAN-Assoziation fehlt, BLE weiterhin aktiv → Link/Reconnect/Driver untersuchen |
| **Ja** | bestätigte Antwort | offline | beliebig | Cloud-/App-Sitzung prüfen, nicht automatisch WLAN-Defekt |
| **Ja** | fehlende Antwort | funktioniert | beliebig | lokaler Dienst/IP-Pfad betroffen; funktionierende App ist kein LAN-Beweis |
| **Ja** | fehlende Antwort | offline | beliebig | WLAN assoziiert; IP/DHCP, Netzwerk-/Cloud-Service voneinander trennen |
| **Ja** | ungeprüft | funktioniert | beliebig | aktuell grundsätzlich erreichbar, **LAN unbelegt** |
| **Nein** | bestätigter LAN-ACK | beliebig | beliebig | widersprüchliche Daten, Gerät/Zeiten zuordnen |
| **Unbekannt** | beliebig | beliebig | beliebig | keine Aussage zur momentanen WLAN-Assoziation |

Die Kotlin-Diagnostik schließt Firmware-Ursachenaussagen und sämtliche automatischen Wiederherstellungsmaßnahmen explizit aus. 16 neue JVM-Tests decken AP-/Cloud-/LAN-/BLE-Kombinationen, Zeitverschiebungen, alte Ereignisse, DHCP-Leases und Widersprüche ab. Zwölf synthetische Python-Tests sichern den beschränkten Image-Marker-Scanner.

## Technische Priorität und verbleibende Grenzen

1. **Nicht-invasive Abnahme**: Mit vorhandenen FRITZ!Box-Ereignissen, aktuellen Assoziationsinformationen und Zeitsynchronität den nächsten spontanen Offlinezustand zuordnen, ohne Netzstrom-/Routerreset.
2. **Firmware-Disassembly**: tatsächliche Cortex-M4-Reconnect-/Event-/Retry-/Watchdog-Zustandsmaschine in `1.5.9_0189` statisch analysieren; zu den Diagnosezeichenketten passende Code-Xrefs und Ablaufbedingungen verifizieren. Eventuelle Unterschiede zur historisch bekannten `2.0.6_0193`-Firmware erst mit deren **authentischem Image** untersuchen.
3. **Kein präventiver Patch/OTA**: OEM-Bootloader-Signatur/Recovery und Original-BLE-/Google-/Yeelight-Interoperabilität sind unbestätigt. Ein WLAN-Fix ohne nachgewiesene Rücksicherung wäre kein abnahmefähiges Produkt.
4. **Eigenständige Color4-Lane**: Quota-Refill-/LAN-Drosselung ist ausschließlich in Color4-Research-Issue #32 und separaten Produkt-PRs (#31, #34, #35) zu verfolgen. Kein Auslösen dieser Diagnostik durch WLAN-Verdacht.

**Ergebnis:** Quota und WLAN-Ausfälle sind technisch **klar getrennt**. Das ceiling1-OEM-Image liefert echte Spuren für WLAN-Reconnect, DHCP, Watchdog und Cloud, aber **noch keinen nachweislich korrigierbaren Root Cause**. Dazu sind Disassembly sowie ggf. ein zeitgleicher, nicht-invasive AP-/Geräte-Zustandsnachweis erforderlich.
