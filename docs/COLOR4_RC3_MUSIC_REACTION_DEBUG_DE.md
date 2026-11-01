# RC3 Color4 Music Mode: ausbleibende optische Reaktion – softwareseitige Korrektur

Stand: 1. November 2026. Rückmeldung vom realen Test auf **Couchtisch**: kein sichtbarer musikabhängiger Farbwechsel, Stop und ursprünglicher Zustand wiederhergestellt, Yeelight Classic anschließend weiter steuerbar. **Kein Hardware-PASS der Musikreaktion.**

## Reproduzierbarer Programmdefekt

Im ursprünglichen RC3-Kontrollfluss wird `onMusicBeat(frame)` nur innerhalb von `if (frame.beat)` aufgerufen. Der FFT-Analysator darf aber bei real messbarem Dauersignal `beat=false` melden: seine Bass-Transientenschwelle ist nicht automatisch bei jeder Musik erfüllt. Daher kann ein erfolgreich aufgebauter `set_music`-Rückkanal **null RGB-Telegramme** empfangen, obwohl das Audiospektrum echte Werte anzeigt. Ob genau dies der einzige Grund der beobachteten Nichtreaktion war, bleibt bis zu einer instrumentierten Hardwareprüfung offen.

## Abgesicherte Korrektur

- Jedes echte `MusicFrame` erreicht nun den Dispatcher; reguläre Musikshows bleiben **unverändert beat-getriggert**.
- Nur beim expliziten, auf **genau eine bestätigte Color4** und **acht Sekunden** begrenzten Test erlaubt die Policy bei nachweislich messbarer RMS/Peak/Frequenzband-Aktivität einen maximal sekündlichen Farbimpuls auf einem **bereits aktiven Rückkanal**. Kein künstlicher Beat, keine erfundene BPM, keine Ersatzkommandos über normales TCP/55443.
- Strikte Fail-Closed-Gates bleiben: spezifisches Gerät, `set_music` + `set_rgb` aus SSDP, Quarantäne, Restore-Journal, bestätigter Reverse-Channel, zeitbegrenzte Testsession. Kein Geräte- oder Cloud-Dauermodus.
- Flüchtige, private Diagnosewerte in der Musikansicht zeigen verfügbare Audioframes, hörbare Frames, echte Beat-Erkennungen und RGB-Schreibversuche/-fehler. Eine erfolgreiche Socket-Schreiboperation ist **keine optische Lampenbestätigung**.
- Der Abschlussbericht bleibt nach dem Stopp sichtbar und unterscheidet fehlende Audioframes, unzureichendes Signal, null Farbtelegramme und Übertragungsfehler.

## Regressionsumfang und Grenzen

JVM-Tests mit real synthetisierten PCM-Messframes, absoluter Stille, schwachem Signal, aktiver/inaktiver Reverse-Session, Beat-Strom, Intervall und diagnostischen Negativfällen; zusätzlich vollständige Android-JVM-/Debug-CI. Hardwaretests nur nach separater gezielter Freigabe mit einer intakten Lampe, kein Wiederholungstest auf Wellness, kein automatischer Merge oder Release. Das bereits bewertete RC3-APK wird nicht rückwirkend verändert.
