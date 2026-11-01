# Chroma v1/v2: Offline-Kohorten-Zulassung und Endpunktidentität

Stand: 02.11.2026. Isolierter, gestapelter Nachfolger auf Forschungs-PR #49 → #46 → #40 → #30. **Kein produktiver Color4-Code, kein Socket, keine echte UDP-Sitzung und kein Firmwarepatch.**

## Reproduzierbarer Fehler und Korrektur

Bisher prüfte der räumliche Offline-Show-Runner vor dem Ausgeben eines Gruppen-Ticks lediglich `fleet.canIssueColor()` für alle Lampen. Der darunterliegende Latest-Value-Puffer konnte aber einen Sollwert wegen überschrittener 250-ms-Lebensdauer oder bereits gesendetem identischen Farbwert beim anschließenden `poll()` einzeln verwerfen. Damit war ein partieller Gruppenausgang möglich, obwohl das vorherige Fleet-Pacing alle Lampen freigegeben hatte. Das war kein realer Lampenfehler, sondern eine Lücke im **synthetischen** Gruppenzulassungsvertrag.

`ChromaOfflineLatestValue.preflight()` liefert jetzt fünf ausdrücklich getrennte Ergebnisse: `READY`, `NO_CHANGE`, `RATE_DEFERRED`, `STALE_DROPPED`, `TERMINAL`. Die Gruppen-Orchestrierung prüft **alle** Lane-Ergebnisse, bevor sie einen einzigen simulierten Frame nach außen gibt:

- `RATE_DEFERRED`: Der ganze Tick wird auf den nächsten **expliziten** virtuellen Tick verschoben; es gibt keine versteckte Queue oder automatische Wiederholung. Pro Lane bleibt nur der letzte Sollwert bis zu seiner kurzen TTL.
- `STALE_DROPPED` oder `TERMINAL`: Die ganze Gruppe wird terminal gestoppt und alle Tokens werden verworfen. Ein möglicherweise bereits verlorener alter Sollwert wird niemals erneut versendet.
- `NO_CHANGE`: Ein wirklich identischer, bereits gesendeter Farb-/Helligkeitswert ist ein zulässiger No-op. Er muss weder Quota verbrauchen noch andere, geänderte Lampen künstlich verzögern.
- `READY`: Nur freigegebene Änderungen erzeugen synthetische Frames. Falls der anschließende In-Memory-Commit trotz Vorprüfung unerwartet scheitert, wird die Gruppe geschlossen und **kein** unvollständiges Ausgabebatch zurückgegeben. Bereits intern gezählte Versuche werden nicht zurückgerollt oder wiederholt.

Die Vorprüfung ist keine atomare Transaktion über ein physisches Netzwerk. Nach einem echten UDP-Send kann nicht bewiesen werden, ob ein einzelnes Gerät den Frame empfangen oder ausgeführt hat. Die Software simuliert weder optische Synchronität noch eine Rückstellung des Lampenzustands.

## Zweites Sicherheitsgate: eindeutige simulierte IPv4-Endpunkte

Eine Endpunktidentität ist jetzt ausschließlich ein kanonisches, dezimales IPv4-Unicast-Literal, ohne DNS, IPv6, oktalähnliche Schreibweisen oder mehrdeutige führende Nullen. Leere, nichtnumerische, Loopback-, Multicast- und Broadcast-Adressen werden **vor** einem synthetischen Handshake abgelehnt. Whitespace am Rand wird einmalig entfernt. Die Absenderprüfung verwendet dieselbe kanonische Identität wie das Duplicate-Lane-Gate; verschiedene Sessionobjekte können so nicht durch alternative Schreibweisen mehrere synthetische Quota-Budgets für ein Ziel bekommen.

Dies ist eine **Offline-Policy**, keine Behauptung über Yeelight-Netzwerkadressierung oder Unterstützung eines bestimmten Color4-Geräts. Status und Fehlermeldungen enthalten weder IPs noch Sitzungstokens oder Farben.

## Qualifikation

12 neue JVM-Regressionsfälle: ungültige/mehrdeutige IPv4-Literale, normalisierte Doppel-Endpunkte, Absender-/Portprüfung, explizite No-op- und READY-Zulassung, 100-ms-Pacing ohne Quota-Verbrauch, veraltete Sollwerte ohne Replay, asymmetrisches 2-Lampen-Stale-Failsafe, unveränderte Lampen in einer Delta-Gruppe, 14-Lampen-Quota-Abbruch, 5000 schnelle Offline-Inputs und Uhrzeit-/Token-Verlust. Vorhandene v1/v2-OEM-Wire-Tests, Firmware-Sicherheitsregressionen und Android-Debug-Build bleiben verpflichtend.

