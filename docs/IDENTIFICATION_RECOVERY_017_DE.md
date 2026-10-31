# v0.1.7 – Identifizieren und Wiederherstellung

## Regressionsursache

Seit den Lichtlauf- und Musikfunktionen sind die Identifizieren-Schaltflächen
absichtlich gesperrt, solange eine Lichtshow läuft oder unbestätigte Lampen-
Wiederherstellungen existieren. Das geschieht auch nach einem App-Neustart.
Der sichtbare Geräte-Tab hatte bisher keinen direkten Wiederherstellungsweg.
Schon eine nicht erreichbare Lampe blockierte damit die Identifikation aller Geräte.

## Korrektur

- Sichtbarer Sicherheits-/Wiederherstellungsbereich auf allen vier Tabs, wenn
  eine laufende oder unvollständig zurückgesetzte Lichtshow vorliegt.
- Direkte Aktion **Lichtshow stoppen & Licht wiederherstellen**, mit
  verständlicher Erklärung der Sperre.
- Eine gespeicherte Wiederherstellung bleibt geschützt, selbst wenn die Liste
  laufender IPs aufgrund eines App-Abbruchs fehlt.
- Wiederholter Stopp berücksichtigt auch privat gespeicherte IPs, deren
  Geräteobjekte inzwischen aus der Anzeige verschwunden sind.
- Musiklicht-Stopp wartet auf laufende RGB-Kommandos, bevor Ursprungsfarben
  wiederhergestellt werden.
- Die Sperre wird **nicht** durch App-Neustart oder erzwungenes Löschen umgangen:
  nur bestätigter Stopp und erfolgreiche Wiederherstellung lösen sie.
- JVM-Regressionstests für Leerlauf, aktive Szene, persistierte Sitzung,
  abgebrochenen Wiederherstellungsjournal-Eintrag und Musikmodus.

## Hardware-Abnahme

1. Einen Lichtlauf über mindestens zwei Lampen starten, dann Geräte öffnen:
   Identifizieren ist gesperrt, der Stopp/Wiederherstellen-Block ist erreichbar.
2. Stopp auslösen: alle erreichbaren Lampen stoppen; die Identifizieren-Tasten
   werden wieder aktiv, sobald kein unbestätigter Zustand mehr gespeichert ist.
3. Lichtlauf starten, App schließen/erneut öffnen, dieselbe Abfolge überprüfen.
4. Eine Lampe währenddessen offline lassen: kein stilles Vergessen ihrer
   Wiederherstellung, im Fehlerfall sichtbarer Hinweis und erneuter Stopp.
5. Separat Musiklicht aktivieren und stoppen; voriger Zustand muss zurückkehren.

Unverändert offen: vollständiger Hardwaretest, Ursache der reduzierten
Multicast-Antworten, Lampen-/Raumgrundriss, 1S-Lampennachweis,
interne Audioquelle/Google-Cast-Integration sowie Langzeittest der Lichtläufe.
