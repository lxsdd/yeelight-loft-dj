# Testbare Firmware-/Netzwerkforschung: echter In-Memory-Peer, keine Lampenbefehle

**Stand:** 2026-11-01. Forschungs-Draft-PR #30 (nicht produktiv, keine Geräte-Freigabe).

## Chroma UDP: vollständiger lokaler Dialog

Anhand des [offiziellen Yeelight-Chroma-Connector-Codes](https://github.com/Yeelight/Yeelight-Chroma-Connector) wurde zusätzlich zum bestehenden \`ChromaUdpOfflineSession\` ein **eigenständig interpretierender Fake-Lampenendpunkt** \`ChromaFakeDevice\` erstellt. Er hat keine UDP-/TCP-Sockets, keinen Android-Service und keine Discovery-Aufrufe. Ein \`ChromaFakeDatagram\` ist ein Datentransportobjekt in einem normalen JVM-Prozess; \`toString()\` verbirgt sensible Tokeninhalte.

Der simulierte Endpunkt beantwortet einen passenden v1/v2-Handshake, korreliert Anfragenummern, prüft Tokens und Keepalive-Inhalte, zählt korrekt legitimierte RGB-Frames, verweigert doppelte IDs und liefert **keine** künstlichen RGB-Acknowledgements. Per synthetischen Fehler-/Packetloss-Schaltern können Zeitüberschreitungen, unterdrückte Antworten und echte Fehlerantworten ohne \`params\` getestet werden. Die Client-Seite prüft zusätzlich die Antwort-Herkunfts-IP **und den UDP-Absenderport 55444**; das bleibt eine Simulationsprüfung und ist **keine** Authentisierung des realen LAN-Senders.

**Sicherheitsabnahme:** Fake-Bulb kann keine Lampen aktivieren. \`ChromaUdpResearch.mayOpenUdpSessionForResearch()\` bleibt unverändert \`false\`. Der Factory-/CI-Code veröffentlicht keine UDP-Session, keine Tokens oder Hersteller-Firmwaredateien. Das in Tests benutzte Token ist frei erfunden. Produktion bleibt auf dem getrennten, konservativ regulierten TCP-Kanal von PR #31.

Zu prüfen: Die ursprüngliche Yeelight-C++-Implementierung identifiziert Token per Stringsuche und hat teils unscharfe Fehlerbehandlung. In unserer Forschung gilt: JSON-Struktur/Tokenform, exakte IP/Port, exakte Request-ID, Methode, Byte-Limit und Fail-Closed auf Gerätefehler. Verpasste Antworten lösen nie automatische Retry-Fluten aus.

## ceiling1: zeitliche WLAN-Ausfallanalyse

\`CeilingWifiTimeline\` nimmt **ausschließlich explizit dokumentierte WLAN-Assoziationsereignisse** als absolute Unix-Millisekunden an. Keine FRITZ!Box-Abfrage und keine Lampen-Probe. Vier getrennt erfasste WLAN-Abmeldungen im Abstand von 60 Minuten ± 5 Minuten liefern lediglich \`PERIODIC_NEAR_HOURLY\`, **keine** Firmware-Fehlerdiagnose; fehlende, widersprüchliche und unregelmäßige Ereignisse werden ausdrücklich anders behandelt.

Optionale Kreuzkorrelation zweier Lampen (mindestens drei Abmeldungen innerhalb von 60 Sekunden) zeigt, ob ein Ausfallbild **AP-weit** wirken könnte oder zeitlich unkorreliert ist; daraus folgt noch kein Routerdefekt. Absolute Epoch-Zeit vermeidet den falschen Stundensprung bei Sommer-/Winterzeit. Gerätelisten/MACs/IPs und echte Ereignisprotokolle bleiben ausschließlich privat.

Historischer Befund: Bei YLXD01YL Firmware \`2.0.6_0193\` wurden annähernd stündliche WLAN-Aussetzer gemeldet: https://forum.yeelight.com/t/topic/18057/5 . Das ist ein **Vergleichsszenario**, nicht ein Nachweis für die örtlichen Lampen. Issue #29 hält weitere Indizien und Messregeln fest.

## Tests und Schnittstellen-Grenzen

- \`ChromaFakeDeviceTest\`: 10 neue offline durchführbare Tests für v1/v2-End-to-End, RGB, Keepalive, Absender-Port, Identität, Token, falsche Frame-Syntax, simulierte Packetloss, Gerätefehler und Replay.
- \`CeilingWifiTimelineTest\`: 10 neue Tests für Periodizität, Schwellenwert, sommerzeitfeste Zeitstempel, ungültige Routerlogs und Kreuzkorrelation.
- Bereits vorhandene 33 Forschungs-Testmethoden bleiben bestehen.
- **In Summe 53 Forschungs-JVM-Tests** im Draft. Tatsächliches PASS nur mit exakter GitHub-Actions-HEAD-CI belegen.

## Nicht erledigt

Kein echtes Android-Geräteprotokoll für Chroma UDP und kein physischer Lampentest; keine Prüfung der realen \`color4\`-SUPPORT-Listen; keine sichere Kenntnis des \`color4\`-Chips/Bootloaders; keine tatsächlichen WLAN-Abmeldezeiten der vorhandenen Deckenlampen. Keine Firmwaremodifikation, kein Debug-Flash, keine Veränderung von FRITZ!Box, Google Home, BLE oder produktiver Lichtsteuerung.

**Nächste Hardwareentscheidung:** Erst echte geräteindividuelle, private und möglichst passiv erlangte SUPPORT- und Routerereignisse auswerten. Vor einem späteren aktiven Chroma-Adapter zusätzlich Simulator-Transport, Datagramm-Länge, sender-/sessionbezogene Replayabwehr, Recovery und Client-Quarantäne umfassend gegen echte Hardware modellgenau abnehmen. Kein echter Hochfrequenzbetrieb ohne separate ausdrückliche Freigabe.
