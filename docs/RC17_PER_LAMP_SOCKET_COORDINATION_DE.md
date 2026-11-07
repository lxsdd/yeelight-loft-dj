# RC17 – gerätegebundene LAN-Socket-Freigabe

**Status:** Offline-Quelländerung im bereits autorisierten RC17-Integrations-PR #69. Keine Hardware-/Firmware-Abnahme und keine private APK.

## Fehlerklasse und Korrektur

Normale Status-, Szenen-, Strom- und Music-Handshake-Kommandos verwenden bereits das gemeinsame `DeviceCommandGate` je Lampen-IP. Die explizite TCP-Slot-Freigabe nach einem Music-Handshake bzw. einem einmaligen Music-Ende war zuvor **außerhalb** dieser Sperre möglich. So konnte ein gleichzeitiger, eigentlich seriell gedachter normaler LAN-Aufruf und eine Slot-Freigabe zeitlich überlappen.

`DeviceCommandGate.closeSessionSlot(ip)` serialisiert deshalb die **reine Socket-Freigabe** mit den kommandobudgetierten Aktionen derselben Lampe. `YeelightLan.releaseMusicControlSlot` nutzt diesen Pfad. Für die Freigabe werden **keine** weiteren Protokollbefehle gesendet, keine Quota-Marken verbraucht, keine Wiederholungen ausgelöst und eine vorhandene Firmware-Quarantäne nicht aufgehoben. Andere Lampen besitzen unabhängige Locks. Bereits gekapselte Music-Preflight-/Music-End-Reads schließen den Socket innerhalb ihres bestehenden DeviceCommandGate-Aufrufs.

## Regressionsmatrix (synthetisch)

| Umfang | Prüfkriterium | Nachweis |
| --- | --- | --- |
| 1 Lampe | Ein laufender normaler LAN-Aufruf beendet sich, bevor Music-Slot-Cleanup denselben TCP-Slot schließen darf | Deterministischer JVM-Latch-Test, kein Gerät |
| 1 gesperrte Lampe | TCP-Cleanup auch bei Quarantäne; kein LAN-Kommando, keine Entsperrung, kein Budgetverbrauch | JVM-Test |
| 2 Lampen | Ein anderer Gerätekontext bleibt ausführbar, obwohl die erste Lampe noch arbeitet | JVM-Test |
| 14 Lampen | Music-/Scene-Auswahl nur für die angeforderten Teilnehmer; Quarantäne begrenzt auf die betroffene Lampe | JVM-Test |

**Nicht behauptet:** Ursache der Color4-Firmware-Quota gefunden, vollständige Quota-Heilung, gleichzeitiger echter 14-Lampen-DJ-Betrieb, Pixel-/Standby-/Benachrichtigungsabnahme oder installierbare Classic-APK. Diese Grenzen bleiben in getrennten Hardware- und Classic-Prüfungen.
