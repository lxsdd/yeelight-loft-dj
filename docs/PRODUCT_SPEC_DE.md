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
- [ ] Praxistest am Pixel und Yeelight-Geräten erfolgreich.

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
