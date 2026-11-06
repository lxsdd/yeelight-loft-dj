# AUDIO-02 · Latency/Signal- und musikalische Ereignisdiagnose (Offline)

Stand 1. November 2026. Baseline: UI-Meter-Draft-PR #36, Quell-SHA a728488d327c71a635420c4c7f2bb3fb63b10c8f. Letzter echter Hardware-PASS: RC4, Couchtisch, erkennbare RGB-Reaktion + ursprünglicher Zustand erfolgreich wiederhergestellt. Die wahrgenommene Beat-/Event-Synchronität war trotzdem noch zögerlich.

## Befund

- Die Android AudioPlaybackCaptureConfiguration ist schon tatsächlich implementiert: mediaProjection-Service, RECORD_AUDIO, bewusste Consent-UI, Android-Profile-/Capture-Policy-Grenzen, PCM 16 kHz/Mono, FFT, Live-Status.
- Das bisherige Playback-Capture-Fenster 2.048 / 16.000 = 128 ms erklärt einen Teil der Wartezeit schon *vor* DSP, UI und Yeelight-Rückkanal. Es lässt sich unter Wahrung der gleichen Beat-/LAN-Dispatch-Gates auf 1.024 / 16.000 = 64 ms reduzieren.
- RC4s Musik-Farbengine verwendet weiterhin Bass-Beat oder max. sekündliche Audioaktivität für den ausdrücklich achtsekündigen Reverse-Channel-Test. Eine höhere Anzahl von Audioframes darf **nicht** automatisch mehr TCP-/RGB-Schreibbefehle erzeugen.
- Androids Systemrecht zur Audioaufnahme beweist **nicht**, dass eine konkret abspielende App capture-freigeschaltet ist: Stille, Cast und blockierte Quell-App sind für eine unprivilegierte Capture-App nicht eindeutig unterscheidbar.
- Bei einem vollständigen Activity-/Process-Stop geht die an LoftController gebundene aktive Lampensitzung verloren; die derzeitige foreground MediaProjection-Service-Implementierung ist **noch kein eigener dauerhafter DJ-Lichtshow-Service**, der über beliebige App-/Prozesswechsel robust laufen kann.

## Implementiert (kein Hardware-Risiko)

1. Playback-PCM Analysefenster 1024 Samples @ 16 kHz = 64 ms bei unveränderter, originärer Android AudioPlaybackCapture Berechtigung.
2. Neuer reiner, konservativer Spektral-Anstiegsdetektor: LOW/MID/HIGH auf tatsächlich gemessener positiver FFT-Bandänderung. Keine erfundenen Beats/BPM, keine wiederholten Akzente auf konstantem Ton, Schutz vor NaN, 220 ms Detektor-Cooldown.
3. Akzent-Diagnose in der Musikansicht; noch **nicht** mit Yeelight-RGB-Dispatch verbunden. Weder Gerät noch Cloud erhalten zusätzliche Kommandos.
4. Status für internes Audio bei längerem fehlendem Signal wird von bislang 40 Frames auf echte 2,5 s umgestellt. Er behauptet ausdrücklich nicht, aus digitaler Stille eine Rechteverletzung oder einen Cast-Modus diagnostizieren zu können.
5. JVM-Tests: 64-ms-Frames, echte künstlich erzeugte Low/Mid/High-Töne, Stille, negative Signale, Zeitrücksprung, zeitbasierter Timeout.

## Nächste Abnahme

Das neue Event-/Low-Latency-Ergebnis ist vorerst nur softwarequalifiziert. Keine neue RC-APK, keine Hardwaretests, kein Merge und keine Änderungen an Color4-Firmware oder Quota-Limits. Echtmusikalische Robustheit, End-to-End-Latenz und stabiler Betrieb nach Wechsel zur Audio-App müssen separat gemessen und qualifiziert werden. Langfristig benötigt die professionelle Mehrlampen-DJ-Show eine dedizierte Service-Architektur für Musik + Sessions + Recovery, nicht nur einen am Activity-Lebenszyklus hängenden Controller.
