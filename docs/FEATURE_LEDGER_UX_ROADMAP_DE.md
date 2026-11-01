# Yeelight Loft DJ – verbindliches Feature- und UX-Backlog

**Status: 1. November 2026.** Dieses Dokument ist die gepflegte
übergreifende Arbeitsliste und ergänzt
`PRODUCT_SPEC_DE.md`, `UNIQUE_FEATURE_VISION_DE.md`,
`COMMAND_AUDIT_KNIGHT_RIDER_025_DE.md` und konkrete PRs.
Nichts wird aufgrund eines Chatwechsels oder einer neuen UI-Architektur gestrichen.
Keine geplante Funktion ist allein wegen dieses Dokuments implementiert.

## Verbindliche Regeln (alle Releases)

- Android-first, Kotlin + Material 3, lokal/ohne Cloud für LAN-fähige Yeelights;
  Windows optional. Kein Konto für bereits konfigurierte lokale Lampen erforderlich.
- **UI-Qualität als Abnahmekriterium**: hochwertige Dark-Mode-Flächen,
  visuelle Szenenkarten, klare Primäraktion pro Seite,
  maximal wenige kurze Statuszeilen, technische Details hinter
  `Details`/eigenen Seiten, keine erzwungene horizontale Chip-Navigation,
  vertikale Auswahl und responsive Tablet-Layouts.
  Zustände visuell über Status-/Farbflächen statt technische Textwüsten
  darstellen. Bedienung ohne Hex-/IP-Eingabe für normale Aufgaben.
- **Keine Funktionalität bei UI-Neuordnung verlieren.** Einzel-, Raum-,
  Auswahl- und Gruppensteuerung müssen erreichbar bleiben. Android Back,
  Editorzustand und benannte Gruppenzuordnungen bleiben persistent.
- **Dunkel bedeutet physisches AUS**: 0 % ist NICHT durch Yeelight
  `set_bright` garantiert; auch 1 % ist klar sichtbar. Keine
  Scanner-/Schweifsimulation als „aus“ kennzeichnen; Scanner nutzt
  `set_power(off)`, bestätigt via `get_prop(power)`.
- Kein ständiger Verbindungs-/Befehls-Spam. Yeelight 4 TCP-Clients, max.
  60 Kommandos/Minute/Connection und 144 gesamt: Wiederverwendung oder
  Serialisierung, Rate-Limits pro Lampe, messbare Antwort-/Zustandswerte
  und Keine-Blind-Retries bei unbekanntem ACK.
- Firmware-Zustand `flowing`, Stromstatus, ursprünglicher Zustand,
  Geräte-SUPPORT, App-Befehlsabdeckung und UI-Verfügbarkeit sind
  getrennte Fakten. Keine falschen „erfolgreich“-Meldungen.
- Recovery sperrt nur tatsächlich betroffene Lampen, nicht die
  gesamte App. Vor Freigabe **zuerst lesend prüfen**, sicher
  abgeschaltete und wiederhergestellte Lampen automatisch gezielt
  freigeben; andere bleiben vermerkt. Bewusstes Verwerfen des
  App-Journals ist eine separat benannte Ausnahme und keine
  Geräte-Reparatur.
- Private IPs, Raumlisten, Lampenlabels, Audiodaten und Grundrisse nie
  im öffentlichen Repository/PR. GitHub Actions auf Public Runner;
  keine eigenmächtigen Merges, signierten Releases oder Geräte-Resets.

## Fortschrittsmatrix (Implementierungsstatus ≠ Hardware-Abnahme)

