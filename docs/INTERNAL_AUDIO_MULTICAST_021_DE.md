# Interner Ton + UDP/TCP-Erkennung (Yeelight Loft DJ v0.2.1)

## Audio: kein MP3-Player in dieser App

Die App übernimmt **kein eigenes Abspielen von Audiodateien**. Sie
analysiert die auf dem Telefon bereits abgespielten, von Android freigegebenen
Audio-Streams anderer Apps über `AudioPlaybackCaptureConfiguration`.
Beispiele: ein auf dem Handy selbst spielender BubbleUPnP-Player, YouTube
oder eine andere lokale Audioquelle – **nur, wenn die Quell-App die Aufnahme
erlaubt**.

- Voraussetzung: Android 10+, `RECORD_AUDIO`, persönliche
  `MediaProjection`-Systemfreigabe, passende Media-Nutzung und
  `ALLOW_CAPTURE_BY_ALL` der Quell-App.
- Eine Android-`mediaProjection`-Foreground-Service-Benachrichtigung
  zeigt die aktive Analyse an und hat einen Stopp-Button.
- Kein Mitschnitt auf Datei, kein Bild-/Video-Stream, kein Upload; PCM wird
  ausschließlich im Speicher für denselben realen FFT/Bass/BPM-Analyzer
  und optionalen Musik-Lichtmodus verarbeitet.
- Reine Cast-Wiedergabe auf einem externen Receiver, durch die Quell-App
  untersagte Erfassung und einige geschützte Inhalte können Stille
  verursachen. Das ist keine künstlich erzwingbare Berechtigung;
  Mikrofon steht als alternatives Eingangssignal zur Verfügung.
- Beide Eingänge sind **gegenseitig exklusiv**. Der MediaProjection-Token
  ist auf eine konkrete nutzerbestätigte Sitzung begrenzt und darf nicht
  ohne erneute Freigabe wiederverwendet werden.
- Hardwaretest: direkt auf dem Pixel starten, Freigabe erteilen,
  erlaubte Audioquelle auf demselben Gerät abspielen; danach Cast
  zu externem Empfänger und verweigerte Quell-App vergleichen.
  Nicht jede Drittanbieter-App kann ohne ihren Test vorher zugesagt werden.

## Test (1), Loft und Gruppen

Die Sonderziele `Test (1)` und `Loft` wurden aus dem Zielmenü entfernt.
Stattdessen gibt es **benannte, editierbare Gruppen**, die bestehende
Raumzuordnung, eigene Mehrfachauswahl, einzelne Lampen und `Alle`.
Neue Gruppen sind vollständig lokal gespeichert; Lampen lassen sich
hinzufügen, entfernen; Gruppe kann umbenannt oder gelöscht werden.
Eine veränderte Geräte-ID bei neuer Erkennung wird in allen Gruppen
fortgeführt.

Beim Update bleiben frühere Zuordnungen erhalten:
- altes `Test (1)` wird zur explizit gewählten ersten Lampe (sofern
  vorhanden; ansonsten zur leeren eigenen Auswahl),
- das alte `Loft`-Ziel wird in die eigene Auswahl überführt,
  mit den bisherigen Mitgliedern. Vorhandene Auswahl wird vereinigt,
  nicht blind gelöscht.

## 5 Multicast- versus 15 TCP-erreichbare Lampen

Es handelt sich **um dasselbe physische Gerät mit demselben
Yeelight-TCP-LAN-Protokoll**. Ein IP-Treffer über TCP bedeutet nicht,
dass die Birne weniger Farb- oder Szenenfunktionen beherrscht.

Multicast-Suche (UDP `239.255.255.250:1982`) liefert
`Location`, `id`, `model`, `support` etc. und beantwortet die
Geräteidentität/Fähigkeit direkt. Der bisherige TCP-Direkt-Probe
(`get_prop` auf Port 55443) bestätigt Erreichbarkeit und Status,
aber Yeelight dokumentiert keinen gleichwertigen TCP-Aufruf, mit
dem die vollständige `support`-Liste ausgelesen wird.
Eine fehlende Liste wird deshalb als »unbestätigt« angezeigt, **nicht**
als »diese Hardware unterstützt es nicht«.

Vorherige Messwerte: 15 TCP, 5 Multicast, 47 gültige UDP-Antwortpakete
von nur fünf Geräten. Ein allgemeiner Parserfehler ist damit
unwahrscheinlich. Mögliche Pfade:
1. Multicast-Ausgangsschnittstelle/Routing des Handys,
2. WLAN-AP/IGMP-/Multicast-Zustellung an einige Clients,
3. Firmware-/Timingverhalten bei gleichartigen Birnen,
4. fehlende individuelle UDP-Unicast-Antworten zurück zum Handy.

### App-seitige Korrektur und A/B-Messung

- Bindet den DatagramSocket über `Network.bindSocket` ausdrücklich
  an **Androids tatsächliches Wi-Fi-Network** (wenn vorhanden),
  zusätzlich zur gewählten lokalen IPv4-Adresse.
- Standard-Suche: 5 Runden; manuelle erweiterte Diagnose:
  12 Runden mit längeren Antwortfenstern.
- Zeichnet pro Lampe die **Zahl der Suchrunden** mit Antwort und
  die Antwortpakete auf, plus TCP/UDP-Überlappung und Routing-Status.
- Ein gezielter UDP-Versuch an TCP-bestätigte Lampen bleibt möglich;
  **kein** Antwortpaket ist wegen des multicastbasierten Standards
  keine sichere Fehlerdiagnose.
- Die App verändert bei diesen Diagnosen keine Lampen.

Wenn weiterhin exakt dieselben fünf antworten, ist ohne Beobachtung
der Pakete auf einer zweiten Netzstelle nicht beweisbar, ob sie die
Suche nie erhalten oder die Antwort verloren geht. Für den Abschluss
benötigt man einen unabhängigen WLAN-Sender/Paketmitschnitt und
Vergleich einer antwortenden mit einer nur per TCP erkannten Lampe.

**Nicht** die Routereinstellungen oder Lampen auf Verdacht
zurücksetzen. Privat-IP-Adressen, Gerätedaten, Netzwerk-Mitschnitte
nicht im öffentlichen Repository veröffentlichen.

## Abnahmegrenzen

Unit-Tests und `gradle testDebugUnitTest assembleDebug` laufen in
öffentlichen GitHub Actions. Es gibt keinen verbundenen Android-Simulator
mit echten MediaProjection-Tokens und keinen Zugriff auf die Lampen.
Harte Aussagen wie »YouTube funktioniert garantiert« oder
»Multicast-Rootcause ist Router« sind ohne echte Tests nicht zulässig.

Originalreferenzen:
- https://developer.android.com/media/platform/av-capture
- https://developer.android.com/media/grow/media-projection
- https://developer.android.com/reference/android/net/Network#bindSocket(java.net.DatagramSocket)
- https://www.yeelight.com/download/Yeelight_Inter-Operation_Spec.pdf
