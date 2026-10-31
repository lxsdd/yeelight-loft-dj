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

## WLAN-Erkennung – Korrektur nach erstem Praxistest (5 statt 10)

- Die ursprüngliche Suche brach nach einer kurzen Antwortlücke ab und ersetzte den kompletten Bestand.
- **Geräte im WLAN suchen** läuft nun mehrfach und sammelt alle Antworten.
- Wiederholte Suche ergänzt den Bestand, bisher bekannte Geräte bleiben gespeichert. Der Text unterscheidet „bei letzter Suche bestätigt“ von „gespeichert“.
- **Intensivsuche im WLAN** prüft nur das lokale /24 des WLAN-Interfaces über den TCP-Port 55443 und `get_prop` (nur Lesen). Kein generischer Internet- oder Mobildatenscan.
- Falls nicht alle Geräte gefunden werden: zuerst WLAN und Gästnetz/Client-Isolation prüfen, danach Intensivsuche, zuletzt IP aus der FRITZ!Box ergänzen.
- **Achtung:** Diese App-Version enthält noch keine stabile Update-Signatur. Eine von einem anderen GitHub-Runner gebaute Preview kann eine Deinstallation der alten Test-APK erfordern.
- Ziel für das Loft sind voraussichtlich zehn 1S-Birnen (2 Wohnzimmer, 3 Esszimmer, 5 Hobbyraum), plus LED-Streifen mit separat zu prüfender LAN-Unterstützung. Nicht alle im Yeelight-Konto gezeigten Geräte sind zwingend im WLAN online.
