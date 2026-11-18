# Hersteller-Chroma-UDP – deterministischer Offline-Protokollsimulator

**Stand 2026-11-01.** PR #30 Forschungszweig; **nicht** als produktiver Android-Transport verdrahtet. Ergänzt `CHROMA_UDP_RESEARCH_DE.md`.

## Neue Implementierung

`ChromaUdpOfflineSession.kt`: reine Kotlin-Zustandsmaschine mit JSON-Codec, **ohne** `DatagramSocket`, Netzwerksuche, Hintergrund-Threads oder Lampen-Befehle. Sie verarbeitet das vom Yeelight-Chroma-Connector dokumentierte Schema:

- V1: `udp_new` → `udp_token` → `udp_keep_alive`
- V2: `udp_sess_new` → `udp_sess_token` → `udp_sess_keep_alive`
- Sitzungstoken: exakt 32 hexadezimale Zeichen; geschützt gegen Eingabe von abweichenden Sendern, IDs, Methoden, fehlerhaften oder übergroßen Antworten.
- Nur in der Simulation: `set_scene` für RGB, ausschließlich bei tatsächlich enthaltener `set_scene`-Methode, gültigem Bereich und aktiver Sitzung. Token bleibt privat; der Preview-Frame stellt nur explizit für Tests die Wire-Daten bereit und maskiert `toString()`.
- Kein blindes Replay nach Timeout/Fehler; Timer, Keepalive und eine konservative lokale 4-Fehlintervall-Sperre sind **Simulationspolitik**, keine als verifiziert behauptete Firmware-Spezifikation. Kein automatischer Wiederaufbau.
- `ChromaUdpResearch.mayOpenUdpSessionForResearch` bleibt immer `false` – selbst bei positivem Simulatorergebnis.

**14 JVM-Tests** validieren beide Versionspfade, validiertes Handshake, Fehlerantworten ohne `params`-Block, fremde oder veraltete Fehler, falsche Absender/ID/Methoden, ungültige Token, Fehlerbehandlung ohne Replay, Keepalive, erzwungene Schließung, Farbwerte und exakten Capability-Nachweis. Alle Testdaten sind synthetisch.

## Neue wichtige Erkenntnis aus der offiziellen Quelle

Im Yeelight-Herstellerrepository liegt eine **separate UDP-R2-Protokollspezifikation** und ein C++-Client:
- https://github.com/Yeelight/Yeelight-Chroma-Connector
- https://github.com/Yeelight/Yeelight-Chroma-Connector/blob/main/ChromaBroadcastSampleApplication/CUdpLight.cpp

Die ursprüngliche C++-Implementierung prüft Token teilweise per Substring, ohne hinreichende JSON-Validierung, Absender- oder Anfragen-ID-Prüfung. Diese unsicheren Stellen sind **nicht** übernommen. Der UDP-Steuerungsport ist **55444** (aus `CUdpLight.h`); TCP-LAN-Port bleibt 55443. Der Forschungs-Simulator sendet an keinen der beiden Ports.

## Nächster Entwicklungsschritt

Zuerst *private, bereits vorhandene* `SSDP support`-Daten von `color4` nur lokal prüfen; danach simulierten UDP-Empfänger mit realitätsnahen Sequenzen und Provenienzbindung ergänzen. Erst mit ausdrücklicher kontrollierter Hardwarefreigabe ein echter Client/Koexistenztest. Das Risiko eines erneuten `client quota exceeded` darf dabei nicht durch TCP-Probes erhöht werden.

**Grenzen:** Keine protokollgetreue Rückmeldung über die echte Zeitsteuerung, Keepalive-Antworten, Firmwareparameter oder reale LAN-UDP-Fähigkeit unserer `color4`. Ein erfolgreicher Simulator ist **kein** Nachweis einer Geräteverbindung und keine Flash-Freigabe.
