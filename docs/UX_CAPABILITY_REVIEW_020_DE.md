# Yeelight Loft DJ v0.2.0 – UX-/Fähigkeiten-Review und Testmatrix

Stand: 1. November 2026. Dieser Zweig basiert auf PR #18 (v0.1.9).
Er wird **nicht** ohne Freigabe auf `main` gemergt.

## Quellcodebefunde und konkrete Änderungen

1. **Aufgeblähte Hauptseiten**: Szenen-Editor, Gruppen/Room-Editor,
   Netzwerkdiagnose und Lampenverwaltung waren tief in langen Scrolllisten
   versteckt. Eigenständige Unterseiten mit Zurück-Navigation, Android-Back
   und automatisch an den Anfang gesetztem Scrollbereich geschaffen:
   Lichtlauf-Studio, Lampenauswahl, Raumverwaltung, LAN-Diagnose,
   Szenenimport/-export und individuelle Lampensteuerung.
2. **Zielauswahl nicht dauerhaft**: Trotz gespeicherter Lampen/Gruppe wurde
   `target` bei jedem Activity-Start auf Test (1) zurückgesetzt.
   Das gewählte Ziel wird jetzt in den privaten App-Prefs gespeichert;
   ungültige Zieloptionen werden nicht übernommen.
3. **Unbestätigte Hardware-Funktionen**: Ein leerer `methods`-Satz nach
   direktem TCP-Scan wurde im UI bislang als Unterstützung aller Methoden
   interpretiert. Eine Yeelight-SSDP-Antwort enthält dagegen das
   `support`-Feld mit den unterstützten Methoden. Geräte zeigen jetzt
   `unterstützt` / `nicht gemeldet` / `nicht bestätigt`.
   Unbestätigte Farb-/Helligkeitsfunktionen werden nur nach expliziter
   Zustimmung für **diese** Lampe aktiviert.
4. **Monochrom gegen RGB-Lichtlauf**: Das JSON-RGB-Lauflicht setzt RGB-Frames
   voraus. Ein `start_cf` ohne gemeldetes `set_rgb` reicht nicht für
   unsere RGB-Animation. Solche bekannten Geräte werden aus RGB-Lichtläufen
   herausgefiltert. Unbekannte TCP-only-Lampen gelten weiterhin als
   **unbestätigt**, nicht als nachgewiesen kompatibel; ein ausdrücklicher
   Test kann scheitern, ohne andere Lampen zu blockieren.
5. **Gerätestatusdiagnose**: Eine eigene Schaltfläche liest `get_prop`
   ohne schaltenden Befehl und kennzeichnet fehlende SUPPORT-Daten.
6. **Versionen**: neuer Entwicklungsmeilenstein 0.2.0 (Build 11).
   Header und Footer aus PR #18 lesen die wirkliche BuildConfig.

## Abgrenzung: echte Funktion vs. UI vs. Planung

- **Implementiert**: Yeelight-UDP-Discovery, read-only LAN-Probe, lokales
  Ein/Aus, Helligkeit, RGB/CT nur je nach Gerätemethode, gerätespezifischer
  Identifizierungsimpuls, Lichtweg/Drag-and-drop, Yeelight-native
  RGB-Lichtläufe, gespeicherte Szenen, Wiederherstellungsjournal, Raum-
  und Loft-Gruppen, echtes Mikrofon-FFT/BPM (mit Berechtigung).
- **Abhängig von Geräteeigenschaften/Netz**: RGB/CT/HSV/CF-Support,
  `flowing`-Rückmeldung, Empfang von UDP-Multicast,
  Port 55443, LAN-Funktion und individuelle Temperaturgrenzen.
  Weder ein Modellname noch ein erfolgreicher TCP-Connect beweist
  alle Steuerfunktionen.
- **Noch nicht implementiert**: Android AudioPlaybackCapture fremder
  Quell-Apps, eigener Player, direkte Cast-Empfängerintegration,
  MiIO-Schreibsteuerung der Desk Lamp 1S, vollständiger 2D/3D-Grundriss
  oder garantierte Phasensynchronisierung mehrerer WLAN-Lampen.
  Diese dürfen nicht als verfügbare Features beworben werden.

## Testprotokoll

- Unit/Build in GitHub Actions: `testDebugUnitTest` +
  `assembleDebug`, Android SDK 36, öffentliches Repository, ohne
  private Geräte-Adressen.
- Regressionen: 3 vs. 15 Color4, gemischte Gruppe mit mono,
  bekannte/unbekannte SUPPORT-Daten, Farbfluss-RGB-Gate,
  Offline-Wiederherstellung (PR #18), Auswahlgruppenzuordnung,
  statische Zustandswiederherstellung und Befehlsvalidierung.
- Manuell auf **echten** Lampen noch nötig: drei funktionierende
  Esstischlampen → größere Color4-Gruppe → Weiß-/Desk-Lampe separat,
  wiederholter Start/Stopp über Minuten, Netzwerkausfall/Neustart,
  Erkennung SUPPORT-Methoden, Zurück-Navigation und Rotationen.
  Physischer Gerätezugriff ist über GitHub Actions nicht möglich.

## Yeelight-Originalprotokoll

Quelle: Yeelight WiFi Light Inter-Operation Specification,
https://www.yeelight.com/download/Yeelight_Inter-Operation_Spec.pdf

Abschnitt 3.1 beschreibt die `support`-Anzeige je Gerät. Abschnitt 4
beschreibt JSON-LAN-Steuerbefehle, `get_prop`, `set_rgb`,
`set_ct_abx`, `start_cf`, `stop_cf` und `set_scene`, inklusive
Verbindungslimits. Keine Vermischung mit nicht dokumentierten MiIO-APIs.
