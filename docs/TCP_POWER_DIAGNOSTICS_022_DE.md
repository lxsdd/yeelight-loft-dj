# Yeelight Loft DJ v0.2.2 – TCP-Schaltmessung und gezielte Reaktionsdiagnose

## Warum Multicast die Lampen nicht unmittelbar langsamer macht
Multicast/SSDP wird nur zur Erkennung und zur Übertragung der
Fähigkeitsliste verwendet. Ein/Aus nutzt für **beide** Erkennungsklassen
das gleiche Yeelight-TCP-Steuerprotokoll auf Port 55443. Probleme
beim Multicast können dennoch dieselbe zugrunde liegende
WLAN-/Netzwerkursache haben wie TCP-Timeouts.

## Behobene appseitige Engpässe

Vorher:
1. Pro Gruppen-Schaltvorgang wurde ein frischer TCP-Socket pro Lampe
   aufgebaut und nur die Antwort auf `set_power` berücksichtigt.
2. Die App meldete teilweise »eingeschaltet«, obwohl keine
   anschließende Statusabfrage erfolgt war.
3. Bei **jeder** Gruppen-Schaltung wurde eine vorherige Lichtshow
   automatisch erneut gestoppt. Eine offline bleibende Lampe konnte
   andere Befehle damit unnötig verzögern.
4. `set_power` mit `smooth,300` erzeugte zusätzlich gewollt 300 ms
   Übergang, auch wenn der Schaltbefehl schnell ankam.

Jetzt:
- Für jede Schaltung wird **eine** TCP-Verbindung verwendet. Über sie
  gehen genau ein `set_power`-Befehl und, wenn unterstützt, genau
  eine `get_prop(power)`-Statusabfrage.
- Die Funktion verwendet `sudden,0` ausschließlich für direkte
  Ein/Aus-Schaltvorgänge, nicht für Farbübergänge, Identifizierung oder
  die Wiederherstellungssequenzen.
- Pro Lampe werden getrennt die kumulativen Zeitpunkte von
  TCP-Verbindungsaufbau, passender OK-Antwort, Power-Statusabfrage und
  Gesamtdauer gemessen.
- Ergebnisse sind **getrennt**: `VERIFIED`,
  `ACK_ONLY`, `MISMATCH`, `NO_ACK`, `UNREACHABLE`,
  `REJECTED`, `PROTECTED`, `UNSUPPORTED`.
- Fehlt die Antwort, wird `set_power` **nicht blind wiederholt**.
  Ein akzeptierter Befehl beweist ohne passende Statusantwort noch
  keinen verifizierten Zustand.
- Keine automatische Stopp-Wiederholung vor jedem Gruppenbefehl:
  offene Wiederherstellungs-IP-Adressen bleiben unverändert
  geschützt, andere Lampen werden direkt bedient.
- Verbindungen in Gruppenszenen bleiben auf maximal drei gleichzeitig
  begrenzt. Alle Ergebnisse sind nur vorübergehend in der App sichtbar;
  kein Export privater IP-Adressen ins öffentliche Repository.

## Praxistest (gezielt statt Flooding)
1. Eine antwortende Color4-Lampe und eine nur per TCP erkannte
   identische Lampe auswählen. Jeweils nacheinander EIN/AUS einmal
   betätigen; TCP, OK, Status und Gesamtdauer notieren.
2. Drei bekannte Esstischlampen gemeinsam schalten, anschließend 5 und
   15 Lampen. Bei Zeitüberschreitungen die **Phase** unterscheiden.
3. Falls `.134` weiterhin offen: prüfen, dass nur dieses Gerät
   als »geschützt« erscheint und die anderen direkt reagieren.
4. Nur wenn beide Lampentypen gleich schlechtes TCP zeigen, untersuchen:
   WLAN-Signal/AP-Client-Isolation, Roaming, Firmware, gleichzeitige
   Steuerung durch andere Apps, Yeelight-Limits.
5. Nach einer fehlenden OK-Antwort **nicht schnell mehrfach klicken**;
   der Schaltbefehl kann am Gerät trotzdem ausgeführt worden sein.
6. Keine automatischen Hochfrequenz-Stresstests: offizielle Yeelight
   LAN-Grenzen: maximal vier TCP-Verbindungen pro Lampe, 60
   Befehle pro Verbindung und Minute sowie 144 insgesamt pro Minute.

## Quelle
Yeelight WiFi Light Inter-Operation Specification, Kapitel 4,
https://home.yeelight.de/site/templates/downloads/yeelight_inter-operation-spec.pdf

## Test- und Freigabegrenzen
GitHub CI testet die Klassifikation, Fehlerszenarien und den
Android-Build. Reale Netzwerkzeiten und tatsächliche Lampenreaktionen
können ausschließlich auf den verbundenen Geräten ermittelt werden.
Dieser PR baut auf #20 auf, nach #18 und #19.
