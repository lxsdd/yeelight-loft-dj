# Yeelight Loft DJ – sicherer LAN-/Music-Mode-Abnahmeplan
Stand: 1. November 2026 · PR #28 · keine Freigabe zum produktiven Einsatz

## Ziel und Grenze
Der echte Yeelight-`set_music`-Rückkanal wird nur bei Geräten genutzt, deren
SSDP-`support` sowohl `set_music` als auch `set_rgb` ausdrücklich aufführt.
Eine per TCP gefundene Lampe ohne Fähigkeitsliste wird niemals probeweise
in den Music Mode geschaltet. Der normale LAN-Fallback bleibt quotiert.

Der Rückkanal wird vor `set_music(1, host, port)` auf der WLAN-IPv4-Adresse
des Android-Geräts geöffnet. Es wird nur die erwartete Lampen-IP akzeptiert.
Kommandos müssen durch CRLF getrennt sein. Ein erfolgreicher Socket-Write
ist **keine** Bestätigung des sichtbaren Lampenzustands. Bei Shutdown wird
der Rückkanal geschlossen und einmal `set_music(0)` über die normale,
quotierte Steuerung gesendet. Nach Android-Prozessverlust erlaubt der
gespeicherte Recovery-Marker eine einmalige explizite Music-Mode-Deaktivierung.

## Bereits automatisiert getestet (kein Ersatz für echte Lampen)
- TCP-Rückverbindung vom erwarteten Gerät und korrekter JSON/CRLF-RGB-Frame
- fremde Quell-IP wird abgelehnt
- fehlgeschlagener Start löst genau einen kompensierenden Stoppversuch aus
- wiederholtes Close sendet kein erneutes `set_music(0)`
- RGB-Wertebereich und Musikimpuls-Intervall
- bestehende Quota-, Socket-, Power- und Wiederherstellungstests im Android-CI

## Hardware-Abnahme: erst nach explicit genehmigter Testinstallation
1. Loft DJ vor dem Test beenden. In Yeelight Classic **eine bekannte, intakte**
   Color-Lampe mit ausdrücklich aktiviertem LAN-Modus normal ein/aus/farbig
   steuern; aktuelle Farbe, Helligkeit und Power notieren.
2. In Loft DJ **nur diese Lampe** auswählen; Gerätefähigkeit aus
   Multicast-`support` prüfen. Bei unbekannter Unterstützung keinen
   Music-Mode-Test beginnen. Couchtisch bis zur Entstörung ausschließen.
3. Musiklicht per explizitem Knopfdruck einschalten. Eine kurze Audioquelle
   verwenden; die Rate der Rückkanal-RGB-Befehle auf höchstens etwa 3/s
   begrenzen. Bei Fehler sofort Music Mode stoppen; keine Auto-Retry-Schleife.
4. Music Mode kontrolliert stoppen. Power, Farbmodus, Helligkeit und Farbe
   mit der vorherigen Momentaufnahme vergleichen.
5. Loft DJ beenden, Yeelight Classic öffnen und die Lampe mehrfach mit
   angemessener Pause steuern. Kein `client quota exceeded`, kein merklicher
   Verlust der Yeelight-Steuerbarkeit.
6. App-Bereinigung nach Hintergrundwechsel und erzwungenem Prozessende
   separat prüfen. Beim Prozessverlust darf die App eine unbestätigte
   Wiederherstellung **nicht** als erfolgreich markieren.
7. Erst dann weitere Modelle einzeln qualifizieren. Andere Lampen bzw.
   Firmwarevarianten bekommen nur bestätigte, eigene Fähigkeitsprofile.

## Abnahmeentscheidung
PASS erst wenn gesicherter Status, Stop-/Restore-Pfad, Wechsel zur
Hersteller-App, Wiederanlauf und mehrmalige kurze Echtzeit-Sitzungen bei
der echten Hardware nachweislich funktionieren. Android-JUnit und GitHub-CI
allein begründen ausschließlich **Software-/Simulator-PASS**.

## Firmwareforschung
In einem getrennten, zunächst strikt lesenden Track untersuchen.
Keine geänderte Lampenfirmware, kein Flashen und kein produktiver OTA-Versuch
ohne gesichertes Originalabbild, dokumentierten Wiederherstellungsweg und
explizite Hardwarefreigabe.
