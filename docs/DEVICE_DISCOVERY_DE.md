# Geräteerkennung und Benennung – v0.1.1

## Beobachtung im Heimnetz
Die erste automatische Yeelight-SSDP-Suche fand wiederholt nur fünf Geräte, während die Intensivsuche über lokale TCP-Statusabfragen offenbar alle erreichbaren Leuchten fand. Das weist auf eine unvollständige Multicast-Geräteantwort hin; nicht zwingend auf fehlende LAN-Unterstützung der übrigen Leuchten.

## Korrektur
- „Alle Lampen suchen“ kombiniert jetzt SSDP und eine lesende direkte LAN-Suche, statt nur SSDP zu verwenden.
- Vorhandene Geräteeinträge werden zusammengeführt und nicht gelöscht.
- SSDP liefert typischerweise Modellkennungen wie `color4` und `stripe`.
- TCP-`get_prop` liefert **keine Modellkennung**. Daher zeigt die App „Modell über LAN nicht ermittelbar“ statt den erfundenen Typ „Yeelight (LAN)“.
- Bei einem späteren SSDP-Treffer wird die tatsächliche Modellkennung hinzugefügt. Beim direkten TCP-Treffer werden bereits bekannte Modell-/ID-Informationen erhalten.
- Der lokal gespeicherte Name kann per SSDP `name` oder TCP `get_prop [power, name]` gelesen werden. Falls ältere Firmware diesen Aufruf ablehnt, bleibt die vorherige reine `power`-Abfrage als Fallback.
- Zusätzlich kann jedes Gerät in unserer App mit einem **eigenen Anzeigenamen** versehen werden. Diese Benennung ist lokal und verändert weder Yeelight noch Google Home.
- Yeelight-Cloud-Gerätenamen sind nicht notwendigerweise identisch mit lokalen `name`-Eigenschaften; die App verspricht deshalb keine automatische Übernahme der Cloud-Namen.

## Praxistest
1. Im WLAN „Alle Lampen suchen“ ausführen. Im Idealfall werden die gleichen Geräte wie bei „Intensivsuche“ bestätigt.
2. Die Geräteliste prüfen: `color4`/`stripe` nur dort erwarten, wo echte SSDP-Modellinformationen bekannt sind.
3. Eigene Namen für die Esstisch- und Billardtisch-Birnen vergeben, App neu öffnen und prüfen, ob die Namen bleiben.
4. Eine Szene zunächst auf genau einer Lampe starten; anschließend stoppen.
5. Keine privaten IP-Listen oder Gerätedaten ins öffentliche Repository oder in Issues übertragen.

Quelle: offizielle Yeelight LAN-Protokoll-Dokumentation und Produkterfahrung bei der Endabnahme.
