# Classic First — integrierter Funktions- und Drag-and-drop-Entwicklungsvertrag

**Stand:** 21.11.2026. Quell-/Konzeptstand des offenen Classic-Drafts PR108.
Dieses Dokument ist die **konsolidierte Umsetzungslandkarte**, kein
Geräte-, OEM-APK-, UI- oder Produktabnahmebeleg.
**Classic** und **Color4/Chroma** bleiben separate Ownership-/Abnahmestränge.

## 1. Unveränderliche Classic-Produktstruktur

Die originale App 3.5.4 verfügt bereits über Geräteansicht, Gruppen,
`RoomFragment`, `SceneFragment`, `MusicActivity`/`DiscoFragment`,
Farbwahl und Musik-/Lichtszenenbedienung. Nicht durch konkurrierende
Home-, Musik-, Szenen-, Konto-, Raum- oder Gerätedatenbanken ersetzen.
Keine eigenständige Zweit-App, kein Home-Shortcut oder dauerhaftes
am unteren Bildschirmrand aufgesetztes Debug-Panel als finale UX.

Das private, bereits verifizierte RE-Atlas-Inventar vom 17.11.2026 nennt
`RoomRecyclerViewAdapter` und `SceneRecyclerViewAdapter` sowie
Original-Manager für Räume, Szenen, Geräte und Musik. Es beweist **nicht**
die aktuellen stabilen Item-IDs oder Drag-Callbacks, und ermächtigt
keinen direkten OEM-Datenbank-/Cloud-Schreibzugriff. Das bereits
separat in der Loft-DJ-App entwickelte `LampOrder.relocate` ist
Ausgangspunkt der Sortiersemantik, nicht eine neue zweite Infrastruktur.

## 2. Einheitliche Drag-and-drop-Semantik — vollständige Zielliste

| Classic-Originalbereich | Manuelle Reihenfolge | Gesonderter Scope |
| --- | --- | --- |
| Geräteübersicht, Geräte innerhalb eines Raums | Geräte-ID | global / je Raum |
| Räume | stabile Raum-ID | global |
| Gruppen | stabile Gruppen-ID | global |
| Lampen innerhalb einer Gruppe | stabile Geräte-ID | je Gruppe |
| Szenen und Szene-Karten | stabile Szenen-ID | je Szenenbereich |
| Favoriten | stabile Favorit-/Referenz-ID | je Favoritenbereich |
| Automationen, falls originaler Listentyp belegt | stabile Automations-ID | global / Kategorie |
| Lichtlauf-Reihenfolge der Effekte | stabile Geräte-ID | je Effekt/Sequenz |

**Gleiche UX:** Haltegriff, Drag-Feedback, Verschieben an Anfang/Ende,
Reihenfolge nach App-Neustart, Offline-/neue Geräte, Gruppenwechsel,
Bearbeitungs- und Accessibility-Move-Steuerung. Niemals nur
Erstellungsreihenfolge oder numerische Eingabefelder als einzige Steuerung.
Drag **innerhalb** einer Liste verändert lediglich die Anzeige-Reihenfolge;
keine automatische Änderung von Gruppenmitgliedschaft, Lichtsteuerung,
Cloud-Szenendaten oder physischem Lampenstatus.
Umziehen **zwischen** Raum-/Gruppenlisten wäre eine gesonderte
fachliche Zuordnungsaktion, nicht eine versteckte Wirkung des Sortierens.

**Ein einziges gemeinsames Core-Modell:** `ClassicOrderPolicy`
(Stabil-IDs, Merge neuer/offline Items, fehlende/duplizierte IDs abweisen,
gültige Zielpositionen einschließlich Ende), `ClassicLocalOrderStore`
(nur clone-only/private App-Preferences, nur nach Benutzer-Drop,
sicher gegen beschädigte Einträge), `ClassicDragDropController`
(feste Schnittstelle für Original-Adapter und Accessibility-Aktionen).
Nicht mit einer neuen Geräte-/Szenen-Datenbank gleichsetzen.
Ein unbekannter/wechselnder OEM-ID-Vertrag führt zum **gesperrten
Drag-Modus**, nicht zu einem stillen Namens- oder Index-Fallback.

