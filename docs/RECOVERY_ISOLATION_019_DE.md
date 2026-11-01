# Yeelight Loft DJ v0.1.9 – einzelne fehlgeschlagene Wiederherstellung isolieren

## Reproduktion (Android-Screenshot, 01.11.2026)
- `stop_cf` für eine Lampe mit IP-Endung `.134` fehlgeschlagen;
  `flowing=unbekannt`, Fehlermeldung `0/1`.
- UI meldete fälschlich, dass **jedes** Identifizieren vorübergehend gesperrt sei.
- Kopfbereich war auf `PREVIEW` fest eingestellt; Fußzeile zeigte weiterhin
  `v0.1.6`, trotz tatsächlich installiertem v0.1.8 (Build 9).
- Vorher: drei Esstischlampen funktionierten. Bei größeren Gruppen traten
  Verbindungs-/Wiederherstellungsprobleme und Folgeblockaden auf.

## Korrekturen
1. **Pro Lampe statt global:** Die gespeicherten `running-flow-ips` und
   `pending-scene-recovery` bleiben erhalten, wenn `stop_cf` bzw. die
   Zustandsabfrage nicht bestätigt wurden. Nur die betroffene IP darf
   nicht durch neue Szenen oder manuelle Änderungen überschrieben werden.
2. **Teilweiser Stopp:** Alle erreichbaren Geräte werden weiterhin gestoppt
   und wiederhergestellt. Ein fehlgeschlagenes Gerät setzt einen sichtbaren
   Hinweis, aber wirft keinen globalen Ausnahmefehler mehr.
3. **Start nach Fehlversuch:** Neue Lichtläufe stoppen zunächst laufende
   Teilnehmer, **schließen verbleibende ungeklärte IPs aus** und starten
   erreichbare Teilnehmer. Kein Überschreiben zuvor gesicherter Snapshots:
   `setRecoveryJournal(old + new)` und `setRunningIps(old + new)`.
4. **Identifizieren/Manuell:** Nur betroffene Lampen sind gesperrt;
   Lampen ohne offene Sitzung bleiben bedienbar. Reine lokale
   Gruppen-/Lichtweg-Bearbeitung bleibt möglich.
5. **Wiederherstellung:** Die Anzeige nennt offene Lampen einzeln
   mit Fehlerursache, statt pauschal alle Lampen zu sperren.
6. **Version:** Kopf- und Fußzeile verwenden beide
   `BuildConfig.VERSION_NAME/VERSION_CODE` aus dem Android-Build.
   Gradle erzeugt BuildConfig explizit; kein hartcodierter
   `PREVIEW`/`v0.1.6`-Text mehr.

## Sicherheitsgrenze
Eine offline befindliche Lampe wird **nie stillschweigend als gestoppt oder
wiederhergestellt markiert**. Ihr lokaler Snapshoteintrag bleibt erhalten.
Es gibt absichtlich keinen unsicheren Button »Erfolg erzwingen«. Beim
nächsten Wiederherstellungsversuch kann die Lampe nach Rückkehr ins WLAN
ordnungsgemäß gestoppt werden.

## Hardwareprüfung
1. v0.1.8 nachstellen: eine offene unbestätigte Lampe (z.B. `.134`).
2. Mit v0.1.9 andere erreichbare Lampen identifizieren und deren
   Gruppen/Mitgliedschaft bearbeiten – ohne Löschen von App-Daten.
3. Lichtlauf auf drei bekannten Esstisch-Lampen starten, stoppen.
4. Größere Gruppe auswählen: unbestätigte Lampe wird im Lampenbericht
   mit »Geschützt« angezeigt, andere Lampen sollen animieren.
5. `.134` wieder erreichbar machen, Wiederherstellung erneut
   ausführen und prüfen, dass das Journal erst bei bestätigtem Erfolg
   gelöscht wird.
6. Kopf/Ende der App: Version 0.1.9 (Build 10).

Die Ursache, **warum** `.134` nicht antwortet, ist noch nicht
hardwareseitig geklärt (offline, LAN-Steuerung, Firmware/Port oder
Erreichbarkeit); die App darf dadurch trotzdem nicht global einfrieren.
