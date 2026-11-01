# Yeelight Loft DJ v0.2.4 – Wiederherstellung ohne Sackgasse

## Warum die alte Wiederherstellungslogik zu streng war

Der Screenshot zeigt für Couchtisch (.134)
`stop_cf abgelehnt: client quota exceeded`. Die App darf den Befehl
nicht als erfolgreich bestätigen und muss das ursprüngliche,
gesicherte Lichtbild schützen. Allerdings sagt **fehlgeschlagenes
stop_cf** allein nicht, dass der Farbfluss noch läuft: die Lampe
könnte bereits ohne weitere App-Beteiligung gestoppt worden sein.

V0.2.3 hält das lokale Recovery-Journal zu Recht zunächst offen,
lässt den Benutzer aber nach anhaltenden Firmware-Ablehnungen in einer
Sackgasse zurück. Die ursprüngliche v0.2.4-Variante verlangte
fälschlich eine nicht überprüfbare Aussage, man habe die Lampe
mindestens 15 Sekunden stromlos gemacht. Das ist eine unnötig starre
Bedingung für das **bloße lokale Verwerfen eines Journaleintrags**.

## Neuer Ablauf

- **Empfohlen:** `Lichtshow stoppen & Licht wiederherstellen` sendet
  die Stop-/Restore-Befehle und entfernt den Eintrag nur bei bestätigtem
  Erfolg. Bei `client quota exceeded` bleibt der Eintrag geschützt.
- **Alternative:** `Alten App-Eintrag verwerfen` für genau EINE
  offene Lampe. Kein Stromneustart und keine falsche Aussage darüber
  erforderlich.
- Der Dialog verlangt ZWEI aktive, getrennte Bestätigungen:
  1. Der Benutzer versteht, dass ein Farbfluss weiterhin laufen kann,
     weil diese Option **keinen** Stop-/LAN-Befehl sendet.
  2. Der Benutzer verzichtet bewusst auf die automatische
     Wiederherstellung der ursprünglichen Farbe/Helligkeit dieser Lampe.
- Nur die Marker dieser Lampe werden in einer einzigen atomaren
  `SharedPreferences.Editor.commit()`-Transaktion entfernt.
  Die übrigen Lampen und ihre Journal-Einträge bleiben unberührt.
  Die UI meldet ausdrücklich, dass NICHT gestoppt oder wiederhergestellt
  wurde.
- Weder das Weiterlaufen der Lampe noch ein physischer Stromneustart
  kann allein aus dem App-Journal bewiesen werden.
- Die Funktion ist nicht während aktiver Musik-Licht-Sitzungen,
  Stop-/Sendevorgängen oder anderer Busy-Operationen freigegeben.

## Praktische Empfehlungen

Für die Lampe .134 zuerst eine Minute nach einer Quota-Ablehnung
abwarten, unnötige parallele Steuer-Clients vermeiden und dann einmal
normalen Stopp versuchen. Ein physischer Stromneustart kann die
wahrscheinlichste Quota-/Verbindungsstörung beheben, ist aber nicht
die Voraussetzung, die App-seitige Vormerkung bewusst zu verwerfen.

Wer ohne Stromneustart verwirft, muss damit rechnen, dass eine
alte Animation auf der Lampe noch aktiv ist und spätere Befehle
überlagern kann. Es besteht **kein verifizierter Hardwarezustand**.
Das Verwerfen ist keine automatische Reparatur des Geräteproblems.

## Weitere Korrektur

Die `12-Runden-Multicast-Gegenprobe` testet selbst kein TCP.
Die zuvor verwirrende Anzeige `TCP bestätigt: 0` wurde durch
eine ausdrückliche Erklärung ersetzt. Erst `Alle Lampen suchen`
liefert einen neuen TCP-gegen-UDP-Vergleich.

## Tests

JVM-Regressionen: beide Warnungen obligatorisch, nur eine
gespeicherte lokale Lampe, keine Freigabe während eines
aktiven Vorgangs; andere Journal-Einträge bleiben bestehen.
GitHub-CI baut und testet Android, keine physischen Lampen sind
mit dem Runner verbunden.

Merge/Release auf main nur nach ausdrücklicher Freigabe.