**WICHTIGE IST-GRENZE:** Der genannte gemeinsame Core ist
noch **keine Original-Adapter-Verdrahtung**. Erst nach dem konkreten
privaten ABI-/ViewHolder-/ItemID-Nachweis sind Drag-Handgriffe in der
originalen `RecyclerView` an genau die freigegebene Adapter-Methode zu
binden. Positive und negative Regressionsfälle für jede echte UI-Liste,
Neustart, Cloud-/OEM-Refresh, neue/offline Einträge, Wechsel der Gruppe,
Undo und Zugänglichkeitsaktionen sind Voraussetzung für die Bezeichnung
`CLASSIC_NATIVE_DND_VERIFIED`. OEM-Konten/Cloud sollen zunächst ihre
ursprüngliche Logik behalten; lokale Sortierung ist deklarierte
UI-Präferenz und kein behaupteter Cloud-Sync.

## 3. Bereits existierende Funktionen gezielt zusammenführen

| Domäne | Vorhandene Belege/Bausteine | In Classic noch zwingend |
| --- | --- | --- |
| Mikrofon + BPM/Bass/FFT | separate Loft-DJ-App und bestehender Classic-`CheckpointRuntime` | stabile echte Analyse inkl. Mic-Berechtigung und Live-Status |
| Internes Android-Audio | `InternalAudioCaptureService`, `InternalAudioBridge`, `MusicAnalyzer`; Besitzererfolg 01.11. mit Deezer/BubbleUPnP; 129 Frames, 27 Akzente, 14 RGB-Telegramme, 0 Beats | eine MediaProjection-Session je Start, dieselbe PCM/DSP-Engine, systemweites Consent, Foreground-Betrieb und Stille-/DRM-/Cast-Fallback |
| Originaler Musikfluss | originale `MusicActivity/DiscoFragment`, Einzelgerät- und Gruppeneinstiege, Fünf-Lampen-Gruppentest | originale Erststartfehler/Berechtigung, eigener DJ-Participant-Opt-in und OEM-Music-Session-Grenze |
| Effekte | Farbläufe, Regenbogen-/Meteor-/Schweif, Knight-Rider, Puls Beat/Zeit und Helligkeitsprofile | nativer gemeinsamer Color-Picker und Szene-/Live-Engine, sichere Teilnehmer-/Quota-/Stop-Logik |
| Geräte und Gruppen | Classic-Manager, Loft-DJ lokale Gruppen und Räume, `LampOrder` | keine doppelte Identität, persistente Original-UI-Reihenfolge und echte Scope-Bindung |
| Alltagsszenen | Yeelight original `SceneFragment`; Loft-DJ-Szenenarchiv JSON | vollständiger Originalbestand erst lesend erheben; kein Beispielbestand von nur sechs Szenen, keine stillen Löschungen |
| Spatial-/Floorplan | Räume und Reihenfolge vorhanden, Graph-/Grundrisseditor geplant | 2D Raum-/Tür-/Wand-/Koordinaten-Editor, freie Lampenreihenfolge, echte räumliche Wave-Algorithmen |
| Google Home / Tuya / Xiaomi | parallele Konzepte/Adapter, verschiedene Konten/Serverregionen | getrennte, lesende Übernahme mit expliziter Eigentümerfreigabe; kein Teil des Classic-DnD-Commits |

Weitere verbindliche Wünsche: Live-Szenen-/Farb-/Empfindlichkeits-/
Tempoänderung ohne Music-Neustart; ausgewählte qualifizierte Lampen bei
DJ-Start kontrolliert einschalten, Nichtteilnehmer absolut unverändert;
gesicherter Stop ohne UI-Blockade und ohne Blind-Retries; 14 Color4/1S,
1 % **sichtbar**, nur verifiziertes `set_power(off)` als echtes AUS;
Quota-/Reconnect-/Recovery-Schutz pro Lampe; Farben ohne Hex-Zwang,
Dark-Mode und responsive Tablet-UI, Material-/Classic-Typografie.

