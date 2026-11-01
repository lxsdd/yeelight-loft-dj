# v0.1.8 – Lichtlauf bei größeren Lampengruppen

## Echte Rückmeldung (1. November 2026)
- Lichtlauf funktioniert mit drei Color4 am Esstisch.
- Bei allen gespeicherten oder einer größeren individuell gewählten
  Color4-Menge startet keine sichtbare Szene; Bedienung wirkt später blockiert.
- Lichtlauf-Editor verliert nach Szenenstart seinen offenen Zustand.
- Bedeutung von Test (1) und Loft-Gruppe nicht ersichtlich;
  Loft-Gruppe nicht unabhängig editierbar.

## Quellcode-Ursachen und Änderungen
1. Der frühere **globale Status-Preflight** bei aktivierter Option
   `Nach Stopp vorheriges Licht wiederherstellen` brach beim ersten
   nicht erreichbaren oder nicht wiederherstellbaren Gerät vollständig ab.
   Nun wird **jedes** Gerät einzeln geprüft; nur Lampen mit sicherem
   Ausgangszustand werden geschaltet, andere klar als übersprungen gemeldet.
2. Maximal **drei gleichzeitige** LAN-Befehlsabläufe für gruppierte
   Start-/Status-/Stop-Operationen. Das ist eine konservative,
   kontrollierte Lastbegrenzung, keine Behauptung der Ursache des
   beobachteten Hardware-Hängers.
3. Der Editor bleibt durch einen **stabilen LazyColumn-Key** und
   `rememberSaveable` beim Einfügen des Status-/Wiederherstellungsbereichs
   geöffnet. Der Start liegt nun oben bei der Live-Steuerung, daneben Stopp.
4. **Ein gemeinsamer Zielbereich** für alle Szenen und Lichtläufe,
   keine versteckte zweite Auswahl mit Default `Alle`.
5. `Test (1)` nennt die erste Lampe der gespeicherten Lichtweg-Sortierung
   mit Namen und lokaler IP. `Loft` wird zunächst aus den Räumen
   Wohnzimmer/Esszimmer/Hobbyraum gebildet, kann danach aber unabhängig
   davon per Checkbox geändert und auf Raumregel zurückgesetzt werden.
6. **Lampenbericht** zeigt pro Gerät `Läuft`, `Übersprungen`
   oder `Fehler` und die reale Antwort-/Abbruchursache.
   Bei teilweisem Start werden alle versuchten IPs für einen sicheren
   Stopp vorgemerkt, auch wenn die Antwort unbestätigt ist.

## Noch nicht auf Hardware bewiesen
- Exakte Ursache des früheren Hängers mit 15 Geräten ist noch nicht
  bestätigt. Es kann ein Kombinationsproblem aus nicht erreichbaren
  Lampen, Firmware-/Netzwerklimits und UI/Recovery gewesen sein.
- Nach signiertem Merge auf demselben Smartphone gestuft prüfen:
  drei Esstisch-Lampen → 4 → 8 → 15; einmal mit
  Zustandswiederherstellung, einmal ohne, danach Stopp.
- Im Fehlerfall den Lampenbericht mit Namen und Fehlertyp vergleichen.
  Private IP-Adressen, Namen und Grundrisse nicht im öffentlichen Issue teilen.
- Separate Tests für echten Farbwechsel über mehrere Minuten,
  vollständiges Wiederherstellen, Neustart und erneuten Start.

PR-Builds bleiben unsigniert. Keine neue APK oder private
GitHub-Daten in einem öffentlichen PR.
