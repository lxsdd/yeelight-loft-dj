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
