# Erster Android-Praxistest

Nach erfolgreich abgeschlossenem CI-Lauf die debug APK aus dem Actions-Artefakt installieren.

1. Telefon ins WLAN der Yeelight-Geräte einbuchen, LAN-Steuerung aktiviert lassen.
2. Geräte → Geräte im WLAN suchen; erwartete `color4`-Geräte prüfen.
3. Falls Discovery leer bleibt, eine private IPv4 aus der FRITZ!Box eintragen.
4. Ziel **Test (1)** auswählen und zunächst **Aurora** starten; danach stoppen.
5. Räume zuordnen (Wohnzimmer, Esszimmer, Hobbyraum), Ziel **Loft** wählen.
6. Test mit mehreren Birnen; einzelne Fehler, Latenzen und Stopp-Befehl prüfen.
7. Den LED-Streifen erst nach Bestätigung der `start_cf`-Fähigkeit einbeziehen.
8. App erneut öffnen und prüfen, ob Raumzuordnungen nach neuer Suche erhalten bleiben.

**Hinweis:** Neon und schnelle Impulse sind für lichtempfindliche Personen ungeeignet. Die APK ist ein Preview mit möglicherweise wechselndem Debug-Signaturschlüssel.
