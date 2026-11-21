# Classic: native Audio-/Licht-Integration und UI-Vertrag
Stand: 21.11.2026. Diese Datei ist ein **quelloffener Architekturvertrag**, keine
OEM-/Pixel-Produktabnahme. Eigentumsbereiche: Classic separat von Color4/Chroma.

## Beibehalten statt doppeln
- **Originale Classic-Geräteliste/Gruppe:** Die Original-App bietet Musik über
  Einzellampe **und** Gruppe. Der selektierte OEM-Kontext ist eine Auswahl,
  **keine** automatische Freigabe aller Gruppenmitglieder für Loft-DJ-Befehle.
  Fremde Yeelight-Musiksessions und Nichtteilnehmer bleiben unangetastet.
- **Originaler Musikmodus (heute „Musikfluss“):** Ein einheitlicher
  Quellen-/Analyse-/Live-Effekt-Bereich innerhalb des vorhandenen
  MusicActivity/DiscoFragment. Keine zweite Musik-App und kein Home-Shortcut.
  Kurzfristig die vorhandenen Musik-Controls funktional stabilisieren; danach
  deren Integration in Original-Layout/Design ohne dauerhaftes angeklebtes
  Standard-Button-Panel. Kein neuer Hauptbereich namens „Lichtstudio“,
  solange dieser die existierende „Lichtszene“ duplizieren würde.
- **Originaler Bereich Lichtszene:** bestehende Szenen und Favoriten behalten;
  ergänzte dynamische Effekte (Scanner, Meteor/Schweif, Welle, Puls) teilen
  denselben validierten Effekt-/Farb-/Gruppen-Kontext und können Musik als
  optionale Eingangsquelle benutzen; keine zweite Szenen-Datenbank.
- **Quelle statt Modusduplikat:** Mikrofon und internes Android-Audio sollen
  den **gleichen** DSP-/FFT-/Beat-/Puls-/Szenenpfad speisen. Stopp,
  Session-Ownership, Ziel-Fences und Bedienelemente müssen für beide gelten.
  Quellenwechsel atomar, nie zwei aktive Capture-Worker.

## Schon vorhandene, wiederzuverwendende Audiofunktion
Die separate Loft-DJ-App im **selben Repository** implementiert bereits
`InternalAudioCaptureService.kt`, `InternalAudioBridge.kt`,
`MusicAnalyzer.kt` und den Systemdialog in `MusicScreen.kt`.
`MediaProjection` + `AudioPlaybackCaptureConfiguration` zeichnet
ausschließlich erlaubte Android-Playback-Streams (Media/Game/Unknown) in
16-kHz-PCM auf, analysiert im Speicher, sendet nichts in die Cloud und
bietet eine Foreground-Service-Stoppaktion; eine stille/gesperrte Quelle
wird als solche angezeigt. Deezer und BubbleUPnP wurden am 01.11.2026
im ursprünglichen separaten App-Strang auf einem Pixel erfolgreich getestet;
der RC5-Test meldete 129/129 Frames, 27 Akzente, 14 RGB-Telegramme,
0 Übertragungsfehler, aber 0 Beats: Beat-Erkennung bleibt offen.
**Kein neuer MP3-Player**, keine Umgehung von DRM/Quell-App-Capture-Sperren,
kein Receiver-Cast-Audio als lokalen Stream ausgeben.

## Vereinbarte Integrations-Schnittstelle Classic
1. Bestehendes `ClassicDjService` bleibt alleiniger, session-generations-
   gebundener DSP-/Aufnahme-Owner. Mikrofon und freigegebenes Playback-Capture
   liefern `ClassicPcmCaptureSession.Input` (bereits geprüfte PCM-Abstraktion);
   Original-`DiscoFragment` bleibt Musik-UX-Host.
2. Für internes Audio muss vor jeder Session der originale Android-
   `MediaProjectionManager.createScreenCaptureIntent()`-Dialog erscheinen.
   Auf Android 14+ ist der Foreground-Service mit
   `mediaProjection`-Typ vor Verwendung des Tokens aktiv; jeder Token
   ist einmalig und bei Stop/Entzug freizugeben. Capture-Callback, Cancel,
   Fehlstart, leere Frames, Stop während Erststart und App-Wechsel werden
   generational positiv/negativ getestet. Kein stilles Reuse/Auto-Grant.
3. Erst wenn OEM-Einzel-/Gruppenidentitäten **und** explizit zugelassene
   DJ-Teilnehmer verifiziert sind, darf die opt-in Transport-Bridge im
   Gerätebereich rückkanalbasierte, begrenzte Lichtbefehle ausführen.
   Originale Classic-Gruppenmusik ist nicht automatisch eigener DJ-Transport.
4. Persistenz und Drag-and-drop der vorhandenen Geräte-, Gruppen-,
   Favoriten- und Szenen-Bereiche nur über qualifizierte Classic-Adapter;
   keine neue konkurrierende Geräte-/Szenen-Datenbank.

## Erststart-Berechtigungen
Der Eigentümer berichtet auf Pixel6a/Android17, dass der getrennte Klon
bei erstem Classic-Start ohne manuell erlaubte „Geräte in der Nähe“
abstürzt. Der neue **ausschließlich clone-only** Launcher-Vorgang
fragt BLUETOOTH_SCAN/CONNECT und, auf Android 13+, NEARBY_WIFI_DEVICES
über Androids normalen Runtime-Dialog **vor OEM Activity-Start** ab.
Originale Paketidentität, Original-OEM-Provider, Original-OEM-Layouts
und isolierte Original-Offline-Control bleiben unverändert. Verweigerung
blockiert den OEM-Start anstatt zu umgehen oder automatisch zu erlauben.
Ein gleichzeitiger alter Berechtigungsstatus auf dem Gerät ist **kein**
gültiger First-Run-Test: neuer Klon mit explizit durch Besitzer
bestätigter lokaler Klon-Datenentfernung und Gerätetest ist nötig.

## Qualifikation und Reihenfolge
- Source-JVM-Regressionen + tatsächlicher Android-SDK-Bridge-Build +
  public No-OEM Android17, alle am exakt gleichen PR108-HEAD.
- Besitzer-privater OEM-Testbuild mit vorhandenen 3/6 ursprünglichen
  Klassengraph-Lücken weiterhin **UNQUALIFIED**; APK nicht öffentlich.
- Pixel6a: frischer First-Run ohne Nearby → Android-Prompt → Consent /
  Ablehnung → originale Geräteliste, ohne Lampenbefehle. Danach im
  realen MusicActivity-Kontext 0-Ziel-Analyse und internes Audio mit
  Android-Systemfreigabe; echte Quelle und Display/BPM/Pegel prüfen.
- Erst anschließend exakte Geräte-/Gruppen-Teilnehmerfreigabe und
  positive/negative mehrlampige Szenenabnahme; bis dahin keine
  automatischen Befehle oder Behauptung vollständiger Integration.
