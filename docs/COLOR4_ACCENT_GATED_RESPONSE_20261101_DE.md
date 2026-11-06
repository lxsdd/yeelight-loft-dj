# AUDIO-02 · RC4-Folge: sichere Event-gesteuerte Color4-Farbreaktion

Stand 1. November 2026. Grundlage: Draft-PR #37 (64-ms-Playback-Audio, LOW/MID/HIGH-Onset-Detektor), exakter HEAD `48c0ec48be12e582c1e64b2cb8226a30c20c9e78`.

## Reale Ausgangslage

Nutzer-Hardwarebeobachtung unter **signierter RC4 / Build 22, einzelner Color4**: optisch erkennbare Musikfarbwechsel, erfolgreiches Zurückstellen der ursprünglichen Beleuchtung; **Yeelight Classic bleibt danach voll bedienbar**. Die interne Android-Audioanalyse derselben RC4 funktioniert mit der konkret getesteten erlaubten Wiedergabe; bisheriger linearer PCM-RMS-UI-Pegel etwa **40 %** (≈ −8 dBFS). Kein Beweis genereller App-Capture-Berechtigungen. Farbwechsel wirken noch zögerlich, nicht zu jedem Beat, ohne differenzierte Höhen-/Mittenakzente.

## Implementierter sicherheitsbegrenzter Funktionsblock

Nur für den bestehenden, **ausdrücklich opt-in**, **genau eine bestätigte Color4** betreffenden und **acht Sekunden** langen Musik-Rückkanal-Einzeltest:

- Erkannter Bass-Beat behält **Vorrang** und seine bestehende Mindestdistanz von **350 ms** auf einem aktiven Stream.
- Positive LOW/MID/HIGH-Spektral-Akzente aus echten PCM-FFT-Bändern dürfen bei hinreichender digitaler Energie **höchstens einen RGB-Versuch je 500 ms** auslösen (maximal 2 pro Sekunde bei reinen Akzenten).
- Fallback für einfach messbare Audioaktivität bleibt bei mindestens **1.000 ms**.
- Alle drei Kategorien teilen **denselben letzten Schreibzeitpunkt und denselben einen laufenden Sendeworker**. Keine Doppelspur, kein Queue-Stau, keine Wiedergabe alter Frames.
- **Unverändert**: Geräte-SUPPORT- und IP-Quarantäne-Gates, vorbereitende Status-/Restore-Journale, Reverse-Channel-Socket, automatischer 8-Sekunden-Stopp und wiederherstellende Transition. Normaler LAN-RGB-Verkehr und reguläre Musikshows bleiben bis zur nächsten gesonderten Abnahme beat-only/quota-limitiert.
- Telemetrie trennt tatsächliche (nicht optisch bestätigte) RGB-Schreibversuche für Beats, Klangakzente und generische Aktivität. Die UI behauptet keinen physischen Erfolg allein aufgrund einer geschriebenen TCP-Nachricht.

## Abnahme

Positiv-/Negativ-JVM-Tests für echte 64-ms-PCM-Höhenimpulse, 500-ms-Rate, fehlenden Rückkanal, normalen LAN-Modus, Bass-Priorität, schwache Eingangspegel, Burst-Suppression und Diagnosekonsistenz. Android-CI muss SHA-genau grün sein.

**Nicht autorisiert:** weitere Hardwaretests, neues signiertes APK, geänderte reale Color4-Firmware, dauerhafte Stream-Nutzung, Mehrlampenshows, Merge/Stable-Release. Der signierte RC4-Build bleibt unverändert. Auch ein positiver späterer Acht-Sekunden-Test beweist weder alle Beats noch Langzeit-Stabilität.
