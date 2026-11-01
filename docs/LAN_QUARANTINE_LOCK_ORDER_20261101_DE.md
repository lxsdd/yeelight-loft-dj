# Yeelight-LAN – Quarantäne-Persistenz ohne zyklische Sperren

Stand: 1. November 2026 · unabhängiger Folge-PR auf dem ungeänderten Draft-PR #31.

## Nachgewiesene Quellcode-Risikolage

Bis PR #31 HEAD `a7aae71343f0` war `YeelightLan.writeQuarantine` mit `@Synchronized` auf dem **globalen** `YeelightLan`-Monitor geschützt. `startMusicMode` und `stopMusicMode` verwenden ebenfalls `@Synchronized` und senden dabei Befehle durch einen **per-IP** synchronisierten `DeviceCommandGate`. Umgekehrt kann ein Firmware-Quota-Fehler während eines Gate-Befehls `onQuarantined` aufrufen und über `writeQuarantine` den globalen Monitor anfordern. Das erzeugt einen möglichen ABBA-Deadlock unter parallelem Musikstopp und einem Firmwarefehler.

## Korrektur

- `QuotaPreferenceJournal` besitzt nur einen **eigenen** privaten Monitor; der globale Lampenmonitor wird bei Quarantäne- und Recovery-Schreibvorgängen nicht erworben.
- Alle IP-Mutationen werden weiter in einer serialisierten Read-Modify-Commit-Transaktion gespeichert; unveränderte Daten und das App-Private-Preferences-Format bleiben kompatibel.
- Fehlschlagender synchroner Commit wirft weiterhin einen Fehler. Die per-Lampe-In-Memory-Quarantäne wird dabei nicht aufgehoben; Recovery bleibt auf eine ausdrücklich initiierte Statusabfrage je Minute begrenzt.
- `installQuotaState` liest den Zustand vor Übergabe an das Gate aus, ohne gleichzeitig Journal- und Gate-Monitor zu halten.
- Kein neues Paket, keine Timer, kein Scannen, keine Lampenbefehle, keine Cloud-/Firmwareänderung.

## Qualifikation

JVM-Regressionen gegen verlorene parallele IP-Updates, fehlgeschlagenen Commit und zyklische Global-/Gate-Monitore. Vollständige SHA-genaue Android-JVM-Tests und Debug-Build sind zusätzlich erforderlich. Diese reine Quellcodekorrektur ist **keine** Color4-Hardwareabnahme oder Freigabe des Music-Mode-Praxisbetriebs. Main, Draft-PR #31 und Forschungs-PR #30 bleiben unverändert.
