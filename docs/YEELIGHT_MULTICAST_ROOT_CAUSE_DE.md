# Warum nur fünf Multicast-Antworter bei 15 TCP-erreichbaren Yeelights?

## Fakten (Heimnetz-Praxismessung vom 31.10.2026)

- Fünf eindeutige Geräte antworteten auf Yeelight-M-SEARCH über UDP.
- 15 Geräte waren unabhängig davon per direkter TCP-`get_prop`-Statusabfrage auf Port 55443 erreichbar.
- Bei fünf Geräten überschnitten sich die Ergebnisse, zehn Geräte waren nur per TCP erreichbar.
- 47 UDP-Antwortpakete wurden gelesen, davon null aufgrund fehlender/ungültiger
  `Location` verworfen. Die Paketanzahl sind Mehrfachantworten, keine Lampenanzahl.
- Laut Nutzer sind Modell, Firmware, Setup und WLAN der gleichartigen Lampen einheitlich.
  Diese Aussage wird berücksichtigt; Unterschiede werden **nicht ohne Messung unterstellt**.

## Protokoll und aussagekräftige Schlussfolgerung

Yeelight benutzt **SSDP-ähnliche Discovery, kein mDNS**.
Das Smartphone versendet `M-SEARCH` an IPv4-Multicast
`239.255.255.250:1982`. Die Lampen, die den Suchruf erhalten und akzeptieren,
senden ihre Antwort als UDP-Unicast an genau die Absender-IP und den
ephemeren Absender-Port des Smartphones zurück. Die direkte
TCP-Verbindung nach `IP:55443` ist ein davon unabhängiger Pfad.

Da die fünf empfangenen Antworten fehlerfrei parsbar waren, ist ein
genereller Syntaxfehler der M-SEARCH-Nachricht oder des Response-Parsers
**unwahrscheinlich**, nicht aber jede denkbare App-Ursache ausgeschlossen.
Dass die anderen zehn TCP sprechen, beweist **nur** LAN-Erreichbarkeit,
nicht den Empfang der Multicast-Suchnachricht und nicht das Zurückkommen
der UDP-Unicast-Antwort.

Noch nicht unterscheidbar sind insbesondere:
1. Die Multicast-Suchpakete erreichen nur bestimmte WLAN-Stationen.
2. Alle Lampen empfangen sie, aber ein Teil antwortet nicht (Firmware-/Rate-/Timing).
3. Die Antwort-Unicasts eines Teils werden zwischen Lampe und Smartphone blockiert.
4. Android sendete bisher aus einem ungeeigneten Default-Netz-/VPN-Pfad.

## Implementierte instrumentierte Gegenprobe

Die erneuerte Suche bindet den UDP-Socket **explizit** an die IPv4-Adresse
des tatsächlichen WLAN-Interfaces (nicht an eine vom System gewählte
Standardroute). Sie sendet weiter fünf Suchrunden, erfasst deren
individuelle eindeutige Antworter und zeigt:
- WLAN-Sende-IP,
- `Antworter je Runde` (z. B. `5/5/5/5/5` oder `5/4/8/9/6`),
- Multicast- und TCP-Gesamttreffer und ihre Schnittmenge,
- Anzahl empfangener/ungültiger Pakete,
- Abweichungen zwischen UDP-Absender-IP und angegebener `Location`-IP.

**Interpretation nach Installation:**
- Treffer steigen durch explizite WLAN-Bindung deutlich: Ausgangsschnittstelle/
  Routing als Ursache wahrscheinlicher.
- Über alle Runden **immer fünf** und konsistent dieselben Geräte:
  stabiler Netzwerkpfad- oder Endpoint-Unterschied.
- Schwankungen je Runde, am Ende mehr einzigartige Treffer:
  Multicast-/WLAN-Zustellungsqualität, Timing, Paketverluste, Rate-Beschränkung.
- Quell-/Location-Abweichungen: ungewöhnliche Antworten oder IP-Wechsel untersuchen.
- Wieder nur fünf bei 15 TCP und keine Parserfehler: die App kann ohne
  zweite Messperspektive **nicht beweisen**, wo das UDP-Paket verschwindet.

## Bestätigende A/B-Analyse (ohne Änderung der Lampen-Firmware)

1. **Gleiches Smartphone, gleiche WLAN-Anbindung:** nach Upgrade
   zweimal `Alle Lampen suchen`, Rundenergebnisse vergleichen.
2. **Zweiter unabhängiger Sender im selben WLAN:** gleicher M-SEARCH-Inhalt,
   auf Wunsch Laptop mit Wireshark/Python; beide Sender vergleichen.
3. **Wenn beide nur fünf sehen:** möglichst eine von fünf antwortenden und
   eine der zehn nicht antwortenden Lampen als Kontrastpaar wählen.
   Router-/AP-Paketmitschnitt an Multicast 239.255.255.250:1982 und
   rücklaufende UDP-Unicasts auf den jeweils dynamischen Source-Port;
   dabei auf jedem Segment das Empfangs-/Sendeereignis unterscheiden.
4. **Wenn verschiedene Sender unterschiedlich sehen:** WLAN-AP/Band,
   Client-Routing/VPN und IGMP-/Multicast-Weiterleitung vergleichen;
   nicht pauschal Geräte zurücksetzen.
5. **Erst dann** Firmware-/Geräteeigenschaften als Ursache verfolgen, wenn
   paketbasierte Unterschiede nachgewiesen sind.

Geräte-ID, WLAN-Kennwort, IP-Adressen, private Topologie und Netzwerk-Mitschnitte
bleiben auf den eigenen Geräten. Bei Fehlerberichten nur aggregierte Zahlen angeben.

Quellen:
- https://www.yeelight.com/en_US/developer
- https://home.yeelight.de/site/templates/downloads/yeelight_inter-operation-spec.pdf
- https://www.home-assistant.io/integrations/yeelight/
