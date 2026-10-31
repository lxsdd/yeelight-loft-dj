# Lichtlauf-Studio, Szenen-Import/Export und Sortiermodus (Android v0.1.4)

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

## Lauflicht-Reihenfolge: Visuelle Zuordnung im Gerätetab

1. Unter **Geräte** oben **Lauflicht sortieren** öffnen. Die Geräte erscheinen
   als kompakte, nummerierte Karten mit lokalem Anzeigenamen.
2. Je Lampe auf **Identifizieren** tippen: ein kurzer violetter Impuls wird
   nach vorheriger Statusabfrage ausgeführt und der ursprüngliche Zustand
   anschließend wiederhergestellt. Unsicher lesbare Zustände führen zu einem
   Fehler statt zu unkontrolliertem Schalten.
3. Erkanntes Leuchtmittel mit **Als Nr. N** zur nächsten Wegposition zuweisen;
   der Assistent springt zu Platz N+1. Jede Lampe kann pro Durchgang nur
   einmal zugewiesen werden. Die Reihenfolge wird nach jedem Schritt
   dauerhaft im lokalen App-Speicher gesichert.
4. Für Korrekturen ohne Assistent stehen direkt **unter jeder Gerätekarte**
   Identifizieren und ↑/↓ zur Verfügung. **Zuordnung neu beginnen** beginnt
   eine neue Folge, ohne bereits gespeicherte Platzierungen zurückzusetzen.
5. Identifizieren und Umsortieren sind während aktiver oder noch als aktiv
   erfasster Lichtshows gesperrt. Zuerst zuverlässig stoppen.

Die Sortierung ist eine räumliche Zuordnung und **kein Nachweis**, dass
native Lampen-Animationen laufen oder exakt synchron starten. Ein weiterhin
statischer Farbwechsel muss separat am Gerät diagnostiziert werden.

## Testplan am echten Loft

1. Zunächst Szene `Test (1)` starten, Schweif und Stoppen prüfen.
2. Unter Geräte den Sortiermodus starten, zwei Lampen identifizieren, per
   **Als Nr. 1/2** zuordnen und danach mit ↑/↓ feinjustieren. Die App neu
   öffnen und die persistierte Reihenfolge kontrollieren.
   Erneut starten und Richtung kontrollieren.
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
