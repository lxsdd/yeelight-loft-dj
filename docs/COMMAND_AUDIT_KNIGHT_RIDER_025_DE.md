# Yeelight Loft DJ – Ist-/Soll-Audit, LAN-Befehle und Knight Rider (v0.2.5)

**Stand:** 01.11.2026 · Ausgangsbasis: v0.2.4-PR #23, der auf dem
signierten v0.2.3-`main` basiert. Keine echten Lampen im CI.
Audit-Basis: Yeelight *WiFi Light Inter-Operation Specification*,
insbesondere Kapitel 3 (Discovery), 4.1 (Command), 4.2 (Result), 4.3
(Notification). URL:
https://www.yeelight.com/download/Yeelight_Inter-Operation_Spec.pdf

## 1. Unterscheide 3 unabhängige Zustände

1. **Protokoll kennt Befehl:** Das Yeelight-Protokoll dokumentiert
   einen JSON-Befehl – kein Beweis, dass jede Firmware ihn kann.
2. **Dieses Gerät meldet Befehl:** `support` im echten
   Multicast/Direkt-UDP-Erkennungsheader. Fehlt die Liste bei reiner
   TCP-Probe, lautet der Status »unbestätigt«, nicht »unterstützt alles«.
3. **Diese App hat den Befehl implementiert:** eigener Codepfad,
   Parameterprüfung, Bedienung, Fehlerrückmeldung. Selbst wenn die
   Lampe `support` bestätigt, kann die App diesen Befehl noch nicht
   anbieten. Neuer `YeelightCommandCatalog` legt dies geräteunabhängig
   offen; in den Lampendetails werden noch nicht implementierte, aber
   gemeldete Methoden angezeigt.

Die Lampen werden über TCP 55443 mit CRLF-getrennten JSON-Nachrichten
angesteuert. 2,4-vs.-5-GHz-Suche ist davon unabhängig.
Aktueller Befund: sechs UDP-Multicast-Antworter auf beiden Frequenzbändern,
13 bestätigte TCP-Clients, sieben zuvor über gezieltes UDP erreichbar.
Die Multicast-Ursache ist damit noch nicht abschließend geklärt.

## 2. Vollständige Hauptkanal-Befehlsmatrix

| Yeelight-Befehl | Ist in Loft DJ | Soll / Grenze |
|---|---|---|
| `get_prop` | Ja: Status, flowing, Power-Verifikation, Scanner-Off-Verifikation | Readbacks nicht als physische Lichtmessung überinterpretieren |
| `set_power` | Ja: manuell, Gruppen, Wiederherstellung, Scanner-Off | OK und `get_prop(power)` getrennt ausweisen |
| `set_bright` | Ja: manueller Slider, Restore, Identifizierung | Nur 1–100, kein echter 0%-Auszustand |
| `set_rgb` | Ja: Farbe, Musiklicht, Identifikation, native Flow-Farben | Nur gemeldete / bewusst getestete Fähigkeit |
| `set_hsv` | Ja: Farbton und Sättigung, Restore | Hue 0–359, Sat 0–100 |
| `set_ct_abx` | Ja: Weißtemperatur, Restore, Identifikation | 1700–6500 K laut Spezifikation, modellabhängig |
| `start_cf` | Ja: alte Farbflows/Lichtschweife | Verifizierbare Synchronität zwischen Lampen nicht garantiert |
| `stop_cf` | Ja: expliziter Stopp und sichere Wiederherstellung | Quota-Ablehnung: kein Blind-Retry; lokale Journal-Einträge bleiben |
| `set_scene` | **Teilweise:** native `cf`, Scanner nutzt `color` | `hsv`, `ct`, `auto_delay_off` noch ohne vollständige UI |
| `toggle` | Nein | Verhindert absichtlich unklaren Toggle bei Gruppen |
| `set_default` | Nein | Permanenter Geräte-Flash-Schreibzugriff, nur ausdrücklich opt-in |
| `cron_add`, `cron_get`, `cron_del` | Nein | Echte zeitgesteuerte Jobs fehlen |
| `set_adjust` | Nein | Relatives Erhöhen/Senken/Kreiseln fehlt |
| `set_music` | Nein | Yeelight Music Mode mit Rückverbindung ist NICHT unsere Android AudioPlaybackCapture-Analyse |
| `set_name` | Nein | App-Alias ≠ Firmware-Gerätename |
| `adjust_bright`, `adjust_ct`, `adjust_color` | Nein | Relative Schrittsteuerung fehlt |

