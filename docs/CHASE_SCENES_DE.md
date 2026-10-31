# Lichtlauf-Studio und Szenen-Import/Export (Android v0.1.2)

## Ziel und Abnahme

Für 15 aktuell direkt per LAN erkannte Lampen (5 per Multicast) kann der Nutzer
**Szenen → Lichtlauf-Studio → Alle** wählen, den Effekt einstellen und mit
**Lichtlauf starten** einen räumlichen Lichtlauf auslösen. Die Lampenreihenfolge
wird unter **Geräte → Reihenfolge im Lauflicht** per ↑/↓ konfiguriert und lokal
gespeichert. Die bestehende Auswahl **Test (1)** ermöglicht einen sicheren
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
`start_cf`-Sequenz mit den Phasen des ausgewählten Lichtwegs.
Es sendet beim Start pro Lampe nur `set_power` und `start_cf` und
beim Stopp `stop_cf`. **Keine** permanente Wiederholung von TCP-Kommandos
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

## Testplan am echten Loft

1. Zunächst Szene `Test (1)` starten, Schweif und Stoppen prüfen.
2. Unter Geräte zwei benachbarte Lampen in der Reihenfolge vertauschen;
   erneut starten und Richtung kontrollieren.
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