Die öffentlich gepinnte Hersteller-Referenz bleibt [Yeelight Chroma Connector CUdpLight.cpp, Commit 769a616](https://github.com/Yeelight/Yeelight-Chroma-Connector/blob/769a616b9012877baf7fa3df426b38ca6697a167/ChromaBroadcastSampleApplication/CUdpLight.cpp). Die neue Gruppen-Zulassung ist eine zusätzliche defensive Host-Policy, **keine** neue Herstellerfunktion.

## Unveränderte Beweisgrenzen

- Das private Originalimage `yeelink.light.color4 2.1.7_0039` wurde nicht in GitHub eingecheckt, verteilt, geflasht oder modifiziert. Die bereits verifizierte OEM-Fehlertabelle belegt `client quota exceeded`, **nicht** dessen Trigger, Refill-Timer oder eine sichere Umgehung.
- Kein neuer Code darf reale UDP-/TCP-Pakete erzeugen. Kein automatischer Wechsel zu `set_music` oder klassischem TCP. Keine Hardware- oder Routeraktion.
- 100 ms, 250 ms, 15 s und 45 Versuche sind **synthetische** Schutzgrenzen, keine gemessene Color4-Hardwareleistung.
- Ein öffentlicher grüner CI-Lauf bestätigt nur die statische und synthetische Software-Qualifikation. Echte Chroma-v1/v2-Kompatibilität der vorhandenen Color4-Lampen ist nicht nachgewiesen.

## Neue, private Original-Firmware-Beobachtung: v2-Marker tatsächlich kompiliert

Die lokale, **exakt SHA-256-geprüfte** Color4-Originaldatei `2.1.7_0039` (1.502.532 Bytes; SHA-256 `67f075ca909ae9f5c08ff580a4847683d1899e8ba251a7cd75d275b9848f22aa`) wurde **ohne** Netzwerk-/Gerätezugriff rein lesend nach allowlist-beschränkten Chroma-Methodenmarkern durchsucht. Das neue Tool `tools/color4_chroma_stock_markers.py` verlangt zusätzlich den bereits qualifizierten sieben-Segment-/LAN-Fehlertabellennachweis. Das echte Image bleibt privat; CI verwendet ausschließlich synthetische Fixtures.

| Marker im Originalimage | Rohtexttreffer | eigenständiger NUL-String | Suffix eines NUL-Strings | eingebettet / nicht eigenständig |
|---|---:|---:|---:|---:|
| `udp_sess_new` | 2 | 1 | 0 | 1 |
| `udp_chroma_sess_new` | 1 | 1 | 0 | 0 |
| `udp_sess_token` | 1 | 0 | 0 | 1 |
| `udp_sess_keep_alive` | 1 | 0 | 1 | 0 |
| `set_scene` | 1 | 0 | 1 | 0 |
| `chroma` | 1 | 0 | 0 | 1 |
| `udp_new`, `udp_token`, `udp_keep_alive` | jeweils 0 | 0 | 0 | 0 |
| `set_music` | 3 | 0 | 2 | 1 |
| `client quota exceeded` | 1 | 1 | 0 | 0 |

**Einordnung:** Das ist neue, bytegenaue Evidenz, dass die Stockfirmware **v2-Chroma-/UDP-Sitzungsbegriffe und Token-Antwort-Text** enthält. Die Wörter `chroma` und `udp_sess_token` sind dabei **keine** eigenständigen NUL-Strings, sondern Teil längerer, kompilierten Texte; die Abgrenzung verhindert falsche Trefferinterpretation. Die v1-Methodenstrings wurden im Image nicht gefunden. **Zusätzliche Pointerprüfung:** Beide eigenständigen v2-Methodenstrings `udp_sess_new` und `udp_chroma_sess_new` besitzen im exakten Stockimage jeweils **zwei ausgerichtete, in ESP32-Segmente gemappte 32-Bit-Pointerwort-Referenzen**. Die Zeigerwörter liegen in unterschiedlichen gemappten Bereichen (Daten-/Literalbereiche). Das ist stärkere statische Referenzevidenz als bloße Textpräsenz, beweist aber weiterhin **keinen** tatsächlich ausgeführten Handler oder erfolgreichen Chroma-Handshake. Die öffentlichen Berichte enthalten nur aggregierte Trefferzahlen, keine OEM-Codebytes. Aus statischen Zeichenfolgen lässt sich weder der tatsächlich ausführbare Handler, die aktuelle SSDP-`SUPPORT`-Antwort, eine erfolgreiche UDP-Sitzung noch eine zulässige Framerate ableiten. **Kein automatisches Capability-Gate wird dadurch geöffnet.** Ein OEM-v2-Codepfad könnte modell-/feature-/zustandsabhängig deaktiviert sein.

Die öffentliche Auswertung enthält ausschließlich vorab festgelegte Markernamen und Trefferzahlen. Keine Originalbytes, dynamischen Tokenwerte, JSON-Tokenformatvorlagen, echten Lampenadressen oder privaten Dumps werden veröffentlicht. Elf zusätzliche synthetische Python-Regressionsfälle sichern Exact-/Suffix-/Embedded-Klassifizierung, falsche Hashes, Größenbegrenzung und redigierte Ausgabe ab.

**Offene Kernfrage:** Die vorhandene Firmware enthält ebenfalls den bestätigten LAN-Fehler `client quota exceeded`, aber weiterhin keinen verifizierten Quota-Refill-Kontrollfluss. Das Vorhandensein von v2-Chroma-Markern ist keine Quota-Umgehung und ersetzt nicht die separate Xtensa-Forschung in PR #48.
