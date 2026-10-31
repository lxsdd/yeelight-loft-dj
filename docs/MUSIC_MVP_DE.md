# Yeelight Loft DJ – echtes Musik-/Mikrofon-MVP (v0.1.3)

## Implementiert

### Quellen und Datenschutz
- Android-Tab **Musik** öffnet einen echten PCM-Audiomonitor.
- Die Aufnahme startet ausschließlich nach ausdrücklicher Berührung des Buttons
  **Mikrofonanalyse starten**. Android fordert `RECORD_AUDIO` über den
  normalen System-Berechtigungsdialog an.
- `AudioRecord` mit 16 kHz, Mono, 16-Bit PCM, 2048 Samples pro Analysefenster.
  Ein- und Ausschalten sowie Fehlerstatus im UI sichtbar.
- Aufnahme wird beim Verlassen des App-Vordergrunds gestoppt; kein
  Foreground-Service, kein dauerhafter Hintergrundbetrieb.
- **Keine** Audiodateien, Mitschnitte, Spektrendaten oder Mikrofon-Frames werden
  lokal gespeichert oder an einen Server gesendet.
- Primärer Einsatzzweck: Audio aus Pioneer DJ-/RX3-Lautsprechern, Musik am
  externen Cast-Empfänger und jeder anderen hörbaren Quelle.

### Messung statt Animation ohne Audio
- Eigenständige Kotlin-FFT mit Hann-Fenster (radix-2 FFT).
- Sechs gemessene Frequenzbänder: Bass, Kick, Tiefmitte, Mitten, Präsenz,
  Höhen; dazu RMS/Peak-Messung und sechs Live-Pegelbalken.
- Adaptiver Bass-Transienten-Detektor mit Mindestpegel, steigender Flanke
  und 300-ms-Debounce.
- Geschätzte BPM **nur**, wenn mindestens vier konsistente Beat-Ereignisse
  in einem plausiblen Zeitabstand aufgetreten sind. Ohne Nachweis zeigt die
  App `–`; das ist kein Metronom und keine BPM-Erfindung.
- Bei Stille erscheinen keine künstlich bewegten Balken oder Beats.
- Die Bass-Detektion ist ein MVP; nicht mit fertig kalibrierter DJ-Software,
  exakter Track-BPM oder garantierter Kick-Erkennung zu verwechseln.

### Musikreaktives Licht (separat freizugeben)
- Das Betätigen des Mikrofon-Start-Buttons verändert **keine** Leuchte.
- Zweiter Button **Licht auf N Lampen aktivieren** verwendet den ausdrücklich
  gewählten Zielbereich (sichere Voreinstellung: **Test (1)**).
- Vor dem ersten Lichtbefehl werden von **allen** ausgewählten Leuchten
  echte Ein-/Aus-, Farbmodus-, RGB/Kelvin/HSV- und Helligkeitsdaten gelesen,
  auf Wiederherstellbarkeit geprüft und lokal mit zuverlässigem Commit
  abgespeichert. Bei Fehlern wird die gesamte Aktivierung abgebrochen.
- Bei einem bestätigten Bassimpuls dürfen die gewählten Leuchten
  räumlich versetzt eine aus den gemessenen Frequenzbändern abgeleitete
  Farbe erhalten.
- **Pro Lampe höchstens ein RGB-Befehl pro 1500 ms**, also maximal
  40 Farbänderungen/Minute, deutlich unter dem Yeelight-LAN-Quotenlimit.
  Keine hochfrequenten Stroboskop- oder ununterbrochenen Farbkommandos.
- Beim Stoppen werden keine neuen Beat-Kommandos angenommen. Bestehende
  Farbkommandos laufen aus, **dann** wird der ursprüngliche Zustand
  wiederhergestellt. Fehler bleiben im privaten lokalen Recovery-Journal
  und können nach Neustart erneut gestoppt/wiederhergestellt werden.
- Nur lokale TCP-Kommandos; keine Cloud und keine Änderung der
  Hersteller-/Google-Home-Konfiguration.

## Nicht implementiert – verbindliche nächste Aufgaben

- Systeminternes Android AudioPlaybackCapture/MediaProjection benötigt
  gesonderte Nutzerzustimmung und Erlaubnis der Quell-App. Es dürfen weder
  DRM-/Deezer-Sperren umgangen noch interne Audiosignale versprochen werden,
  wenn die Quell-App die Aufnahme nicht erlaubt.
- Eigener lokaler Musikplayer und Analyse des von ihm abgespielten Audios.
- Tiefer gehende automatische Beat-/BPM- und Drop-/Build-up-Erkennung,
  Frequenz-Zonen und räumlicher Grundriss mit Wänden/Durchgängen.
- Google Cast spielt typischerweise auf dem Empfänger: es gibt keinen
  einfachen lokalen Smartphone-Audiostream. Mikrofon ist aktuell Fallback.
- Persistenter Betrieb im Hintergrund ist **absichtlich nicht vorgesehen**.

## Testprotokoll (nur in privatem WLAN, keine IPs öffentlich hochladen)

1. App starten. **Kein** aktives Mikrofon und **keine** Lampenänderung.
2. Im Tab **Musik** das Mikrofon aktivieren und Berechtigung erlauben.
3. Ruhe: keine erfundenen Frequenzbalken, Beats oder BPM-Anzeige.
4. DJ-Musik/Beat abspielen: sechs Pegel, RMS und Beats beobachten.
   BPM nur als Schätzwert behandeln und gegen andere Quellen vergleichen.
5. Bei **Test (1)** explizit Musiklicht aktivieren, Originalfarbe und
   -helligkeit notieren, nach Bassimpulsen stoppen und Rückkehr prüfen.
6. App verlassen während Aufnahme: Mikrofon beendet.
7. Bei zunächst ausgeschalteter Lampe aktivieren und wieder deaktivieren:
   danach muss die Lampe wieder aus sein.
8. Nach erfolgreichem Einzelgerätetest ausgewählte Loft-Gruppe testen;
   maximale 40 RGB-Befehle/Minute/Lampe respektieren.
9. Bei Fehlern im Netzwerk/fehlenden Gerätestatus: Einwandfreies
   Abbrechen oder klare Fehlermeldung ohne stille Änderungen.

**Abnahmestatus:** Quellcode + GitHub-CI ermöglichen strukturelle und
synthetische DSP-Tests. Echter AudioRecord-/Yeelight-Hardwaretest
kann nur auf dem Smartphone im privaten WLAN stattfinden.