| ID | Funktion / Soll | Implementiert | Hardwarebeleg / nächster Schritt |
|---|---|---|---|
| CORE-01 | LAN-Discovery Multicast + TCP + gezieltes UDP mit SUPPORT | v0.2.3 main | 6/13/7-Test; IP-Wechsel/Duplikate weiter testen |
| CORE-02 | Alias, Räume, frei benannte Gruppen, Favoriten/Mehrfachauswahl und Sortierung per Drag & Drop | Teilweise | End-to-End für alle Lampen; Raumgraph/Favoriten noch offen |
| CORE-03 | Einzel- und Gruppen Ein/Aus, Brightness, RGB, HSV, Weißtemperatur, Status-Readback | Vorhanden | echte Color4-Fähigkeiten, Fehler- und Timing-Messung |
| CORE-04 | Protokollaudit aller `support`-Methoden einschließlich `bg_*`, Cron, Relative, Segment, `set_music` | Katalog PR #24 | gerätespezifische Befehle und UI weiter implementieren |
| SAFE-01 | Quota- und Recovery-Fehler lokal begrenzen | Teilweise v0.2.3, PR #23/#24 | Dauerhafte Ursache `client quota exceeded` inkl. Messwerte untersuchen |
| SAFE-02 | Nichtschaltende Lampen `flowing` + Power + Zustand lesend prüfen, bestätigte Fälle ohne Nutzer-Neustart freigeben | PR #25 in Entwicklung | Firmware ohne `flowing` darf nicht falsch freigegeben werden |
| SAFE-03 | Persistente Wiederherstellung, pro Lampe statt globaler Sperre, optional bewusstes Verwerfen | PR #23/#24 | Force-stop/Crash/Offline/Quota-Abnahme |
| FX-01 | Native Farbflows mit Schweif, Phasen, Geschwindigkeit, Richtung | Vorhanden | 1-%-Nachleuchten bleibt; niemals echtes AUS behaupten |
| FX-02 | Knight-Rider-Scanner mit OFF-vor-ON, direkter Farbwahl, Pingpong, Geschwindigkeit, Auswahl, sicherem Stop | PR #24 | zuerst 3 dann 5 dann 15 Lampen; Quota- und Stop-Langzeittest |
| FX-03 | Graphbasierte räumliche Effekte, Höhe, Wände/Türen, Wellenfront, Layer | Geplant | 2D-Grundrisseditor, kein falsches 3D-Versprechen |
| AUDIO-01 | Mikrofonaufnahme, FFT, Bass/BPM, Musiklicht | MVP vorhanden | echte Smartphone-/DJ-Hardwareabnahme, adaptive Analyse |
| AUDIO-02 | **Andere interne Smartphone-Audioquellen** via erlaubtes AudioPlaybackCapture (z.B. BubbleUPnP/YouTube) | v0.2.1 integriert | erlaubte Quell-Apps testen; DRM-Verbote respektieren |
| AUDIO-03 | Google Cast am Receiver: Mikrofon-Fallback oder zulässige Integration; kein vorgetäuschter Handystream | Fallback teilweise | Echte Cast-Receiver-Sitzung testen |
| AUDIO-04 | **Keinen eigenen MP3-Player bauen** (ausdrückliche Entscheidung) | Produktconstraint | andere Player anzapfen, soweit Android erlaubt |
| AUDIO-05 | eigener hochwertiger DJ-FX-/Beat-Scene-Mixer inkl. Hysterese/Latenzsteuerung | Geplant | Signalgate, Musik-Layer, On-device only |
| UX-01 | optische, weitgehend textfreie Start-/Szenen-/Geräte-/Musik-Karten | teilweise | Hauptansichten visuell überarbeiten |
| UX-02 | kurze vertikale Zielauswahl statt horizontaler Chiplisten; Gruppen und erweiterte Optionen separat | PR #25 in Arbeit | Navigation, Tablets, Screenreader, Persistenz testen |
| UX-03 | echte Farbfelder, Farbwahl ohne Hex-Zwang, Mini-Vorschau, max. eine Hauptaktion pro Abschnitt | teilweise | harmonisches M3-System konsolidieren |
| UX-04 | Gerätestatus, Netzwerk und Recovery als kompakte visuelle Indikatoren; Diagnose eigene Unterseite | teilweise | lange Fehlertexte aus Hauptseiten entfernen |
| UX-05 | Szeneneditor beim Start offen halten, Live-Steuerung unabhängig von Konfiguration | teilweise | Regression bei App-Rotation/Navigationswechsel |
| HOME-01 | Alltagsszenen, Übergänge, Favoriten, Timer/Schedules | Teilweise / geplant | Cron nur per SUPPORT und mit Rückgängig |
| HOME-02 | Mehrkanal/Zonen, Yeelight `bg_*`, Segment-Lampen, Mi Desk Lamp 1S | Noch nicht | MiIO derzeit nur lesende Diagnose; keine Schreib-Kompatibilität behaupten |
| HOME-03 | Geräteprovisionierung/Yeelight-Firmware/Cloud/Google Home Migration | Nicht durch LAN alleine möglich | offizielle/protokollkonforme Integration separat |
| MAP-01 | Interaktiver Wohnungsplan, x/y/z, Türen, Wände, Bild/PDF-Hintergrund, Raumnachbarschaft | Geplant | lokale Speicherung, Export, Drag/drop |
| MAP-02 | Gruppe-/Raumübergreifende Pfade inkl. geometrischem/graphbasiertem Modus | Geplant | räumliche Laufalgorithmen und Previews |
| PLATFORM-01 | dauerhaft gleich signierte Update-APK, korrekte BuildConfig, JSON-Szenenimport/-export | vorhanden | alle Migrationspfade und APK-Downloads testen |
| PLATFORM-02 | Szenen-Backups, Tablet-Layouts, helle Darstellung optional, Geräte-Eigentümer/Konfliktvermeidung | Teilweise/geplant | getrennte Hardwareabnahme |

