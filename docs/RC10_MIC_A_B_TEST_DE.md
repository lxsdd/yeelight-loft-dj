# RC10: Android Mikrofon-A/B und Rohpegel, basierend auf realem RC9-Erfolg

## Verifizierte RC9-Hardwarebeobachtung des Nutzers

Eine einzelne Color4 hat unter RC9 mit Android-Mikrofonpegel ×4 sichtbar die Farbe geändert; der Test meldete music_on=0 und Yeelight Classic funktionierte danach weiterhin. Das ist ein einmaliger kurzer Hardwareerfolg, **keine** Langzeit-, Mehrlampen- oder Quota-Root-Cause-Abnahme. Windows-Laptop-Rohpegeldaten sind nicht Android-Messwerte.

## Implementiert auf neuem isoliertem RC10-Draft #62

- Wählbares Android-Aufnahmeprofil MIC / VOICE_RECOGNITION / VOICE_COMMUNICATION und UNPROCESSED nur bei bestätigter OS-Eigenschaft. Aufnahme zuerst stoppen; keine Telefonlautsprecher-Freisprechumschaltung.
- Vor der lokalen ×-Verstärkung gemessener Android-RMS/Peak (dBFS); Roh- und digital erzeugtes PCM-Clipping getrennt. Anzeige der tatsächlichen AudioRecord-Eingangsroute. Werte nur vorübergehend in App-RAM, keine Audioaufzeichnung/Cloud.
- Optionaler digitaler Auto-Analyse-Gain ×0,5..4, Ziel-RMS 0,09 und zusätzliche Peakbegrenzung; keine Hochregelung von Stille/Rauschen unter dem Mindestpegel. Bei digitalem Verstärkungswechsel wird die FFT-/Beat-Historie neu begonnen, damit kein künstlicher Beat durch die Änderung entsteht. Kein Ändern der Hardware-Mikrofonverstärkung oder Lautstärke.
- Der funktionierende RC9 Color4-Rückkanal, seine 8-Sekunden-/8-Frame-Begrenzung, Quarantäne- und Readback-Sicherungen bleiben im Ausgangscode unverändert. Keine automatischen Lampenbefehle durch Änderungen an der Audioeingabe.
- Versionskennung RC10, Build 28. Nur der Repo-Eigentümer kann nach grünem exaktem PR-CI über eine manuelle Workflow-Dispatch-Berechtigung das vorhandene Signierzertifikat verwenden. RC10 erzeugt dann ausschließlich ein temporäres signiertes Actions-Artefakt, **keinen GitHub-Release**.

## Praktischer RC10-A/B-Test nach Build/Installation

1. Auf der Musik-Seite bei gestopptem Mikrofon zuerst MIC und Analyse ×1 wählen; Auto-Aus. Mit unverändertem Smartphone-Standort und gleicher Musikquelle **nur** Android-Roh-RMS/Peak, digitalen RMS/Peak und Clipping notieren. Keine Lampe schalten.
2. Mikrofon stoppen, VOICE_RECOGNITION wählen, erneut starten. Rohpegel und Frequenzbänder vergleichen. Dasselbe mit VOICE_COMMUNICATION; UNPROCESSED nur falls in der Liste. Die Profile können Android-Gerätehardware unterschiedlich routen. Bei unerwarteten Routen oder Fehlermeldungen MIC zurücksetzen.
3. MIC wählen und optional begrenzte automatische Analyseverstärkung aktivieren. Bei gleichbleibender Musik Anzeige "Analyse ×..." mit Roh-RMS vergleichen. Der errechnete Verstärkungswert darf nicht als analoge Mikrofonempfindlichkeit bezeichnet werden.
4. Erst nach reinem Audio-A/B und bei einer **intakten einzelnen Color4** einen kurzen RC9-Rückkanaltest mit RC10 anfragen. Keine parallele Yeelight-/andere LAN-Steuerung. Bei Quota oder music_on unbestätigt nicht wiederholen. Sichtbare Farbänderung, music_on=0 und Classic danach sind die relevanten Abnahmekriterien.
5. Danach getrennt messen: längere Einzel-Lampen-Musikshow, andere Lampen, Räume/Gruppen und fließende Chroma-Animationen erfordern jeweils eigene kontrollierte Hardwarequalifikation. Nicht aus CI ableiten.

**Hinweis:** Bei ×4 kann die Android-Lichtreaktion bereits durch den festen RMS-Testschwellwert erzeugt werden. Das bedeutet nicht automatisch, dass das Smartphone ein schlechtes Mikrofon hat. Die Rohmessung soll die bisherige Spekulation ersetzen.