**Audio-Architekturentscheidung:** Die bereits implementierte
`MediaProjection`-/`AudioPlaybackCapture`-Route wiederverwenden;
Mikrofon und internes Audio bleiben alternative Quellen **einer**
sitzungsgebundenen DSP-/Effektpipeline. Kein MP3-Player, kein
DRM-Bypass, kein stilles Capture, kein Anspruch, dass Cast-Receiver-Audio
im Telefon-PCM vorhanden ist. Erteilte Zustimmung für Capture ist
nicht gleichbedeutend mit Freigabe physischer Lampen.

## 4. Gemeinsame IA und Bedienkonzept vor weiteren UI-Patches

- **Geräte/Räume/Gruppen:** Originalansichten bleiben Einstieg und
  besitzen einen konsistenten Bearbeiten-/Sortiermodus.
- **Musik (bestehender Originalbereich):** Quellwahl Mikrofon / internes
  Android-Audio, Live-Messung, gemeinsamer Effekt- und Szenenparameter-
  Editor, verifizierte Teilnehmer, klare Stoppaktion.
- **Lichtszene (bestehender Originalbereich):** Presets, gespeicherte
  Szenen, Farb-/Effekteditor, Favoriten und Lauflichtfolgen. Musik als
  optionale Quelle derselben Effekte; keine zweite „Studio“-Szenenliste.
- **Visualisierung:** Classic-eigene Farben/Picker/Layouts verwenden,
  keine konkurrierende Material-3-Komplettapp in einem fragmentierten
  Overlay. Funktion zuerst, Design als gleiches Endabnahmeziel.
- **Naming:** „Musikfluss“ darf später überdacht werden. Der neue
  Hauptname „Lichtstudio“ ist **nicht** beschlossen, da die App Musik
  und Lichtszene schon enthält. Zuerst originale Navigationslabels,
  Texte und tatsächliche Zuständigkeiten zuordnen.

## 5. Zusammenhängende Ausführungs-/Abnahmeblöcke

**Block A — Stabiler gemeinsamer Unterbau, aktuell:** Exact-HEAD-
Classic-CI, Nearby-First-Launch-Gate, Mikrofon-/interne Audioaufnahme
mit einem Owner, aktive/stale Callback-, Stopp- und Consent-Negativtests;
in Public-Runnern keine OEM-APK; separater Pixel6a-SHA-PIN-Test danach.
Zusätzlich vollständig gemeinsame, offline-testbare Drag-Reihenfolge
für alle acht Domänen; **kein unqualifizierter OEM-Adapter-Eingriff**.

**Block B — Original-UI-Integration:** Aus dem bestehenden privaten
3.5.4-RE-Atlas die **konkreten** RecyclerView-Adapter, stabilen Row-IDs,
Drag- und Persistenzpunkte von Devices/Rooms/Groups/Scenes/Favorites
und OEM-Neuladelogik gezielt verifizieren (kein erneuter Vollscan).
Erst dann alle nachgewiesenen originalen Listen in **einem**
funktionalen Block an `ClassicDragDropController` anschließen, mit
sichtbaren Griffen, Persistenz, Accessibility und Tests. Fehlende IDs
als echte Blockade dokumentieren, nicht durch Namen erraten.

**Block C — Music/Scene native Daten-/Effektverdrahtung:** Live-Musik-
/Quellenwahl, echte DJ-Session-Teilnehmer aus Einzel-/Gruppenidentität
plus explizites Opt-in, Original-Farbpicker, vorhandener Scene-Editor,
Puls/Scanner/Meteor/Schweif/Welle; vor echter Lampensteuerung
geprüfte Color4-Fähigkeiten und Besitzerfreigabe. Nichtteilnehmer,
fremde Sessions, Rate-Limits, ACKs, Power-Off/1S-Helligkeit und
sichere asynchrone Stopp-/Restore-Fälle komplett testen.

