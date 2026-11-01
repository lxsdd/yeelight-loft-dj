# Chroma-UDP als separate, sicher zu prüfende Color4-Alternative

Stand 01.11.2026. Eigenständiger Forschungs-Draft auf PR #30, keine Änderung an
produktiven Color4-PRs #31 bis #39. Kein Gerätetest, Netzwerksender oder Release.

## Beleglage

Yeelights Chroma Connector (BSD-3-Clause) veröffentlicht die UDP-R2-Sitzungen
und v1/v2-Handshake-Methoden:
https://github.com/Yeelight/Yeelight-Chroma-Connector

Das Herstellerdatenblatt YLDP13YL nennt Razer-Chroma-Unterstützung:
https://yeelight.de/media/akeneo_connector/media_files/y/e/yeelight_smart_led_lampe_1s_color_datenblatt_EN_d310.pdf

Eine bestimmte Lampe auf Original-Firmware 2.1.7_0039 muss dennoch anhand
ihrer **eigenen** gemeldeten SSDP-SUPPORT-Methoden geprüft werden.
`set_music`, OEM-Binary-Strings und ein `color4`-Modellname beweisen
keine freigegebene UDP-v1/v2-Sitzung. `ChromaUdpResearch` verwirft jetzt
verwechselbare Teilstrings wie `not_chroma` und akzeptiert nur exakt
normalisierte Chroma-Marker plus eigene Sessionmethoden.

## In dieser Version implementiert

`ChromaUdpOfflineFleet` kapselt mehrere rein simulierte Chroma-Sitzungen.
Je Lampen-Lane gelten getrennte Token/Antwort-IDs, maximal 15 s und
45 versuchte RGB-Frames, mindestens 100 ms zwischen Frames, maximal
16 synthetische Lampen. **Das sind Test-Grenzen, keine gemessenen
Color4-Firmware-Quotas oder hardwarefreigegebene 10-Hz-Geschwindigkeit.**

Der Test mit 5.000 schnellen Reglerereignissen begrenzt die RGB-Versuche
und verwirft veraltete Zwischenwerte ohne Warteschlange und Replay.
Für fehlerhafte Handshakes, Gerätefehler, Quarantäne, Zeitrücksprung,
Zeitablauf und fehlende Unterstützung bleibt die betroffene Lane geschlossen.
Andere Simulationslampen bleiben unabhängig. Es gibt weder eine
automatische TCP-Rückfallsteuerung noch eine versteckte Reconnect-Schleife.

## Hardware-/Produktionsfreigabe weiterhin gesperrt

1. Bereits gespeicherte eigene SSDP-SUPPORT-Meldungen von Wellness und
   Couchtisch passiv prüfen, keine zusätzlichen belastenden Such-Bursts.
2. Bei bestätigten Chroma-Methoden originalen v1/v2-RGB-Frame-Vertrag und
   Port 55444 mit isoliertem Simulator prüfen. UDP ist anders als
   `set_music` über Reverse-TCP, weder automatisch schneller noch sicherer.
3. Danach nur nach eigener Freigabe und gesundem Quota-Zustand kontrollierter
   Einzel-Lampentest: Latenz/Jitter, Drops, echte optische Reaktion, Stop,
   Restore, Yeelight Classic/Google Home und TCP-Quota-Koexistenz.
4. Erst jede weitere Lampe einzeln, dann gesonderter Mehrlampentest.
   Kein gemeinsames Token, kein blindes UDP-Broadcast und kein Firmwareflash.

Der Forschungs-Adapter ist absichtlich **nicht** mit Android-DatagramSockets
oder produktiver Lampensteuerung verbunden. Ein erfolgreicher
Offline-JVM-Test beweist ausschließlich die Sicherheitslogik.

## Erweiterung: verlässlicheres Capability-/Protokollgate

Die bislang gespeicherte SSDP-`support`-Liste wird jetzt **zweistufig** bewertet:
`chroma` + `udp_new`/`udp_sess_new` belegt allenfalls einen beworbenen
Sitzungsmodus; für die im OEM-Connector gezeigten normalen Farbframes ist
zusätzlich `set_scene` nötig. `set_music` (Reverse-TCP), `set_rgb`
(klassisches LAN), `bg_set_rgb` (anderer Gerätetyp) oder Namensähnlichkeiten
dürfen diese zweite Bedingung nicht ersetzen. Die bestehende passive
Geräte-Forschungsanzeige unterscheidet ab jetzt explizit, ob RGB noch
unbelegt oder nur **für die Offline-Simulation** plausibel ist.
Die `ChromaColorReadinessReport`-Auswertung kennt außerdem ein
vorrangiges Quarantäne-Gate; `maySendUdp` bleibt unter **allen**
Testergebnissen `false`.