### Hintergrundkanal und Mehrzonenfunktionen

Nicht implementiert: `bg_set_power`, `bg_set_rgb`,
`bg_set_hsv`, `bg_set_ct_abx`, `bg_start_cf`, `bg_stop_cf`,
`bg_set_scene`, `bg_set_default`, `bg_set_bright`,
`bg_set_adjust`, `bg_toggle`, `dev_toggle`,
`bg_adjust_bright`, `bg_adjust_ct`, `bg_adjust_color`.
Diese Befehle sind ausschließlich für Modelle relevant, die einen
separaten Hintergrundkanal ausdrücklich per `support` melden.
Hersteller-/Firmware-spezifische Extras, etwa `set_segment_rgb` oder
`udp_chroma_sess_new`, müssen einzeln geprüft werden und sind
noch keine implementierte Funktion.

### Weitere wichtige Ist-Funktionen und Grenzen

- **Discovery:** Multicast 239.255.255.250:1982, gebunden an WLAN;
  TCP-Probe, Direkt-UDP-Fallback mit `support` und deduplizierter ID.
- **Steuerung:** RGB, HSV, CT, Helligkeit, Ein/Aus, manuelle
  Einzel-Lampe, Gruppen, benannte Gruppen und Räume.
- **Lichtshows:** Yeelight-native `start_cf` pro Lampe; Phase/Synchronität
  und tatsächlicher `flowing`-Wert können zwischen Geräten abweichen.
  Die alten Schweif-Effekte verwenden für inaktive Lampen
  bisher **1 % Helligkeit, nicht `set_power off`**.
  Das erfüllt *nicht* die Anforderung des echten Dunkelzustands.
- **Scanner:** Eigenständiger Modus mit verifiziertem `set_power off`.
  Diese Funktion ist **nicht** mit dem bisherigen nativen Schweif zu
  verwechseln.
- **Recovery:** Laufende Geräte vor Steuerbefehlen persistiert, nach
  falschen/fehlenden Bestätigungen gezielte Wiederherstellung.
  PR #23 ermöglicht zusätzlich bewusstes lokales Verwerfen einer
  alten Vormerkung ohne falsche Stromneustartbehauptung.
- **Audio:** Mikrofon und Android AudioPlaybackCapture erlaubt;
  DRM/Quell-App/Cast-Empfänger können interne Wiedergabe verhindern.
  Kein eigener MP3-Player und keine Implementierung von `set_music`.
- **Mi Desk Lamp 1S:** MiIO-Erkennung nur lesend. Kein MiIO-Schreiben.

## 3. Soll: Knight-Rider-Scanner über 2–24 Lampen

Die aktive Auswahl kommt aus benannter Gruppe, Raum, Einzellampen-Auswahl
oder »Alle«. Die räumliche Reihenfolge kommt aus dem
Drag-and-drop-Lichtweg. Konfiguration:

- Freie RGB-Farbpalette (z. B. `#FF0000`), Schnellfarben;
- Helligkeit 5–100 %, Richtung vorwärts/rückwärts;
- Pingpong wie das Original oder optional einseitige Umlaufrichtung;
- Geschwindigkeit in ms, automatisch auf ein konservatives
  Befehlsbudget von 55 Befehlen/Minute je Lampe begrenzt;
- gespeicherte Szenen-Konfiguration, Live-Schrittnummer,
  eigener Stopp und eigene Editor-Seite.

**Sequenz, kein Dimmen auf 1 %:**

