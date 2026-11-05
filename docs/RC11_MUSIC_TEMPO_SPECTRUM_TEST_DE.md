# RC11 – Android-Audio-A/B, echtes Spektrum und Color4-Tempo-Transporttest

## Anforderung / Feldbefund (2026-11-05)

Der Nutzer hat RC10 auf Android getestet: **MIC + adaptive Analyseverstärkung** reagiert am besten. VOICE_RECOGNITION und VOICE_COMMUNICATION zeigen deutlich weniger Ausschlag. Tiefmitte gut, Mitten gering, Präsenz/Höhen optisch kaum. Eine einzelne Color4 reagiert sichtbar, Music-Ende music_on=0 und Yeelight Classic bleibt danach bedienbar. Etwa 130 BPM wirken verzögert.

Die RC9/RC10-Referenzprüfung hat bewusst **mindestens 1.000 ms zwischen RGB-Frames** (maximal 8 Frames in 8 s). Bei 130 BPM beträgt der Abstand etwa 462 ms. Dieser starre Testabstand erklärt zwangsläufig, warum nicht jeder Bassbeat einen Farbwechsel auslösen kann; es ist kein Beweis für Android-Mikrofonlatenz.

## Was sich in RC11 geändert hat

1. **Nur Anzeige-Signalverarbeitung:** In AudioSpectrumDisplay wird die feste Anzeigeunterdrückung bei normierten FFT-Werten <0,018 durch eine deutlich kleinere Grenze von 0,003 und eine nichtlineare relative Skala ersetzt. Die wirklich gemessenen normierten FFT-Bandwerte stehen nun rechts neben jedem Balken; falsche physikalische dBFS-Bandwerte werden nicht ausgegeben. Die Roh-Bandwerte im MusicFrame, Bass-/Beat-/BPM-/Akzentdetektion und sämtliche LAN-Quoten bleiben vollständig unverändert. Ein gemessenes Null-Band bleibt weiterhin Null, Stille bleibt Null.
2. **Echter Tempo-A/B-Versuch** zusätzlich zum **unveränderten RC10-Referenztest**. Der Benutzer muss auf der Musik-Seite eine ausdrücklich eigene Bestätigungsabfrage akzeptieren. Ein einziges zuvor bestätigtes Color4, vor Start music_on=0, alleiniger aktiver Musik-Rückkanal, maximal 8 Sekunden, maximal 16 RGB-Frames, mindestens 450 ms zwischen bestätigten Bassbeat-Ereignissen. Es gibt kein normales LAN-RGB und keine automatische Wiederholung. Der 1-Sekunden-Fallback ist ausdrücklich als **Audioaktivität**, nicht als Bassbeat markiert.
3. **Keine verlorenen Beat-Impulse durch UI-Abtastung:** Statt nur das letzte Audioframe alle 120 ms auf frame.beat zu prüfen, beobachtet der neue Versuch das bereits vorhandene echte Music-Beat-Ereigniszählerinkrement. Der 40-ms-Prüftakt erhöht nur die Android-interne Beobachtungsfrequenz, NICHT die Anzahl normaler LAN-Befehle. Display- und Testberichte unterscheiden Beats und Fallbacks.
4. **Firmware-Stopp und Quarantäne unverändert:** Das Testprogramm schließt den Music-Rückkanal ohne unbestätigtes set_music(0), prüft anschließend music_on=0 durch einmaliges Lesen, und löscht den dauerhaften Sperrmarker nur bei bestätigt beendetem Zustand. Jede unbestätigte Music-Sitzung bleibt gesperrt. Die ursprünglichen Color4-Dauer- und Zwei-Lampen-Shows bleiben unverändert deaktiviert.

## Sichere Testabfolge (erst nach erfolgreicher GitHub-CI und signierter RC11 APK)

- Ohne Lampen zunächst MIC + automatische Analyseverstärkung beibehalten. Bei unverändertem Musiktitel/Telefonstandort **Roh-RMS/Peak/Clipping, angezeigten FFT-Bandwert für Präsenz/Höhen, echte Beats und erkannte BPM** anschauen. Bei Bandwert 0,000 trotz lauter Musik kann kein Darstellungsalgorithmus physikalisch fehlende Höhen herbeizaubern; dann braucht es eine Rohsignal-/Mikrofon- oder FFT-Analyse.
- **Nicht** zuerst mit schneller Color4 experimentieren. Der bereits unter RC9/RC10 erfolgreiche Referenztest mit einem Color4 und maximal 8 Frames/8 Sekunden bleibt verfügbar. Er kann zum Vergleich dienen; ein erneuter Basistest ist nicht zwingend, wenn keine Transportcodeänderung vorgenommen wurde.
- Nur bei weiterhin funktionierender, eingeschalteter Color4 und ohne andere aktive Steuerung danach den **neuen, ausdrücklich einzeln bestätigten Tempo-Test** wählen; vorab wissen, dass er noch **keinen Hardware-Pass** besitzt. Bei Quota-Meldung oder ungeklärtem Music-Ende sofort beenden, keinen zweiten Test starten. Am Ende Firmware music_on=0 sowie danach Bedienung über Classic bestätigen. Keine Rückkehr zu unbegrenzter Show ohne getrennte Qualifikation.
- Während des Tests auf echte Beats und Farbwechsel achten, Ergebnisbericht vollständig notieren: „Tempo: N RGB über Music-Socket, davon M gemessene Beats und K Audio-Aktivität; music_on=0“. Sichtbare Farbwechsel und gefühlte Synchronität qualitativ melden, keine Wiederholung nötig.

## Signierweg

Version 0.2.8-rc11 / Build 29, Draft PR #63, isolierter Branch chat/rc11-music-visuals-tempo-20261105. Der GitHub Android-CI-Workflow kann nur auf **owner-dispatch** nach exakt-grüner PR-CI und geprüftem SHA/Zweig/Version den vorhandenen Signing-Store benutzen. Der Release-Job bleibt für RC11 **aus**, nur temporäres signiertes GitHub Actions Artefakt, keine dauerhafte öffentliche APK. Der vorherige RC10 bleibt installierbar.

**Weder grüne CI noch das bestehende RC10-Feldfeedback beweisen, dass die neue kürzere Frame-Pause über die reale Color4-Firmware stabil ist.**