## UX-Zielbild (Informationsarchitektur)

1. **Home:** vier klar anklickbare Karten: Räume/Gruppen, Licht einschalten,
   letzte Szene, Musik. Aktiver Effekt als kompakte Live-Kachel; Stopp immer sichtbar.
2. **Szenen:** visuelles Kartenraster, schnell `Start/Stopp`, eigene
   Seite pro Effekt; Knight Rider als eigener Editor, nicht als
   horizontal scrollende Chipleiste.
3. **Geräte:** kompaktes Statusinventar, Farb-/Schalt-Schnellaktionen,
   Drag-and-drop-Lichtweg als eigener Bearbeitungsmodus; Detailseite
   mit Register „Steuerung“, „Fähigkeiten“, „Diagnose“.
4. **Musik:** Quelle auswählen, Live-Frequenzanzeige, Effektzuordnung,
   Licht steuern; Berechtigung und Aufnahme-Grenzen als kurze Hinweise.
5. **Ziele/Gruppen:** vertikale Dropdown-Auswahl mit Anzahl der Lampen;
   Räume, benannte Gruppen, Einzellampen und Auswahl editieren auf
   getrennten Seiten; kein horizontaler Swipe nötig.
6. **Diagnose/Wiederherstellung:** wenige Ampelzustände und Primäraktion
   `Jetzt lesen & prüfen`; Sekundäraktionen `Normal wiederherstellen`
   und `Alten App-Eintrag bewusst verwerfen` hinter Details/Bestätigung.
   Nur wenn ein Firmwarezustand und eine gültige Wiederherstellung
   bestätigt sind, darf die App den Journalmarker automatisch löschen.

## Abnahme für jede neue Funktion

- Quellcode + pure JVM-Regressionen + Android Build erfolgreich.
- Keine Datenmigration beschädigt vorhandene Lampen/IDs, Gruppen,
  gespeicherte Presets und Recovery.
- Leuchten physisch getestet: 3 Esstisch → 5 → 15 Color4;
  Reconnect, Offline, Quota, Android-App-Stop.
- Kein blindes „funktioniert“ aus erfolgreicher CI;
  echte Lampen-/Musikergebnisse gesondert kennzeichnen.
- Feature im **Backlog belassen** bis hardwareseitig abgeschlossen;
  neue PR enthält konkrete ID und Statusänderung.

## Verknüpfte Reviewkette

- `main` aktuell v0.2.3/build 14, vertrauenswürdig signiert.
- PR #23 v0.2.4: lokales Recovery-Verwerfen und korrektes UDP/TCP-Label.
- PR #24 v0.2.5: Scanner und Protokoll-Katalog (baut auf #23).
- PR #25: diese gesicherte Roadmap, Zustand prüfen und vereinfachte
  Benutzerführung (baut auf #24). **Nicht** ohne freigegebene
  Reihenfolge auf main mergen.
