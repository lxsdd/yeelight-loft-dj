# Color4 – manuelle Mikrofonanalyse, 15-s-Einzelabnahme und Zwei-Lampen-Eignungsstufe

Stand: 1. November 2026. Ausgangspunkt ist der tatsächlich qualifizierte PR #38 (HEAD 79b2ccbb946132f81959e7d43c02befda48e3053) und signierte RC5 (Build 23). Nutzer meldet RC5: 129/129 Audiodaten, 27 Akzente, 0 Bassbeats, 14 RGB-Schreibversuche, 0 Übertragungsfehler, spürbar flottere aber noch uniforme optische Farbfolge; Yeelight Classic danach nutzbar. Hieraus folgt **kein Mehrlampen-PASS**.

## Implementiert

- Lokal einstellbare **Mikrofon-Analyseempfindlichkeit ×0,50–×4,00 in 0,25er Schritten**, Standard ×1. Multiplikation der erfassten PCM-Samples *nur in der Mikrofonanalyse*. Clipping saturiert an Short-Grenzen, keine Überläufe, Stille bleibt Stille, keine Änderung der Hardware-Aufnahmeverstärkung. Digitaler Signalpegel im UI ist bei Verstärkung **kein Rohmikrofon-dBFS** mehr. Capture aus Deezer/BubbleUPnP wird **nicht** verstärkt.
- Einstellungsänderungen auch bei aktiver Mikrofonanalyse; der Analyzer wird beim Wechsel zurückgesetzt, das erste Frame wird zur Kalibrierung verwendet und nicht als plötzlicher musikalischer Beat an die Lampen weitergereicht. Gain wird lokal gespeichert, nicht übertragen.
- Mikrofon-Analysefenster **1.024 Samples bei 16 kHz = 64 ms**, passend zum bereits kürzeren internen Audioweg; weder Android-Berechtigung noch Geräteschnittstelle werden verändert.
- Begrenzter **Einzel-Color4-Test mit 15 Sekunden statt 8**, unverändert ausdrücklich opt-in mit Preflight aller notwendigen Lampenregister und persistenter Restore-Absicherung. Zusätzlich **maximal 45 RGB-Sendeversuche** über vorhandenen Music-Mode-Reverse-Channel als Defense-in-Depth; bekannte Quota-/Recovery-Gates und pro-Frame-Pacing bleiben erhalten.
- **Unbegrenztes Musiklicht für Color4 und unidentifizierte Geräte ausdrücklich gesperrt**, sowohl im UI als auch in der Controller-Operation. Dies verhindert den unbeabsichtigten Sicherheits-Bypass über den allgemeinen „Licht auf ... Lampen aktivieren“-Button. Nicht-Color4-Modelle behalten den bisherigen Weg.
- **Zwei-Color4-Eignungsprüfung ohne Lampenbefehle** für genau zwei unterschiedliche Geräte-IPs: eindeutige Color4-Modellidentifikation, beide SUPPORT(set_music,set_rgb), keine lokale Firmware-Quarantäne, kein offenes Recovery. PASS bedeutet nur Metadaten-/Bereitschaftskandidat; die jeweilige physische Isolation, Stop-/Restore-Funktion, Optik und gemeinsame Strom-/WLAN-Stabilität sind noch nicht bestätigt. Es gibt **keine** schreibende Zwei-Lampen-Testaktion.

## Abnahmegrenzen

JVM Regressionen für Gain-Standard, Clipping, extreme/ungültige Werte, 15-s/45-Telegramm-Kappen und positiven/negativen Zwei-Lampen-Vorcheck. Android-Pflicht-CI erst nach SHA-genauem Erfolg akzeptieren. **Keine signierte RC6, kein Merge, keine tatsächliche Lampensteuerung und kein Zwei-Lampen-Stresstest durch diesen Source-PR.** Vor einer echten Zweilampenabnahme: zweite unabhängige Color4 einzeln optisch+Restore+Yeelight Classic erfolgreich prüfen, dann explizit genehmigte kurze Dual-Sitzung auf Music-Mode-Rückkanälen; Wellness ohne ausdrückliche gesonderte Freigabe nicht verwenden.
