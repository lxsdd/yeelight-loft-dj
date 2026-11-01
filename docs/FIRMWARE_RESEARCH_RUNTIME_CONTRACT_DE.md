# Firmware-Forschung: ausführbarer Read-only-Kern und Diagnosevertrag

Stand: 2026-11-01. Forschungs-Draft #30. Ergänzung zu [FIRMWARE_RESEARCH_FEASIBILITY_DE.md](FIRMWARE_RESEARCH_FEASIBILITY_DE.md), LAN-Fix #28 und ceiling1-Offline-Issue #29.

## Was implementiert ist

In `app/src/main/java/io/github/lxsdd/yeelightloftdj/FirmwareResearch.kt` ist ein rein funktionaler Forschungs- und Klassifikationskern ohne Netzwerkanbindung, Android-Service, Hardwarezugriff oder Schreibpfad entstanden. Er ist derzeit **nicht** in die produktive UI verdrahtet: Ergebnisse aus Unit-Tests, keine behaupteten echten WLAN-Messungen.

1. **FirmwareResearchCatalog.researchProfile(model, firmware)** übernimmt ausschließlich angegebene Modell-/Versionskennungen. Er gibt ein **Forschungsprofil** aus, *niemals* eine Flash-Freigabe (`safeToFlash` ist bei allen Ergebnissen `false`, selbst wenn ein Fremdbeispiel denselben OTA-Firmwarestand meldet).
2. **Ceiling1OfflineAnalysis.classify(evidence)** nimmt manuell/anderweitig belegte aktuelle Beobachtungen entgegen und klassifiziert WLAN-Abmeldung, aktive WLAN-Verbindung mit lokalem IP-/TCP-Problem, Cloud-Problem, explizite Quota-Rejektion, widersprüchliche/ungenügende Evidenz oder erreichbares Gerät. Er sendet **keine** Befehle und repariert bewusst **nichts** automatisch.

## Diagnose-Evidenz

`OfflineEvidence.routerAssociatedNow` meint **aktive WLAN-Assoziation in der FRITZ!Box**, keine alte DHCP-Lease. `tcp55443Reachable` meint nur einen tatsächlichen lokalen Port-/Protokollbeleg, nicht vermeintlich jede Cloud-Verbindung. `cloudControlReachable` ist ein unabhängig ermittelter Yeelight-/Google-Cloud-Steuerungsbeleg. `explicitQuotaError` darf nur aus **echter Gerätefehlermeldung** gesetzt werden, niemals aus einem Timeout geschätzt.

`bluetoothRemoteResponds` und `ssdpReplyReceived` sind absichtlich **nicht entscheidungsbestimmend**: Fernbedienung kann ohne WLAN gehen, fehlende Multicastantwort beweist kein WLAN-Problem. Zeitlich nicht zusammenpassende/inkonsistente Resultate ergeben `CONTRADICTORY_OBSERVATIONS` und verlangen erneutes Messen.

Vor einer Übernahme ins UI müssen Anzeige der Evidenzquelle, Zeitpunkt, Opt-in und die **beweisfreie** Behandlung fehlender Router- oder Cloud-Belege sichergestellt sein. Standardmäßig keine WLAN-Probes im Hintergrund, keine kontinuierlichen TCP-Sockets, keine sensitiven Daten in GitHub/Analytics.

## Modell- und Versionssicherungen

- `ceiling1`: nur **extern dokumentierter Controllerersatz** (ESP32-C6-Projekt), keine direkt vorhandene ESPHome-Firmware für MediaTek.
- `color4`: **keine** bestätigte Firmwarealternativroute; SSDP-Modell ist keine MCU- oder Platinenrevision.
- `strip6`: ESPHome-Konfiguration belegt, aber keine Flash-/Rollbackfreigabe für ein nur modellgleich benanntes Gerät.
- `lamp9`, `ceiling10`, `ceilb`, `ceilc`: bekannte modell-/versionsgebundene **Fremd-OTA-Beispiele**. Selbst exakter Versionsvergleich ergibt nur `externalOtaFirmwareExactMatch` und **niemals** Hardwarebestätigung oder Flashrecht. `safeToFlash` bleibt false.
- Unbekannte Modellkennung, fehlerhafte Groß-/Kleinschreibung oder Substrings dürfen keine automatische Eignung ergeben; nur exakt bekannte Kennungen werden wiedererkannt.

Upstream:
- https://github.com/syssi/esphome-yeelight-ceiling-light
- https://github.com/syssi/esphome-yeelight-ceiling-light/blob/main/docs/flashing-over-the-network.md
- https://github.com/bedson87/yeelight_YLXD01YL_xiao_esp32c6_LD2410_BH1750
- https://en.yeelight.com/razer-chroma-integration/ (Razer Chroma: **gesondertes OEM-Funktionspotential** für 1S Color ab dokumentierter Firmware; kein offener, für Android bestätigter Streaming-LAN-Port)

## Automatisierte Regressionen

`app/src/test/java/io/github/lxsdd/yeelightloftdj/FirmwareResearchTest.kt` testet **14 Fälle**: je Familie, exakte Modell-/Firmwarebindung, unbekannte Substrings, Flashverbot, WLAN vs. lokaler Port vs. Cloud vs. Quota, BLE-/SSDP-Negativpfade, widersprüchliche Beobachtung und positives TCP-/Cloud-Beispiel. Pflichttest und Android-Build laufen im vorhandenen öffentlichen Android-CI-Workflow.

**Nicht nachgewiesen:** tatsächliche Chip-/Platinenversion der Geräte, ein Firmware-Dump, OEM-Bootloader-/OTA-Kompatibilität von color4/ceiling1, ein installierbares alternatives Image, Hardware- oder mehrtägige WLAN-Stabilität. Keine Firmware-Aktionen auf produktiven 230-V-Leuchten.

## Nächste belastbare Entwicklungsstufe

Ein späteres opt-in-Diagnose-UI soll **bestehende** LAN-Befunde auswerten und nur auf Nutzerinitiative zusätzliche Router-/Cloud-Belege verknüpfen. WLAN-Assoziation kann die Android-App nicht aus einem gewöhnlichen TCP-Probe erraten; FRITZ!Box-Datenzugriff wäre separat zu autorisieren. Keine automatischen Router-Neukonfigurationen, Restarts, Power-Cycles, WLAN-/Cloud-Anmeldeversuche oder versteckten Probe-Schleifen.