Zusätzliche Protokollhärtung: JSON-Antwort-IDs müssen echte numerische
Integer sein, keine zu Zahlen konvertierbaren Strings. Tokenfelder
müssen echte 32-stellige Hex-Strings sein. Die monotone virtuelle
Zeit wird nach **jedem** akzeptierten Farb-/Keepalive-/Antwortzeitpunkt
geprüft, nicht nur gegenüber der letzten Geräteantwort. Die simulierte
Lampen-Lane wird jetzt bereits beim exakt **45. RGB-Sendeversuch**
geschlossen, auch wenn kein weiterer Event ankommt. Ein gescheiterter
Sitzungsbeginn führt nicht zu unbegrenzten erneuten Begin-Versuchen.
Alle Schutz- und Zeitlimits sind weiterhin nur synthetische
Forschungsannahmen und keine OEM-Firmware-Grenzen.

Der Hersteller-Connector `CUdpLight.cpp` dient als Protokollreferenz
für die separat tokenisierten UDP-v1/v2-Sitzungen und
`set_scene`-Frames; dessen permissives Substring-Parsing wird bewusst
nicht übernommen. Die Android-App enthält durch diese Erweiterung
weiterhin **keinen** aktiven Chroma-UDP-Socket, keinen Backgroundsender,
keinen automatisch ausgelösten Discovery-Scan und keinen TCP-RGB-Fallback.
Hardwarequalifikation nur nach eigenständiger Freigabe.


## Dritter Block: Letzter-Wert-Koaleszierung und gerätegetrennte Tokens

\`ChromaOfflineLatestValue\` ergänzt die bisherige strikte UDP-Simulations-Fleet
um einen **einzelnen, höchstens 250 ms alten RGB-Sollwert pro Lampe**.
Schnelle Farbregler- und Musikereignisse überschreiben den alten Sollwert,
statt Befehle für jedes Zwischenergebnis oder eine verzögerte Warteschlange
anzulegen. Erst ein **expliziter** Aufruf von \`poll(key, nowMs)\`
erzeugt ein simuliertes Frame, wenn die bereits vorhandene Fleet-Rate/Quote,
Session-ID, Token und Frist es zulassen. Kein Scheduler und keine Hardware-I/O.

Eigenschaften und Grenzen:

- Das gewünschte *letzte* Ergebnis kann nach einem 100-ms-Ratefenster
  verarbeitet werden. Wird länger als 250 ms nicht abgeholt, wird es bewusst
  verworfen, damit ein alter Show-Frame nicht nach einer Störung aufblitzt.
- Identische aufeinanderfolgende Sollwerte verursachen keine Duplikate.
  Nur tatsächlich ausgegebene **simulierte** Frames erhöhen die Versuchszahl.
- Quarantäne, Stop, Zeitrücksprung, Sitzungsfehler und Ablauf löschen den
  jeweils wartenden Sollwert; andere Lampen bleiben getrennt.
- Das Offline-Fake kann nun **unterschiedliche 32-stellige synthetische
  Sitzungstoken pro Lampe** besitzen. Vertauschte Token werden verworfen,
  ohne die gültige Anfragen-ID des Zielgeräts zu verbrauchen. Unzulässige
  JSON-IDs, falsche Token und fremde Absender bleiben Negativtests.
- Ein Erfolg in der In-Memory-Simulation ist ausdrücklich weder Messung von
  UDP-Paketverlust noch Hardware-Latenz, noch ein Nachweis einer durch
  UDP umgangenen Color4-Firmware-Quota.

Die neuen JVM-Tests prüfen 5.000 Slider-Updates, letzte Farbausgabe nach
temporärer Drosselung, Zeitablauf, Doppelwertunterdrückung, Fehlwerte,
Quarantäne, Uhrzeitumkehr, fehlenden Handshake, fehlendes \`set_scene\`,
V1/V2-Mehrlampen-Isolation, getauschte Token sowie Replay-/ID-Manipulation.
Geräte- und Netzwerkzugriffe bleiben weiterhin nicht implementiert. Die
sichere produktive Color4-Quota-Steuerung und Musik-PRs sind unangetastet.
