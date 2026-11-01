# v0.2.4: Einzelnen festhängenden Lichtshow-Eintrag sicher freigeben

## Vom Gerät gemeldeter Fehler
Die Couchtisch-Lampe mit IP-Endung `.134` antwortete auf
`stop_cf` mit `client quota exceeded`. Die App behielt deshalb
die gespeicherte Wiederherstellung – korrekt, weil der Lampenzustand
nicht bestätigt war. Ein erneuter Stop nach 60 Sekunden kann weiter
fehlschlagen. Das war bislang ein Sackgassenfall.

Offizielle Yeelight-LAN-Spezifikation: maximal vier gleichzeitige
TCP-Verbindungen pro Lampe; 60 Befehle je Verbindung pro Minute
sowie 144 Befehle pro Minute über alle LAN-Verbindungen. Die konkrete
Quota-Meldung kann auch bei unerwünschtem häufigen Polling auftreten.

## Sofortmaßnahme mit v0.2.3
1. Andere Steuer-Apps/Integrationen für **nur diese Lampe** beenden.
2. Die betreffende Couchtisch-Lampe einmal **ca. 15 Sekunden stromlos**
   machen und wieder einschalten. Nicht mehrfach schnell schalten
   (Werksreset-Verfahren wäre ein anderer Ablauf).
3. Prüfen, dass die Lampe nach dem Neustart im Router immer noch die
   IP-Endung `.134` hat; andernfalls die neue IP in der App ermitteln.
4. Nach mindestens einer Minute einmal `Alle Lampen suchen`,
   anschließend `Lichtshow stoppen & Licht wiederherstellen` ausführen.
5. Bei weiterhin ausdrücklich abgelehntem `stop_cf`: mit der
   bisherigen App gibt es **keinen sicheren manuellen Reset** des
   Journaleintrags, ohne andere Einstellungen zu gefährden.
   Keine App-Daten löschen.

## Neue gezielte Option in v0.2.4
Unter dem Wiederherstellungs-Hinweis kann der Anwender nach
tatsächlichem Stromneustart **genau eine** gespeicherte Lampe auswählen.
Der Dialog verlangt zwei getrennte, bewusste Bestätigungen:
- Genau diese Lampe wurde für mindestens 15 Sekunden stromlos neu
  gestartet und ist wieder eingeschaltet.
- Der ursprüngliche Lichtzustand (Farbe/Helligkeit) muss nicht mehr
  automatisch wiederhergestellt werden.

Erst danach werden **beide lokalen Journaleinträge derselben IP**
in einer einzigen synchronen Prefs-Transaktion entfernt.
Andere Lampen, Zimmer, Gruppen, Szenen und verbleibende
Wiederherstellungs-Snapshots bleiben vollständig bestehen.
Es wird **kein LAN-Steuerbefehl** versendet, keine erfolgreiche
Hardware-Wiederherstellung behauptet und keine Geräte-IP aus
dem privaten Netzwerk veröffentlicht.

Das ist kein Ersatz für einen fehlgeschlagenen Stopp ohne
Stromneustart: andernfalls könnte noch eine Show laufen.
Die Bestätigung kann nicht technisch beweisen, dass das richtige
Leuchtmittel stromlos war. Darum ist sie ausdrücklich freiwillig.

## Diagnosekorrektur
Die 12-Runden-Probe testet **nur Multicast UDP**; sie ist keine
TCP-Intensivsuche. Bisher zeigte sie bei einer frisch geöffneten App
irreführend `TCP bestätigt: 0`. Jetzt steht klar, dass TCP in dieser
Runde nicht neu geprüft wurde, und verweist bei fehlender
Vergleichsmessung auf `Alle Lampen suchen`.

## Weiterhin nicht bewiesen
Bei sechs Multicast-Antwortern auf 5 und 2,4 GHz und sieben zuvor
positiven gerichteten UDP-Antwortern liegt der Unterschied eher
im Multicast-Suchpfad als an einem generellen UDP-Ausfall.
Die tatsächliche Ursache innerhalb FRITZ!Box/Yeelight-Firmware
kann nur über zusätzliche Paketweg-Diagnose endgültig belegt werden.

## Abnahme
- JVM: beide Bestätigungen obligatorisch, nur lokale IPs und
  nur gespeicherte offene Einträge, kein Eingriff bei aktiver Sitzung,
  anderes Journal bleibt erhalten.
- GitHub Actions: `testDebugUnitTest` und `assembleDebug`.
- Hardware: eine einzelne Lampe stromlos neu starten, gewünschte
  Wiederherstellung einmal versuchen, danach gezielte Bestätigung
  in der App testen. **Kein automatisches Release bei CI-Erfolg.**
