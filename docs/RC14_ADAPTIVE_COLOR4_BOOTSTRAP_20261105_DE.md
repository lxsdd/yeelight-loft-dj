# RC14 | Adaptive Color4 Music visuals + notification navigation

Owner requested after hardware confirmation of RC13 four Color4 channels, screen-off continuation, Classic reusability, and notification STOP. Source-only stacked draft based on RC13 `8dcf2aa528a376f14ab139ffbe528ca8d901c200`.

Scope: notification content tap returns to the existing DJ UI without restarting capture; retain proven beat mode and provide explicit opt-in adaptive sound-to-light mode with broad 360-degree hue coverage, smooth RGB changes, bounded brightness from measured RMS, real beats and six spectral bands, 1–4 eligible Color4 independent music reverse sockets, no normal LAN fallback. Persist no hidden account data. Keep per-bulb music_on journals, stop/readback, quarantine and recovery guards unchanged.

Engineering: unit tests + Android compile CI; no hardware interaction or automatic task modifications. No unsigned public release, merge, or signing outside owner-only exact-source CI gate. Manufacturer Classic app modification remains a separate scope.

## Implementierte Funktionen (RC14 Quellcode)

- Eine Berührung der laufenden Live-DJ-Benachrichtigung öffnet die vorhandene Musikseite; die separate STOP-Aktion bleibt bestehen. Der Klick startet keine neue Aufnahme.
- Auswahl zwischen Beat und Dynamisch, vor Sitzungsbeginn; 1–4 Color4 mit bestätigten eigenen Music-Rückkanälen.
- Vollständiger HSV-Farbkreis mit spektraler Gewichtung aller sechs Frequenzbänder, zusätzlicher zeitlicher Variation und räumlichen Hue-Offsets pro Lampe statt drei festen Farbtönen.
- Dynamisch: pro Lampe `set_rgb(..., smooth, duration)` im Music-Rückkanal, dazu bei relevanter Lautstärkeänderung optional `set_bright(..., smooth, duration)`, ausschließlich bei bestätigtem `set_bright`-Support. RMS-Auswertung mit langsamerer Abschwächung und Helligkeit 8–96; keine 0%-AUS-Vortäuschung.
- Maximal ein dynamischer RGB-Frame/900 ms (ruhig: 1.850 ms) und ein Helligkeitsbefehl/1.500 ms pro Lampe. Keine normalen LAN-RGB-/Helligkeits-Fallbacks, keine Recovery-Schreibversuche durch RGB-Fehler.
- Bewährter Beat-Modus bleibt als auswählbare Alternative: ausschließlich RGB auf erkannte Audioimpulse, ohne Helligkeit.
- Alle bisherigen per-IP Music-Journale, Quota-Quarantänen und `music_on`-Stopp-Lesebestätigungen bleiben erhalten. Alte 8-Sekunden-Transportproben bleiben separat.
- Android Quell-CI baut und testet, ohne Signing-Secrets im PR. Eine signierte Test-APK nur durch manuellen GitHub-Owner-Dispatch auf exakt grün geprüftem PR #66 HEAD, Version 32 / `0.2.8-rc14`. Kein Release und kein Merge.
- Noch **nicht** durch physische Color4 für sanfte Übergänge/Helligkeit abgenommen. RC13-Hardwaretest bleibt davon getrennt. Das Modifizieren der Yeelight-Classic-Original-App ist ein separater späterer Entwicklungsbereich.
