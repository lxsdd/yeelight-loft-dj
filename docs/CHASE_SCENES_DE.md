# Lichtlauf-Studio, Szenen-Import/Export und Sortiermodus (Android v0.1.5)

## Ziel und Abnahme

Für 15 aktuell direkt per LAN erkannte Lampen (5 per Multicast) kann der Nutzer
**Szenen → Lichtlauf-Studio → Alle** wählen, den Effekt einstellen und mit
**Lichtlauf starten** einen räumlichen Lichtlauf auslösen. Die Lampenreihenfolge
wird unter **Geräte → Lauflicht-Reihenfolge** mit direktem **Identifizieren**
und ↑/↓ oder dem neuen **Lauflicht sortieren**-Assistenten konfiguriert und
lokal gespeichert. Die bestehende Auswahl **Test (1)** ermöglicht einen sicheren
Einzelgerätetest, **Loft** nutzt die Raumzuordnung.

## Drei Effekte

- **Regenbogenschweif**: heller wandernder Kopf und farblich abgestufter Schweif
  gemäß frei definierter RGB-Palette.
- **Sternschnuppe**: heller Lichtpunkt, abklingender Schweif, Farbe aus Palette.
- **Farbwelle**: alle Lampen mit räumlich versetzten Farbwechseln.

Jeder Effekt kann in beiden Richtungen laufen; Pingpong kehrt an den Enden um.
Parameter: Palette (1–16 Hexfarben im Format `#RRGGBB`), Tempo 180–2500 ms
je Schritt, Schweiflänge 1–16 Leuchten, maximale Helligkeit 5–100 %,
Laufrichtung, Pingpong, Zielbereich. Eine **schrittweise Vorschau** zeigt die
berechnete Position auf dem Handy, ausdrücklich nicht den echten Gerätestatus.

## LAN-Implementierung und Grenzen

Das Android-Telefon kompiliert pro Lampe eine unterschiedliche native Yeelight
Farbverlauf-Sequenz mit den Phasen des ausgewählten Lichtwegs.
Seit v0.1.3 startet es bevorzugt atomar per `set_scene("cf")`, bei älteren
Modellen mit bekannter Einschränkung über verzögertes `set_power` +
`start_cf` und prüft danach die tatsächliche `flowing`-Eigenschaft.
Beim Stopp verwendet es `stop_cf`. **Keine** permanente Wiederholung von TCP-Kommandos
pro Animationsframe. Das reduziert die WLAN-Last erheblich. Das Yeelight-
Protokoll begrenzt die Anzahl der LAN-Befehle pro Verbindung und Gerät.
Das Starten von 15 Lampen ist nur näherungsweise synchron; Netzwerklatenzen
und Controllerreaktionen begrenzen die Präzision.

Technisches Limit dieser ersten Version: maximal 24 Lampen pro Lichtlauf.
Der Schweif dimmt nicht aktive Lampen auf 1 %; das ist keine echte Dunkelheit.
Ein laufender Effekt endet nach `stop_cf`; beim Start einer anderen Szene
werden vorherige Lampen zunächst angehalten. Nach einem App-Absturz kann eine
von der Lampe autonom wiederholte `start_cf`-Sequenz weiterlaufen:
nach Neustart ggf. Zielbereich auswählen und eine neue Szene starten, oder
den Hersteller-Controller zum Stoppen verwenden.
Rasche Szenenwechsel können bei lichtempfindlichen Personen Beschwerden auslösen.

## JSON-Archiv – nur lokale Szenen, keine Privatinformationen

Unter **Szenen → Szenen sichern und übertragen** stehen Android-Dateidialoge
für Export und Import zur Verfügung (Storage Access Framework). Das
Format enthält `format=yeelight-loft-dj-scenes`, `version=1`, zwei Listen
`flows` und `chases`, die entsprechenden Farbverlauf-Frames und
Lichtlaufparameter. Exporte enthalten die vier Grundszenen sowie selbst
importierte Farbverläufe und vom Nutzer **gespeicherte** Lichtläufe.

- Eine Szene muss explizit gespeichert werden, bevor sie im JSON-Export erscheint.
- Import fügt neue Szenen hinzu, erhält bestehende und überspringt
  identische bereits bekannte Szenen.
- Neue importierte IDs werden lokal erzeugt; keine Überschreibung von
  Yeelight-Hersteller- oder bestehenden lokalen Szenen.
- Strenge Importlimits: 96 KiB Datei, höchstens 64 Szenen, 2–48 Flowframes,
  1–16 Farben pro Lichtlauf und begrenzte Parameter-/Namenswerte.
- **Nicht enthalten:** private IP-Adressen, Yeelight-ID, Räume,
  Grundrisse, Standortkoordinaten, Anmelde- oder Signierschlüssel.

## Lauflicht-Reihenfolge: Modernes Drag-and-drop (v0.1.5)

1. **Geräte → Lichtweg anordnen** öffnet eine kompakte, nummernfreie Liste.
   Jedes Gerät hat einen farbigen **Drag-Griff** und eine **Identifizieren**-
   Aktion (Glühbirnensymbol). Ein Gerät ist nicht auf IP-Endziffern als
   Bediennamen angewiesen: bestehende Anzeigenamen bleiben sichtbar.
2. Den Griff berühren und die Lampe per Drag-and-drop unmittelbar im
   Lichtweg verschieben. Die Karten bewegen sich mit Animation,
   bei Bedarf scrollt die Liste automatisch am Rand.
3. Erst **beim Loslassen** wird die neue vollständige Reihenfolge mit
   allen IDs in den lokalen App-Einstellungen dauerhaft gespeichert;
   die gespeicherte Reihenfolge steuert auch die Lichtlauf-Sequenz.
