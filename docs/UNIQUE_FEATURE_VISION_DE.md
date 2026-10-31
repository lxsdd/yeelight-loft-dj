# Produktideen – Yeelight Loft DJ als eigenständige, außergewöhnliche App

Diese Vision ergänzt die verbindliche `PRODUCT_SPEC_DE.md` und ersetzt keine
bisherigen Anforderungen. Android ist Hauptplattform, Windows optional.

## Priorität A: Musikmotor und räumliches DJ-Licht (nächster großer Funktionsblock)

1. **Musikquellen-Eingang** mit drei transparent ausgewiesenen Betriebsarten:
   Smartphone-Mikrofon für XDJ-RX3/Lautsprecher/Cast, eigenes
   lokales Audio und erlaubte interne Android-Wiedergabe via
   `MediaProjection`/`AudioPlaybackCapture`. Keine Zusage, DRM oder
   Aufnahmeverbote anderer Apps umgehen zu können.
2. **On-device Audio-Engine**: FFT-Bänder, Bassdrum-/Onset-Signale,
   Pegelautomatik, adaptive Störgeräuschfilterung und
   BPM-/Beat-Konfidenz. Audioframes werden nicht in die Cloud gesendet.
3. **DJ-FX** mit frei verschaltbaren Reaktionen: Bassimpuls, Kick-Welle,
   Frequenz-Split pro Raum, Beat-getriggertes Sternschnuppenlicht,
   stille Abschnitte, Build-ups und Drops; Latenz-/Hysterese-Schutz.
4. **Echte, nicht simulierte Live-Visualisierung** von Eingangssignal,
   Frequenzen, Beat-Confidence und tatsächlich angesteuerten Lampen.
   Keine erfundenen Beats; Source-Limits im UI sichtbar.

## Priorität A/B: Echter nichtlinearer Grundriss statt Raumliste

- 2D-Grundriss-Canvas mit optionalem lokalen Bild/PDF-Hintergrund,
  Wänden, Türöffnungen, Zonen, Möbeln; bei Lampen x/y und optional Höhe z.
- Drei alternative Pfadmodelle: manuell sortierter Pfad, geometrische
  Wellenfront mit Wandabschirmung, und Raum-Tür-Verbindungsgraph.
- **Loft Composer**: Effektpfade im Grundriss zeichnen; Meteore laufen
  um Ecken, Wellen passieren Türen, Höhenstaffelungen in Esszimmer
  lassen sich sichtbar und steuerbar berücksichtigen.
- Assistierte Lampenidentifikation per kontrolliertem Aufleuchten und
  manuellem Zuordnen; optional Kamerakalibrierung nur mit
  ausdrücklicher Berechtigung, keine erfundene automatische Ortung.

## Priorität B: Differenzierung durch Präzision und Kontrolle

- **Latenzkalibrierung je Leuchte**: gemessene Antwort-/Schaltzeiten,
  daraus bewusstes Vorziehen langsam reagierender Lampen. Keine
  Millisekunden-Genauigkeit ohne Hardwarebeweis versprechen.
- **Szenen-Layer und Übergänge**: Ambient-Grundlicht + DJ-Ereignisse
  + einzelner Spotlight-Impuls, klar priorisiert; optionales
  Zurückkehren zu vorherigem Lichtzustand.
- **Sicherheitsregler für Fotosensitivität**: maximale Blinkfrequenz,
  Helligkeit/Übergangsdauer, strikte Default-Grenzen und Warnungen.
- **Presets als portable Einzeldatei** und Szenenpakete; Version,
  Offline-Backup/Restore, keine privaten Geräteinformationen.
- **Geräte-Heilung**: Offline-/Adresswechsel-Erkennung, UDP-vs.-TCP-
  Vergleich, Lesestatus und nachvollziehbare Diagnose ohne Cloud.

## Priorität C: Alltag und Wow-Momente

- **Aktivitäts-Profile**: Billardabend, Dinner, Arbeiten, Filme,
  nächtlicher Weg durch den Flur, sanftes Erwachen – lokal.
- **Tageslicht und Umgebungsstimmung** mit lokal einstellbarer
  Zeit-/Kelvin-Kurve und manueller Übersteuerung.
- **DJ-Show-Aufzeichnung als Ereignis-Timeline** (Musik-BPM-/Licht-
  Trigger, ausdrücklich ohne Musikmitschnitt), später abspielbar.
- **Mehrgeräte-/Tablet-Regie** mit benanntem Controller-Owner und
  Konfliktvermeidung, optional lokalem Studio-/Live-Modus.
- **Einzel-/Gruppen-Health** für alle Color4 und Stripe, ohne
  unnötige Firmware- oder Kontoabhängigkeit.

## Weiterer Entwicklungsfahrplan

1. Den bereits entwickelten v0.1.2-Block (Alltagssteuerung,
   Szenenstudio, Einzel-/Gesamtimport, Recovery und Multicastdiagnose)
   abschließen, CI und privater Hardwaretest.
2. **Musikmotor-MVP**: Mikrofon, Live-Frequenzbild, mindestens Bassbeat
   und musikgesteuerter Lichtlauf mit sicheren Kommando-/Latenzlimits.
3. **Grundrisseditor-MVP**: Räume/Wände/Türen, Lampenpositionen, grafischer
   Effektpfad und lokale Dateisicherung.
4. Weitere Audioquellen, AudioPlaybackCapture mit Erlaubniskontrolle,
   Cast-Mikrofon-Fallback und lokaler Player.
5. Schicht-/Story-Editor, Kalibrierung, Material-3-Tablet-Layout und
   App-Migrationsprüfung.

Keine dieser Folgefunktionen wird als implementiert bezeichnet, solange
sie nicht in Quellcode und CI/Hardwareprüfung nachgewiesen ist.
