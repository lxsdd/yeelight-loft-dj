# Yeelight Loft DJ 0.1.6 – Musik, Räume, Geräte und Kompatibilität

## Ausgangsstand / Regressionen verhindern

Basis ist **main 9924b272** aus Version 0.1.5 mit PR #12 (zuverlässiger
Farbfluss), PR #13 (Sortierung mit Identifikation) und PR #14 (Drag-and-drop).
Das ältere Musik-PR #11 wurde **nicht blind** auf main übernommen: Der
Kotlin-Audio-Motor und seine Tests wurden auf den aktuellen Code integriert,
das bestehende moderne Sortier-UI bleibt erhalten. Signierte Updates behalten
die monotone Buildnummer (0.1.6 / 7).

## Geräteansicht

- Listenzeile zeigt Namen, **kleine IP** und Raum/Status, mit direkt
  zugänglichem 2,5-Sekunden-Identifizieren-Button.
- Detaileinstellungen sind pro Lampe zunächst **eingeklappt**, erst
  beim Tippen erscheinen Alias, Modell/Erkennungsweg, Raumwahl, manuelle
  Lichtsteuerung und Bestätigung beim Entfernen.
- Die erweiterte Netzdiagnose ist separat einklappbar; die Hauptsuche
  bleibt sofort erreichbar.
- Im **Lichtweg-Sortiermodus** weiterhin Drag-and-drop; Identifizieren
  direkt neben jedem Eintrag, kleine IP unter dem Lampennamen.
- Weißlicht-/Desk-Lampen erhalten **keinen ungeprüften RGB-Befehl**. Bei
  bekanntem Weißmodus wird per Farbtemperatur + Helligkeit identifiziert,
  ansonsten nur per Helligkeitsimpuls. Der ursprüngliche Zustand wird
  anschließend bestmöglich wiederhergestellt, auch bei Fehlern. Bei nicht
  sicher wiederherstellbaren/fehlenden Funktionen bricht die Identifikation ab.

## Zielwahl und Räume

- **Test (1)** = erste Lampe gemäß manueller Reihenfolge.
- **Alle** = alle bekannten Yeelight-LAN-Lampen, **Loft** = Wohnzimmer,
  Esszimmer und Hobbyraum entsprechend gespeicherter Zuweisung.
- **Raum:<Name>** = alle Lampen eines frei benannten Raums.
- **Lampe:<ID>** = genau diese einzelne Lampe.
- **Auswahl** = benutzerdefinierte, dauerhaft gespeicherte Mehrfachauswahl.
  Jede Auswahl gilt auch für Szene/Musiksteuerung.
- **Raumzuordnungseditor** unter Geräte: Namen erstellen/umbenennen/
  entfernen, optisches Identifizieren, alle oder unzugeordnete Lampen
  markieren, mehrere Lampen in einem Schritt in einen Raum verschieben.
  Umbenennung/Entfernung aktualisiert Zuweisungen ohne Lampe zu löschen.
- Das ist **noch kein maßstabsgerechter Grundriss-/Wand-/Türeditor**.
  Dessen eigenständige Anforderungen bleiben verbindlich.

## Szenen und Musik

- Standard-Szenenkarten bleiben direkt bedienbar.
- **Szenenwerkstatt**: Lichtlauf-Editor und JSON-Import/-Export
  einzeln ausklappen, um die Oberfläche nicht zu überfüllen.
- Vierter Tab **Musik**: echtes Android-Mikrofon nur auf ausdrücklichen
  Start und nach Berechtigung, 6-Band FFT, RMS, Bassimpulse, konservative
  BPM-Schätzung; bei Stille keine erfundenen Beats.
- Beatgetriebene Lichtsteuerung benötigt zweite ausdrückliche
  Freigabe, bewahrt vorab lokal bestätigte Lampenzustände und sendet
  höchstens einen Farbwechsel pro 1,5 Sekunden je Lampe.
- Das Mikrofon endet beim Verlassen der Vordergrundansicht. Kein
  Audiomitschnitt, keine Cloud-Übertragung, keine Hintergrundüberwachung.

## Mi LED Desk Lamp 1S / differenzierte Diagnose

Xiaomis Mi LED Desk Lamp 1S **MJTD01SYL** ist ein anderes Modell als
die ältere Mi Desk Lamp **MJTD01YL**. Dass eine klassische Lampe
über Yeelight-LAN erreichbar ist, beweist nicht, dass die 1S dieselben
LAN-Methoden nutzt.

Unter **Erweiterte Suche und Netzwerkdiagnose**: IP-Adresse der 1S aus
der Heimnetz-/FRITZ!Box-Geräteliste eintragen; danach
**Mi Desk Lamp 1S an dieser IP prüfen**. Der Test sendet nur
Yeelight-`get_prop` auf TCP 55443 bzw. Xiaomi-MiIO-`HELLO` an UDP 54321
und zeigt, welcher Kanal antwortet – **keine Schaltbefehle, Tokens,
Passwörter oder Cloud-Anfragen**. Ein positiver MiIO-Treffer ist
**keine** Garantie für lokale Steuerbarkeit; dafür kann die
separat zu integrierende authentifizierte MiIO/MIoT-Schnittstelle nötig
sein. Ohne Antwort sind WLAN/IP/LAN-Modus zu verifizieren.

## Privater Hardwaretest (nach signiertem main-Update)

1. Bestehende 0.1.5 ohne Deinstallation auf 0.1.6 aktualisieren.
2. Geräte/Lichtweg: drei verschiedene Lampen ziehen, direkte
   Identifikation je Zeile kontrollieren, kleine IP mit FRITZ!Box
   vergleichen, Reihenfolge nach Neustart erneut prüfen.
3. Weißlicht-Desk-Lamp: Identifizieren (keine RGB-Versuche), nach
   2,5 Sekunden wieder ursprüngliche Helligkeit/Weißtemperatur.
4. Räume: eigenen Raum anlegen, mehrere Lampen gleichzeitig zuweisen,
   Ziel `Raum:<Name>` schalten und im Szenen-Editor auswählen.
5. Einzellampe und eigene Mehrfachauswahl testen; anschließend
   Gesamtauswahl und alle Lichtläufe weiterverwenden.
6. Szenenwerkstatt öffnen/ schließen, Einzel-/Gesamtimport prüfen.
7. Musik: Mikrofonberechtigung, echte Pegel/Beats, Test(1)-Licht
   und Rückkehr zum Ausgangszustand.
8. Mi Desk Lamp 1S: nur lokale IP prüfen; Ergebnis des dualen
   Protokolltests ohne private IP/Token teilen.
9. Multicast: trotz 15 TCP-Treffern weiterhin fünf SSDP-Antworter?
   Runde-für-Runde-Diagnose und gerichteten UDP-Test dokumentieren.

## Ausstehende unabhängige Produktbereiche

Interaktiver zweidimensionaler Grundrisseditor mit Wänden, Türen,
Höhen und räumlichen Lichtwegen; genauerer DJ-Musikmotor, interner
Audio-Capture nur bei Android-/App-Erlaubnis, lokaler Audioplayer,
authentifizierte MiIo/MiOT-Steuerung falls technisch nötig.
