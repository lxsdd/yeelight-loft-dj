# Color4 / Music Mode – kontrollierte Einzelgeräte-Abnahme

Stand: 1. November 2026 · Draft-PR #31 · signierte Testfassung **0.2.8-rc2 / Build 20**.

## Ausgangslage
- Signierte Version 0.2.8-rc1 / Build 19: Nutzer meldete für **Couchtisch** einen Hardware-PASS für Ein/Aus, Helligkeit, Farbe und anschließende Yeelight-Classic-Steuerung.
- Dieser PASS gilt **nicht** als Nachweis für Music Mode oder andere Color4.
- Zuvor trat bei **Wellness** `client quota exceeded` auf. Wellness **nicht testen**.

## Eingebaute Sicherungen der nächsten Teststufe
1. Eigener Color4-Testknopf in **Musik im Loft → Musikreaktive Lichtsteuerung**. Die normale DJ-Musikaktivierung bleibt davon getrennt.
2. Nur bei **genau einer** ausgewählten Color4 mit durch SSDP bestätigtem `set_music` **und** `set_rgb`. Firmware-Quarantäne und offene Wiederherstellung verhindern den Test.
3. Vor der ersten Schalthandlung wird der aktuelle Gerätezustand über genau eine Statusabfrage eingelesen und das Wiederherstellungsjournal dauerhaft gespeichert. Bei fehlendem/uneindeutigem Zustand keine Änderung.
4. Das Smartphone wartet auf einen Rückkanal **von genau dieser Lampe**. RGB-Impulse laufen nicht über die normale quotierte LAN-Steuerung.
5. Testdauer nach erfolgreichem Start: **8 Sekunden**, danach automatischer Stopp und Wiederherstellung. Manueller früherer Stopp bleibt möglich. Bei blockierter RGB-Ausgabe wird nur der Rückkanal-Socket unterbrochen; die reguläre Music-Mode-Deaktivierung folgt einmalig.
6. Scheitert der Handshake, wird eine bereits durchgeführte Kompensationsdeaktivierung **nicht** automatisch erneut gesendet. War die Kompensation erfolglos, bleibt die Wiederherstellung offen.
7. Kein Merge, kein öffentliches Release und keine Tests an anderen Lampen.

## Zwingende Gerätebedienung (Pixel 6a)
1. Signierte APK 0.2.8-rc2 (Build 20) installieren; Bluetooth kann auf dem Pixel 6a vorerst deaktiviert bleiben.
2. Unter **Ziel** ausschließlich die bekannte, funktionierende Lampe **Couchtisch** auswählen. Nicht `Alle`, nicht einen Raum oder eine Gruppe.
3. Eine schonende, gleichmäßige Musikquelle am Telefon/Lautsprecher starten. Auf **Musik im Loft** das **Mikrofon** aktivieren und prüfen, dass das Live-Spektrum reagiert. Das Mikrofon selbst löst keine Lampenbefehle aus.
4. Ist der separate Testknopf deaktiviert, den angezeigten Grund lesen und **nicht** durch einen normalen Musikmodus-Start umgehen. Insbesondere kein `set_music`-Support: keine Testfreigabe zur Spekulation.
5. Den separaten **Color4-Einzeltest · 8 Sekunden** auswählen; in der Bestätigungsabfrage nochmals den Gerätenamen prüfen, dann nur Couchtisch bestätigen.
6. **Nicht mehrfach starten**. Für acht Sekunden beobachten, ob die Farbe auf Musik reagiert. Automatischen Stopp und Wiederherstellung abwarten.
7. Danach in Yeelight Classic Couchtisch einmal bedienen. Bei `client quota exceeded` sofort abbrechen; kein Powercycle als automatische Routine.
8. Nur tatsächliche Beobachtungen zurückmelden: Knopf aktiv oder nicht, Farbänderungen sichtbar ja/nein, ursprünglicher Zustand wiederhergestellt ja/nein, Yeelight Classic anschließend steuerbar ja/nein, etwaige Fehlertexte.

## Bewertung
Software-CI kann Frame-Format, Rückkanal, Einzelziel, Ablehnung unbekannter Fähigkeiten, Timeout/Abbruch und kompensierten Handshake prüfen. **Physische Reaktion und stabile Firmware** müssen am Gerät bestätigt werden. Eine einzige erfolgreiche Acht-Sekunden-Session beweist keine Langzeitstabilität.