4. Die **Identifikation leuchtet 2,5 Sekunden** in Violett und stellt danach
   den vorher gelesenen Ein-/Aus-Zustand, Farbmodus und die Helligkeit
   wieder her. Bei unvollständigem Status wird nicht blind geschaltet.
5. Bei aktiver bzw. noch gespeicherter Lichtshow ist Sortieren und
   Identifizieren gesperrt. Die Reihenfolge kann für TalkBack auch mit
   den Aktionen **Im Lichtweg nach oben/unten** geändert werden.
6. Der Netzwerkstatus wird unabhängig davon angezeigt. Yeelight liefert
   gelegentlich asynchrone Statusmeldungen auf einer TCP-Verbindung:
   `set_power`, `get_prop` und `stop_cf` müssen die Antwort-ID abgleichen;
   ein fremder `props`-Datensatz gilt nicht als Fehler/Erfolg.

Das Drag-and-drop verwendet die quelloffene Apache-2.0-Bibliothek
`sh.calvin.reorderable:reorderable:3.1.0` und behält die bewährte
lokale Speicherung ohne Server/Cloud. Keine automatische Lampenschaltung
durch die Sortierung. Die physische Show-Synchronität bleibt separat
hardwareseitig zu prüfen.

## Testplan am echten Loft

1. Zunächst Szene `Test (1)` starten, Schweif und Stoppen prüfen.
2. Unter Geräte den Lichtweg öffnen, zwei Lampen per längerem
   Identifikationsimpuls erkennen und per Ziehen vertauschen. Die App
   neu öffnen und die persistierte Reihenfolge kontrollieren. Bei
   TalkBack alternativ die zugänglichen Verschiebeaktionen prüfen.
   Danach Richtung und Synchronität des Lichtlaufs kontrollieren.
3. Mit `Alle` den 15-Lampen-Lauf auf längerer Tempoeinstellung auslösen;
   auf fehlende Leuchten, ungeplante Blitze und Startversatz achten.
4. Regenbogenschweif → Sternschnuppe → Pingpong → Farbwelle prüfen,
   ohne vorherige Dauerschleife im Hintergrund zurückzulassen.
5. Eine eigene Palette eingeben und als Szene speichern; App schließen
   und erneut öffnen, Werte laden und starten.
6. Alle Szenen in JSON exportieren und wieder importieren: identische
   Szenen dürfen nicht mehrfach entstehen. Eine ungültige JSON-Datei
   muss sauber als Fehler angezeigt werden.
7. Eine ausgeschaltete und eine eingeschaltete Lampe im manuellen
   Identifikationsmodus prüfen; Zustandswiederherstellung separat testen.

Alle neuen Funktionen sind erst mit erfolgreichem CI-Check **kompilierungs- und
Unit-testverifiziert**. Ein echter Hardwaretest kann ausschließlich
am privaten LAN durchgeführt werden.


## Neue Bedienoptionen (Einzel-Import/Export und Zurücksetzen)

- **Alle Szenen exportieren** erstellt wie bisher eine vollständige JSON-Datei.
- Unter **Einzeln exportieren** besitzt jeder Farbverlauf und jeder gespeicherte
  Lichtlauf einen eigenen Export. Das erzeugt dieselbe unterstützte Dateistruktur,
  jedoch mit genau **einer** Szene.
- Beim Import einer Einzeldatei wird eine Szene zur Auswahl angezeigt.
  Bei umfangreichen JSON-Sammlungen erscheint ein Auswahldialog mit
  Checkbox je Farbverlauf/Lichtlauf: nur markierte Szenen werden importiert.
  Bestehende Szenen bleiben erhalten; identische werden übersprungen.
- Neue Einstellung **Nach Stopp vorheriges Licht wiederherstellen**:
  vor Szenenbeginn jede betroffene Lampe über LAN lesen, bestätigten
  Ein-/Aus-Zustand, Farbmodus, Farbwert und Helligkeit lokal sichern.
  Ist dieser Zustand nicht sicher wiederherstellbar, startet die Szene nicht.
  Nach ausdrücklichem **Stopp** wird ein erneutes Lesen/Schreiben nicht
  simuliert, sondern per Yeelight-LAN echte Wiederherstellungsbefehle gesendet.
- Das Wiederherstellungsjournal wird **vor** jedem verändernden Befehl
  lokal persistiert; nach Neustart kann der Nutzer den Stopp erneut auslösen.
  Nicht bestätigte Wiederherstellungen bleiben für einen erneuten Versuch.
  Keine automatische Endzeit, weil die Yeelight-Flowloops endlos laufen.
  Schlägt die Wiederherstellung fehl, keine Erfolgsbehauptung.
- Die Recovery-Datei enthält private Geräte-IP und alten Lichtzustand nur
  in Android-App-Speicher; sie wird **niemals** in die Szenen-JSON exportiert.

### Hardwaretests zur optionalen Wiederherstellung

1. Eine ausgeschaltete und eine farbig eingeschaltete Lampe mit Option
   aktiv starten/stoppen: ursprüngliches Power/RGB/Helligkeit wiederherstellen.
2. Weiße Lampe im Farbtemperatur-Modus: Kelvin und Helligkeit zurücksetzen.
3. Während der Show Zielbereich wechseln, dann Stopp: **alle gestarteten**
   Lampen erreichen und ursprüngliche Werte wiederherstellen.
4. App bei aktivem Lichtlauf schließen, erneut öffnen, Stopp betätigen:
   aus privatem Wiederherstellungsjournal rekonstruieren.
5. Gerät vor Szene unlesbar/offline: bei aktivierter Option darf **keine**
   Leuchte der Zielgruppe verändert werden.
