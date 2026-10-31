# Yeelight Loft DJ – Produktvorgaben

## Basis
- Android (Pixel 9, Pixel 6a und Android-Tablet), Kotlin / Jetpack Compose / Material 3.
- Lokales Yeelight-LAN für `color4` (1S); LED-Streifen erst nach Kompatibilitätsprüfung einbeziehen.
- Wohnung: Gästebad → Flur → Wohnzimmer → Esszimmer → Hobbyraum → Masterbad → Schlafzimmer.
- Zusammenhängendes Loft: Wohnzimmer (zwei Birnen + Streifen), Esszimmer (drei in der Höhe gestaffelte Birnen), Hobbyraum (drei nebeneinander über Billard + zwei weitere Birnen).
- Yeelight-App und Google Home bleiben erhalten.

## UX
- Modern, hochwertiger Dark Mode, Animationen und visuelle Szene-Karten.
- Kernaufgaben in wenigen Berührungen; technische Parameter im Diagnosebereich.
- Klare Feedbackmeldungen; keine simulierte Musikvisualisierung ohne echte Quelle.

## v0.1 (erste Entwicklungsstufe)
- [x] Android-Grundstruktur und GitHub-Actions-Workflow geschrieben.
- [x] Yeelight-LAN-Suche, lokale IP-Fallback-Steuerung, vier Farbanimationen.
- [x] Lokal gespeicherte Raumzuordnung, erste visuelle Oberfläche.
- [ ] GitHub-CI erfolgreich abgeschlossen und APK-Artefakt geprüft.
- [x] Erste Farbverläufe auf einer echten Yeelight im Modus **Test (1)** erfolgreich (Nutzerbericht, 31.10.2026).
- [ ] Vollständige Hardware-Abnahme inklusive Gerätesuche, mehrerer Lampen, Stop-Befehl und LED-Streifen.

## Weiterer Ausbau
1. Mikrofonaufnahme und Echtzeit-Spektrumanalyse mit nutzerkontrollierter Berechtigung.
2. Musiksynchronisierte Gruppenanimationen, Beats/BPM, Grenzen für WLAN-Kommandoraten.
3. Internes Android-Audio über MediaProjection und AudioPlaybackCapture, sofern Quell-App erlaubt.
4. Google Cast: Audio wird am Empfänger abgespielt; Mikrofon-Fallback ohne falsche Zusagen.
5. Lokaler Musikplayer, visuelles Szenenstudio, räumliche Gerätefolge, optionaler Google-Home-Trigger.
6. Stable Release Signing getrennt von Quellcode und Build-Logs.

## Sicherheitsregeln
- Öffentliche Runner, keine privaten Gerätedaten im Repository.
- Keine eigenmächtigen Merges oder Releases.
- Keine Internetfreigabe für unverschlüsseltes Yeelight-LAN.

## Erweiterte Produktvision (31.10.2026)

**Ziel:** Android-first, local-first Ersatz der Yeelight-App für sämtliche im Heimnetz
unterstützten täglichen Lichtfunktionen. Die DJ-Musikvisualisierung bleibt ein
gleichwertiges Unterscheidungsmerkmal und wird nicht gestrichen.

### Geräte, Räume, Gruppen, Grundriss
- Geräteinventar mit stabiler Identität bevorzugt aus Yeelight-Geräte-ID;
  bei reiner TCP-Erkennung IP-Fallback und spätere Zusammenführung beim SSDP-Treffer.
- Frei benennbare Lampen sowie lokale Zuordnung zu Raum, mehreren Gruppen und
  einer globalen oder gruppenbezogenen **Reihenfolge**.
- Räumlicher Editor: Grundrisse pro Etage/Raum mit frei verschiebbaren Punkten;
  normierte x/y-Positionen und optionale Höhe (z) pro Lampe; Zoom und Rotation
  nach Bedarf, alles lokal und exportierbar.
- Geräteidentifikation per kontrolliertem kurzem Aufblinken mit Wiederherstellung
  des vorherigen Zustands. Nur auf ausdrückliche Berührung.
- Szenen/Gruppen können manuelle Sortierung oder räumliche Anordnung verwenden.
- Effekte: Raum-übergreifendes Lauflicht, Wellenfront anhand Position und
  Geschwindigkeit, Spiral-/Kreisbewegungen, Zonen, Audio-Bass-Pulse und
  frequenzabhängige Gruppen. Berechnungen erfolgen auf dem Telefon.
- Gleichzeitige Steuerung vieler Yeelights muss die unterstützten LAN-Funktionen,
  Befehlsraten und Netzwerk-Latenz berücksichtigen; keine perfekte
  Millisekunden-Synchronität versprechen.
