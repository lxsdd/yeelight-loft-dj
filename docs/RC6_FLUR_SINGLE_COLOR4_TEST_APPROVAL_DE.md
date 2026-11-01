# Color4-Einzeltest Flur: kontrollierte Benutzerfreigabe

Hardwarefreigabe vom 1. November 2026: einmaliger 15-Sekunden-Einzeltest der benannten Color4 `Flur`, nach erfolgreicher lokaler Eignungsprüfung der beiden Kandidaten Couchtisch und Flur. Das Eignungsergebnis umfasst nur Modell-/SUPPORT-/Quarantäne-/Recovery-Metadaten, **nicht** die tatsächliche Music-Mode- oder Restore-Funktion von Flur.

## Codeprüfung

RC6 nutzt eine generische `Color4MusicAcceptancePolicy`: exakt eine physische Color4 mit bestätigtem `set_music` und `set_rgb`, ohne Quarantäne oder offene Wiederherstellung. **Keine technische Beschränkung auf Couchtisch.** Falsch waren nur der statische Hilfetext und die Beschriftung `Nur Couchtisch testen`; sie spiegelten die frühere Freigabe wider.

Dieser PR macht den Dialog zielbezogen (dynamischer Anzeigename), erklärt explizit, dass nur einzeln und gesondert freigegebene Lampen getestet werden dürfen, und entfernt den veralteten Couchtisch-Namen aus dem allgemeinen Ablehnungsgrund. JVM-Test für `Flur` als einzelnes Ziel. **Keinerlei Änderungen** an Audioanalyse, Musik-Rückkanal, LAN-Quote, 15-s-Timeout, 45-RGB-Budget, persistiertem Restore, Quarantäne oder dem weiterhin gesperrten Zwei-Lampen-Test.

## Konkreter Ablauf unter bereits signiertem RC6

- Vorher am Auswahlbildschirm ausschließlich `Flur` auswählen; bei 2 markierten Lampen bleibt der Einzeltest deaktiviert.
- Musikaufnahme muss laufen. Der Dialog zeigt die aktuelle Auswahl; **nur wenn sie Flur ist** den Einzeltest einmal bestätigen. Unter RC6 heißt der finale Knopf irreführend noch `Nur Couchtisch testen`, löst aber die überprüfte *aktuelle Einzelauswahl* aus.
- Automatischer Stop und Restore; bei Problemen nicht erneut starten. Anschließend originale Yeelight-App einmal zur normalen Bedienbarkeit benutzen.
- Bildschirmbericht mit Audio/Beat/Akzent/RGB-Fehlerzahlen und ausdrückliche Beobachtung der physischen Wiederherstellung dokumentieren. Kein Test auf Wellness, kein gemeinsamer 2-Lampen-Modus.

Keine neue APK-Signierung oder Veröffentlichung durch diesen PR.