1. Vorherige unbekannte/ausstehende Show-Teilnehmer werden
   **ausgenommen**, niemals ungeprüft überschrieben.
2. **Nur** Lampen mit bestätigtem `set_scene`, `set_power`,
   `set_rgb` und `get_prop` sind qualifiziert.
3. Ein privater Recovery-Eintrag wird vor allen Befehlen dauerhaft
   geschrieben. Anschließend werden alle ausgewählten Teilnehmer
   mit `set_power(off)` abgeschaltet und `get_prop(power)=off`
   verifiziert. Der Scanner startet erst, wenn alle Teilnehmer
   nachweisbar dunkel sind.
4. Für den nächsten Schritt zuerst die bisherige Lampe
   `set_power(off)` + `get_prop(power)` **bestätigen**,
   erst danach die folgende Lampe mit
   `set_scene("color", rgb, brightness)` einschalten.
   Maximal eine Lampe erhält eine aktive ON-Anweisung; unverifizierte
   Off-Antwort führt zum **Abbruch**, niemals zum Einschalten
   der nächsten Lampe.
5. Stop und Fehlercleanup versuchen ein explizites, bestätigtes
   Ausschalten *aller* Teilnehmer; nicht bestätigte Lampen
   behalten ihren privaten Recovery-Eintrag. Der Stopp löst
   **kein `stop_cf`** für Scanner-Geräte aus.
6. Bei App-Verlassen erfolgt Stop-Anforderung; nach einem
   abrupten Prozessabbruch bleiben die Journal-Einträge für die
   Wiederherstellung. Keine Garantie für die Reaktionszeit einer
   ausgeschalteten oder unerreichbaren Lampe.

**Abgrenzung:** Nicht ausgewählte Lampen werden nicht verändert,
auch wenn sie vorher eingeschaltet waren. Der Scanner stellt den
vorherigen Zustand **nicht** wieder her; am Ende sollen die
Teilnehmer AUS sein. Fehlende Lampe/Quotafehler können verhindern,
dass die Hardware tatsächlich abgeschaltet wird; die App zeigt
dies an, statt Erfolg zu erfinden.

## 4. Tests / Endabnahme

Code: 2, 3, 5 und 15 Lampen, Pingpong/reverse, wechselnde Farbe,
maximale Befehlsrate und nur genau ein leuchtender Teilnehmer im
simulierten Protokoll. Offline-Gerät beim Start, fehlende
Off-Bestätigung zwischen Schritten, Stopp während des Durchlaufs,
finaler Cleanup, unbekannte RGB-Fähigkeit.
GitHub Actions prüft `testDebugUnitTest` + `assembleDebug`.

**Realer Test** ist weiterhin notwendig:
zuerst die drei sicheren Esstisch-Lampen, dann fünf, dann möglichst
alle bestätigten Color4. Ein per TCP erreichbares Gerät kann
weiterhin Quota- oder Statusfehler liefern. Erst wenn die reale
Lampe tatsächlich AUS bestätigt und beobachtet wird, kann das
»dunkel nach dem Scanner« als hardwareseitig abgenommen gelten.

## 5. Nächste Soll-Prioritäten

1. Scanner-Langzeittest und sauberes Stopp-/Crash-Verhalten,
   gemessene OFF-Quittungszeiten an allen 15 Lampen.
2. Direkte, intelligente Behandlung von Quota-/Firmware-Capabilities,
   statt globaler Sperren; geräteweise Diagnosen.
3. Yeelight `set_music` als ausdrücklich getrennte,
   nach `support` geöffnete Option für hochfrequente Steuerung –
   erst nach sicherer TCP-Rückverbindung und Rate-Limit-Tests.
4. Hintergrundkanal für geeignete Doppelzonen-Lampen,
   segmentierte Modelle und Timer erst nach Hardware-Evidenz.
5. Noch weniger verschachtelte Unterseiten, zugängliche
   Farbauswahl und einheitliche Live-Schnellbedienung; aktuelle
   Hex-Farbpalette bleibt ein **fortgeschrittener** Editor.