**Block D — vollständige UX-/Datenabnahme:** Reihenfolge nach Reboot,
Cloud-/Original-Refresh, alle Räume und Gruppen, Szenen-/Favoriten,
Tablet, Frontend-State, Original-App-Erststart; anschließend
Grundriss-/Raumgraph-Editor, relevante Backup-/Export-/Google-Home-
Übernahme nur in eigenem freigegebenem Scope. Nicht durch ad-hoc
Neubenennung oder zweite Hauptnavigation vorwegnehmen.

## 6. Grenzen und Besitzeraktionen

Keine eigenmächtigen Merges, Releases, öffentlichen APKs, OEM-Bytecode-
Exporte, Secret-/Nutzerdatei-/Cloud-/Geräteänderungen, Controller-
Fremdclaims oder Geräte-/Lampen-/Firmware-/Szenenschreibaktionen.
Originales `com.yeelight.cherry` bleibt unberührt.
Der freiwillig gestartete **Pixel6a-Klon-Install-Modus** kann nach
ausdrücklichem Eigentümerwunsch ohne zusätzliche Ja/Nein-Prompts
gestaltet werden, aber ausschließlich nach Quell-SHA, APK-SHA,
genauem `bluejay`/API37/4KiB/Paket-Check und ohne automatische
App-/Musik-/Geräteaktion. Durch den bewusst aufgerufenen Install-Modus
ist nur die Ersetzung **des separaten lokalen Testklons** autorisiert;
dessen lokale Daten können dabei verloren gehen. Keine übrigen
Geräte-/User-App-Daten dürfen geändert werden.

**Keine CI als Geräte-/Music-/Drag-UX-Abnahme ausgeben.** Je Kandidat
Original-OEM-3/6-Graph-Lücken und private Abnahmegrenzen separat führen.


## 24.11.2026 – Original SceneFragment: gültige Gesten statt später Fremdänderungen

Produktionsklasse `ClassicSceneGestureFence`: eine explizite UI-Aktion trägt
genau den ursprünglichen Fragment-Lease, die gemeinsame DJ-Szenenrevision und
die nachgelesene clone-lokale DJ-Effekt-Reihenfolge. Drag-and-drop wird bei
Fragment-Recreation, Detach, veränderter MusicActivity-Szene oder durch einen
zweiten Original-Host geänderter Reihenfolge verworfen. Die neue Prüfung
erzeugt keine Geräte- oder Netzwerkaktion und bleibt auf die sieben eigenen
DJ-Effektkarten beschränkt (keine fingierten OEM-Szenen-IDs).

Favoriten-Speichern/-Löschen aus einem offenen Dialog darf nach einer
Kontextänderung oder zwischenzeitlichem Edit desselben Slots nicht mehr
ausgeführt werden. Gespeichert wird ausschließlich das beim echten Long-Press
gewählte DJ-Profil; eine später angezeigte andere Szene wird nicht nachträglich
übernommen. Auch die Farbauswahl aus einem noch offenen Dialog ist
revisionsgebunden. Sämtliche originalen Yeelight-Szenen und Favoriten bleiben
unangetastet. Im Original MusicActivity-Panel wird eine verspätete
Szenenwahl nach Pause abgewiesen.

Positive und negative JDK8-Originalklassen-Regressionsfälle:
`test_classic_scene_gesture_fence.py` (11 Profilfelder, alte/neue Hosts,
Szenenwechsel, andere gespeicherte Reihenfolge, doppelte/fremde IDs,
spätes Favoriten-Löschen, ersetzte Favoriten und erneuter gültiger Host).
Das öffentliche SDK/Bridge-CI muss die tatsächlichen vier betroffenen
Produktions-Java-Dateien mitkompilieren. **Kein echter OEM-/Pixel-/Lampentest
und keine Freigabe zum Schreiben vorhandener Yeelight-Szenen.**