- Lokale Sicherung/Export/Import der Lampenstruktur, bevorzugt JSON, ohne
  öffentliche Offenlegung persönlicher Adressen/IPs.

### Alltag und Migration von der Yeelight-App
- Schalten, Dimmen, RGB, Weißtemperatur, Übergänge, Status-Updates,
  Favoriten, zeitgesteuerte Szenen (Android-Betriebsrestriktionen berücksichtigen).
- Kein Konto und keine Cloud **für die Steuerung bereits eingerichteter,
  LAN-fähiger Geräte im selben WLAN**.
- Neue WLAN-Geräte provisionieren, Firmware aktualisieren, Yeelight-Cloud-Szenen,
  Remote-Zugriff und etwaige Google-Home-Cloud-Anbindung sind **nicht** durch
  das LAN-Protokoll allein gegeben. Diese Lücken gesondert behandeln, bevor
  der Nutzer die Hersteller-App oder sein Konto entfernt.
- Bedienoberfläche hochwertig und selbsterklärend: Compose Material 3,
  visuelle Raumkarten, direkt-manipulierbarer Grundriss, responsive Tablet-UI,
  dunkles und optional helles Design.
- Mikrofon-Analyse, Android AudioPlaybackCapture soweit von Quell-App erlaubt,
  Google-Cast-Mikrofon-Fallback, eigener lokaler Player: weiterhin geplant.

### Umsetzungsempfehlung
1. Zuverlässige Geräteliste, Identifikation, Lese-/Schreibbefehle, Live-Status.
2. Persistentes Datenmodell (Lamp/Room/Group/Location/Order/Scene), lokal sichern.
3. Gruppen- und räumlicher Editor, Multi-Device-Ausführung, Lauflicht/Lichtwellen.
4. Alltagssteuerung und Szenen-/Zeitplanverwaltung vollständig machen.
5. Musik/DJ-Engine, Android-Interne-Audioquellen und erweiterte Visualisierung.
6. Lückenprüfung gegenüber Yeelight-App vor endgültiger Migration.

**Hardware-Abnahme:** Erfolgreicher Farbverlaufs-Test einer echten Birne im
Modus "Test (1)" ist bestätigt. Mehrlampen- und Langzeittest noch offen.

### Nichtlinearer Wohnungsgrundriss und räumliche Lichtwege (verbindliche Präzisierung)

Die Räume verlaufen **nicht** durchgehend linear hintereinander. Teilweise liegen sie
nebeneinander, sind durch Wände getrennt und über Flure oder Türöffnungen
verbunden. Die exemplarische Reihenfolge Wohnzimmer → Esszimmer → Hobbyraum
ist **kein** geometrisches Abbild des tatsächlichen Grundrisses.

- **Grundriss als editierbare 2D-Zeichenfläche**, auf Wunsch mit einem importierten
  Foto, Bild oder PDF-Grundriss als Hintergrund. Zeichnen von Raumkonturen,
  Wänden, Türen/Durchgängen und optional Möbeln/Zonen.
- Jede Lampe besitzt **x/y-Koordinaten innerhalb eines Raums** und optional eine
  **z-Höhe** (wichtig für unterschiedlich hoch montierte Esstisch-Lampen).
- Ein **Verbindungsgraph** legt benachbarte Räume, Türen und Durchgänge fest,
  damit Lichtwellen nicht unrealistisch durch geschlossene Wände springen.
- **Drei unabhängig einstellbare Effektlogiken:**
  1. Freie manuell definierte Lampenreihenfolge für Lauflichter.
  2. Geometrische Wellenfront nach realer Distanz zu einem Startpunkt;
     optionale Wände als Abschirmung.
  3. Raum- und durchgangsbasierter Weg entlang des Verbindungsgraphen
     für natürliche Übergänge um Ecken und durch Türen.
- Gruppen und Effektpfade können unabhängig von der Raumzuordnung
  zusammengestellt und gespeichert werden.
- Grundrisseditor mit Drag-and-drop, Zoom/Pan, Snap und
  Einzel-Lampen-Identifikation per Testsignal. Automatische
  Lampenlokalisierung ohne zusätzliche Positionssensorik nicht behaupten:
  assistiertes Einmessen statt vorgetäuschter Präzision.
- Alles lokal speichern, exportieren und wieder importieren. Private
  Grundrisse, räumliche Koordinaten und Netzwerk-IPs nicht ins
  öffentliche GitHub-Repository hochladen.
